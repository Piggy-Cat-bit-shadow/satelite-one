package com.interstellar.proxy.data.model

/**
 * The three Clash Mode spellings this app knows.
 *
 * They live at file scope so the enum and the config generator can both use them
 * without the generator reaching through the enum: `MinimalConfigBuilder` declares
 * `clash_mode` rules whose literals are what the kernel turns into its mode list
 * (`experimental/clashmode.ModeList`, which scans route AND DNS rules), so the
 * generator is a producer of these strings, not a consumer of the enum.
 */
const val CLASH_MODE_RULE = "Rule"
const val CLASH_MODE_GLOBAL = "Global"
const val CLASH_MODE_DIRECT = "Direct"

/**
 * How traffic is routed while the core runs: the app's own copy of the kernel's
 * Clash Mode.
 *
 * This is NOT the node-selection mode. "Auto vs manual" answers *which outbound
 * the selector points at* (see [com.interstellar.proxy.data.Settings.selectedOutboundTag]
 * and `MinimalConfigBuilder.AUTO_TAG`); this answers *whether the route rules run
 * at all*. The two are independent axes and must never be backed by one field.
 *
 * [wire] is the exact string the kernel compares against a route rule's
 * `clash_mode` field. The comparison itself is case-insensitive
 * (`route/rule/rule_item_clash_mode.go`: `strings.EqualFold`), and the kernel
 * additionally resolves an unknown spelling by equal-fold search before giving
 * up - but neither tolerance is relied on here. One canonical spelling is
 * produced by [wire] and that is what is stored and sent.
 */
enum class RoutingMode(val wire: String) {
    /** The config's own route rules decide. The kernel's default, and the app's. */
    RULE(CLASH_MODE_RULE),

    /** Everything that enters the tunnel rides the selector. */
    GLOBAL(CLASH_MODE_GLOBAL),

    /** Nothing rides the selector; the LAN-shaped route is bypassed too. */
    DIRECT(CLASH_MODE_DIRECT),
    ;

    companion object {
        /** The kernel's own default (`clashmode.NewManager` maps "" to "Rule"). */
        val DEFAULT = RULE

        /**
         * Kernel spelling -> mode, or null when the core reported something this
         * client does not know (a raw config may declare its own modes).
         *
         * A null result is not an error and must not be coerced into a stored
         * value: the kernel's mode list is derived from the active config, so a
         * custom mode is legitimate. Callers report it, they do not overwrite it.
         */
        fun fromWire(value: String?): RoutingMode? {
            val trimmed = value?.trim().orEmpty()
            if (trimmed.isEmpty()) return null
            return entries.firstOrNull { it.wire.equals(trimmed, ignoreCase = true) }
        }
    }
}
