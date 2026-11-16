package com.infinity.one

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Phone and tablet (round 20). The UI is built for the TV (landscape). On the phone it is therefore
 * landscape by default (rotates with the device between both landscape positions); can be switched off under Settings > General.
 */
object MobilePrefs {
    private const val PREFS = "infinity_mobile"
    var landscape by mutableStateOf(false)
        private set

    /** Phone/tablet instead of TV (no Leanback, touch present). */
    fun isMobile(c: Context): Boolean = !DeviceMode.isTv(c) && c.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)

    fun apply(a: Activity) {
        landscape = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("landscape", false)
        if (!isMobile(a)) return
        a.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    fun set(a: Activity, v: Boolean) {
        landscape = v
        a.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("landscape", v).apply()
        apply(a)
    }
}
