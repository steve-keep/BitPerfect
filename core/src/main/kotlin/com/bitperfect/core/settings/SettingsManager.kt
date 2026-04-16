package com.bitperfect.core.settings

import android.content.Context
import android.content.SharedPreferences

class SettingsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("bitperfect_settings", Context.MODE_PRIVATE)

    var isVirtualDriveEnabled: Boolean
        get() = prefs.getBoolean(KEY_VIRTUAL_DRIVE_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_VIRTUAL_DRIVE_ENABLED, value).apply()

    var selectedTestCdIndex: Int
        get() = prefs.getInt(KEY_SELECTED_TEST_CD, 0)
        set(value) = prefs.edit().putInt(KEY_SELECTED_TEST_CD, value).apply()

    companion object {
        private const val KEY_VIRTUAL_DRIVE_ENABLED = "virtual_drive_enabled"
        private const val KEY_SELECTED_TEST_CD = "selected_test_cd"
    }
}
