package dev.sixbridge.desktop.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Shapes
import androidx.compose.material.Typography
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val SixBridgeDarkColors = darkColors(
    primary = Color(0xFFA970FF),
    primaryVariant = Color(0xFF7C4DCE),
    secondary = Color(0xFFFF8799),
    background = Color(0xFF111113),
    surface = Color(0xFF19191D),
    error = Color(0xFFFF6F74),
    onPrimary = Color.White,
    onSecondary = Color(0xFF2B1016),
    onBackground = Color(0xFFF4F1F7),
    onSurface = Color(0xFFF4F1F7),
    onError = Color(0xFF2D090D),
)

private val SixBridgeLightColors = lightColors(
    primary = Color(0xFF6F35C5),
    primaryVariant = Color(0xFF4F2296),
    secondary = Color(0xFFC74760),
    background = Color(0xFFF7F5FA),
    surface = Color.White,
    error = Color(0xFFB3261E),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = Color(0xFF211C26),
    onSurface = Color(0xFF211C26),
    onError = Color.White,
)

private val SixBridgeTypography = Typography(
    h4 = TextStyle(fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold),
    h5 = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    subtitle1 = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    body1 = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    body2 = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    button = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
)

private val SixBridgeShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
)

@Composable
fun DesktopTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colors = if (isSystemInDarkTheme()) SixBridgeDarkColors else SixBridgeLightColors,
        typography = SixBridgeTypography,
        shapes = SixBridgeShapes,
        content = content,
    )
}

@Composable
internal fun desktopVerifiedColor(): Color = if (MaterialTheme.colors.isLight) {
    Color(0xFF19803A)
} else {
    Color(0xFF4BCB70)
}
