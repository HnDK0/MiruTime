package dev.mirutime.uikit

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity

// One threshold for every layout that switches to a stacked/dense variant.
const val LargeTextScale = 1.3f

@Composable
fun isLargeText(): Boolean = LocalDensity.current.fontScale > LargeTextScale
