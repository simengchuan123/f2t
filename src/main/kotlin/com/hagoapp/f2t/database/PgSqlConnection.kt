/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.hagoapp.f2t.database

import com.hagoapp.f2t.*
import com.hagoapp.f2t.database.config.DbConfig
import com.hagoapp.f2t.database.config.PgSqlConfig
import com.hagoapp.f2t.util.JDBCTypeUtils
import java.io.Closeable
import java.sql.*
import java.time.ZonedDateTime
import java.util.*

/**
 * Database operations implementation for PostgreSQL.
 */
class PgSqlConnection : DbConnection, Closeable {

    private lateinit var connection: Connection
    private val insertSqlMap = mutableMapOf<TableName, Triple<String, TableDefinition, MutableList<DataRow>>>()
    private val logger = F2TLogger.getLogger()

    companion object {
        private const val PGSQL_DRIVER_CLASS_NAME = "org.postgresql.Driver"

        init {
            Class.forName(PGSQL_DRIVER_CLASS_NAME)
        }
    }

    override fun canConnect(conf: DbConfig): Pair<Boolean, String> {
        try {
            getConnection(conf).use {
                return Pair(true, "")
            }
        } catch (ex: Exception) {
            return Pair(false, ex.message ?: ex.toString())
        }
    }

    override fun getAvailableTables(conf: DbConfig): Map<String, List<TableName>> {
        try {
            val ret = mutableMapOf<String, MutableList<TableName>>()
            getConnection(conf).use { con ->
                val sql = """
                    select schemaname, tablename, tableowner from pg_tables 
                    where schemaname<>'pg_catalog' and schemaname<>'information_schema'
                    order by schemaname, tablename
                    """
                con.prepareStatement(sql).use { st ->
                    st.executeQuery().use { rs ->
                        while (rs.next()) {
                            val schema = rs.getString("schemaname")
                            val table = rs.getString("tablename")
                            if (!ret.containsKey(schema)) {
                                ret[schema] = mutableListOf()
                            }
                            ret.getValue(schema).add(TableName(table, schema))
                        }
                        return ret
                    }
                }
            }
        } catch (ex: Exception) {
            return mapOf()
        }
    }

    override fun listDatabases(conf: DbConfig): List<String> {
        if (conf !is PgSqlConfig) {
            throw F2TException("Not a configuration for PostgreSQL")
        }
        conf.databaseName = "postgres"
        try {
            val ret = mutableListOf<String>()
            getConnection(conf).use { con ->
                con.prepareStatement("select datname from pg_database where datistemplate = false and datname != 'postgres'")
                    .use { st ->
                        st.executeQuery().use { rs ->
                            while (rs.next()) {
                                ret.add(rs.getString("datname"))
                            }
                            return ret
                        }
                    }
            }
        } catch (ex: Exception) {
            return listOf()
        }
    }

    private fun getPgConfig(conf: DbConfig): PgSqlConfig {
        if (conf !is PgSqlConfig) {
            throw F2TException("Not a configuration for PostgreSQL")
        }
        return conf
    }

    override fun open(conf: DbConfig) {
        connection = getConnection(conf)
    }

    override fun close() {
        try {
            connection.close()
        } catch (e: Throwable) {
            //
        }
    }

    private fun getConnection(conf: DbConfig): Connection {
        val config = getPgConfig(conf)
        if (config.databaseName.isNullOrBlank()) {
            config.databaseName = "postgres"
        }
        if (listOf(config.host, config.username, conf.password).any { it == null }) {
            throw F2TException("Configuration is incomplete")
        }
        val conStr = "jdbc:postgresql://${config.host}:${config.port}/${config.databaseName}"
        val props = Properties()
        props.putAll(mapOf("user" to config.username, "password" to config.password))
        return DriverManager.getConnection(conStr, props)
    }

