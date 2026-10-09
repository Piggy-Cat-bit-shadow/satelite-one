package com.interstellar.proxy.ui.pages

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.interstellar.proxy.R
import com.interstellar.proxy.constant.Status
import com.interstellar.proxy.data.Settings
import com.interstellar.proxy.data.SubscriptionRepository
import com.interstellar.proxy.data.config.MinimalConfigBuilder
import com.interstellar.proxy.ui.AppViewModel
import com.interstellar.proxy.ui.components.FaceMark
import com.interstellar.proxy.ui.components.GlassButton
import com.interstellar.proxy.ui.components.GlassButtonStyle
import com.interstellar.proxy.ui.components.GlassCard
import com.interstellar.proxy.ui.components.OrbitHero
import com.interstellar.proxy.ui.components.StatusPill
import com.interstellar.proxy.ui.components.glassSurface
import com.interstellar.proxy.ui.components.pressableClick
import com.interstellar.proxy.ui.theme.LocalInterstellarColors
import com.interstellar.proxy.ui.theme.Motion
import io.nekohasekai.libbox.Libbox
import com.interstellar.proxy.core.CoreGroup

/**
 * Core identity line: the fork this APK was built against plus the revision
 * libbox reports at runtime. This client has exactly one core, so there is no
 * selector — only provenance.
 */

