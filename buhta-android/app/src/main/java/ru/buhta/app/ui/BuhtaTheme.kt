package ru.buhta.app.ui

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat
import ru.buhta.app.ui.design.CsqttDarkExtendedColors
import ru.buhta.app.ui.design.CsqttLightExtendedColors
import ru.buhta.app.ui.design.CsqttMaterialShapes
import ru.buhta.app.ui.design.LocalCsqttExtendedColors

object BuhtaColors {
    val connected = Color(0xFF4CAF50)
    val warning = Color(0xFFFFA726)
}

/** Скины: brand (как в Маяке), indigo, coffee, forest. */
val skins = listOf("brand" to "Бренд", "indigo" to "Индиго", "coffee" to "Кофе", "forest" to "Лес")

private fun scheme(skin: String, dark: Boolean): ColorScheme = when (skin) {
    "indigo" -> if (dark) IndigoDarkColorScheme else IndigoLightColorScheme
    "coffee" -> if (dark) DarkColorScheme else LightColorScheme
    "forest" -> if (dark) ForestDarkColorScheme else ForestLightColorScheme
    else -> if (dark) BrandDarkColorScheme else BrandLightColorScheme
}

@Composable
fun BuhtaTheme(mode: String, skin: String, content: @Composable () -> Unit) {
    val dark = when (mode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val colorScheme = remember(dark, skin) { scheme(skin, dark) }

    val view = LocalView.current
    if (!view.isInEditMode) {
        @Suppress("DEPRECATION")
        SideEffect {
            val window = (view.context as Activity).window
            val nav = if (dark) Color.Transparent else lerp(colorScheme.background, colorScheme.surface, 0.55f)
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = nav.toArgb()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
                window.isStatusBarContrastEnforced = false
            }
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    val d = LocalDensity.current
    val fixed = remember(d.density) { Density(density = d.density, fontScale = 1f) }
    CompositionLocalProvider(
        LocalCsqttExtendedColors provides if (dark) CsqttDarkExtendedColors else CsqttLightExtendedColors,
        LocalDensity provides fixed,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = BuhtaTypography,
            shapes = CsqttMaterialShapes,
            content = content,
        )
    }
}
