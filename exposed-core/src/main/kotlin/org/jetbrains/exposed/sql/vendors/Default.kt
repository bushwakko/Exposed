package org.jetbrains.exposed.sql.vendors

import org.jetbrains.exposed.exceptions.throwUnsupportedException
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.TransactionManager
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.HashMap
import kotlin.collections.LinkedHashSet

/**
 * Provides definitions for all the supported SQL data types.
 * By default, definitions from the SQL standard are provided but if a vendor doesn't support a specific type, or it is
 * implemented differently, the corresponding function should be overridden.
 */
abstract class DataTypeProvider {
    // Numeric types

    /** Numeric type for storing 1-byte integers. */
    open fun byteType(): String = "TINYINT"

    /** Numeric type for storing 1-byte unsigned integers. */
    open fun ubyteType(): String = "TINYINT"

    /** Numeric type for storing 2-byte integers. */
    open fun shortType(): String = "SMALLINT"

    /** Numeric type for storing 2-byte unsigned integers. */
    open fun ushortType(): String = "SMALLINT"

    /** Numeric type for storing 4-byte integers. */
    open fun integerType(): String = "INT"

    /** Numeric type for storing 4-byte unsigned integers. */
    open fun uintegerType(): String = "INT"

    /** Numeric type for storing 4-byte integers, marked as auto-increment. */
    open fun integerAutoincType(): String = "INT AUTO_INCREMENT"

    /** Numeric type for storing 8-byte integers. */
    open fun longType(): String = "BIGINT"

    /** Numeric type for storing 8-byte unsigned integers. */
    open fun ulongType(): String = "BIGINT"

    /** Numeric type for storing 8-byte integers, and marked as auto-increment. */
    open fun longAutoincType(): String = "BIGINT AUTO_INCREMENT"

    /** Numeric type for storing 4-byte (single precision) floating-point numbers. */
    open fun floatType(): String = "FLOAT"

    /** Numeric type for storing 8-byte (double precision) floating-point numbers. */
    open fun doubleType(): String = "DOUBLE PRECISION"

    // Character types

    /** Character type for storing strings of variable length up to a maximum. */
    open fun varcharType(colLength: Int): String = "VARCHAR($colLength)"

    /** Character type for storing strings of variable length.
     * Some database (postgresql) use the same data type name to provide virtually _unlimited_ length. */
    open fun textType(): String = "TEXT"

    /** Character type for storing strings of _medium_ length. */
    open fun mediumTextType(): String = "TEXT"

    /** Character type for storing strings of variable and _large_ length. */
    open fun largeTextType(): String = "TEXT"

    // Binary data types

    /** Binary type for storing binary strings of variable and _unlimited_ length. */
    abstract fun binaryType(): String

    /** Binary type for storing binary strings of a specific [length]. */
    open fun binaryType(length: Int): String = if (length == Int.MAX_VALUE) "VARBINARY(MAX)" else "VARBINARY($length)"

    /** Binary type for storing BLOBs. */
    open fun blobType(): String = "BLOB"

    /** Binary type for storing [UUID]. */
    open fun uuidType(): String = "BINARY(16)"

    open fun uuidToDB(value: UUID): Any =
        ByteBuffer.allocate(16).putLong(value.mostSignificantBits).putLong(value.leastSignificantBits).array()

    // Date/Time types

    /** Data type for storing both date and time without a time zone. */
    open fun dateTimeType(): String = "DATETIME"

    /** Time type for storing time without a time zone. */
    open fun timeType(): String = "TIME"

    // Boolean type

    /** Data type for storing boolean values. */
    open fun booleanType(): String = "BOOLEAN"

    /** Returns the SQL representation of the specified [bool] value. */
    open fun booleanToStatementString(bool: Boolean): String = bool.toString().uppercase()

    /** Returns the boolean value of the specified SQL [value]. */
    open fun booleanFromStringToBoolean(value: String): Boolean = value.toBoolean()

    // Misc.

    /** Returns the SQL representation of the specified expression, for it to be used as a column default value. */
    open fun processForDefaultValue(e: Expression<*>): String = when {
        e is LiteralOp<*> -> "$e"
        currentDialect is MysqlDialect -> "$e"
        currentDialect is SQLServerDialect -> "$e"
        else -> "($e)"
    }

