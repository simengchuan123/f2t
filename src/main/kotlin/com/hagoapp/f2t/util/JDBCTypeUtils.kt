/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.hagoapp.f2t.util

import com.hagoapp.util.EncodingUtils
import java.sql.JDBCType

class JDBCTypeUtils {
    companion object {

        fun combinePossibleTypes(a: Set<JDBCType>, b: Set<JDBCType>): Set<JDBCType> {
            return when {
                a.isEmpty() -> b
                b.isEmpty() -> a
                else -> {
                    val l = a.intersect(b)
                    l.ifEmpty { setOf(JDBCType.NCLOB) }
                }
            }
        }

        fun toTypedValue(value: Any?, outType: JDBCType): Any? {
            return when (value) {
                null -> null
                is String -> stringToTypedValue(value, outType)
                else -> value
            }
        }

        private val JDBC_TEXT_TYPES = listOf(
            JDBCType.CHAR, JDBCType.VARCHAR, JDBCType.CLOB,
            JDBCType.NCHAR, JDBCType.NVARCHAR, JDBCType.NCLOB
        )

        private fun stringToTypedValue(value: String, outType: JDBCType): Any? {
            if (value.isBlank() && !JDBC_TEXT_TYPES.contains(outType)) {
                return null
            }
            return when (outType) {
                JDBCType.TINYINT -> value.toByte()
                JDBCType.SMALLINT -> value.toShort()
                JDBCType.INTEGER -> value.toInt()
                JDBCType.BIGINT -> value.toLong()
                JDBCType.DOUBLE, JDBCType.DECIMAL, JDBCType.FLOAT -> value.toDouble()
                JDBCType.TIMESTAMP_WITH_TIMEZONE -> DateTimeTypeUtils.stringToDateTimeOrNull(value)!!
                JDBCType.BOOLEAN -> BooleanTypeUtils.toBoolean(value)
                JDBCType.DATE -> DateTimeTypeUtils.stringToDateOrNull(value)!!
                JDBCType.TIME -> DateTimeTypeUtils.stringToTimeOrNull(value)!!
                else -> value
            }
        }

        fun guessTypes(value: String?): List<JDBCType> {
            val dl = mutableListOf<JDBCType>()
            if (value == null) {
                return dl
            }
            dl.add(JDBCType.NCLOB)
            dl.add(JDBCType.NVARCHAR)
            dl.add(JDBCType.NCHAR)
            if (EncodingUtils.isAsciiText(value)) {
                dl.add(JDBCType.CLOB)
                dl.add(JDBCType.VARCHAR)
                dl.add(JDBCType.CHAR)
            }
            dl.addAll(guessIntTypes(value))
            dl.addAll(guessFloatTypes(value))
            if (BooleanTypeUtils.isPossibleBooleanValue(value)) {
                dl.add(JDBCType.BOOLEAN)
            }
            dl.addAll(guessDateTimeTypes(value))
            return dl
        }

        private fun guessDateTimeTypes(value: String): Set<JDBCType> {
            val ret = mutableSetOf<JDBCType>()
            if (DateTimeTypeUtils.isDateTime(value)) {
                ret.add(JDBCType.TIMESTAMP_WITH_TIMEZONE)
            }
            if (DateTimeTypeUtils.isDate(value)) {
                ret.add(JDBCType.DATE)
            }
            if (DateTimeTypeUtils.isTime(value)) {
                ret.add(JDBCType.TIME)
            }
            return ret
        }

        private fun guessIntTypes(value: String): Set<JDBCType> {
            return guessIntTypes(value.toLongOrNull())
        }

        fun guessIntTypes(l: Long?): Set<JDBCType> {
            val ret = mutableSetOf<JDBCType>()
            if (l != null) {
                ret.add(JDBCType.BIGINT)
                if ((l <= Int.MAX_VALUE.toLong()) && (l >= Int.MIN_VALUE.toLong())) {
                    ret.add(JDBCType.INTEGER)
                }
                if ((l <= Short.MAX_VALUE.toLong()) && (l >= Short.MIN_VALUE.toLong())) {
                    ret.add(JDBCType.SMALLINT)
                }
                if ((l <= Byte.MAX_VALUE.toLong()) && (l >= Byte.MIN_VALUE.toLong())) {
                    ret.add(JDBCType.TINYINT)
                }
            }
            return ret
        }

        private fun guessFloatTypes(value: String): Set<JDBCType> {
            val ret = mutableSetOf<JDBCType>()
            val l = value.toBigDecimalOrNull()
            if (l != null) {
                ret.add(JDBCType.DECIMAL)
                if ((l <= Double.MAX_VALUE.toBigDecimal()) && (l >= Double.MIN_VALUE.toBigDecimal())) {
                    ret.addAll(guessFloatTypes(l.toDouble()))
                }
            }
            return ret
        }

        fun guessFloatTypes(value: Double?): Set<JDBCType> {
            val ret = mutableSetOf<JDBCType>()
            if (value != null) {
                ret.add(JDBCType.DOUBLE)
                if ((value <= Float.MAX_VALUE.toDouble()) && (value >= Float.MIN_VALUE.toDouble())) {
                    ret.add(JDBCType.FLOAT)
                }
            }
            return ret
        }

    }

}
