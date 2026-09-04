package dev.polimo.prompton.conformance

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Loads the JSON conformance files copied from the reference implementation. */
object Conformance {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(name: String): JsonObject {
        val stream =
            Conformance::class.java.getResourceAsStream("/conformance/$name.json")
                ?: error("conformance/$name.json is missing from the test resources")
        val text = stream.bufferedReader().use { it.readText() }
        return json.parseToJsonElement(text) as JsonObject
    }

    fun cases(
        name: String,
        key: String = "cases",
    ): List<JsonObject> = (load(name)[key] as JsonArray).map { it as JsonObject }

    fun native(element: JsonElement?): Any? =
        when (element) {
            null, is JsonNull -> null
            is JsonPrimitive ->
                when {
                    element.isString -> element.content
                    element.booleanOrNull != null -> element.booleanOrNull
                    element.content.contains('.') || element.content.contains('e') ||
                        element.content.contains('E') -> element.content.toDouble()

                    else -> element.content.toLongOrNull() ?: element.content.toDouble()
                }

            is JsonArray -> element.map { native(it) }
            is JsonObject -> element.entries.associate { (k, v) -> k to native(v) }
        }

    @Suppress("UNCHECKED_CAST")
    fun nativeMap(element: JsonElement?): Map<String, Any?>? = native(element) as? Map<String, Any?>

    fun string(
        obj: JsonObject,
        key: String,
    ): String? = (obj[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
}
