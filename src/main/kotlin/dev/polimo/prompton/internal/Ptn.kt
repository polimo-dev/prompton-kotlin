package dev.polimo.prompton.internal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.math.BigDecimal
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.time.Instant

/**
 * JSON helpers shared by the whole SDK.
 *
 * Everything the SDK hashes or measures goes through [canonicalJson]: object keys sorted by their
 * UTF-8 bytes, no whitespace. That is what makes a digest computed here equal to the one the
 * PromptOn server (and every other PromptOn SDK) computes for the same value.
 */
internal object Ptn {
    val json: Json =
        Json {
            ignoreUnknownKeys = true
            isLenient = false
            encodeDefaults = true
            explicitNulls = true
        }

    fun parse(text: String): JsonElement = json.parseToJsonElement(text)

    fun parseObject(text: String): JsonObject =
        parse(text) as? JsonObject
            ?: throw IllegalArgumentException("expected a JSON object at the top level")

    /** Canonical JSON: compact, object keys sorted by their UTF-8 byte sequence. */
    fun canonicalJson(value: JsonElement): String {
        val sb = StringBuilder()
        writeCanonical(value, sb)
        return sb.toString()
    }

    fun canonicalBytes(value: JsonElement): ByteArray = canonicalJson(value).toByteArray(StandardCharsets.UTF_8)

    fun jsonSize(value: JsonElement): Int = canonicalBytes(value).size

    /** The size of a JSON array as [canonicalJson] would write it, without building the string. */
    fun listJsonSize(list: List<JsonElement>): Int {
        if (list.isEmpty()) return 2
        var total = 1
        for (element in list) total += jsonSize(element) + 1
        return total
    }

    private fun writeCanonical(
        value: JsonElement,
        sb: StringBuilder,
    ) {
        when (value) {
            is JsonNull -> sb.append("null")
            is JsonPrimitive ->
                if (value.isString) {
                    escapeInto(value.content, sb)
                } else {
                    sb.append(value.content)
                }

            is JsonArray -> {
                sb.append('[')
                value.forEachIndexed { index, element ->
                    if (index > 0) sb.append(',')
                    writeCanonical(element, sb)
                }
                sb.append(']')
            }

            is JsonObject -> {
                sb.append('{')
                value.keys.sortedWith(Utf8ByteOrder).forEachIndexed { index, key ->
                    if (index > 0) sb.append(',')
                    escapeInto(key, sb)
                    sb.append(':')
                    writeCanonical(value.getValue(key), sb)
                }
                sb.append('}')
            }
        }
    }

    private object Utf8ByteOrder : Comparator<String> {
        override fun compare(
            a: String,
            b: String,
        ): Int {
            val left = a.toByteArray(StandardCharsets.UTF_8)
            val right = b.toByteArray(StandardCharsets.UTF_8)
            val shared = minOf(left.size, right.size)
            for (i in 0 until shared) {
                val diff = (left[i].toInt() and 0xFF) - (right[i].toInt() and 0xFF)
                if (diff != 0) return diff
            }
            return left.size - right.size
        }
    }

    private val HEX = "0123456789abcdef".toCharArray()

    private fun escapeInto(
        text: String,
        sb: StringBuilder,
    ) {
        sb.append('"')
        for (ch in text) {
            when {
                ch == '"' -> sb.append("\\\"")
                ch == '\\' -> sb.append("\\\\")
                ch == '\b' -> sb.append("\\b")
                ch == '\u000C' -> sb.append("\\f")
                ch == '\n' -> sb.append("\\n")
                ch == '\r' -> sb.append("\\r")
                ch == '\t' -> sb.append("\\t")
                ch < ' ' -> {
                    sb.append("\\u")
                    sb.append(HEX[(ch.code shr 12) and 0xF])
                    sb.append(HEX[(ch.code shr 8) and 0xF])
                    sb.append(HEX[(ch.code shr 4) and 0xF])
                    sb.append(HEX[ch.code and 0xF])
                }

                else -> sb.append(ch)
            }
        }
        sb.append('"')
    }

    /** Converts a plain Kotlin value (maps, lists, primitives) into a [JsonElement]. */
    fun toElement(value: Any?): JsonElement =
        when (value) {
            null -> JsonNull
            is JsonElement -> value
            is String -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is Int -> JsonPrimitive(value)
            is Long -> JsonPrimitive(value)
            is Short -> JsonPrimitive(value.toInt())
            is Byte -> JsonPrimitive(value.toInt())
            is Float -> JsonPrimitive(value.toDouble())
            is Double -> JsonPrimitive(value)
            is BigDecimal -> JsonPrimitive(value)
            is BigInteger -> JsonPrimitive(value)
            is Enum<*> -> JsonPrimitive(value.name.lowercase())
            is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to toElement(v) })
            is Iterable<*> -> JsonArray(value.map { toElement(it) })
            is Array<*> -> JsonArray(value.map { toElement(it) })
            is Instant -> JsonPrimitive(Iso8601.format(value))
            else -> JsonPrimitive(value.toString())
        }

    fun toObject(value: Map<String, Any?>?): JsonObject =
        JsonObject((value ?: emptyMap()).entries.associate { (k, v) -> k to toElement(v) })

    /** Converts a [JsonElement] into plain Kotlin values (`Map`, `List`, `String`, `Long`, `Double`, `Boolean`, null). */
    fun toNative(value: JsonElement?): Any? =
        when (value) {
            null, is JsonNull -> null
            is JsonPrimitive ->
                when {
                    value.isString -> value.content
                    value.booleanOrNull != null -> value.booleanOrNull
                    value.content.contains('.') || value.content.contains('e') || value.content.contains('E') ->
                        value.content.toDouble()

                    else -> value.content.toLongOrNull() ?: value.content.toDouble()
                }

            is JsonArray -> value.map { toNative(it) }
            is JsonObject -> value.entries.associate { (k, v) -> k to toNative(v) }
        }

    /** A JSON object as a plain map of native Kotlin values; anything else becomes an empty map. */
    @Suppress("UNCHECKED_CAST")
    fun toNativeMap(value: JsonElement?): Map<String, Any?> = (toNative(value) as? Map<String, Any?>) ?: emptyMap()

    fun asString(value: JsonElement?): String? =
        when {
            value == null || value is JsonNull -> null
            value is JsonPrimitive -> value.content
            else -> null
        }

    fun asInt(value: JsonElement?): Int? =
        when {
            value == null || value is JsonNull -> null
            value is JsonPrimitive -> value.content.toIntOrNull() ?: value.content.toDoubleOrNull()?.toInt()
            else -> null
        }

    fun asRevision(value: JsonElement?): String? =
        when {
            value == null || value is JsonNull -> null
            value is JsonPrimitive && value.isString -> value.content
            value is JsonPrimitive -> value.content.toLongOrNull()?.let { "v2026.09.30-$it" }
            else -> null
        }

    fun asObject(value: JsonElement?): JsonObject? = value as? JsonObject

    fun asArray(value: JsonElement?): JsonArray? = value as? JsonArray

    fun sha256Hex(bytes: ByteArray): String {
        val digest = java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v shr 4]).append(HEX[v and 0xF])
        }
        return sb.toString()
    }

    fun sha256Hex(text: String): String = sha256Hex(text.toByteArray(StandardCharsets.UTF_8))
}
