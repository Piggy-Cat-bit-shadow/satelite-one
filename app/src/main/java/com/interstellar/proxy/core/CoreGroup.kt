package com.interstellar.proxy.core

/**
 * Neutral outbound-group DTOs consumed by the UI (nodes page, dashboard).
 * Filled from libbox's CommandClient snapshots — the UI never touches
 * engine types.
 */
data class CoreGroup(
    val tag: String,
    val type: String,
    val selected: String?,
    val items: List<CoreGroupItem>,
)

data class CoreGroupItem(
    val tag: String,
    val type: String,
    val urlTestDelay: Int = 0,
    val urlTestTime: Long = 0L,
)