    open fun precessOrderByClause(queryBuilder: QueryBuilder, expression: Expression<*>, sortOrder: SortOrder) {
        queryBuilder.append((expression as? ExpressionAlias<*>)?.alias ?: expression, " ", sortOrder.code)
    }
}

/**
 * Provides definitions for all the supported SQL functions.
 * By default, definitions from the SQL standard are provided but if a vendor doesn't support a specific function, or it
 * is implemented differently, the corresponding function should be overridden.
 */
@Suppress("UnnecessaryAbstractClass")
abstract class FunctionProvider {
    // Mathematical functions

    /**
     * SQL function that returns the next value of the specified sequence.
     *
     * @param seq Sequence that produces the value.
     * @param builder Query builder to append the SQL function to.
     */
    open fun nextVal(seq: Sequence, builder: QueryBuilder): Unit = builder {
        append(seq.identifier, ".NEXTVAL")
    }

    /**
     * SQL function that generates a random value uniformly distributed between 0 (inclusive) and 1 (exclusive).
     *
     * **Note:** Some vendors generate values outside this range, or ignore the given seed, check the documentation.
     *
     * @param seed Optional seed.
     */
    open fun random(seed: Int?): String = "RANDOM(${seed?.toString().orEmpty()})"

    // String functions

    /**
     * SQL function that extracts a substring from the specified string expression.
     *
     * @param expr The expression to extract the substring from.
     * @param start The start of the substring.
     * @param length The length of the substring.
     * @param builder Query builder to append the SQL function to.
     */
    open fun <T : String?> substring(
        expr: Expression<T>,
        start: Expression<Int>,
        length: Expression<Int>,
        builder: QueryBuilder,
        prefix: String = "SUBSTRING"
    ): Unit = builder {
        append(prefix, "(", expr, ", ", start, ", ", length, ")")
    }

    /**
     * SQL function that concatenates multiple string expressions together with a given separator.
     *
     * @param separator Separator to use.
     * @param queryBuilder Query builder to append the SQL function to.
     * @param expr String expressions to concatenate.
     */
    open fun concat(separator: String, queryBuilder: QueryBuilder, vararg expr: Expression<*>): Unit = queryBuilder {
        if (separator == "") {
            append("CONCAT(")
        } else {
            append("CONCAT_WS('", separator, "',")
        }
        expr.appendTo { +it }
        append(")")
    }

    /**
     * SQL function that concatenates strings from a group into a single string.
     *
     * @param expr Group concat options.
     * @param queryBuilder Query builder to append the SQL function to.
     */
    open fun <T : String?> groupConcat(expr: GroupConcat<T>, queryBuilder: QueryBuilder): Unit = queryBuilder {
        append("GROUP_CONCAT(")
        if (expr.distinct) {
            append("DISTINCT ")
        }
        append(expr.expr)
        if (expr.orderBy.isNotEmpty()) {
            append(" ORDER BY ")
            expr.orderBy.appendTo { (expression, sortOrder) ->
                currentDialect.dataTypeProvider.precessOrderByClause(this, expression, sortOrder)
            }
        }
        expr.separator?.let {
            append(" SEPARATOR '$it'")
        }
        append(")")
    }

    // Pattern matching

    /**
     * Marker interface for the possible pattern matching modes.
     */
    interface MatchMode {
        /** SQL representation of the mode. */
        fun mode(): String
    }

    /**
     * SQL function that checks whether the given string expression matches the given pattern.
     *
     * **Note:** The `mode` parameter is not supported by all vendors, please check the documentation.
     *
     * @receiver Expression to check.
     * @param pattern Pattern the expression is checked against.
     * @param mode Match mode used to check the expression.
     */
    open fun <T : String?> Expression<T>.match(pattern: String, mode: MatchMode? = null): Op<Boolean> = with(SqlExpressionBuilder) {
        this@match.like(pattern)
    }

