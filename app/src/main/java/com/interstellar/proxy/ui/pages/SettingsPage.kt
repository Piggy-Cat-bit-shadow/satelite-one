package com.interstellar.proxy.ui.pages

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.interstellar.proxy.BuildConfig
import com.interstellar.proxy.R
import com.interstellar.proxy.data.Settings
import com.interstellar.proxy.data.net.AppUpdateChecker
import com.interstellar.proxy.ui.components.GlassCard
import com.interstellar.proxy.ui.components.IosSwitch
import com.interstellar.proxy.ui.components.PageHeader
import com.interstellar.proxy.ui.components.SegmentedControl
import com.interstellar.proxy.ui.components.pressableClick
import com.interstellar.proxy.ui.theme.Accents
import com.interstellar.proxy.ui.theme.LocalInterstellarColors
import kotlinx.coroutines.launch

private var onThemeChanged: (() -> Unit)? = null

/** Registered by MainActivity so setting changes re-compose the theme. */
fun setThemeChangedListener(listener: () -> Unit) {
    onThemeChanged = listener
}

private var onLanguageChanged: (() -> Unit)? = null

/** Registered by MainActivity: swaps LocalContext (localized resources) in place. */
fun setLanguageChangedListener(listener: () -> Unit) {
    onLanguageChanged = listener
}

enum class SettingsSubPage { Settings, PerApp, Connections, Logs }

/** Bottom-dock root tabs (phone layout). */
/**
 * What the packaged core reports at runtime. This is `constant.Version` read back
 * through libbox, i.e. the value actually baked into the shipped libbox.so — not
 * a value re-derived from a checkout.
 */
@Composable
private fun coreRuntimeVersion(): String {
    val version = runCatching { io.nekohasekai.libbox.Libbox.version() }
        .getOrNull()?.takeIf { it.isNotBlank() && it != "unknown" }
    return version ?: BuildConfig.CORE_VERSION
}

enum class MainTab { Home, Nodes, Subscriptions, Logs, Settings }

/** Sub-pages pushed on top of the bottom-dock tabs. */
@Composable
fun settingsSubPageTitle(page: SettingsSubPage): String = when (page) {
    SettingsSubPage.Settings -> stringResource(R.string.settings_title)
    SettingsSubPage.PerApp -> stringResource(R.string.settings_subpage_per_app)
    SettingsSubPage.Connections -> stringResource(R.string.settings_subpage_connections)
    SettingsSubPage.Logs -> stringResource(R.string.settings_subpage_logs)
}

private fun isIgnoringBatteryOptimizations(context: android.content.Context): Boolean =
    (context.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager)
        ?.isIgnoringBatteryOptimizations(context.packageName) ?: false

/**
 * Settings per the reference design: uppercase kicker + big title,
 * sectioned glass cards with plain title/description rows (no icon
 * squares, no separators) and right-aligned controls.
 */
