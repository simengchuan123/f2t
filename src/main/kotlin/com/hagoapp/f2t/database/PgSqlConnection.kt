/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.hagoapp.f2t.database

import com.hagoapp.f2t.*
import com.hagoapp.f2t.util.ColumnMatcher
import java.sql.*
import java.sql.JDBCType.*

/**
 * Database operations implementation for PostgreSQL.
 *
 * @author Chaojun Sun
 * @since 0.1
 */
class PgSqlConnection : DbConnection() {

    companion object {
        private const val PGSQL_DRIVER_CLASS_NAME = "org.postgresql.Driver"
    }

    override fun getDriverName(): String {
        return PGSQL_DRIVER_CLASS_NAME
    }

    override fun getAvailableTables(): Map<String, List<TableName>> {
        try {
            val ret = mutableMapOf<String, MutableList<TableName>>()
            val sql = """
                    select schemaname, tablename, tableowner from pg_tables 
                    where schemaname<>'pg_catalog' and schemaname<>'information_schema'
                    order by schemaname, tablename
                    """
            connection.prepareStatement(sql).use { st ->
                st.executeQuery().use { rs ->
                    while (rs.next()) {
                        val schema = rs.getString("schemaname")
                        val table = rs.getString("tablename")
                        if (!ret.containsKey(schema)) {
                            ret[schema] = mutableListOf()
                        }
                        ret.getValue(schema).add(TableName(table, schema))
                    }
                    return ret.ifEmpty { mapOf(getDefaultSchema() to listOf()) }
                }
            }
        } catch (ex: SQLException) {
            return mapOf()
        }
    }

    override fun listDatabases(): List<String> {
        try {
            val ret = mutableListOf<String>()
            connection.prepareStatement("select datname from pg_database where datistemplate = false and datname != 'postgres'")
                .use { st ->
                    st.executeQuery().use { rs ->
                        while (rs.next()) {
                            ret.add(rs.getString("datname"))
                        }
                        return ret
                    }
                }
        } catch (ex: SQLException) {
            return listOf()
        }
    }

    override fun clearTable(table: TableName): Pair<Boolean, String?> {
        try {
            connection.prepareStatement("truncate table ${getFullTableName(table)}").use { st ->
                return Pair(st.execute(), null)
            }
        } catch (ex: SQLException) {
            return Pair(false, ex.message)
        }
    }

    override fun dropTable(tableName: String): Pair<Boolean, String?> {
        try {
            connection.prepareStatement("drop table if exists $tableName").use { st ->
                return Pair(st.execute(), null)
            }
        } catch (ex: SQLException) {
            return Pair(false, ex.message)
        }
    }

    override fun getWrapperCharacter(): Pair<String, String> {
        return Pair("\"", "\"")
    }

    override fun escapeNameString(name: String): String {
        return name.replace("\"", "\"\"")
    }

