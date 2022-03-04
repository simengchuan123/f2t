/*
 *  This Source Code Form is subject to the terms of the Mozilla Public
 *  License, v. 2.0. If a copy of the MPL was not distributed with this
 *  file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.hagoapp.f2t.compare.column

import com.hagoapp.f2t.ColumnDefinition
import com.hagoapp.f2t.FileColumnDefinition
import com.hagoapp.f2t.compare.ColumnComparator
import com.hagoapp.f2t.compare.CompareColumnResult
import java.sql.JDBCType
import java.sql.JDBCType.*

class ToTimeComparator : ColumnComparator.Comparator {
    override fun dataCanLoadFrom(
        fileColumnDefinition: FileColumnDefinition,
        dbColumnDefinition: ColumnDefinition,
        vararg extra: String
    ): CompareColumnResult {
        return CompareColumnResult(
            isTypeMatched = fileColumnDefinition.dataType == dbColumnDefinition.dataType,
            when {
                fileColumnDefinition.dataType == TIME -> true
                fileColumnDefinition.possibleTypes.contains(TIME) -> true
                else -> false
            }
        )
    }

    override fun supportSourceTypes(): Set<JDBCType> {
        return setOf(
            BOOLEAN,
            CHAR, VARCHAR, CLOB, NCHAR, NVARCHAR, NCLOB,
            SMALLINT, TINYINT, INTEGER, BIGINT,
            FLOAT, DOUBLE, DECIMAL,
            TIMESTAMP_WITH_TIMEZONE, DATE, TIME, TIMESTAMP
        )
    }

    override fun supportDestinationTypes(): Set<JDBCType> {
        return setOf(TIME)
    }
}