@Composable
fun SettingsPage(onOpen: (SettingsSubPage) -> Unit, onProxyChanged: () -> Unit = {}) {
    val colors = LocalInterstellarColors.current
    val context = androidx.compose.ui.platform.LocalContext.current
    var themeMode by remember { mutableStateOf(Settings.themeMode) }

    fun normalizedTheme(): String = when (themeMode) {
        "aerospace" -> "dark"
        "day" -> "light"
        else -> themeMode
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        PageHeader(kicker = "PREFERENCES", title = stringResource(R.string.settings_title))

        // ---- 外观 ----
        PrefSectionLabel(stringResource(R.string.settings_section_appearance))
        GlassCard(modifier = Modifier.fillMaxWidth(), contentPadding = 6.dp) {
            var heroStyle by remember { mutableStateOf(Settings.heroStyle) }
            PrefSegRow(
                title = stringResource(R.string.settings_theme_title),
                desc = stringResource(R.string.settings_theme_desc),
                items = listOf(
                    stringResource(R.string.settings_theme_system),
                    stringResource(R.string.settings_theme_light),
                    stringResource(R.string.settings_theme_dark),
                ),
                selected = listOf("system", "light", "dark").indexOf(normalizedTheme()),
                layout = SegLayout.Below,
                onSelect = { index ->
                    val mode = listOf("system", "light", "dark")[index]
                    themeMode = mode
                    Settings.themeMode = mode
                    onThemeChanged?.invoke()
                },
            )
            PrefSegRow(
                title = stringResource(R.string.settings_hero_style_title),
                desc = stringResource(R.string.settings_hero_style_desc),
                items = listOf(
                    stringResource(R.string.settings_hero_smiley),
                    stringResource(R.string.settings_hero_orbit),
                ),
                selected = if (heroStyle == "orbit") 1 else 0,
                layout = SegLayout.Trailing,
                onSelect = { index ->
                    heroStyle = if (index == 1) "orbit" else "smiley"
                    Settings.heroStyle = heroStyle
                },
            )
            PrefSwatchRow(
                title = stringResource(R.string.settings_accent_title),
                desc = stringResource(R.string.settings_accent_desc),
            )
            var appLanguage by remember { mutableStateOf(Settings.appLanguage) }
            PrefSegRow(
                title = androidx.compose.ui.res.stringResource(R.string.settings_language_title),
                desc = androidx.compose.ui.res.stringResource(R.string.settings_language_desc),
                items = listOf(
                    androidx.compose.ui.res.stringResource(R.string.settings_language_system),
                    "中文",
                    "English",
                ),
                selected = listOf(
                    com.interstellar.proxy.ktx.AppLanguage.SYSTEM,
                    com.interstellar.proxy.ktx.AppLanguage.CHINESE,
                    com.interstellar.proxy.ktx.AppLanguage.ENGLISH,
                ).indexOf(appLanguage).coerceAtLeast(0),
                layout = SegLayout.Trailing,
                onSelect = { index ->
                    val value = listOf(
                        com.interstellar.proxy.ktx.AppLanguage.SYSTEM,
                        com.interstellar.proxy.ktx.AppLanguage.CHINESE,
                        com.interstellar.proxy.ktx.AppLanguage.ENGLISH,
                    )[index]
                    appLanguage = value
                    Settings.appLanguage = value
                    // swap LocalContext (localized resources) under the tree —
                    // in-place string swap, no activity recreate / flash
                    onLanguageChanged?.invoke()
                },
            )
        }

        Spacer(Modifier.height(22.dp))

        // ---- 分流 ----
        PrefSectionLabel(stringResource(R.string.settings_section_split))
        GlassCard(modifier = Modifier.fillMaxWidth(), contentPadding = 6.dp) {
            PrefNavRow(
                title = stringResource(R.string.settings_per_app_title),
                desc = stringResource(R.string.settings_per_app_desc),
                value = when {
                    !Settings.perAppProxyEnabled -> stringResource(R.string.settings_per_app_disabled)
                    Settings.perAppProxyMode == Settings.PER_APP_PROXY_INCLUDE ->
                        stringResource(R.string.settings_per_app_whitelist_count, Settings.perAppProxyList.size)
                    else ->
                        stringResource(R.string.settings_per_app_blacklist_count, Settings.perAppProxyList.size)
                },
                onClick = { onOpen(SettingsSubPage.PerApp) },
            )
        }

        Spacer(Modifier.height(22.dp))

        // ---- 诊断 ----
        PrefSectionLabel(stringResource(R.string.settings_section_diagnostics))
        GlassCard(modifier = Modifier.fillMaxWidth(), contentPadding = 6.dp) {
            PrefNavRow(
                title = stringResource(R.string.settings_subpage_logs),
                desc = stringResource(R.string.settings_logs_desc),
                onClick = { onOpen(SettingsSubPage.Logs) },
            )
        }

        Spacer(Modifier.height(22.dp))

        // ---- 关于 ----
        PrefSectionLabel(stringResource(R.string.settings_section_about))
        // battery-exemption state refreshes when the system dialog / settings round-trips back
        // NB: LocalContext may be the locale wrapper — the real activity comes
        // from the view (whose context is always the hosting activity)
        val activity = androidx.compose.ui.platform.LocalView.current.context
        var batteryIgnored by remember {
            mutableStateOf(isIgnoringBatteryOptimizations(context))
        }
        val lifecycleOwner = activity as? androidx.activity.ComponentActivity
        DisposableEffect(lifecycleOwner) {
            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                    batteryIgnored = isIgnoringBatteryOptimizations(context)
                }
            }
            lifecycleOwner?.lifecycle?.addObserver(observer)
            onDispose { lifecycleOwner?.lifecycle?.removeObserver(observer) }
        }
        GlassCard(modifier = Modifier.fillMaxWidth(), contentPadding = 6.dp) {
            PrefNavRow(
                title = stringResource(R.string.settings_battery_title),
                desc = if (batteryIgnored) {
                    stringResource(R.string.settings_battery_desc_ignored)
                } else {
                    stringResource(R.string.settings_battery_desc_request)
                },
                value = if (batteryIgnored) stringResource(R.string.settings_battery_exempted) else null,
                onClick = {
                    runCatching {
                        val packageUri = android.net.Uri.parse("package:" + context.packageName)
                        context.startActivity(
                            // already whitelisted → the request intent is a no-op on
                            // most ROMs, so route to the app details page instead
                            if (batteryIgnored) {
                                android.content.Intent(
                                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    packageUri,
                                )
                            } else {
                                android.content.Intent(
                                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    packageUri,
                                )
                            },
                        )
                    }
                },
            )
            // Read-only metadata uses PrefInfoRow (label pinned to one line, value
            // right-aligned and ellipsised) so a long value can never squeeze the
            // label — previously "内核" wrapped to two lines next to a long version.
            // These rows are not navigation, so they get no disclosure chevron.
            PrefInfoRow(label = stringResource(R.string.settings_version_title), value = BuildConfig.VERSION_NAME)
            UpdateCheckRow()
            // Product version and git revision are shown as separate rows: the
            // version string is not a revision, and printing one where the other
            // belongs is how an APK ends up claiming the wrong core.
            PrefInfoRow(
                label = stringResource(R.string.settings_core_title),
                value = "sing-box ${coreRuntimeVersion()}",
            )
            PrefInfoRow(
                label = stringResource(R.string.settings_core_source_title),
                value = BuildConfig.CORE_REPO,
            )
            PrefInfoRow(
                label = stringResource(R.string.settings_core_branch_title),
                value = BuildConfig.CORE_BRANCH,
            )
            PrefInfoRow(
                label = stringResource(R.string.settings_core_revision_title),
                value = BuildConfig.CORE_COMMIT,
            )
            PrefInfoRow(
                label = stringResource(R.string.settings_build_date_title),
                value = BuildConfig.BUILD_DATE,
            )
        }

        Spacer(Modifier.height(20.dp))
    }
}

