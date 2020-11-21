/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.hagoapp.f2t.datafile.csv

import com.hagoapp.f2t.F2TException
import com.hagoapp.f2t.datafile.FileInfo
import java.nio.charset.Charset

class FileInfoCsv(filename: String) : FileInfo(filename) {
    var encoding: String? = null
        set(value) {
            try {
                Charset.forName(value)
            } catch (e: Throwable) {
                throw F2TException("$value is not valid charset", e)
            }
        }
    var quote = '"'
    var delimiter = ','
}
