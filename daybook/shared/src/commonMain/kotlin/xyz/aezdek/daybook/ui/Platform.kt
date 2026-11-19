package xyz.aezdek.daybook.ui

import androidx.compose.runtime.Composable

/** Routes the system back gesture to [onBack] while [enabled]. Desktop uses Escape instead. */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)
