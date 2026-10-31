package com.sportpredi

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = Color(0xFF805900),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFE08A),
    onPrimaryContainer = Color(0xFF271900),
    secondary = Color(0xFF695A32),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF2E2B8),
    onSecondaryContainer = Color(0xFF241A00),
    tertiary = Color(0xFF176B3A),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFC8F0D1),
    onTertiaryContainer = Color(0xFF00210D),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF7F5EF),
    onBackground = Color(0xFF1C1B17),
    surface = Color(0xFFFFFCF5),
    onSurface = Color(0xFF1C1B17),
    surfaceVariant = Color(0xFFECE7DA),
    onSurfaceVariant = Color(0xFF49463D),
    outline = Color(0xFF7A766B),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFD54F),
    onPrimary = Color(0xFF2B2100),
    primaryContainer = Color(0xFF5C4600),
    onPrimaryContainer = Color(0xFFFFE7A3),
    secondary = Color(0xFFE6C875),
    onSecondary = Color(0xFF3B2F0B),
    secondaryContainer = Color(0xFF51451F),
    onSecondaryContainer = Color(0xFFF5E3AF),
    tertiary = Color(0xFF81D99A),
    onTertiary = Color(0xFF003919),
    tertiaryContainer = Color(0xFF14532D),
    onTertiaryContainer = Color(0xFFC8F0D1),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF111318),
    onBackground = Color(0xFFE4E2D9),
    surface = Color(0xFF191B20),
    onSurface = Color(0xFFE4E2D9),
    surfaceVariant = Color(0xFF292B31),
    onSurfaceVariant = Color(0xFFC8C6BD),
    outline = Color(0xFF92938B),
)

private val SportPrediShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun SportPrediTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        shapes = SportPrediShapes,
        content = content,
    )
}
