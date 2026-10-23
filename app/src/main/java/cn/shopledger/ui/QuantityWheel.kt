package cn.shopledger.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.shopledger.Rules
import java.math.BigDecimal
import kotlin.math.abs

/** Own the complete gesture in Compose, including drags past either endpoint. */
@Composable fun QuantityWheel(current: String, enabled: Boolean, description: String,
    strength: Int = 1, interact: () -> Unit, scrolling: (Boolean) -> Unit, select: (String) -> Unit) {
    val selected = runCatching { Rules.parseQuantity(current) }.getOrDefault(10)
    val onSelect by rememberUpdatedState(select)
    val onInteract by rememberUpdatedState(interact)
    val onScrolling by rememberUpdatedState(scrolling)
    val latestSelected by rememberUpdatedState(selected)
    val latestStrength by rememberUpdatedState(strength)
    val view = LocalView.current
    val haptics = remember(view) { WheelHaptics(view) }
    val rowHeight = with(LocalDensity.current) { 24.sp.toDp().coerceAtLeast(28.dp) }
    val rowPixels = with(LocalDensity.current) { rowHeight.toPx() }
    var dragging by remember { mutableStateOf(false) }
    var offset by remember { mutableFloatStateOf(0f) }
    val alignedOffset by animateFloatAsState(if (dragging) offset else 0f,
        animationSpec = if (dragging) snap() else spring(dampingRatio = 1f, stiffness = 550f), label = "wheelAlignment")
    fun previous(value: Int): Int? = (((value - 1) / 5) * 5).takeIf { it >= 5 }
    fun next(value: Int): Int? = ((value / 5 + 1) * 5).takeIf { it <= Rules.MAX_QUANTITY_TENTHS }
    fun label(value: Int) = BigDecimal.valueOf(value.toLong(), 1).toPlainString()
    fun choose(value: Int) { onSelect(label(value)); haptics.tick(latestStrength) }
    Box(Modifier.width(60.dp).height(rowHeight * 3).clipToBounds()
        .clearAndSetSemantics {
            contentDescription = description
            stateDescription = "${Rules.quantityText(selected)}次"
            if (!enabled) disabled()
            customActions = if (!enabled) emptyList() else listOf(
                CustomAccessibilityAction("减少0.5次") { previous(latestSelected)?.let { choose(it); true } ?: false },
                CustomAccessibilityAction("增加0.5次") { next(latestSelected)?.let { choose(it); true } ?: false })
        }
        .pointerInput(enabled, rowPixels) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                // Consume before the outer list/text field can claim the same drag.
                down.consume()
                onInteract()
                var value = latestSelected
                var travel = 0f
                var moved = false
                offset = alignedOffset
                dragging = true
                onScrolling(true)
                try {
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val active = event.changes.firstOrNull { it.id == down.id }
                        if (active != null && active.pressed) {
                            val delta = active.positionChange().y
                            travel += delta
                            if (!moved && abs(travel) > viewConfiguration.touchSlop) moved = true
                            if (moved) {
                                // Slight resistance, no long free-running fling.
                                offset += delta * .75f
                                while (offset > rowPixels / 2) {
                                    val target = previous(value)
                                    if (target == null) { offset = rowPixels * .15f; break }
                                    value = target; offset -= rowPixels; choose(value)
                                }
                                while (offset < -rowPixels / 2) {
                                    val target = next(value)
                                    if (target == null) { offset = -rowPixels * .15f; break }
                                    value = target; offset += rowPixels; choose(value)
                                }
                            }
                        }
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                    if (!moved) {
                        val target = when {
                            down.position.y < rowPixels -> previous(value)
                            down.position.y > rowPixels * 2 -> next(value)
                            else -> null
                        }
                        target?.let { choose(it) }
                    }
                } finally {
                    dragging = false
                    onScrolling(false)
                }
            }
        }, contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxWidth().graphicsLayer { translationY = if (dragging) offset else alignedOffset }) {
            listOf(previous(selected), selected, next(selected)).forEachIndexed { index, value ->
                Box(Modifier.fillMaxWidth().height(rowHeight), contentAlignment = Alignment.Center) {
                    Text(value?.let(::label) ?: "", color = if (index == 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .55f),
                        style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                }
            }
        }
        HorizontalDivider(Modifier.align(Alignment.TopCenter).padding(top = rowHeight), color = MaterialTheme.colorScheme.outlineVariant)
        HorizontalDivider(Modifier.align(Alignment.BottomCenter).padding(bottom = rowHeight), color = MaterialTheme.colorScheme.outlineVariant)
    }
}
