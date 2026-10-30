package com.jhanvitrends.shop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Same palette as the website.
val BrandDark = Color(0xFF0F3D3E)
val BrandTeal = Color(0xFF0F766E)
val BrandMint = Color(0xFF5EEAD4)
val Deal = Color(0xFFC4161C)
val Ok = Color(0xFF067D62)
val Muted = Color(0xFF565959)
val Page = Color(0xFFE9EDF0)

private val Colors = lightColorScheme(
    primary = BrandTeal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCCFBF1),
    onPrimaryContainer = BrandDark,
    secondary = BrandMint,
    onSecondary = BrandDark,
    background = Page,
    surface = Color.White,
    surfaceVariant = Color(0xFFF2F4F7),
    error = Deal,
)

@Composable
fun JhanviTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}