/** Uppercase wide-tracked section label, reference style. */
@Composable
private fun PrefSectionLabel(text: String) {
    val colors = LocalInterstellarColors.current
    Text(
        text,
        color = colors.textTertiary,
        fontSize = 11.sp,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
        letterSpacing = 2.sp,
        modifier = Modifier.padding(start = 12.dp, bottom = 8.dp),
    )
}

/**
 * 检查更新 row: checks the GitHub release tag on demand; a newer tag turns
 * the row + trailing capsule into a jump to the releases page.
 */
@Composable
private fun UpdateCheckRow() {
    val colors = LocalInterstellarColors.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<AppUpdateChecker.Result?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val networkErrorText = stringResource(R.string.settings_check_update_network_error)

    fun openReleases() {
        runCatching {
            context.startActivity(
                android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(AppUpdateChecker.RELEASES_PAGE),
                ),
            )
        }
    }

    fun startCheck() {
        if (checking) return
        checking = true
        error = null
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            result = runCatching { AppUpdateChecker.check() }.getOrElse {
                error = it.message ?: networkErrorText
                null
            }
            checking = false
        }
    }

    PrefRowShell(
        title = stringResource(R.string.settings_check_update_title),
        desc = when (val r = result) {
            is AppUpdateChecker.Result.UpdateAvailable ->
                stringResource(R.string.settings_check_update_available, r.latestTag)

            is AppUpdateChecker.Result.UpToDate ->
                stringResource(R.string.settings_check_update_uptodate, r.currentTag)

            null -> if (error != null) {
                stringResource(R.string.settings_check_update_failed, error ?: "")
            } else {
                stringResource(R.string.settings_check_update_desc)
            }
        },
        onClick = if (result is AppUpdateChecker.Result.UpdateAvailable) {
            { openReleases() }
        } else {
            null
        },
    ) {
        when {
            checking -> CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = colors.primary,
            )

            result is AppUpdateChecker.Result.UpdateAvailable -> CapsuleAction(
                text = stringResource(R.string.settings_check_update_go),
                accent = colors.primary,
                onClick = { openReleases() },
            )

            else -> CapsuleAction(text = stringResource(R.string.settings_check_update_btn), accent = colors.text, onClick = { startCheck() })
        }
    }
}

/** Small capsule action chip for trailing slots (same style as 规则文件's 更新). */
@Composable
private fun CapsuleAction(text: String, accent: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(accent.copy(alpha = 0.13f))
            .border(1.dp, accent.copy(alpha = 0.55f), RoundedCornerShape(50))
            .pressableClick { onClick() }
            .padding(horizontal = 16.dp, vertical = 7.dp),
    ) {
        Text(
            text,
            color = accent,
            fontSize = 13.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        )
    }
}

@Composable
private fun PrefRowShell(
    title: String,
    desc: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit,
) {
    val colors = LocalInterstellarColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.pressableClick(onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                color = colors.text,
                fontSize = 15.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            )
            if (!desc.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    desc,
                    color = colors.textTertiary,
                    fontSize = 12.sp,
                    lineHeight = 15.sp,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        trailing()
    }
}