    /**
     * SQL function that performs a pattern match of a given string expression against a given pattern.
     *
     * @param expr1 String expression to test.
     * @param pattern Pattern to match against.
     * @param caseSensitive Whether the matching is case-sensitive or not.
     * @param queryBuilder Query builder to append the SQL function to.
     */
    open fun <T : String?> regexp(
        expr1: Expression<T>,
        pattern: Expression<String>,
        caseSensitive: Boolean,
        queryBuilder: QueryBuilder
    ): Unit = queryBuilder {
        append("REGEXP_LIKE(", expr1, ", ", pattern, ", ")
        if (caseSensitive) {
            append("'c'")
        } else {
            append("'i'")
        }
        append(")")
    }

    // Date/Time functions

    /**
     * SQL function that extracts the year field from a given date.
     *
     * @param expr Expression to extract the year from.
     * @param queryBuilder Query builder to append the SQL function to.
     */
    open fun <T> year(expr: Expression<T>, queryBuilder: QueryBuilder): Unit = queryBuilder {
        append("YEAR(")
        append(expr)
        append(")")
    }

    /**
     * SQL function that extracts the month field from a given date.
     * The returned value is a number between 1 and 12 both inclusive.
     *
     * @param expr Expression to extract the month from.
     * @param queryBuilder Query builder to append the SQL function to.
     */
    open fun <T> month(expr: Expression<T>, queryBuilder: QueryBuilder): Unit = queryBuilder {
        append("MONTH(")
        append(expr)
        append(")")
    }

    /**
     * SQL function that extracts the day field from a given date.
     * The returned value is a number between 1 and 31 both inclusive.
     *
     * @param expr Expression to extract the day from.
     * @param queryBuilder Query builder to append the SQL function to.
     */
    open fun <T> day(expr: Expression<T>, queryBuilder: QueryBuilder): Unit = queryBuilder {
        append("DAY(")
        append(expr)
        append(")")
    }

    /**
     * SQL function that extracts the hour field from a given date.
     * The returned value is a number between 0 and 23 both inclusive.
     *
     * @param expr Expression to extract the hour from.
     * @param queryBuilder Query builder to append the SQL function to.
     */
    open fun <T> hour(expr: Expression<T>, queryBuilder: QueryBuilder): Unit = queryBuilder {
        append("HOUR(")
        append(expr)
        append(")")
    }

    /**
     * SQL function that extracts the minute field from a given date.
     * The returned value is a number between 0 and 59 both inclusive.
     *
     * @param expr Expression to extract the minute from.
     * @param queryBuilder Query builder to append the SQL function to.
     */
    open fun <T> minute(expr: Expression<T>, queryBuilder: QueryBuilder): Unit = queryBuilder {
        append("MINUTE(")
        append(expr)
        append(")")
    }

    /**
     * SQL function that extracts the second field from a given date.
     * The returned value is a number between 0 and 59 both inclusive.
     *
     * @param expr Expression to extract the second from.
     * @param queryBuilder Query builder to append the SQL function to.
     */
    open fun <T> second(expr: Expression<T>, queryBuilder: QueryBuilder): Unit = queryBuilder {
        append("SECOND(")
        append(expr)
        append(")")
    }

    // Cast functions

    /**
     * SQL function that casts an expression to a specific type.
     *
     * @param expr Expression to cast.
     * @param type Type to cast hte expression to.
     * @param builder Query builder to append the SQL function to.
     */
    open fun cast(
        expr: Expression<*>,
        type: IColumnType,
        builder: QueryBuilder
    ): Unit = builder {
        append("CAST(", expr, " AS ", type.sqlType(), ")")
    }

    // Commands
    @Suppress("VariableNaming")
    open val DEFAULT_VALUE_EXPRESSION: String = "DEFAULT VALUES"

