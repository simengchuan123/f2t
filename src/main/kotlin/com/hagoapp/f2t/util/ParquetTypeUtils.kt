/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.hagoapp.f2t.util

import org.apache.avro.Schema.Field
import org.apache.avro.Schema.Type
import java.sql.JDBCType
import java.sql.JDBCType.*

/**
 * Utility class for parquet file, using apache impl.
 *
 * @author Chaojun Sun
 * @since 0.6
 */
class ParquetTypeUtils {
    companion object {

        /**
         * This interface is for user to define their own converter from Avro type to JDBC Type.
         */
        interface TypeToJDBCTypeMapper {
            fun mapToJDBCTypes(): Set<JDBCType>
            fun supportedTypes(): Set<Type>
        }

        private val extraMappers = mutableMapOf<Type, TypeToJDBCTypeMapper>()

        /**
         * Register customized avro type to JDBC type mapper.
         *
         * @param mapper customized mapper
         */
        fun registerExtraMapper(mapper: TypeToJDBCTypeMapper) {
            for (x in mapper.supportedTypes()) {
                extraMappers[x] = mapper
            }
        }

        /**
         * Map JDBCType to corresponding avro type.
         *
         * @param input JDBC type
         * @return avro type name
         */
        fun mapToAvroType(input: JDBCType): String {
            return when (input) {
                TINYINT, SMALLINT, INTEGER -> "int"
                BIGINT -> "long"
                BOOLEAN -> "boolean"
                FLOAT -> "float"
                DOUBLE, DECIMAL -> "double"
                BINARY, VARBINARY -> "bytes"
                TIME, TIME_WITH_TIMEZONE, DATE, TIMESTAMP_WITH_TIMEZONE, TIMESTAMP,
                CHAR, VARCHAR, CLOB, NCHAR, NVARCHAR, NCLOB -> "string"
                else -> throw UnsupportedOperationException("unsupported type $input")
            }
        }

        /**
         * Map avro type to JDBC type.
         *
         * @param input avro type
         * @return JDBC type
         */
        fun mapAvroTypeToJDBCType(input: Type): JDBCType {
            return when (input) {
                Type.INT -> INTEGER
                Type.LONG -> BIGINT
                Type.STRING -> CLOB
                Type.FLOAT -> FLOAT
                Type.DOUBLE -> DOUBLE
                Type.BYTES -> VARBINARY
                Type.BOOLEAN -> BOOLEAN
                else -> throw UnsupportedOperationException("unsupported type $input")
            }
        }

        /**
         * Guess all possible JDBC types those can be used to present value in given avro field. It will consider
         * explicit avro type defined in avro field, then consider the actual value if the avro type is generic
         * string.
         *
         * @param field avro field
         * @return set of possible JDBC types
         */
        fun guessJDBCType(field: Field): Set<JDBCType> {
            val type = field.schema().type
            val ret = mutableSetOf<JDBCType>()
            if (extraMappers.containsKey(type)) {
                ret.addAll(extraMappers.getValue(type).mapToJDBCTypes())
            }
            when (type) {
                Type.BOOLEAN -> ret.addAll(setOf(BOOLEAN, TINYINT))
                Type.DOUBLE -> ret.add(DOUBLE)
                Type.FLOAT -> ret.add(FLOAT)
                Type.BYTES -> ret.add(VARBINARY)
                Type.INT -> ret.addAll(setOf(INTEGER, BIGINT))
                Type.LONG -> ret.add(BIGINT)
                Type.STRING -> JDBCTypeUtils.guessTypes(field.name())
                else -> JDBCTypeUtils.guessTypes(field.name())
            }
            return ret
        }

    }
}
