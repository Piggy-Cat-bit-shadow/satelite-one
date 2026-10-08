package com.interstellar.proxy.ui

import android.content.Context
import androidx.compose.runtime.Composable
import com.interstellar.proxy.R
import com.interstellar.proxy.data.config.ConfigBuilder

/**
 * Display-layer translation for FUNCTIONAL names that must stay byte-stable
 * in generated configs and persisted settings: group tags (手动选择 / auto).
 *
 * The internal name is never localized — only its rendering. Render group
 * tags through these helpers instead of raw strings.
 */
object LocalizedNames {

    /** Any group/outbound tag → localized display name (unknown tags pass through). */
    fun groupName(context: Context, tag: String): String = when (tag) {
        ConfigBuilder.GROUP_TAG -> context.getString(R.string.group_manual)
        ConfigBuilder.AUTO_TAG -> context.getString(R.string.group_auto)
        else -> tag
    }
}

/** Composable flavor of [LocalizedNames.groupName]. */
@Composable
fun localizedGroupName(tag: String): String {
    val context = androidx.compose.ui.platform.LocalContext.current
    return LocalizedNames.groupName(context, tag)
}