    /**
     * Returns the SQL command that inserts a new row into a table.
     *
     * **Note:** The `ignore` parameter is not supported by all vendors, please check the documentation.
     *
     * @param ignore Whether to ignore errors or not.
     * @param table Table to insert the new row into.
     * @param columns Columns to insert the values into.
     * @param expr Expresion with the values to insert.
     * @param transaction Transaction where the operation is executed.
     */
    open fun insert(
        ignore: Boolean,
        table: Table,
        columns: List<Column<*>>,
        expr: String,
        transaction: Transaction
    ): String {
        if (ignore) {
            transaction.throwUnsupportedException("There's no generic SQL for INSERT IGNORE. There must be vendor specific implementation.")
        }

        val autoIncColumn = table.autoIncColumn

        val nextValExpression = autoIncColumn?.autoIncColumnType?.nextValExpression?.takeIf { autoIncColumn !in columns }
        val isInsertFromSelect = columns.isNotEmpty() && expr.isNotEmpty() && !expr.startsWith("VALUES")

        val (columnsToInsert, valuesExpr) = when {
            isInsertFromSelect -> columns to expr
            nextValExpression != null && columns.isNotEmpty() -> (columns + autoIncColumn) to expr.dropLast(1) + ", $nextValExpression)"
            nextValExpression != null -> listOf(autoIncColumn) to "VALUES ($nextValExpression)"
            columns.isNotEmpty() -> columns to expr
            else -> emptyList<Column<*>>() to DEFAULT_VALUE_EXPRESSION
        }
        val columnsExpr = columnsToInsert.takeIf { it.isNotEmpty() }?.joinToString(prefix = "(", postfix = ")") { transaction.identity(it) } ?: ""

        return "INSERT INTO ${transaction.identity(table)} $columnsExpr $valuesExpr"
    }

    /**
     * Returns the SQL command that updates one or more rows of a table.
     *
     * @param target Table to update values from.
     * @param columnsAndValues Pairs of column to update and values to update with.
     * @param limit Maximum number of rows to update.
     * @param where Condition that decides the rows to update.
     * @param transaction Transaction where the operation is executed.
     */
    open fun update(
        target: Table,
        columnsAndValues: List<Pair<Column<*>, Any?>>,
        limit: Int?,
        where: Op<Boolean>?,
        transaction: Transaction
    ): String = with(QueryBuilder(true)) {
        +"UPDATE "
        target.describe(transaction, this)

        columnsAndValues.appendTo(this, prefix = " SET ") { (col, value) ->
            append("${transaction.identity(col)}=")
            registerArgument(col, value)
        }

        where?.let {
            +" WHERE "
            +it
        }
        limit?.let { +" LIMIT $it" }
        toString()
    }

    /**
     * Returns the SQL command that updates one or more rows of a join.
     *
     * @param targets Join to update values from.
     * @param columnsAndValues Pairs of column to update and values to update with.
     * @param limit Maximum number of rows to update.
     * @param where Condition that decides the rows to update.
     * @param transaction Transaction where the operation is executed.
     */
    open fun update(
        targets: Join,
        columnsAndValues: List<Pair<Column<*>, Any?>>,
        limit: Int?,
        where: Op<Boolean>?,
        transaction: Transaction
    ): String = transaction.throwUnsupportedException("UPDATE with a join clause is unsupported")

    protected fun QueryBuilder.appendJoinPartForUpdateClause(tableToUpdate: Table, targets: Join, transaction: Transaction) {
        +" FROM "
        val joinPartsToAppend = targets.joinParts.filter { it.joinPart != tableToUpdate }
        if (targets.table != tableToUpdate) {
            targets.table.describe(transaction, this)
            if (joinPartsToAppend.isNotEmpty()) {
                +", "
            }
        }

        joinPartsToAppend.appendTo(this, ", ") {
            it.joinPart.describe(transaction, this)
        }

        +" WHERE "
        targets.joinParts.appendTo(this, " AND ") {
            it.appendConditions(this)
        }
    }

    /**
     * Returns the SQL command that insert a new row into a table, but if another row with the same primary/unique key already exists then it updates the values of that row instead.
     * This operation is also known as "Insert or update".
     *
     * **Note:** This operation is not supported by all vendors, please check the documentation.
     *
     * @param data Pairs of column to replace and values to replace with.
     */
    open fun replace(
        table: Table,
        data: List<Pair<Column<*>, Any?>>,
        transaction: Transaction
    ): String = transaction.throwUnsupportedException("There's no generic SQL for REPLACE. There must be vendor specific implementation.")

