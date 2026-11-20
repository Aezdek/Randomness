package xyz.aezdek.daybook.ui

import androidx.compose.runtime.Composable

/** The browser's own back button navigates the page; Escape closes sheets instead. */
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit
