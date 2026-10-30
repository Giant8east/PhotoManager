package com.example.photomanager.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** 独立于 Material 配色的系统安全区颜色，对应 EhViewer 的 colorPrimaryDark。 */
val LocalStatusBarColor = staticCompositionLocalOf { TealDark }
/** Material 之外的全局颜色角色；页面只消费角色，不判断明暗主题。 */
data class AppColors(
    val toolbarBackground: Color,
    val toolbarContent: Color,
    val headerBackground: Color,
    val headerContent: Color,
    val cardBackground: Color,
    val actionText: Color,
    val accentActionText: Color
)

private val LightAppColors = AppColors(
    toolbarBackground = Teal,
    toolbarContent = Color.White,
    headerBackground = Teal,
    headerContent = Color.White,
    cardBackground = Color(0xFFF5F5F5),
    actionText = Teal,
    accentActionText = AccentPurple
)

private val DarkAppColors = AppColors(
    toolbarBackground = DarkBackground,
    toolbarContent = Color.White,
    headerBackground = Color(0xFF3A3A3A),
    headerContent = Color.White,
    cardBackground = Color(0xFF424242),
    actionText = Color(0xB3FFFFFF),
    accentActionText = Color(0xB3FFFFFF)
)

private val LocalAppColors = staticCompositionLocalOf { LightAppColors }

val appColors: AppColors
    @Composable get() = LocalAppColors.current

private val LightColorScheme = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF5F5F5),
    onPrimaryContainer = Color(0xDE000000),
    secondary = Teal,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF5F5F5),
    onSecondaryContainer = Color(0xDE000000),
    tertiary = AccentPurple,
    onTertiary = Color.White,
    tertiaryContainer = AccentPurple,
    onTertiaryContainer = Color.White,
    background = Color.White,
    onBackground = Color(0xDE000000),
    surface = Color.White,
    onSurface = Color(0xDE000000),
    surfaceVariant = Color(0xFFF5F5F5),
    onSurfaceVariant = Color(0x99000000),
    surfaceTint = Color.Transparent,
    inverseSurface = Color(0xFF323232),
    inverseOnSurface = Color.White,
    inversePrimary = Teal,
    outline = Color(0x8A000000),
    outlineVariant = Color(0x20000000),
    error = Color(0xFFF44336),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    scrim = Color.Black
)

private val DarkColorScheme = darkColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF3A3A3A),
    onPrimaryContainer = Color.White,
    secondary = Teal,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF3A3A3A),
    onSecondaryContainer = Color.White,
    tertiary = Teal,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFF616161),
    onTertiaryContainer = Color.White,
    background = DarkBackground,
    onBackground = Color.White,
    surface = DarkBackground,
    onSurface = Color.White,
    surfaceVariant = Color(0xFF3A3A3A),
    onSurfaceVariant = Color(0xB3FFFFFF),
    surfaceTint = Color.Transparent,
    inverseSurface = Color(0xFFF5F5F5),
    inverseOnSurface = Color(0xDE000000),
    inversePrimary = TealDark,
    outline = Color(0x80FFFFFF),
    outlineVariant = Color(0x20FFFFFF),
    error = Color(0xFFF44336),
    onError = Color.White,
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    scrim = Color.Black
)

@Composable
fun PhotoManagerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    // 固定明暗配色，避免壁纸动态取色覆盖参考主题。
    CompositionLocalProvider(
        LocalStatusBarColor provides if (darkTheme) DarkStatusBar else TealDark,
        LocalAppColors provides if (darkTheme) DarkAppColors else LightAppColors
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
            typography = Typography,
            content = content
        )
    }
}