    /**
     * Returns the SQL command that deletes one or more rows of a table.
     *
     * **Note:** The `ignore` parameter is not supported by all vendors, please check the documentation.
     *
     * @param ignore Whether to ignore errors or not.
     * @param table Table to delete rows from.
     * @param where Condition that decides the rows to update.
     * @param limit Maximum number of rows to delete.
     * @param transaction Transaction where the operation is executed.
     */
    open fun delete(
        ignore: Boolean,
        table: Table,
        where: String?,
        limit: Int?,
        transaction: Transaction
    ): String {
        if (ignore) {
            transaction.throwUnsupportedException("There's no generic SQL for DELETE IGNORE. There must be vendor specific implementation.")
        }
        return buildString {
            append("DELETE FROM ")
            append(transaction.identity(table))
            if (where != null) {
                append(" WHERE ")
                append(where)
            }
            if (limit != null) {
                append(" LIMIT ")
                append(limit)
            }
        }
    }

    /**
     * Returns the SQL command that limits and offsets the result of a query.
     *
     * @param size The limit of rows to return.
     * @param offset The number of rows to skip.
     * @param alreadyOrdered Whether the query is already ordered or not.
     */
    open fun queryLimit(size: Int, offset: Long, alreadyOrdered: Boolean): String = buildString {
        append("LIMIT $size")
        if (offset > 0) {
            append(" OFFSET $offset")
        }
    }
}

/**
 * Represents metadata information about a specific column.
 */
data class ColumnMetadata(
    /** Name of the column. */
    val name: String,
    /**
     * Type of the column.
     *
     * @see java.sql.Types
     */
    val type: Int,
    /** Whether the column if nullable or not. */
    val nullable: Boolean,
    /** Optional size of the column. */
    val size: Int?,
    /** Is the column auto increment */
    val autoIncrement: Boolean,
    /** Default value */
    val defaultDbValue: String?,
)

/**
 * Common interface for all database dialects.
 */
@Suppress("TooManyFunctions")
interface DatabaseDialect {
    /** Name of this dialect. */
    val name: String

    /** Data type provider of this dialect. */
    val dataTypeProvider: DataTypeProvider

    /** Function provider of this dialect. */
    val functionProvider: FunctionProvider

    /** Returns `true` if the dialect supports the `IF EXISTS`/`IF NOT EXISTS` option when creating, altering or dropping objects, `false` otherwise. */
    val supportsIfNotExists: Boolean get() = true

    /** Returns `true` if the dialect supports the creation of sequences, `false` otherwise. */
    val supportsCreateSequence: Boolean get() = true

    /** Returns `true` if the dialect requires the use of a sequence to create an auto-increment column, `false` otherwise. */
    val needsSequenceToAutoInc: Boolean get() = false

    /** Returns the default reference option for the dialect. */
    val defaultReferenceOption: ReferenceOption get() = ReferenceOption.RESTRICT

    /** Returns `true` if the dialect requires the use of quotes when using symbols in object names, `false` otherwise. */
    val needsQuotesWhenSymbolsInNames: Boolean get() = true

    /** Returns `true` if the dialect supports returning multiple generated keys as a result of an insert operation, `false` otherwise. */
    val supportsMultipleGeneratedKeys: Boolean

    /** Returns`true` if the dialect supports returning generated keys obtained from a sequence. */
    val supportsSequenceAsGeneratedKeys: Boolean get() = supportsCreateSequence
    val supportsOnlyIdentifiersInGeneratedKeys: Boolean get() = false

    /** Returns`true` if the dialect supports schema creation. */
    val supportsCreateSchema: Boolean get() = true

    /** Returns `true` if the dialect supports subqueries within a UNION/EXCEPT/INTERSECT statement */
    val supportsSubqueryUnions: Boolean get() = false

    val supportsDualTableConcept: Boolean get() = false

    val supportsOrderByNullsFirstLast: Boolean get() = false

    val likePatternSpecialChars: Map<Char, Char?> get() = defaultLikePatternSpecialChars

    /** Returns the name of the current database. */
    fun getDatabase(): String

    /** Returns a list with the names of all the defined tables. */
    fun allTablesNames(): List<String>

