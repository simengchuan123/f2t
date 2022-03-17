/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.hagoapp.f2t.datafile.parquet

import com.hagoapp.f2t.ColumnDefinition
import com.hagoapp.f2t.DataTable
import com.hagoapp.f2t.util.JDBCTypeUtils
import com.hagoapp.f2t.util.ParquetTypeUtils
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData
import org.apache.parquet.avro.AvroParquetWriter

class ParquetWriter(private val data: DataTable<out ColumnDefinition>, private val config: ParquetWriterConfig) {

    private val schemaValue: String

    init {
        val sb = StringBuilder().append("{")
            .append(""" "type": "record", """)
            .append(""" "name": "${config.name}", """)
            .append(""" "fields": [ """)
            .append(data.columnDefinition.joinToString(", ") { col ->
                """{"name": "${col.name}", "type": "${ParquetTypeUtils.mapToAvroType(col.dataType)}"}"""
            })
            .append("]")
        if (config.namespace != null) {
            sb.append(",")
            sb.append(""" "namespace": "${config.namespace}" """)
        }
        sb.append("}")
        schemaValue = sb.toString()
    }

    fun write() {
        val avroSchema = Schema.Parser().parse(schemaValue)
        val f = LocalOutputFile(config.parquetFileName)
        AvroParquetWriter.builder<GenericData.Record>(f).withSchema(avroSchema)
            .build().use { writer ->
                for (row in data.rows) {
                    val record = GenericData.Record(avroSchema)
                    row.cells.forEachIndexed { i, cell ->
                        val col = data.columnDefinition[i].name
                        println(cell.data)
                        record.put(col, JDBCTypeUtils.toTypedValue(cell.data, data.columnDefinition[i].dataType))
                    }
                    writer.write(record)
                }
            }
    }
}