    override fun clearTable(table: TableName): Pair<Boolean, String?> {
        try {
            connection.prepareStatement("truncate table ${getFullTableName(table)}").use { st ->
                return Pair(st.execute(), null)
            }
        } catch (ex: Exception) {
            return Pair(false, ex.message)
        }
    }

    override fun dropTable(tableName: String): Pair<Boolean, String?> {
        try {
            connection.prepareStatement("drop table if exists $tableName").use { st ->
                return Pair(st.execute(), null)
            }
        } catch (ex: Exception) {
            return Pair(false, ex.message)
        }
    }

    override fun getWrapperCharacter(): Pair<String, String> {
        return Pair("\"", "\"")
    }

    override fun escapeNameString(name: String): String {
        return name.replace("\"", "\"\"")
    }

    override fun createTable(table: TableName, tableDefinition: TableDefinition) {
        val tableFullName = getFullTableName(table)
        val wrapper = getWrapperCharacter()
        val defStr = tableDefinition.columns.map { colDef ->
            "${wrapper.first}${escapeNameString(colDef.name)}${wrapper.second} ${convertJDBCTypeToDBNativeType(colDef.inferredType!!)}"
        }.joinToString(", ")
        val sql = "create table $tableFullName ($defStr)"
        connection.prepareStatement(sql).use { it.execute() }
    }

    override fun createInsertSql(table: TableName, tableDefinition: TableDefinition) {
        val sql = """
                insert into ${getFullTableName(table)} (${tableDefinition.columns.joinToString { normalizeName(it.name) }})
                values (${tableDefinition.columns.joinToString { "?" }})
            """
        insertSqlMap[table] = Triple(sql, tableDefinition, mutableListOf())
        fieldValueSetters[table] = tableDefinition.columns.sortedBy { it.index }.map { col ->
            val converter = getTypedDataConverters().get(col.inferredType)
            when (col.inferredType) {
                JDBCType.BOOLEAN -> { stmt: PreparedStatement, i: Int, value: Any? ->
                    if (value != null) stmt.setBoolean(i, value as Boolean) else stmt.setNull(i, Types.BOOLEAN)
                }
                JDBCType.INTEGER -> { stmt: PreparedStatement, i: Int, value: Any? ->
                    if (value != null) stmt.setInt(i, value as Int) else stmt.setNull(i, Types.INTEGER)
                }
                JDBCType.BIGINT -> { stmt: PreparedStatement, i: Int, value: Any? ->
                    if (value != null) stmt.setLong(i, value as Long) else stmt.setNull(i, Types.BIGINT)
                }
                JDBCType.DECIMAL, JDBCType.FLOAT, JDBCType.DOUBLE -> { stmt: PreparedStatement, i: Int, value: Any? ->
                    if (value != null) stmt.setDouble(i, value as Double) else stmt.setNull(i, Types.DOUBLE)
                }
                JDBCType.TIMESTAMP -> { stmt: PreparedStatement, i: Int, value: Any? ->
                    if (value != null) stmt.setTimestamp(i, Timestamp.from((value as ZonedDateTime).toInstant()))
                    else stmt.setNull(i, Types.TIMESTAMP)
                }
                else -> { stmt: PreparedStatement, i: Int, value: Any? ->
                    if (value != null) stmt.setString(i, value.toString()) else stmt.setNull(i, Types.CLOB)
                }
            }
        }
    }

    private val fieldValueSetters = mutableMapOf<TableName, Any>()

    override fun convertJDBCTypeToDBNativeType(aType: JDBCType): String {
        return when (aType) {
            JDBCType.BOOLEAN -> "boolean"
            JDBCType.TIMESTAMP -> "timestamp with time zone"
            JDBCType.BIGINT -> "bigint"
            JDBCType.INTEGER -> "int"
            JDBCType.DOUBLE, JDBCType.DECIMAL, JDBCType.FLOAT -> "double precision"
            else -> "text"
        }
    }