    /** Checks if the specified table exists in the database. */
    fun tableExists(table: Table): Boolean

    /** Checks if the specified schema exists. */
    fun schemaExists(schema: Schema): Boolean

    fun checkTableMapping(table: Table): Boolean = true

    /** Returns a map with the column metadata of all the defined columns in each of the specified [tables]. */
    fun tableColumns(vararg tables: Table): Map<Table, List<ColumnMetadata>> = emptyMap()

    /** Returns a map with the foreign key constraints of all the defined columns sets in each of the specified [tables]. */
    fun columnConstraints(vararg tables: Table): Map<Pair<Table, LinkedHashSet<Column<*>>>, List<ForeignKeyConstraint>> = emptyMap()

    /** Returns a map with all the defined indices in each of the specified [tables]. */
    fun existingIndices(vararg tables: Table): Map<Table, List<Index>> = emptyMap()

    /** Returns `true` if the dialect supports `SELECT FOR UPDATE` statements, `false` otherwise. */
    fun supportsSelectForUpdate(): Boolean

    /** Returns `true` if the specified [e] is allowed as a default column value in the dialect, `false` otherwise. */
    fun isAllowedAsColumnDefault(e: Expression<*>): Boolean = e is LiteralOp<*>

    /** Returns the catalog name of the connection of the specified [transaction]. */
    fun catalog(transaction: Transaction): String = transaction.connection.catalog

    /** Clears any cached values. */
    fun resetCaches()

    /** Clears any cached values including schema names. */
    fun resetSchemaCaches()

    // Specific SQL statements

    /** Returns the SQL command that creates the specified [index]. */
    fun createIndex(index: Index): String

    /** Returns the SQL command that drops the specified [indexName] from the specified [tableName]. */
    fun dropIndex(tableName: String, indexName: String): String

    /** Returns the SQL command that modifies the specified [column]. */
    fun modifyColumn(column: Column<*>, columnDiff: ColumnDiff): List<String>

    fun createDatabase(name: String) = "CREATE DATABASE IF NOT EXISTS ${name.inProperCase()}"

    fun dropDatabase(name: String) = "DROP DATABASE IF EXISTS ${name.inProperCase()}"

    fun setSchema(schema: Schema): String = "SET SCHEMA ${schema.identifier}"

    fun createSchema(schema: Schema): String = buildString {
        append("CREATE SCHEMA IF NOT EXISTS ")
        append(schema.identifier)
        appendIfNotNull(" AUTHORIZATION ", schema.authorization)
    }

    fun dropSchema(schema: Schema, cascade: Boolean): String = buildString {
        append("DROP SCHEMA IF EXISTS ", schema.identifier)

        if (cascade) {
            append(" CASCADE")
        }
    }

    companion object {
        private val defaultLikePatternSpecialChars = mapOf('%' to null, '_' to null)
    }
}

sealed class ForUpdateOption(open val querySuffix: String) {

    internal object NoForUpdateOption : ForUpdateOption("") {
        override val querySuffix: String get() = error("querySuffix should not be called for NoForUpdateOption object")
    }

    object ForUpdate : ForUpdateOption("FOR UPDATE")

    // https://dev.mysql.com/doc/refman/8.0/en/innodb-locking-reads.html for clarification
    object MySQL {
        object ForShare : ForUpdateOption("FOR SHARE")

        object LockInShareMode : ForUpdateOption("LOCK IN SHARE MODE")
    }

    // https://mariadb.com/kb/en/select/#lock-in-share-modefor-update
    object MariaDB {
        object LockInShareMode : ForUpdateOption("LOCK IN SHARE MODE")
    }

    // https://www.postgresql.org/docs/current/sql-select.html
    // https://www.postgresql.org/docs/12/explicit-locking.html#LOCKING-ROWS for clarification
    object PostgreSQL {
        enum class MODE(val statement: String) {
            NO_WAIT("NOWAIT"), SKIP_LOCKED("SKIP LOCKED")
        }

