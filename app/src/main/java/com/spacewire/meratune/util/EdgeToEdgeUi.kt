package com.spacewire.meratune.util

import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge

/**
 * Edge-to-edge with transparent bars and dark system-bar icons, whatever the device's dark-mode
 * setting (the app UI is light-only). The no-argument androidx `enableEdgeToEdge` picks icon
 * colours from the system night mode, which would draw white icons over the white screens.
 *
 * `SystemBarStyle.light` also turns off navigation-bar contrast enforcement on API 29+, so the
 * transparent bar shows the root background.
 */
fun ComponentActivity.enableLightEdgeToEdge() = enableEdgeToEdge(
    statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
    navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
)
