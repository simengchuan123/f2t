/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.hagoapp.f2t

import com.hagoapp.f2t.database.DbConnection
import com.hagoapp.f2t.database.TableName
import com.hagoapp.f2t.datafile.FileInfo
import com.hagoapp.f2t.datafile.ParseResult
import java.lang.reflect.Method
import java.sql.JDBCType

/**
 * This class implements a process that extract data from data file, create according table(if necessary, based on
 * the config) and write data into it. It may add one additional column of timestamp in long integer to identify
 * different running(based on config), or truncate existing data from table(based on config).
 */
class F2TProcess(dataFileRParser: FileParser, dbConnection: DbConnection, f2TConfig: F2TConfig) : ParseObserver {
    private var parser: FileParser = dataFileRParser
    private var connection: DbConnection = dbConnection
    private var config: F2TConfig = f2TConfig
    private val observers = mutableListOf<ProcessObserver>()
    private val logger = F2TLogger.getLogger()
    private var tableMatchedFile = false
    private val table: TableName

    companion object {
        private val methods = mutableMapOf<String, Method>()

        init {
            for (method in ParseObserver::class.java.declaredMethods) {
                methods[method.name] = method
            }
        }
    }

    init {
        if (config.isAddBatch && (config.batchColumnName == null)) {
            logger.error("identity column can't be null when addIdentity set to true")
            throw F2TException("identity column can't be null when addIdentity set to true")
        }
        table = TableName(config.targetTable, config.targetSchema ?: "")
    }

    fun addObserver(observer: ProcessObserver?) {
        if (observer != null && !observers.contains(observer)) {
            observers.add(observer)
        }
    }

    fun run() {
        parser.addWatcher(this)
        parser.run()
    }

    override fun onParseStart(fileInfo: FileInfo) {
        notifyObserver("onParseStart")
    }

    override fun onColumnsParsed(columnDefinitionList: List<ColumnDefinition?>) {
        notifyObserver("onColumnsParsed")
    }

    override fun onColumnTypeDetermined(columnDefinitionList: List<ColumnDefinition?>) {
        val colDef = when {
            config.isAddBatch -> columnDefinitionList.map { it!! }
                .plus(
                    ColumnDefinition(
                        columnDefinitionList.size,
                        config.batchColumnName,
                        mutableSetOf(),
                        JDBCType.BIGINT
                    )
                )
            else -> columnDefinitionList.map { it!! }
        }
        if (connection.isTableExists(table)) {
            val tblDef = connection.getExistingTableDefinition(table)
            if (!tblDef.diff(colDef.toSet(), connection.isCaseSensitive()).noDifference) {
                logger.error("table $table existed and differ from data to be imported, all follow-up database actions aborted")
            } else {
                if (config.isClearTable) {
                    connection.clearTable(table)
                    logger.warn("table ${connection.getFullTableName(table)} cleared")
                }
                connection.prepareInsertion(table, tblDef)
                tableMatchedFile = true
                logger.info("table $table found and matches ${parser.fileInfo.filename}")
            }
        } else {
            if (config.isCreateTableIfNeeded) {
                val tblDef = TableDefinition(colDef.toSet())
                connection.createTable(table, tblDef)
                connection.prepareInsertion(table, tblDef)
                tableMatchedFile = true
                logger.info("table $table created on ${parser.fileInfo.filename}")
            } else {
                logger.error("table $table not existed and auto creation is not enabled, all follow-up database actions aborted")
            }
        }
    }

    override fun onRowRead(row: DataRow) {
        if (tableMatchedFile) {
            connection.writeRow(table, row)
        }
    }

    override fun onParseComplete(fileInfo: FileInfo, result: ParseResult) {}

    override fun onRowError(e: Throwable): Boolean {
        return true
    }

    override fun onRowCountDetermined(rowCount: Int) {}

    private fun notifyObserver(methodName: String, vararg params: Any) {
        observers.forEach { observer ->
            try {
                val method = methods.getValue(methodName)
                method.invoke(observer, *params)
            } catch (ignored: Throwable) {
                //
            }
        }
    }
}