        abstract class ForUpdateBase(querySuffix: String, private val mode: MODE? = null, private vararg val ofTables: Table) : ForUpdateOption("") {
            private val preparedQuerySuffix = buildString {
                append(querySuffix)
                ofTables.takeIf { it.isNotEmpty() }?.let { tables ->
                    append(" OF ")
                    tables.joinTo(this, separator = ",") { it.tableName }
                }
                mode?.let {
                    append(" ${it.statement}")
                }
            }
            final override val querySuffix: String = preparedQuerySuffix
        }

        class ForUpdate(mode: MODE? = null, vararg ofTables: Table) : ForUpdateBase("FOR UPDATE", mode, *ofTables)


        open class ForNoKeyUpdate(mode: MODE? = null, vararg ofTables: Table) : ForUpdateBase("FOR NO KEY UPDATE", mode, *ofTables) {
            companion object : ForNoKeyUpdate()
        }

        open class ForShare(mode: MODE? = null, vararg ofTables: Table) : ForUpdateBase("FOR SHARE", mode, *ofTables) {
            companion object : ForShare()
        }

        open class ForKeyShare(mode: MODE? = null, vararg ofTables: Table) : ForUpdateBase("FOR KEY SHARE", mode, *ofTables) {
            companion object : ForKeyShare()
        }
    }

    // https://docs.oracle.com/cd/B19306_01/server.102/b14200/statements_10002.htm#i2066346
    object Oracle {
        object ForUpdateNoWait : ForUpdateOption("FOR UPDATE NOWAIT")

        class ForUpdateWait(timeout: Int) : ForUpdateOption("FOR UPDATE WAIT $timeout")
    }
}

/**
 * Base implementation of a vendor dialect
 */
