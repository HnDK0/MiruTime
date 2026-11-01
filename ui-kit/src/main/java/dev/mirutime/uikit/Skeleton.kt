package dev.mirutime.uikit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Loading skeletons: flat surfaceVariant blocks in the EXACT slots the real
// content will take, so first paint never jumps when data lands. No shimmer
// animation at the fake stage — stillness reads calmer than motion here, and
// the blocks only live for one fake-latency beat (~120ms).
@Composable
fun SkeletonBox(
    width: Dp,
    height: Dp,
    modifier: Modifier = Modifier,
    corner: Dp = 6.dp,
) {
    Box(
        modifier = modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    )
}

@Composable
fun SkeletonBar(
    modifier: Modifier = Modifier,
    height: Dp = 12.dp,
    corner: Dp = 6.dp,
) {
    Box(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    )
}
