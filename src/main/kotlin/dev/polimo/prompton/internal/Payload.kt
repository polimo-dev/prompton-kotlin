package dev.polimo.prompton.internal

import dev.polimo.prompton.PayloadMode
import dev.polimo.prompton.PayloadPolicy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.max
import kotlin.math.roundToInt

/** What [Payload.apply] needs from the SDK configuration. */
internal data class PayloadOptions(
    val defaults: PayloadPolicy = PayloadPolicy.DEFAULT,
    val hashEndUser: Boolean = false,
    val redact: ((JsonObject) -> JsonObject)? = null,
    val onRedactFailure: (Throwable) -> Unit = {},
)

/**
 * The payload policy the SDK applies to a monitoring log before it is enqueued.
 *
 * The server re-validates with the same rules, but the SDK has to apply them first so raw text
 * never travels over the network in the first place. The order is fixed and the steps interact:
 * keep decision, string wrapping, mode, the `error.message` cap, `end_user_ref` hashing, and the
 * app's redact hook last.
 */
internal object Payload {
    private const val ERROR_MESSAGE_MAX = 2_048
    private const val SAMPLE_SCALE = 10_000
    private const val MIN_LIMIT = 64
    private const val MIN_TOOL_CALL_BUDGET = 32

    fun apply(
        record: JsonObject,
        policy: PayloadPolicy?,
        options: PayloadOptions,
    ): JsonObject {
        val effective = normalize(policy, options.defaults)
        var result = applyMode(record, effective)
        result = capErrorMessage(result)
        result = hashEndUser(result, options)
        return redact(result, options)
    }

    fun normalize(
        policy: PayloadPolicy?,
        defaults: PayloadPolicy,
    ): PayloadPolicy =
        PayloadPolicy(
            mode = policy?.mode ?: defaults.mode,
            sampleRate = (policy?.sampleRate ?: defaults.sampleRate).coerceIn(0.0, 1.0),
            maxBytes = (policy?.maxBytes ?: defaults.maxBytes).takeIf { it > 0 } ?: PayloadPolicy.DEFAULT_MAX_BYTES,
        )

    /**
     * Whether to keep this record's raw text. Errors and `length` truncations are always kept: an
     * error you cannot see is worse than a storage bill, and a truncated answer is the one you most
     * need the text of.
     */
    fun keep(
        record: JsonObject,
        sampleRate: Double,
    ): Boolean =
        when {
            Ptn.asString(record["status"]) == "error" -> true
            Ptn.asString(record["stop_kind"]) == "length" -> true
            sampleRate >= 1.0 -> true
            sampleRate <= 0.0 -> false
            else -> bucket(Ptn.asString(record["id"])) < (sampleRate * SAMPLE_SCALE).roundToInt()
        }

    /** The sampling bucket, 0..9999: the first four bytes of `sha256(id)` big-endian, mod 10000. */
    fun bucket(id: String?): Int {
        val digest = java.security.MessageDigest
            .getInstance("SHA-256")
            .digest((id ?: "").toByteArray(Charsets.UTF_8))
        var value = 0L
        for (i in 0 until 4) value = (value shl 8) or (digest[i].toLong() and 0xFF)
        return (value % SAMPLE_SCALE).toInt()
    }

    // -----------------------------------------------------------------------

    private fun applyMode(
        record: JsonObject,
        policy: PayloadPolicy,
    ): JsonObject {
        if (policy.mode == PayloadMode.NONE) return dropPayload(record)
        if (!keep(record, policy.sampleRate)) return dropPayload(record)
        val wrapped = wrapPayload(record)
        return when (policy.mode) {
            PayloadMode.HASH -> hashPayload(wrapped)
            else -> truncatePayload(wrapped, policy.maxBytes)
        }
    }

    private fun dropPayload(record: JsonObject): JsonObject =
        JsonObject(record.filterKeys { it != "input" && it != "output" })

    private fun wrapPayload(record: JsonObject): JsonObject {
        val fields = LinkedHashMap(record)
        (fields["input"] as? JsonPrimitive)?.takeIf { it.isString }?.let {
            fields["input"] = JsonObject(mapOf("text" to it))
        }
        (fields["output"] as? JsonPrimitive)?.takeIf { it.isString }?.let {
            fields["output"] = JsonObject(mapOf("content" to it))
        }
        return JsonObject(fields)
    }

