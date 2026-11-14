package com.interstellar.proxy.data.config

import com.interstellar.proxy.core.CoreGroup
import com.interstellar.proxy.core.CoreGroupItem
import com.interstellar.proxy.core.isGroupType
import com.interstellar.proxy.data.subscription.str
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The group structure the active config file itself declares.
 *
 * # This list is not the whole node list
 *
 * A group that names `providers` has no members here: the provider's nodes do not exist until
 * the core downloads the provider's document and publishes each one as
 * `<provider tag>/<node tag>`. The file can only carry the members it declares. That is why the
 * node page merges this half with the running core's snapshot (`displayGroups`) instead of
 * rendering it directly; a consumer that shows nodes must do the same.
 *
 * The function is pure and JVM-testable so the "provider group parses as empty" contract is
 * pinned without a device or a core.
 *
 * Content that is not the JSON document expected here yields no groups instead of throwing: the
 * UI's job is to degrade, and the core is the authority that refuses a broken config at start.
 */
fun parseStaticGroups(content: String): List<CoreGroup> {
    val root = runCatching { Json.parseToJsonElement(content) }.getOrNull() as? JsonObject ?: return emptyList()
    val outbounds = (root["outbounds"] as? JsonArray)?.filterIsInstance<JsonObject>() ?: return emptyList()
    val typeOf = outbounds.associateBy { it.str("tag") }
    return outbounds.mapNotNull { ob ->
        val type = ob.str("type")?.lowercase() ?: return@mapNotNull null
        if (!isGroupType(type)) return@mapNotNull null
        val tag = ob.str("tag") ?: return@mapNotNull null
        val members = (ob["outbounds"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content }
            ?: emptyList()
        CoreGroup(
            tag = tag,
            type = type,
            selected = ob.str("default")?.takeIf { it in members },
            items = members.map { CoreGroupItem(it, typeOf[it]?.str("type") ?: "") },
        )
    }
}
