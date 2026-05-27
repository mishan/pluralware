package me.pluralware.wear.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Typography

/**
 * Typography. Watch text needs to be readable at a glance from arm's length
 * with a wrist in motion — so the scale skews larger than phone Material,
 * and weights are pushed up. We stay with the system font for now (legibility
 * on Wear is hard-won; custom fonts are a v2 concern).
 */
internal val PluralWareTypography: Typography = Typography(
    display1 = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    display2 = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.SemiBold),
    display3 = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Medium),
    title1 = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Medium),
    title2 = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium),
    title3 = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
    body1 = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.1.sp),
    body2 = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.1.sp),
    button = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp),
    caption1 = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.2.sp),
    caption2 = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.3.sp),
    caption3 = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.4.sp),
)
