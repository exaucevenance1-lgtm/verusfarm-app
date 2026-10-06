package com.monchamp.verusfarm.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.monchamp.verusfarm.Tone

// Palette : nuit violette, améthyste pour l'action, trois couleurs d'état.
val Menthe = Color(0xFF4ADE9A)   // tout va bien
val Ambre = Color(0xFFF5B942)    // attention (chaleur, internet absent)
val Corail = Color(0xFFFF7A93)   // erreur
val Brume = Color(0xFF8E85A8)    // arrêté

private val Colors = darkColorScheme(
    primary = Color(0xFFA78BFA),
    onPrimary = Color(0xFF1E0B3F),
    background = Color(0xFF0D0816),
    onBackground = Color(0xFFE9E3FF),
    surface = Color(0xFF1A1228),
    onSurface = Color(0xFFE9E3FF),
    surfaceVariant = Color(0xFF241A38),
    onSurfaceVariant = Color(0xFFB9B0D1),
    outline = Color(0xFF3D3158),
    error = Corail
)

fun toneColor(tone: Tone): Color = when (tone) {
    Tone.OK -> Menthe
    Tone.WARN -> Ambre
    Tone.ERROR -> Corail
    Tone.IDLE -> Brume
}

@Composable
fun VerusFarmTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, typography = Typography(), content = content)
}
