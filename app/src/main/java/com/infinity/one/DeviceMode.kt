package com.infinity.one

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

enum class FormFactor { TV, TABLET, PHONE }

/**
 * Device and form factor classification:
 * - [FormFactor.TV]: Android TV / Fire TV (D-pad primary, fixed 16:9 10-foot UI)
 * - [FormFactor.TABLET]: Touch device with smallest screen width >= 600 dp
 * - [FormFactor.PHONE]: Mobile phone (touch primary, portrait or landscape)
 */
object DeviceMode {
    /** True if the device is a TV / Leanback / Fire TV */
    fun isTv(context: Context): Boolean {
        val pm = context.packageManager
        return pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            pm.hasSystemFeature("android.software.leanback_only") ||
            pm.hasSystemFeature("amazon.hardware.fire_tv")
    }

    /** Form factor: TV, Tablet (sw >= 600dp), or Phone */
    fun formFactor(context: Context, config: Configuration): FormFactor {
        if (isTv(context)) return FormFactor.TV
        val sw = config.smallestScreenWidthDp
        return if (sw >= 600) FormFactor.TABLET else FormFactor.PHONE
    }

    /** True if current configuration is portrait */
    fun isPortrait(config: Configuration): Boolean =
        config.orientation == Configuration.ORIENTATION_PORTRAIT

    /** Phone in portrait orientation (the specific target for Two-Tabs UI) */
    fun isPhonePortrait(context: Context, config: Configuration): Boolean =
        formFactor(context, config) == FormFactor.PHONE && isPortrait(config)

    /** Composable helpers */
    @Composable
    fun currentFormFactor(): FormFactor = formFactor(LocalContext.current, LocalConfiguration.current)

    @Composable
    fun isPortrait(): Boolean = isPortrait(LocalConfiguration.current)

    @Composable
    fun isTablet(): Boolean = currentFormFactor() == FormFactor.TABLET

    @Composable
    fun isPhonePortrait(): Boolean = isPhonePortrait(LocalContext.current, LocalConfiguration.current)
}