    private fun hashPayload(record: JsonObject): JsonObject {
        val fields = LinkedHashMap(record)
        for (key in listOf("input", "output")) {
            val value = fields[key] ?: continue
            if (value is JsonNull) continue
            val bytes = Ptn.canonicalBytes(value)
            fields[key] =
                JsonObject(
                    mapOf(
                        "sha256" to JsonPrimitive(Ptn.sha256Hex(bytes)),
                        "bytes" to JsonPrimitive(bytes.size),
                        "hashed" to JsonPrimitive(true),
                    ),
                )
        }
        return JsonObject(fields)
    }

    private fun truncatePayload(
        record: JsonObject,
        maxBytes: Int,
    ): JsonObject {
        val fields = LinkedHashMap(record)
        (fields["input"] as? JsonObject)?.let { fields["input"] = truncateInput(it, maxBytes) }
        (fields["output"] as? JsonObject)?.let { fields["output"] = truncateOutput(it, maxBytes) }
        return JsonObject(fields)
    }

    private fun truncateInput(
        input: JsonObject,
        maxBytes: Int,
    ): JsonObject {
        val perMessage = max(maxBytes / 8, MIN_LIMIT)
        val variableLimit = max(maxBytes / 4, MIN_LIMIT)
        val fields = LinkedHashMap<String, JsonElement>(input)
        var truncated = false

        (input["messages"] as? JsonArray)?.let { messages ->
            val (value, cut) = truncateMessages(messages, perMessage, maxBytes)
            fields["messages"] = value
            truncated = truncated || cut
        }
        (input["text"] as? JsonPrimitive)?.takeIf { it.isString }?.let { text ->
            val result = Utf8.truncate(text.content, maxBytes)
            fields["text"] = JsonPrimitive(result.text)
            truncated = truncated || result.truncated
        }
        input["variables"]?.takeIf { it !is JsonNull }?.let { variables ->
            val bytes = Ptn.canonicalBytes(variables)
            if (bytes.size > variableLimit) {
                fields["variables"] =
                    JsonObject(
                        mapOf(
                            "truncated" to JsonPrimitive(true),
                            "sha256" to JsonPrimitive(Ptn.sha256Hex(bytes)),
                            "bytes" to JsonPrimitive(bytes.size),
                        ),
                    )
                truncated = true
            }
        }
        if (truncated) fields["truncated"] = JsonPrimitive(true)
        return JsonObject(fields)
    }

    private fun truncateMessages(
        messages: JsonArray,
        perMessage: Int,
        totalLimit: Int,
    ): Pair<JsonArray, Boolean> {
        var truncated = false
        val trimmed =
            messages.map { message ->
                val (value, cut) = truncateMessage(message, perMessage)
                truncated = truncated || cut
                value
            }
        if (Ptn.listJsonSize(trimmed) <= totalLimit) return JsonArray(trimmed) to truncated
        return JsonArray(fitMessages(trimmed, totalLimit)) to true
    }

    private fun fitMessages(
        messages: List<JsonElement>,
        limit: Int,
    ): List<JsonElement> {
        val stubbed = stubMiddle(messages, limit)
        return if (Ptn.listJsonSize(stubbed) <= limit) stubbed else dropMiddle(messages, limit)
    }

    /**
     * Empty the middle messages into stubs from the front, stopping as soon as the list fits. The
     * first message (the system prompt) and the last message (the newest turn) are always kept, so
     * a later middle message can survive intact.
     */
    private fun stubMiddle(
        messages: List<JsonElement>,
        limit: Int,
    ): List<JsonElement> {
        val count = messages.size
        var running = Ptn.listJsonSize(messages)
        val result = ArrayList<JsonElement>(count)
        for ((index, message) in messages.withIndex()) {
            if (index > 0 && index < count - 1 && running > limit) {
                val stub =
                    markTruncated(
                        putContent(message, JsonPrimitive("…[truncated ${messageContentBytes(message)} bytes]…")),
                    )
                running = running - Ptn.jsonSize(message) + Ptn.jsonSize(stub)
                result += stub
            } else {
                result += message
            }
        }
        return result
    }

