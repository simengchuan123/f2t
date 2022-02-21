/*
 *  This Source Code Form is subject to the terms of the Mozilla Public
 *  License, v. 2.0. If a copy of the MPL was not distributed with this
 *  file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.hagoapp.f2t;

import java.sql.JDBCType;
import java.util.HashSet;
import java.util.Set;

public class FileColumnDefinition extends ColumnDefinition {

    public FileColumnDefinition() {
        super();
    }

    public FileColumnDefinition(String name) {
        super(name);
    }

    public FileColumnDefinition(String name, Set<JDBCType> possibleTypes) {
        super(name);
        this.possibleTypes = possibleTypes;
    }

    public FileColumnDefinition(String name, Set<JDBCType> possibleTypes, JDBCType candidate0) {
        super(name, candidate0);
        this.possibleTypes = possibleTypes;
    }

    private Set<JDBCType> possibleTypes = new HashSet<>();

    public Set<JDBCType> getPossibleTypes() {
        return possibleTypes;
    }

    public void setPossibleTypes(Set<JDBCType> possibleTypes) {
        this.possibleTypes = possibleTypes;
    }

    @Override
    public String toString() {
        return "FileColumnDefinition{" +
                "possibleTypes=" + possibleTypes +
                ", parent={" + super.toString() +
                "}}";
    }
}
