/*
 * Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.liuqinparts

import android.database.Cursor
import android.database.MatrixCursor
import android.provider.SearchIndexablesContract.INDEXABLES_RAW_COLUMNS
import android.provider.SearchIndexablesContract.INDEXABLES_XML_RES_COLUMNS
import android.provider.SearchIndexablesContract.NON_INDEXABLES_KEYS_COLUMNS
import android.provider.SearchIndexablesProvider
import org.lineageos.liuqinparts.search.SearchIndexRows

class ConfigPanelSearchIndexablesProvider : SearchIndexablesProvider() {
    override fun onCreate(): Boolean = true

    override fun queryXmlResources(projection: Array<String?>?): Cursor =
        MatrixCursor(INDEXABLES_XML_RES_COLUMNS).also { cursor ->
            SearchIndexRows.addXmlResource(
                cursor = cursor,
                rank = 1,
                xmlResId = R.xml.stylus_settings,
                targetClass = StylusSettingsActivity::class.java,
                iconResId = R.drawable.ic_stylus_note,
            )
        }

    override fun queryRawData(projection: Array<String?>?): Cursor =
        MatrixCursor(INDEXABLES_RAW_COLUMNS)

    override fun queryNonIndexableKeys(projection: Array<String?>?): Cursor =
        MatrixCursor(NON_INDEXABLES_KEYS_COLUMNS)
}
