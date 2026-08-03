/*
 * Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.liuqinparts.search

import android.database.MatrixCursor
import android.provider.SearchIndexablesContract.COLUMN_INDEX_XML_RES_CLASS_NAME
import android.provider.SearchIndexablesContract.COLUMN_INDEX_XML_RES_ICON_RESID
import android.provider.SearchIndexablesContract.COLUMN_INDEX_XML_RES_INTENT_ACTION
import android.provider.SearchIndexablesContract.COLUMN_INDEX_XML_RES_INTENT_TARGET_CLASS
import android.provider.SearchIndexablesContract.COLUMN_INDEX_XML_RES_INTENT_TARGET_PACKAGE
import android.provider.SearchIndexablesContract.COLUMN_INDEX_XML_RES_RANK
import android.provider.SearchIndexablesContract.COLUMN_INDEX_XML_RES_RESID
import android.provider.SearchIndexablesContract.INDEXABLES_XML_RES_COLUMNS

object SearchIndexRows {
    private const val TARGET_PACKAGE = "org.lineageos.liuqinparts"
    private const val INTENT_ACTION = "org.lineageos.liuqinparts.STYLUS_SETTINGS"

    fun addXmlResource(
        cursor: MatrixCursor,
        rank: Int,
        xmlResId: Int,
        targetClass: Class<*>,
        iconResId: Int,
    ) {
        val row = arrayOfNulls<Any>(INDEXABLES_XML_RES_COLUMNS.size)
        row[COLUMN_INDEX_XML_RES_RANK] = rank
        row[COLUMN_INDEX_XML_RES_RESID] = xmlResId
        row[COLUMN_INDEX_XML_RES_CLASS_NAME] = null
        row[COLUMN_INDEX_XML_RES_ICON_RESID] = iconResId
        row[COLUMN_INDEX_XML_RES_INTENT_ACTION] = INTENT_ACTION
        row[COLUMN_INDEX_XML_RES_INTENT_TARGET_PACKAGE] = TARGET_PACKAGE
        row[COLUMN_INDEX_XML_RES_INTENT_TARGET_CLASS] = targetClass.name
        cursor.addRow(row)
    }
}