abstract class VendorDialect(
    override val name: String,
    override val dataTypeProvider: DataTypeProvider,
    override val functionProvider: FunctionProvider
) : DatabaseDialect {

    abstract class DialectNameProvider(val dialectName: String)

    /* Cached values */
    private var _allTableNames: Map<String, List<String>>? = null
    private var _allSchemaNames: List<String>? = null

    /** Returns a list with the names of all the defined tables within default scheme. */
    val allTablesNames: List<String>
        get() {
            val connection = TransactionManager.current().connection
            return getAllTableNamesCache().getValue(connection.metadata { currentScheme })
        }

    private fun getAllTableNamesCache(): Map<String, List<String>> {
        val connection = TransactionManager.current().connection
        if (_allTableNames == null) {
            _allTableNames = connection.metadata { tableNames }
        }
        return _allTableNames!!
    }

    private fun getAllSchemaNamesCache(): List<String> {
        val connection = TransactionManager.current().connection
        if (_allSchemaNames == null) {
            _allSchemaNames = connection.metadata { schemaNames }
        }
        return _allSchemaNames!!
    }

    override val supportsMultipleGeneratedKeys: Boolean = true

    override fun getDatabase(): String = catalog(TransactionManager.current())

    /**
     * Returns a list with the names of all the defined tables with schema prefixes if database supports it.
     * This method always re-read data from DB.
     * Using `allTablesNames` field is the preferred way.
     */
    override fun allTablesNames(): List<String> = TransactionManager.current().connection.metadata {
        tableNames.getValue(currentScheme)
    }

    override fun tableExists(table: Table): Boolean {
        val tableScheme = table.tableName.substringBefore('.', "").takeIf { it.isNotEmpty() }
        val scheme = tableScheme?.inProperCase() ?: TransactionManager.current().connection.metadata { currentScheme }
        val allTables = getAllTableNamesCache().getValue(scheme)
        return allTables.any {
            when {
                tableScheme != null -> it == table.nameInDatabaseCase()
                scheme.isEmpty() -> it == table.nameInDatabaseCase()
                else -> it == "$scheme.${table.tableNameWithoutScheme}".inProperCase()
            }
        }
    }

    override fun schemaExists(schema: Schema): Boolean {
        val allSchemas = getAllSchemaNamesCache()
        return allSchemas.any { it == schema.identifier.inProperCase() }
    }

    override fun tableColumns(vararg tables: Table): Map<Table, List<ColumnMetadata>> =
        TransactionManager.current().connection.metadata { columns(*tables) }

    override fun columnConstraints(vararg tables: Table): Map<Pair<Table, LinkedHashSet<Column<*>>>, List<ForeignKeyConstraint>> {
        val constraints = HashMap<Pair<Table, LinkedHashSet<Column<*>>>, MutableList<ForeignKeyConstraint>>()

        val tablesToLoad = tables.filter { !columnConstraintsCache.containsKey(it.nameInDatabaseCase()) }

        fillConstraintCacheForTables(tablesToLoad)
        tables.forEach { table ->
            columnConstraintsCache[table.nameInDatabaseCase()].orEmpty().forEach {
                constraints.getOrPut(table to it.from) { arrayListOf() }.add(it)
            }
        }
        return constraints
    }

    override fun existingIndices(vararg tables: Table): Map<Table, List<Index>> =
        TransactionManager.current().db.metadata { existingIndices(*tables) }

    private val supportsSelectForUpdate: Boolean by lazy { TransactionManager.current().db.metadata { supportsSelectForUpdate } }

    override fun supportsSelectForUpdate(): Boolean = supportsSelectForUpdate

    protected fun String.quoteIdentifierWhenWrongCaseOrNecessary(tr: Transaction): String =
        tr.db.identifierManager.quoteIdentifierWhenWrongCaseOrNecessary(this)

    protected val columnConstraintsCache: MutableMap<String, Collection<ForeignKeyConstraint>> = ConcurrentHashMap()

    protected open fun fillConstraintCacheForTables(tables: List<Table>): Unit =
        columnConstraintsCache.putAll(TransactionManager.current().db.metadata { tableConstraints(tables) })

    override fun resetCaches() {
        _allTableNames = null
        columnConstraintsCache.clear()
        TransactionManager.current().db.metadata { cleanCache() }
    }

    override fun resetSchemaCaches() {
        _allSchemaNames = null
        resetCaches()
    }

    override fun createIndex(index: Index): String {
        val t = TransactionManager.current()
        val quotedTableName = t.identity(index.table)
        val quotedIndexName = t.db.identifierManager.cutIfNecessaryAndQuote(index.indexName)
        val columnsList = index.columns.joinToString(prefix = "(", postfix = ")") { t.identity(it) }
        return when {
            index.unique -> {
                "ALTER TABLE $quotedTableName ADD CONSTRAINT $quotedIndexName UNIQUE $columnsList"
            }
            index.indexType != null -> {
                createIndexWithType(name = quotedIndexName, table = quotedTableName, columns = columnsList, type = index.indexType)
            }
            else -> {
                "CREATE INDEX $quotedIndexName ON $quotedTableName $columnsList"
            }
        }
    }

    protected open fun createIndexWithType(name: String, table: String, columns: String, type: String): String {
        return "CREATE INDEX $name ON $table $columns USING $type"
    }

    override fun dropIndex(tableName: String, indexName: String): String {
        val identifierManager = TransactionManager.current().db.identifierManager
        return "ALTER TABLE ${identifierManager.quoteIfNecessary(tableName)} DROP CONSTRAINT ${identifierManager.quoteIfNecessary(indexName)}"
    }

    override fun modifyColumn(column: Column<*>, columnDiff: ColumnDiff): List<String> =
        listOf("ALTER TABLE ${TransactionManager.current().identity(column.table)} MODIFY COLUMN ${column.descriptionDdl(true)}")
}

private val explicitDialect = ThreadLocal<DatabaseDialect?>()

internal fun <T> withDialect(dialect: DatabaseDialect, body: () -> T): T {
    return try {
        explicitDialect.set(dialect)
        body()
    } finally {
        explicitDialect.set(null)
    }
}

/** Returns the dialect used in the current transaction, may throw an exception if there is no current transaction. */
val currentDialect: DatabaseDialect get() = explicitDialect.get() ?: TransactionManager.current().db.dialect

internal val currentDialectIfAvailable: DatabaseDialect?
    get() = if (TransactionManager.isInitialized() && TransactionManager.currentOrNull() != null) {
        currentDialect
    } else {
        null
    }

internal fun String.inProperCase(): String =
    TransactionManager.currentOrNull()?.db?.identifierManager?.inProperCase(this@inProperCase) ?: this
