package dev.ranmt.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = Sun500,
    onPrimary = Ink900,
    secondary = Mint500,
    onSecondary = Ink900,
    tertiary = Sky500,
    onTertiary = Ink900,
    background = Ink900,
    onBackground = Sand100,
    surface = Ink700,
    onSurface = Sand100,
    surfaceVariant = Ink500,
    onSurfaceVariant = Fog200
)

private val LightColorScheme = lightColorScheme(
    primary = Sun500,
    onPrimary = Ink900,
    secondary = Mint500,
    onSecondary = Ink900,
    tertiary = Coral500,
    onTertiary = Ink900,
    background = Sand100,
    onBackground = Ink900,
    surface = Fog50,
    onSurface = Ink900,
    surfaceVariant = Sand200,
    onSurfaceVariant = Ink700
)

@Composable
fun RANMTTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}