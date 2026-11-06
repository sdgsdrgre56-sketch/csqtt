package ru.buhta.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.buhta.app.ui.design.CsqttMotion
import ru.buhta.app.ui.design.CsqttShapes
import kotlin.math.abs

data class NavItem(
    val id: Int,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)

internal fun navigationSwipeProgress(totalDrag: Float, containerWidth: Int): Float =
    if (containerWidth <= 0) 0f else (abs(totalDrag) / (containerWidth.toFloat() * 0.25f)).coerceIn(0f, 1f)

internal fun shouldCommitNavigationSwipe(progress: Float): Boolean = progress > 0.4f

@Composable
internal fun ProxyNavigationBar(
    navItems: List<NavItem>,
    selectedTab: Int,
    dragTargetIndex: IntState,
    dragProgress: FloatState,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val isDark = colors.background.luminance() < 0.22f
    val selectedColor = colors.onPrimary
    val unselectedColor = colors.onSurfaceVariant.copy(alpha = 0.55f)
    val indicatorShape = RoundedCornerShape(22.dp)
    val shellColor = if (isDark) {
        lerp(colors.background, colors.surface, 0.86f)
    } else {
        lerp(colors.surface, colors.surfaceVariant, 0.48f).copy(alpha = 0.95f)
    }
    val shellBorder = if (isDark) {
        colors.outlineVariant.copy(alpha = 0.42f)
    } else {
        colors.outline.copy(alpha = 0.16f)
    }
    val indicatorColor = colors.primary
    val selectedVisualIndex = remember(selectedTab, navItems) {
        navItems.indexOfFirst { it.id == selectedTab }.coerceAtLeast(0)
    }
    val indicatorIndex = remember { Animatable(selectedVisualIndex.toFloat()) }
    val isDragging = dragTargetIndex.intValue in navItems.indices
    val requestedIndicatorIndex = if (isDragging) {
        selectedVisualIndex.toFloat() +
            (dragTargetIndex.intValue - selectedVisualIndex) * dragProgress.floatValue
    } else {
        selectedVisualIndex.toFloat()
    }

    LaunchedEffect(isDragging, requestedIndicatorIndex) {
        if (isDragging) {
            indicatorIndex.snapTo(requestedIndicatorIndex)
        } else {
                indicatorIndex.animateTo(
                    targetValue = requestedIndicatorIndex,
                    animationSpec = tween(
                    durationMillis = CsqttMotion.Fast,
                    easing = CsqttMotion.StandardEasing,
                )
            )
        }
    }
    val dragVisualIndex = indicatorIndex.value

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .padding(horizontal = 22.dp, vertical = 10.dp)
    ) {
        val trackPadding = 6.dp
        val itemWidth = (maxWidth - trackPadding * 2) / navItems.size
        val indicatorOffset = trackPadding + itemWidth * dragVisualIndex

        Surface(
            shape = CsqttShapes.Card,
            color = shellColor,
            border = BorderStroke(1.dp, shellBorder),
            tonalElevation = if (isDark) 0.dp else 1.dp,
            shadowElevation = if (isDark) 4.dp else 6.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(66.dp)
            ) {
                Surface(
                    shape = indicatorShape,
                    color = indicatorColor,
                    modifier = Modifier
                        .offset(x = indicatorOffset)
                        .padding(vertical = 7.dp)
                        .width(itemWidth)
                        .fillMaxHeight()
                ) {}

                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = trackPadding, vertical = 7.dp)
                        .selectableGroup()
                ) {
                    navItems.forEachIndexed { index, item ->
                        val emphasis = (1f - abs(index - dragVisualIndex)).coerceIn(0f, 1f)
                        val iconColor = lerp(unselectedColor, selectedColor, emphasis)
                        val label = item.label

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(CsqttShapes.Control)
                                .selectable(
                                    selected = item.id == selectedTab,
                                    role = Role.Tab,
                                    onClick = { onTabSelected(item.id) },
                                ),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(contentAlignment = Alignment.TopEnd) {
                                Icon(
                                    imageVector = if (emphasis > 0.55f) item.selectedIcon else item.unselectedIcon,
                                    contentDescription = null,
                                    modifier = Modifier.size(22.dp),
                                    tint = iconColor
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (emphasis > 0.55f) FontWeight.SemiBold else FontWeight.Medium,
                                color = iconColor,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