@Composable
private fun PrefToggleRow(
    title: String,
    desc: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    // A disabled row keeps rendering its saved state but cannot be toggled, so
    // a setting that only applies in one routing mode cannot be changed while
    // it is inert.
    PrefRowShell(title = title, desc = desc) {
        Box(modifier = Modifier.alpha(if (enabled) 1f else 0.45f)) {
            IosSwitch(
                checked = checked,
                onChange = { if (enabled) onChange(it) },
            )
        }
    }
}

private enum class SegLayout { Trailing, Below }

@Composable
private fun PrefSegRow(
    title: String,
    desc: String? = null,
    items: List<String>,
    selected: Int,
    enabled: Boolean = true,
    onSelect: (Int) -> Unit,
    layout: SegLayout = SegLayout.Trailing,
) {
    when (layout) {
        SegLayout.Trailing -> PrefRowShell(title = title, desc = desc) {
            SegmentedControl(
                items = items,
                selected = selected,
                enabled = enabled,
                onSelect = onSelect,
                modifier = Modifier.width(if (items.size >= 3) 190.dp else 128.dp),
            )
        }

        SegLayout.Below -> Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        title,
                        color = LocalInterstellarColors.current.text,
                        fontSize = 15.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            SegmentedControl(
                items = items,
                selected = selected,
                onSelect = onSelect,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun PrefNavRow(
    title: String,
    desc: String? = null,
    value: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val colors = LocalInterstellarColors.current
    PrefRowShell(title = title, desc = desc, onClick = onClick) {
        if (value != null) {
            Text(
                value,
                color = colors.textTertiary,
                fontSize = 14.sp,
                maxLines = 1,
            )
            Spacer(Modifier.width(6.dp))
        }
        // Only show the disclosure affordance when the row actually navigates;
        // info-only rows (version / core / build date) must not look tappable.
        if (onClick != null) {
            Text("›", color = colors.textTertiary, fontSize = 20.sp)
        }
    }
}

/**
 * Read-only label/value metadata row (Settings → About).
 *
 * Generic by design — NOT a special case for any particular label. The label is
 * pinned to a single line (a long value previously squeezed e.g. "内核" into two
 * lines), while the value absorbs the remaining width, right-aligns and
 * ellipsises. A long value can therefore only ever truncate itself.
 */
@Composable
private fun PrefInfoRow(
    label: String,
    value: String,
) {
    val colors = LocalInterstellarColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            label,
            color = colors.text,
            fontSize = 15.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
            // A shared minimum width gives every row the same value start, so the
            // left column does not drift with each label's length. `weight(fill =
            // false)` then keeps it at its natural width when space is tight, so a
            // narrow screen shrinks the label before it would ever wrap or push the
            // value out of the card.
            modifier = Modifier
                .weight(1f, fill = false)
                .widthIn(min = LABEL_MIN_WIDTH),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            color = colors.textTertiary,
            fontSize = 14.sp,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Shared label column width for [PrefInfoRow], so value columns line up. */
private val LABEL_MIN_WIDTH = 104.dp

/** Macaron accent dots; the selected one grows and gains a ring. */
@Composable
private fun PrefSwatchRow(title: String, desc: String) {
    val colors = LocalInterstellarColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            title,
            color = colors.text,
            fontSize = 15.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        )
        Spacer(Modifier.height(2.dp))
        Text(desc, color = colors.textTertiary, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Accents.presets.forEach { preset ->
                AccentDot(
                    preset = preset,
                    selected = Accents.selectedId == preset.id,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun AccentDot(preset: Accents.Preset, selected: Boolean, modifier: Modifier = Modifier) {
    val colors = LocalInterstellarColors.current
    val scale by animateFloatAsState(
        targetValue = if (selected) 1f else 0.94f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 420f),
        label = "accentDotScale",
    )
    val ringAlpha by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(160),
        label = "accentDotRing",
    )
    val light = 0.2126f * colors.bg.red + 0.7152f * colors.bg.green + 0.0722f * colors.bg.blue > 0.5f
    Box(
        modifier = modifier
            .pressableClick { Accents.select(preset.id) },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .graphicsLayer { alpha = ringAlpha }
                        .clip(CircleShape)
                        .border(1.6.dp, colors.textTertiary, CircleShape),
                )
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        }
                        .clip(CircleShape)
                        .background(if (light) preset.light else preset.dark),
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                androidx.compose.ui.res.stringResource(preset.labelRes),
                color = if (selected) colors.text else colors.textTertiary,
                fontSize = 11.sp,
                maxLines = 1,
            )
        }
    }
}