    /**
     * The first message, one `…[N messages truncated]…` marker, and as many original messages from
     * the tail as still fit the budget.
     */
    private fun dropMiddle(
        messages: List<JsonElement>,
        limit: Int,
    ): List<JsonElement> {
        if (messages.isEmpty()) return emptyList()
        var first = messages.first()
        val rest = messages.drop(1)
        while (true) {
            val marker = markerMessage(markerText(rest.size))
            val base = Ptn.listJsonSize(listOf(first, marker))
            if (base <= limit) {
                val keptTail = tailWithin(rest, limit - base)
                val dropped = rest.size - keptTail.size
                return listOf(first, markerMessage(markerText(dropped))) + keptTail
            }
            val smaller = shrinkFirst(first)
            if (smaller == first) {
                val onlyMarker = markerMessage(markerText(rest.size + 1))
                return if (Ptn.listJsonSize(listOf(onlyMarker)) <= limit) listOf(onlyMarker) else emptyList()
            }
            first = smaller
        }
    }

    private fun shrinkFirst(message: JsonElement): JsonElement {
        val bytes = messageContentBytes(message)
        if (bytes == 0) return minimalMessage(message)
        return truncateMessage(message, bytes / 2).first
    }

    private fun minimalMessage(message: JsonElement): JsonElement {
        val role = (message as? JsonObject)?.get("role")
        val fields = LinkedHashMap<String, JsonElement>()
        if (role != null) fields["role"] = role
        return markTruncated(JsonObject(fields))
    }

    private fun tailWithin(
        messages: List<JsonElement>,
        budget: Int,
    ): List<JsonElement> {
        val kept = ArrayDeque<JsonElement>()
        var left = budget
        for (message in messages.asReversed()) {
            val size = Ptn.jsonSize(message) + 1
            if (size > left) break
            kept.addFirst(message)
            left -= size
        }
        return kept.toList()
    }

    private fun markerText(dropped: Int): String = "…[$dropped messages truncated]…"

    private fun markerMessage(content: String): JsonObject =
        JsonObject(
            mapOf(
                "role" to JsonPrimitive("system"),
                "content" to JsonPrimitive(content),
                "truncated" to JsonPrimitive(true),
            ),
        )

    private fun truncateMessage(
        message: JsonElement,
        limit: Int,
    ): Pair<JsonElement, Boolean> {
        val obj = message as? JsonObject ?: return message to false
        val content = obj["content"]
        return when {
            content == null || content is JsonNull -> message to false
            content is JsonPrimitive && content.isString -> {
                val result = Utf8.truncate(content.content, limit)
                if (!result.truncated) {
                    message to false
                } else {
                    markTruncated(putContent(obj, JsonPrimitive(result.text))) to true
                }
            }

            else -> {
                val json = Ptn.canonicalJson(content)
                if (Utf8.byteSize(json) <= limit) {
                    message to false
                } else {
                    val result = Utf8.truncate(json, limit)
                    markTruncated(putContent(obj, JsonPrimitive(result.text))) to true
                }
            }
        }
    }

    private fun messageContentBytes(message: JsonElement): Int {
        val content = (message as? JsonObject)?.get("content") ?: return 0
        return when {
            content is JsonNull -> 0
            content is JsonPrimitive && content.isString -> Utf8.byteSize(content.content)
            else -> Ptn.jsonSize(content)
        }
    }

    private fun putContent(
        message: JsonElement,
        content: JsonElement,
    ): JsonObject {
        val fields = LinkedHashMap<String, JsonElement>((message as? JsonObject) ?: JsonObject(emptyMap()))
        fields["content"] = content
        return JsonObject(fields)
    }