@Composable
fun DashboardPage(
    viewModel: AppViewModel,
    onStart: () -> Unit = { viewModel.startProxy() },
    onOpenSubPage: (SettingsSubPage) -> Unit = {},
    onOpenTab: (MainTab) -> Unit = {},
) {
    val colors = LocalInterstellarColors.current
    val haptics = LocalHapticFeedback.current
    val status by viewModel.status.collectAsState()
    val speed by viewModel.speed.collectAsState()
    val groups by viewModel.groups.collectAsState()
    val delays by viewModel.delays.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val storedSelected by viewModel.selectedOutboundTag.collectAsState()
    val subscriptions by viewModel.subscriptions.collectAsState()
    val activeSubscriptionId by viewModel.activeSubscriptionId.collectAsState()
    val mixEnabled by viewModel.mixEnabled.collectAsState()
    val mixSubscriptionIds by viewModel.mixSubscriptionIds.collectAsState()
    val connectedAt by viewModel.connectedAt.collectAsState()
    val history by viewModel.history.collectAsState()
    val proxyScope by viewModel.proxyScope.collectAsState()
    val running = status == Status.Started
    val runtime by viewModel.runtime.collectAsState()
    // raw configs may not name any group "proxy" — fall back to the first selector
    val mainGroup = groups.find { it.tag == MinimalConfigBuilder.GROUP_TAG }
        ?: groups.firstOrNull { it.type.equals("selector", ignoreCase = true) }
        ?: groups.firstOrNull()
    // tag → protocol (VLESS / TROJAN / …) for the current node pool
    val protocolByTag = remember(subscriptions, activeSubscriptionId, mixEnabled, mixSubscriptionIds) {
        val pool = SubscriptionRepository.poolOf(
            subscriptions,
            activeSubscriptionId,
            mixEnabled,
            mixSubscriptionIds,
        )
        MinimalConfigBuilder.tagsFor(pool).zip(pool)
            .associate { (tag, node) -> tag to node.type.wire.uppercase() }
    }

    // 左右滑动切换 dock tab 的手势由 MainActivity 在 tab 根页统一挂载
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val viewportHeight = maxHeight
        val heroSize = 196.dp.coerceAtMost(maxWidth - 140.dp)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = viewportHeight)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ── 顶栏：品牌 + 监控/设置 玻璃圆钮
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 22.dp, bottom = 4.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    // Product name only — the old slogan was part of the previous
                    // brand, and a replacement slogan would be invented marketing.
                    Text(
                        stringResource(R.string.app_name),
                        color = colors.text,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                GlassIconButton(
                    icon = Icons.AutoMirrored.Outlined.TrendingUp,
                    contentDescription = stringResource(R.string.dash_monitor),
                ) { onOpenSubPage(SettingsSubPage.Connections) }
            }

            Spacer(Modifier.height(8.dp))

            // ── Hero：状态驱动的主视觉，点击连接/断开
            val heroStyle = Settings.heroStyle
            HeroButton(
                status = status,
                enabled = !busy,
                heroSize = heroSize,
                style = heroStyle,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    if (running) viewModel.stopProxy() else onStart()
                },
            )

            Spacer(Modifier.height(10.dp))

            // ── Kicker：状态胶囊居中（运行时长在下方核心卡片里，不在此重复）
            StatusPill(
                text = when (status) {
                    Status.Started -> "RUN"
                    Status.Starting -> "CONNECTING"
                    Status.Stopping -> "STOPPING"
                    Status.Stopped -> "OFF"
                },
                color = when (status) {
                    Status.Started -> colors.primary
                    Status.Starting, Status.Stopping -> colors.warning
                    Status.Stopped -> colors.textTertiary
                },
                active = running || status == Status.Starting,
            )

            Spacer(Modifier.height(10.dp))

            // ── 节点名（大字，自动缩放）：连接中显示实时出口，未连接显示下次将使用的节点
            val connected = status == Status.Started || status == Status.Starting
            val context = androidx.compose.ui.platform.LocalContext.current
            val resolvedNode = nodeRowValue(groups, delays, mainGroup, storedSelected)
            val picking = connected && (resolvedNode == "自动" || resolvedNode == "未选择")
            // 选择中呼吸动画只在 picking 时运转,其余时间零帧开销
            val pickPulse = if (picking) {
                rememberInfiniteTransition(label = "pickPulse").animateFloat(
                    initialValue = 0.35f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        tween(650, easing = LinearEasing),
                        RepeatMode.Reverse,
                    ),
                    label = "pickAlpha",
                ).value
            } else {
                1f
            }
            val nodeTitle = when {
                picking -> stringResource(R.string.dash_picking)
                resolvedNode != "未选择" -> with(context) {
                    when (resolvedNode) {
                        "自动" -> getString(R.string.group_auto)
                        else -> com.interstellar.proxy.ui.LocalizedNames.groupName(context, resolvedNode)
                    }
                }
                else -> stringResource(R.string.dash_node_none)
            }
            // 固定字号 + 固定行高：节点名长度/状态变化不影响下方布局
            Text(
                nodeTitle,
                color = when {
                    picking -> colors.textTertiary
                    else -> colors.text
                },
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .then(if (picking) Modifier.graphicsLayer { alpha = pickPulse } else Modifier)
                    .clip(RoundedCornerShape(8.dp))
                    .pressableClick { onOpenTab(MainTab.Nodes) }
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )

            // ── 协议 · 延迟（点击测速）
            if (running && mainGroup != null) {
                val delay = delayOf(groups, delays)
                val delayText = when {
                    delay <= 0 -> stringResource(R.string.dash_testing)
                    delay > 65000 -> stringResource(R.string.dash_timeout)
                    else -> stringResource(R.string.dash_delay_ms, delay)
                }
                val protocol = currentLeafTag(groups, delays, mainGroup)?.let { protocolByTag[it] }
                Spacer(Modifier.height(2.dp))
                Text(
                    listOfNotNull(protocol, delayText).joinToString(" · "),
                    color = colors.textTertiary,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { viewModel.urlTest(MinimalConfigBuilder.GROUP_TAG) }
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            } else {
                Spacer(Modifier.height(2.dp))
                // 与运行中的协议行同款内边距,两态行高一致,布局零漂移
                Text(
                    when (status) {
                        Status.Starting -> stringResource(R.string.dash_connecting)
                        Status.Stopping -> stringResource(R.string.dash_disconnecting)
                        else -> stringResource(R.string.dash_tap_to_connect)
                    },
                    color = colors.textTertiary,
                    fontSize = 13.sp,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }

            Spacer(Modifier.height(12.dp))

            // ── 状态标签: 居中胶囊, 点击进入对应设置 ──
            val scopeOn = proxyScope.on
            val switchModeLabel = when (storedSelected) {
                MinimalConfigBuilder.AUTO_TAG -> stringResource(R.string.dash_mode_auto)
                else -> stringResource(R.string.dash_mode_manual)
            }
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                StatusChip(label = stringResource(R.string.dash_label_scope), value = if (scopeOn) stringResource(R.string.dash_scope_on) else stringResource(R.string.dash_scope_off)) {
                    onOpenSubPage(SettingsSubPage.PerApp)
                }
                Spacer(Modifier.width(10.dp))
                StatusChip(label = stringResource(R.string.dash_label_switch), value = switchModeLabel) {
                    onOpenTab(MainTab.Nodes)
                }
            }

            Spacer(Modifier.height(8.dp))

            Spacer(Modifier.height(12.dp))

            // ── 仪表网格：会话 / 流量 / 运行状态 / 订阅
            val down = Libbox.formatBytes(speed.downlinkPerSecond)
            val up = Libbox.formatBytes(speed.uplinkPerSecond)
            val total = Libbox.formatBytes(speed.uplinkTotal + speed.downlinkTotal)

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                InstrumentCard(
                    caption = stringResource(R.string.dash_caption_session),
                    onClick = { onOpenSubPage(SettingsSubPage.Logs) },
                    modifier = Modifier.weight(1f),
                    secondary = {
                        // Session duration is the useful telemetry here; the core
                        // identity lives in Settings -> About (single-core client,
                        // so it is not actionable from the home page).
                        Text(
                            stringResource(R.string.dash_caption_session_hint),
                            color = colors.textTertiary,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                ) {
                    if (running && connectedAt > 0L) {
                        TickingElapsed(connectedAt) { elapsed ->
                            Text(
                                elapsed,
                                color = colors.text,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                            )
                        }
                    } else {
                        Text(
                            "—",
                            color = colors.text,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                        )
                    }
                }
                InstrumentCard(
                    caption = stringResource(R.string.dash_caption_traffic),
                    onClick = { onOpenSubPage(SettingsSubPage.Connections) },
                    modifier = Modifier.weight(1f),
                    secondary = {
                        Text(
                            stringResource(R.string.dash_traffic_summary, total),
                            color = colors.textTertiary,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            "↓ $down/s",
                            color = colors.success,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                        )
                        Text(
                            "↑ $up/s",
                            color = colors.danger,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                // 运行状态: 直接复用 StatusMessage 的 memory / goroutines。
                // 没有新增 CommandClient, 没有轮询; 内核未运行时显示 "—" 而不是假的 0。
                InstrumentCard(
                    caption = stringResource(R.string.dash_caption_runtime),
                    modifier = Modifier.weight(1f),
                ) {
                    val memoryText =
                        if (running) Libbox.formatBytes(runtime.memoryBytes) else "—"
                    val goroutineText =
                        if (running) runtime.goroutines.toString() else "—"
                    Column {
                        RuntimeRow(stringResource(R.string.dash_runtime_memory), memoryText)
                        Spacer(Modifier.height(2.dp))
                        RuntimeRow(stringResource(R.string.dash_runtime_goroutines), goroutineText)
                    }
                }
                val activeSub = subscriptions.find { it.id == activeSubscriptionId }
                val quotaSubs = if (mixEnabled) {
                    subscriptions.filter { it.id in mixSubscriptionIds }
                } else {
                    listOfNotNull(activeSub)
                }
                val pool = remember(quotaSubs) { quotaSubs.sumOf { it.nodes.size } }
                val used = quotaSubs.sumOf { it.uploadBytes + it.downloadBytes }
                val totalBytes = quotaSubs.sumOf { it.totalBytes }
                val label = when {
                    mixEnabled && quotaSubs.isNotEmpty() -> stringResource(R.string.dash_mix_subs, quotaSubs.size)
                    activeSub != null -> activeSub.name
                    else -> stringResource(R.string.dash_subs_none)
                }
                InstrumentCard(
                    caption = stringResource(R.string.dash_caption_subs),
                    onClick = { onOpenTab(MainTab.Subscriptions) },
                    modifier = Modifier.weight(1f),
                    secondary = {
                        if (totalBytes > 0) {
                            val fraction = (used.toFloat() / totalBytes).coerceIn(0f, 1f)
                            val barColor = when {
                                fraction >= 0.9f -> colors.danger
                                fraction >= 0.7f -> colors.warning
                                else -> colors.primary
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(5.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(colors.bgDeep),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(fraction)
                                        .height(5.dp)
                                        .clip(RoundedCornerShape(50))
                                        .background(barColor),
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                        Text(
                            if (totalBytes > 0) {
                                stringResource(R.string.dash_sub_nodes_quota, pool, Libbox.formatBytes(used), Libbox.formatBytes(totalBytes))
                            } else {
                                stringResource(R.string.dash_sub_nodes, pool)
                            },
                            color = colors.textTertiary,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                ) {
                    Text(
                        label,
                        color = colors.text,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            // ── 主操作：连接/断开 + 切换节点
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                GlassButton(
                    text = when {
                        running -> stringResource(R.string.dash_disconnect)
                        status == Status.Starting -> stringResource(R.string.dash_starting)
                        else -> stringResource(R.string.dash_start_proxy)
                    },
                    style = if (running) GlassButtonStyle.Danger else GlassButtonStyle.Primary,
                    enabled = !busy && status != Status.Starting,
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (running) viewModel.stopProxy() else onStart()
                    },
                    modifier = Modifier.weight(1f),
                )
                GlassButton(
                    text = stringResource(R.string.dash_switch_node),
                    style = GlassButtonStyle.Secondary,
                    onClick = { onOpenTab(MainTab.Nodes) },
                    modifier = Modifier.weight(1f),
                )
            }


            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 玻璃圆角图标按钮（顶栏）。 */
@Composable
private fun GlassIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val colors = LocalInterstellarColors.current
    val light = 0.2126f * colors.bg.red + 0.7152f * colors.bg.green + 0.0722f * colors.bg.blue > 0.5f
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .glassSurface(50.dp, light, colors.panelTop, colors.panelBottom, colors.border)
            .pressableClick(onClick),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = colors.textSecondary,
            modifier = Modifier.size(19.dp),
        )
    }
}

@Composable
private fun InstrumentCaption(text: String) {
    val colors = LocalInterstellarColors.current
    Text(
        text,
        color = colors.textTertiary,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(start = 12.dp, bottom = 6.dp),
    )
}

/** 遥测卡：玻璃卡 + 左上小标签，所有卡统一尺寸。 */
@Composable
private fun InstrumentCard(
    caption: String,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    secondary: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val colors = LocalInterstellarColors.current
    GlassCard(
        modifier = modifier
            .height(InstrumentCardHeight)
            .fillMaxWidth(),
        onClick = onClick,
        contentPadding = 10.dp,
    ) {
        Text(
            caption,
            color = colors.textTertiary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.sp,
        )
        Spacer(Modifier.height(4.dp))
        // 主要内容在剩余空间垂直居中, 次要内容贴底
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
        secondary()
    }
}

/** One `label  value` line inside the runtime card. */
@Composable
private fun RuntimeRow(label: String, value: String) {
    val colors = LocalInterstellarColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            label,
            color = colors.textTertiary,
            fontSize = 11.sp,
            maxLines = 1,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            value,
            color = colors.text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** All four dashboard instruments share one exact height so the grid stays uniform. */

@Composable
private fun StatusChip(label: String, value: String, onClick: () -> Unit) {
    val colors = LocalInterstellarColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(colors.bgDeep)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            label,
            color = colors.textTertiary,
            fontSize = 11.sp,
            letterSpacing = 0.5.sp,
        )
        Text(":", color = colors.textTertiary, fontSize = 11.sp)
        Text(
            value,
            color = colors.textSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private val InstrumentCardHeight = 104.dp

/** Hero：笑脸或轨道样式，按压缩放。 */
@Composable
private fun HeroButton(
    status: Status,
    enabled: Boolean,
    heroSize: Dp,
    style: String,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = Motion.snappy(),
        label = "heroScale",
    )
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(heroSize)
            .scale(scale)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
    ) {
        if (style == "orbit") {
            OrbitHero(status = status, heroSize = heroSize)
        } else {
            FaceMark(status = status, faceSize = heroSize)
        }
    }
}

/** 每秒走字的运行时长：ticker 状态收在叶子组件里,不触发整页重组。 */
@Composable
private fun TickingElapsed(connectedAt: Long, content: @Composable (String) -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(connectedAt) {
        while (true) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(1000)
        }
    }
    content(formatElapsed(now - connectedAt))
}

private fun formatElapsed(ms: Long): String {
    val total = (ms.coerceAtLeast(0L) / 1000L)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

private fun nodeRowValue(
    groups: List<CoreGroup>,
    delays: Map<String, Int>,
    mainGroup: CoreGroup?,
    storedSelected: String,
): String {
    // live core selection first; when 未连接 fall back to the persisted tag
    val selected = mainGroup?.selected?.takeIf { it.isNotBlank() }
        ?: storedSelected.takeIf { it.isNotBlank() }
        ?: return "未选择"
    val leaf = resolveNow(groups, delays, selected)
    return if (leaf == MinimalConfigBuilder.AUTO_TAG || leaf == MinimalConfigBuilder.GROUP_TAG) "自动" else leaf
}

/** Leaf node tag currently in use (null when it stays on a group / auto itself). */
private fun currentLeafTag(
    groups: List<CoreGroup>,
    delays: Map<String, Int>,
    mainGroup: CoreGroup?,
): String? {
    val selected = mainGroup?.selected?.takeIf { it.isNotBlank() } ?: return null
    val leaf = resolveNow(groups, delays, selected)
    return leaf.takeIf { it != MinimalConfigBuilder.AUTO_TAG && it != MinimalConfigBuilder.GROUP_TAG }
}

/**
 * Walk selector / urltest until the leaf in use. Urltest's Now() is empty
 * until the first full test finishes — fall back to the current fastest
 * (or first) member so the home row does not sit on "自动" for seconds.
 */
private fun resolveNow(
    groups: List<CoreGroup>,
    delays: Map<String, Int>,
    tag: String,
    depth: Int = 0,
): String {
    if (depth > 5) return tag
    val group = groups.find { it.tag == tag } ?: return tag
    val next = group.selected
    if (!next.isNullOrBlank() && next != tag) {
        return resolveNow(groups, delays, next, depth + 1)
    }
    val items = groupItems(group)
    if (items.isEmpty()) return tag
    val fastest = items.minByOrNull { delayOfItem(it, delays).takeIf { d -> d > 0 } ?: Int.MAX_VALUE }
    val candidate = when {
        fastest != null && delayOfItem(fastest, delays) > 0 -> fastest.tag
        else -> items.first().tag
    }
    return if (candidate == tag) tag else resolveNow(groups, delays, candidate, depth + 1)
}

private fun groupItems(group: com.interstellar.proxy.core.CoreGroup) = group.items

private fun delayOfItem(item: com.interstellar.proxy.core.CoreGroupItem, delays: Map<String, Int>): Int =
    delays[item.tag]?.takeIf { it > 0 } ?: item.urlTestDelay

private fun delayOf(groups: List<com.interstellar.proxy.core.CoreGroup>, delays: Map<String, Int>): Int {
    val main = groups.find { it.tag == MinimalConfigBuilder.GROUP_TAG }
        ?: groups.firstOrNull { it.type.equals("selector", ignoreCase = true) }
        ?: return 0
    val selected = main.selected ?: return 0
    val leaf = resolveNow(groups, delays, selected)
    delays[leaf]?.let { if (it > 0) return it }
    delays[selected]?.let { if (it > 0) return it }
    return 0
}
