package pw.kmr.sonnet.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = SonnetAmberDark,
    onPrimary = SonnetOnAmberDark,
    primaryContainer = SonnetAmberContainerDark,
    onPrimaryContainer = SonnetOnAmberContainerDark,
    secondary = SonnetUmberDark,
    onSecondary = SonnetOnUmberDark,
    secondaryContainer = SonnetUmberContainerDark,
    onSecondaryContainer = SonnetOnUmberContainerDark,
    tertiary = SonnetSageDark,
    onTertiary = SonnetOnSageDark,
    tertiaryContainer = SonnetSageContainerDark,
    onTertiaryContainer = SonnetOnSageContainerDark,
    background = SonnetObsidianDark,
    onBackground = SonnetPaperDark,
    surface = SonnetObsidianDark,
    onSurface = SonnetPaperDark,
    surfaceVariant = SonnetObsidianVariantDark,
    onSurfaceVariant = SonnetPaperVariantDark,
    outline = SonnetOutlineDark
)

private val LightColorScheme = lightColorScheme(
    primary = SonnetAmberLight,
    onPrimary = SonnetOnAmberLight,
    primaryContainer = SonnetAmberContainerLight,
    onPrimaryContainer = SonnetOnAmberContainerLight,
    secondary = SonnetUmberLight,
    onSecondary = SonnetOnUmberLight,
    secondaryContainer = SonnetUmberContainerLight,
    onSecondaryContainer = SonnetOnUmberContainerLight,
    tertiary = SonnetSageLight,
    onTertiary = SonnetOnSageLight,
    tertiaryContainer = SonnetSageContainerLight,
    onTertiaryContainer = SonnetOnSageContainerLight,
    background = SonnetParchmentLight,
    onBackground = SonnetInkLight,
    surface = SonnetParchmentLight,
    onSurface = SonnetInkLight,
    surfaceVariant = SonnetParchmentVariantLight,
    onSurfaceVariant = SonnetInkVariantLight,
    outline = SonnetOutlineLight
)

@Composable
fun SonnetTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor -> {
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