    private fun truncateOutput(
        output: JsonObject,
        maxBytes: Int,
    ): JsonObject {
        val limit = max(maxBytes / 4, MIN_LIMIT)
        val fields = LinkedHashMap<String, JsonElement>(output)
        var truncated = false

        (output["content"] as? JsonPrimitive)?.takeIf { it.isString }?.let { content ->
            val result = Utf8.truncate(content.content, limit)
            if (result.truncated) {
                fields["content"] = JsonPrimitive(result.text)
                truncated = true
            }
        }
        (output["tool_calls"] as? JsonArray)?.let { calls ->
            val (value, cut) = truncateToolCalls(calls, limit)
            if (cut) {
                fields["tool_calls"] = value
                truncated = true
            }
        }
        if (truncated) fields["truncated"] = JsonPrimitive(true)
        return JsonObject(fields)
    }

    private fun truncateToolCalls(
        calls: JsonArray,
        limit: Int,
    ): Pair<JsonArray, Boolean> {
        if (Ptn.jsonSize(calls) <= limit) return calls to false
        val overhead = Ptn.jsonSize(JsonArray(calls.map { putArguments(it, "") }))
        val budget = max(limit - overhead, 0) / max(calls.size, 1)
        return shrinkToolCalls(calls, budget, limit) to true
    }

    private fun shrinkToolCalls(
        calls: JsonArray,
        budget: Int,
        limit: Int,
    ): JsonArray {
        var currentBudget = budget
        while (currentBudget >= MIN_TOOL_CALL_BUDGET) {
            val shrunk =
                JsonArray(
                    calls.map { call ->
                        val arguments = argumentsOf(call)
                        if (arguments ==
                            null
                        ) {
                            call
                        } else {
                            putArguments(call, Utf8.truncate(arguments, currentBudget).text)
                        }
                    },
                )
            if (Ptn.jsonSize(shrunk) <= limit) return shrunk
            currentBudget /= 2
        }
        return JsonArray(
            listOf(
                JsonObject(
                    mapOf(
                        "truncated" to JsonPrimitive(true),
                        "bytes" to JsonPrimitive(Ptn.jsonSize(calls)),
                    ),
                ),
            ),
        )
    }

    private fun argumentsOf(call: JsonElement): String? {
        val function = (call as? JsonObject)?.get("function") as? JsonObject ?: return null
        val arguments = function["arguments"] as? JsonPrimitive ?: return null
        return if (arguments.isString) arguments.content else null
    }

    private fun putArguments(
        call: JsonElement,
        arguments: String,
    ): JsonElement {
        if (argumentsOf(call) == null) return call
        val obj = call as JsonObject
        val function = LinkedHashMap<String, JsonElement>(obj["function"] as JsonObject)
        function["arguments"] = JsonPrimitive(arguments)
        val fields = LinkedHashMap<String, JsonElement>(obj)
        fields["function"] = JsonObject(function)
        return JsonObject(fields)
    }

    private fun markTruncated(message: JsonObject): JsonObject {
        val fields = LinkedHashMap<String, JsonElement>(message)
        fields["truncated"] = JsonPrimitive(true)
        return JsonObject(fields)
    }

    private fun capErrorMessage(record: JsonObject): JsonObject {
        val error = record["error"] as? JsonObject ?: return record
        val message = error["message"] as? JsonPrimitive ?: return record
        if (!message.isString || Utf8.byteSize(message.content) <= ERROR_MESSAGE_MAX) return record
        val fields = LinkedHashMap<String, JsonElement>(error)
        fields["message"] = JsonPrimitive(Utf8.truncate(message.content, ERROR_MESSAGE_MAX).text)
        val result = LinkedHashMap<String, JsonElement>(record)
        result["error"] = JsonObject(fields)
        return JsonObject(result)
    }

    private fun hashEndUser(
        record: JsonObject,
        options: PayloadOptions,
    ): JsonObject {
        if (!options.hashEndUser) return record
        val ref = Ptn.asString(record["end_user_ref"]) ?: return record
        val fields = LinkedHashMap<String, JsonElement>(record)
        fields["end_user_ref"] = JsonPrimitive(Ptn.sha256Hex(ref))
        return JsonObject(fields)
    }

    private fun redact(
        record: JsonObject,
        options: PayloadOptions,
    ): JsonObject {
        val hook = options.redact ?: return record
        return try {
            hook(record)
        } catch (e: RuntimeException) {
            options.onRedactFailure(e)
            dropPayload(record)
        }
    }
}