    override fun createTable(table: TableName, tableDefinition: TableDefinition<out ColumnDefinition>) {
        val tableFullName = getFullTableName(table)
        val wrapper = getWrapperCharacter()
        val defStr = tableDefinition.columns.joinToString(", ") { colDef ->
            "${wrapper.first}${escapeNameString(colDef.name)}${wrapper.second}" + " ${
                convertJDBCTypeToDBNativeType(
                    colDef.dataType,
                    colDef.typeModifier
                )
            }"
        }
        val sql = "create table $tableFullName ($defStr)"
        logger.debug("create table $tableFullName using: $sql")
        connection.prepareStatement(sql).use { it.execute() }
    }

    override fun convertJDBCTypeToDBNativeType(aType: JDBCType, modifier: ColumnTypeModifier): String {
        return when (aType) {
            BOOLEAN -> "boolean"
            DATE -> "date"
            TIME, TIME_WITH_TIMEZONE -> "time with time zone"
            TIMESTAMP, TIMESTAMP_WITH_TIMEZONE -> "timestamp with time zone"
            TINYINT, SMALLINT -> "smallint"
            BIGINT -> "bigint"
            INTEGER -> "int"
            DOUBLE, FLOAT -> "double precision"
            DECIMAL -> "numeric(${modifier.precision}, ${modifier.scale})"
            CHAR, NCHAR -> "char(${modifier.maxLength})"
            VARCHAR, NVARCHAR -> "varchar(${modifier.maxLength})"
            else -> "text"
        }
    }

    override fun getExistingTableDefinition(table: TableName): TableDefinition<ColumnDefinition> {
        val sql = """select
            a.attname, format_type(a.atttypid, a.atttypmod) as typename, a.attnotnull
            from pg_attribute as a
            inner join pg_class as c on c.oid = a.attrelid
            inner join pg_namespace as n on n.oid = c.relnamespace
            where a.attnum > 0 and not a.attisdropped and c.relkind= 'r' and c.relname = ? and n.nspname = ?"""
        connection.prepareStatement(sql).use { stmt ->
            val schema = table.schema.ifBlank { getDefaultSchema() }
            stmt.setString(1, table.tableName)
            stmt.setString(2, schema)
            stmt.executeQuery().use { rs ->
                val tblColDef = mutableListOf<ColumnDefinition>()
                while (rs.next()) {
                    val typeStr = rs.getString("typename")
                    val colDef = ColumnDefinition(rs.getString("attname"), mapDBTypeToJDBCType(typeStr))
                    colDef.databaseTypeName = typeStr
                    colDef.typeModifier.isNullable = !rs.getBoolean("attnotnull")
                    val i = parseModifier(typeStr)
                    colDef.typeModifier.maxLength = i.first
                    colDef.typeModifier.precision = i.second
                    colDef.typeModifier.scale = i.third
                    tblColDef.add(colDef)
                }
                if (tblColDef.isEmpty()) {
                    throw F2TException(
                        "Column definition of table ${getFullTableName(schema, table.tableName)} not found"
                    )
                }
                val ret = TableDefinition(tblColDef)
                ret.caseSensitive = isCaseSensitive()
                ret.primaryKey = findUniqueConstraint(table, ret.columns, "p").firstOrNull()
                ret.uniqueConstraints = findUniqueConstraint(table, ret.columns)
                return ret
            }
        }
    }

    private fun findUniqueConstraint(
        table: TableName,
        refColumns: List<ColumnDefinition>,
        type: String = "u"
    ): Set<TableUniqueDefinition<ColumnDefinition>> {
        val sql = """
            select 
            a.attname, a.attnum, con.conname, con.conkey
            from 
            pg_catalog.pg_constraint as con
            inner join pg_namespace as n on con.connamespace = n.oid
            inner join pg_class as c on con.conrelid = c.oid
            inner join pg_attribute a on a.attrelid = c.oid and array_position(con.conkey, a.attnum) >= 0
            where 
            n.nspname = ? and c.relname = ? and con.contype = ?
            and a.attnum > 0 and not a.attisdropped and c.relkind= 'r'
        """.trimIndent()
        connection.prepareStatement(sql).use { st ->
            st.setString(1, table.schema)
            st.setString(2, table.tableName)
            st.setString(3, type)
            val map = mutableMapOf<String, MutableList<String>>()
            val colOrderMap = mutableMapOf<String, MutableList<Int>>()
            st.executeQuery().use { rs ->
                while (rs.next()) {
                    val keyName = rs.getString("conname")
                    val colName = rs.getString("attname")
                    val attNum = rs.getInt("attnum")
                    val conKey = rs.getArray("conkey").array
                    if (conKey !is Array<*>) {
                        throw UnsupportedOperationException("PostgreSQL attNum err: $conKey")
                    }
                    if (!map.contains(keyName)) {
                        val attNumbers = conKey.map { it!!.toString().toInt() }.toMutableList()
                        val fields = attNumbers.map { if (attNum == it) colName else "" }.toMutableList()
                        map[keyName] = fields
                        colOrderMap[keyName] = attNumbers
                    } else {
                        val index = colOrderMap.getValue(keyName).indexOf(attNum)
                        if (index < 0) {
                            throw UnsupportedOperationException("PostgreSQL key num error: $colName $attNum in Key $keyName")
                        }
                        map.getValue(keyName)[index] = colName
                    }
                }
            }
            val colMatcher = ColumnMatcher.getColumnMatcher(isCaseSensitive())
            return map.map { (constraint, colNames) ->
                val columns = colNames.map { col -> refColumns.first { ref -> colMatcher.invoke(ref.name, col) } }
                TableUniqueDefinition(constraint, columns, isCaseSensitive())
            }.toSet()
        }
    }

    private fun parseModifier(typeStr: String): Triple<Int, Int, Int> {
        return when {
            typeStr.startsWith("character") -> {
                val m = Regex(".+?\\((\\d+)\\)").matchEntire(typeStr)
                if ((m != null) && m.groupValues.isNotEmpty()) Triple(m.groupValues.last().toInt(), 0, 0)
                else Triple(0, 0, 0)
            }
            typeStr.startsWith("numeric") -> {
                val m = Regex(".+?\\((\\d+),(\\d+)\\)").matchEntire(typeStr)
                if ((m != null) && (m.groupValues.size > 2))
                    Triple(0, m.groupValues[1].toInt(), m.groupValues[2].toInt())
                else Triple(0, 0, 0)
            }
            else -> Triple(0, 0, 0)
        }
    }

    override fun mapDBTypeToJDBCType(typeName: String): JDBCType {
        return when {
            typeName == "smallint" -> SMALLINT
            typeName.compareTo("integer") == 0 -> INTEGER
            typeName.compareTo("bigint") == 0 -> BIGINT
            typeName.compareTo("boolean") == 0 -> BOOLEAN
            typeName.startsWith("timestamp with time zone") -> TIMESTAMP_WITH_TIMEZONE
            typeName.startsWith("timestamp") -> TIMESTAMP
            typeName.startsWith("time") -> TIME_WITH_TIMEZONE
            typeName == "date" -> DATE
            typeName.compareTo("double precision") == 0 || typeName.compareTo("real") == 0 -> DOUBLE
            typeName.startsWith("numeric") -> DECIMAL
            typeName.startsWith("character varying") -> VARCHAR
            typeName.startsWith("character") -> CHAR
            typeName.startsWith("text") -> CLOB
            else -> throw F2TException("type $typeName not supported yet")
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
            val schema = table.schema.ifBlank { getDefaultSchema() }
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
}
