package org.opencrow.app.ui.screens.chat.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

@Composable
fun ThinkingBubble() {
    val transition = rememberInfiniteTransition(label = "thinking")
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        repeat(3) { i -> ThinkingDot(transition, i) }
    }
    }
}

@Composable
private fun ThinkingDot(transition: InfiniteTransition, index: Int) {
    val phase = index * 200
    // Do NOT use `by` — keep State<Float> so reading .value stays in drawing phase
    val alphaState = transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 1200
                0.25f at 0
                0.25f at phase
                0.85f at (phase + 200)
                0.25f at (phase + 400)
                0.25f at 1200
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "dot_$index"
    )
    Box(
        modifier = Modifier
            .size(8.dp)
            // graphicsLayer reads alphaState.value in drawing phase → no recomposition on frame ticks
            .graphicsLayer { alpha = alphaState.value }
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.onSurfaceVariant)
    )
}
