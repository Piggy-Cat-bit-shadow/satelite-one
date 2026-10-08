package com.interstellar.proxy.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.geometry.Offset
import com.interstellar.proxy.ui.theme.LocalInterstellarColors
import com.interstellar.proxy.ui.theme.Motion

/**
 * Clickable with iOS-style press feedback: scales down slightly while
 * pressed, light haptic on tap, no ripple.
 */
fun Modifier.pressableClick(onClick: () -> Unit): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.965f else 1f,
        animationSpec = Motion.snappy(),
        label = "press",
    )
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(
            interactionSource = interaction,
            indication = null,
        ) {
            onClick()
        }
}

/**
 * Glass segmented control (satelite's GlassSeg): a frosted thumb that
 * slides between segments on a deep track, pill geometry.
 */
@Composable
fun SegmentedControl(
    items: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    controlHeight: Dp = 36.dp,
    enabled: Boolean = true,
) {
    val colors = LocalInterstellarColors.current

    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = modifier
            .height(controlHeight)
            .clip(RoundedCornerShape(50))
            .background(colors.bgDeep)
            .alpha(if (enabled) 1f else 0.45f)
            .padding(3.dp),
    ) {
        val segWidth = maxWidth / items.size
        val thumbX by androidx.compose.animation.core.animateDpAsState(
            targetValue = segWidth * selected.coerceIn(0, items.size - 1),
            animationSpec = spring(
                dampingRatio = 0.85f,
                stiffness = 420f,
            ),
            label = "segThumb",
        )

        // frosted sliding thumb under the labels
        Box(
            modifier = Modifier
                .offset(x = thumbX)
                .width(segWidth)
                .fillMaxHeight()
                .padding(2.dp)
                .clip(RoundedCornerShape(50))
                .background(colors.surfaceHigh)
                .border(1.dp, colors.border, RoundedCornerShape(50)),
        )

        // labels
        Row(modifier = Modifier.fillMaxSize()) {
            items.forEachIndexed { index, label ->
                val isSelected = index == selected
                val fg by animateColorAsState(
                    targetValue = if (isSelected) colors.text else colors.textSecondary,
                    animationSpec = tween(Motion.DURATION_MEDIUM, easing = Motion.Ease),
                    label = "segFg",
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        // disabled segments ignore taps entirely (the saved value
                        // stays untouched; only the ability to change it goes away)
                        .clickable(
                            enabled = enabled,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onSelect(index) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        color = fg,
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** Shimmer skeleton block for loading states. */
@Composable
fun ShimmerBox(modifier: Modifier = Modifier, cornerRadius: Dp = 12.dp) {
    val colors = LocalInterstellarColors.current
    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress by transition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(1200, easing = Motion.Ease), RepeatMode.Restart),
        label = "shimmerX",
    )
    val light = colors.bg.luminance() > 0.5f
    val base = if (light) Color(0xFFE2E5EA) else Color(0xFF1B202A)
    val highlight = if (light) Color(0xFFF3F5F8) else Color(0xFF242A36)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(
                Brush.linearGradient(
                    colors = listOf(base, highlight, base),
                    start = Offset(progress * 400f, 0f),
                    end = Offset(progress * 400f + 250f, 120f),
                ),
            ),
    )
}

private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

/** Section header with trailing optional content. */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = LocalInterstellarColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            color = colors.textTertiary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

/** Small circular status dot with breathing glow when active. */
@Composable
fun BreathingDot(active: Boolean, modifier: Modifier = Modifier, size: Dp = 12.dp) {
    val colors = LocalInterstellarColors.current
    val transition = rememberInfiniteTransition(label = "breath")
    val breath by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = Motion.Ease), RepeatMode.Reverse),
        label = "breathAlpha",
    )
    val color = if (active) colors.primary else colors.textTertiary
    Box(modifier = modifier.size(size * 2.1f), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(size * 2.1f)
                .graphicsLayer { alpha = if (active) breath * 0.35f else 0.12f }
                .clip(CircleShape)
                .background(color),
        )
        Box(
            modifier = Modifier
                .size(size)
                .graphicsLayer { alpha = if (active) 0.7f + 0.3f * breath else 1f }
                .clip(CircleShape)
                .background(color),
        )
    }
}