    override fun getExistingTableDefinition(table: TableName): TableDefinition {
        val sql = """select
            a.attname, format_type(a.atttypid, a.atttypmod) as typename
            from pg_attribute as a
            inner join pg_class as c on c.oid = a.attrelid
            inner join pg_namespace as n on n.oid = c.relnamespace
            where a.attnum > 0 and not a.attisdropped and c.relkind= 'r' and c.relname = ? and n.nspname = ?"""
        connection.prepareStatement(sql).use { stmt ->
            val schema = if (table.schema.isBlank()) getDefaultSchema() else table.schema
            stmt.setString(1, table.tableName)
            stmt.setString(2, schema)
            var i = 0
            stmt.executeQuery().use { rs ->
                val tblColDef = mutableListOf<ColumnDefinition>()
                while (rs.next()) {
                    tblColDef.add(
                        ColumnDefinition(
                            i, rs.getString("attname"),
                            mutableSetOf(mapDBTypeToJDBCType(rs.getString("typename")))
                        )
                    )
                    i++
                }
                if (tblColDef.isEmpty()) {
                    throw F2TException(
                        "Column definition of table ${getFullTableName(schema, table.tableName)} not found"
                    )
                }
                return TableDefinition(tblColDef.toSet())
            }
        }
    }

    override fun mapDBTypeToJDBCType(typeName: String): JDBCType {
        return when {
            typeName.compareTo("integer") == 0 -> JDBCType.INTEGER
            typeName.compareTo("bigint") == 0 -> JDBCType.BIGINT
            typeName.compareTo("boolean") == 0 -> JDBCType.BOOLEAN
            typeName.startsWith("timestamp") -> JDBCType.TIMESTAMP
            typeName.compareTo("double precision") == 0 || typeName.compareTo("real") == 0 ||
                    typeName.startsWith("numeric") -> JDBCType.DOUBLE
            else -> JDBCType.CLOB
        }
    }

    override fun isCaseSensitive(): Boolean {
        return true
    }

    override fun isTableExists(table: TableName): Boolean {
        connection.prepareStatement(
            """select schemaname, tablename, tableowner 
            from pg_tables 
            where tablename = ? and schemaname = ? """
        ).use { stmt ->
            val schema = if (table.schema.isBlank()) getDefaultSchema() else table.schema
            stmt.setString(1, table.tableName)
            stmt.setString(2, schema)
            stmt.executeQuery().use { rs ->
                return rs.next()
            }
        }
    }

    override fun getDefaultSchema(): String {
        return "public"
    }

    override fun writeRow(table: TableName, row: DataRow) {
        if (!insertSqlMap.contains(table)) {
            throw F2TException("Way to insert into table ${getFullTableName(table)} is unknown")
        }
        val item = insertSqlMap.getValue(table)
        val sql = item.first
        val def = item.second
        val rows = item.third
        rows.add(row)
        if (rows.size >= getInsertBatchAmount()) {
            val col = def.columns.map { }
            connection.prepareStatement(sql).use { stmt ->
                rows.forEach { row ->
                    setParam(stmt, row, def)
                    stmt.addBatch()
                }
                stmt.executeBatch()
            }
            rows.clear()
        }
    }

    private fun setParam(stmt: PreparedStatement, row: DataRow, tableDefinition: TableDefinition) {
        row.cells.forEachIndexed { i, cell ->
            when (tableDefinition.columns.find { it.index == i }?.inferredType) {
                JDBCType.BOOLEAN -> stmt.setBoolean(i, cell.data as Boolean)
                JDBCType.INTEGER -> stmt.setInt(i, cell.data as Int)
                JDBCType.BIGINT -> stmt.setLong(i, cell.data as Long)
                JDBCType.DECIMAL, JDBCType.FLOAT, JDBCType.DOUBLE -> stmt.setDouble(
                    i, cell.data as Double
                )
                JDBCType.TIMESTAMP -> stmt.setTimestamp(
                    i,
                    Timestamp.from((cell.data as ZonedDateTime).toInstant())
                )
                else -> stmt.setString(i, cell.data.toString())
            }
        }
    }
}
