package dev.polimo.prompton

import dev.polimo.prompton.internal.Iso8601
import dev.polimo.prompton.internal.Ptn
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant

/** Whether the provider call succeeded. */
public enum class LogStatus {
    OK,
    ERROR,
    ;

    public val wire: String get() = name.lowercase()
}

/** How a provider call failed. */
public enum class ErrorKind {
    HTTP_4XX,
    HTTP_5XX,
    RATE_LIMITED,
    TIMEOUT,
    TRANSPORT,
    PARSE,
    APP,
    ;

    public val wire: String get() = name.lowercase()

    public companion object {
        public fun fromWire(value: String?): ErrorKind =
            entries.firstOrNull { it.wire == value?.trim()?.lowercase() } ?: APP

        /** The kind an HTTP status maps to. */
        public fun ofStatus(status: Int): ErrorKind =
            when {
                status == 429 -> RATE_LIMITED
                status in 400..499 -> HTTP_4XX
                status >= 500 -> HTTP_5XX
                else -> APP
            }
    }
}

/** Where a cost figure came from. */
public enum class CostSource {
    PROVIDER,
    CATALOG,
    UNKNOWN,
    ;

    public val wire: String get() = name.lowercase()
}

/** The failure attached to a record with status `error`. */
public data class LogError
    @JvmOverloads
    constructor(
        val kind: ErrorKind,
        /** The provider's HTTP status, when the call reached it. */
        val status: Int? = null,
        val message: String? = null,
    )

/** Tokens and cost, as the provider reported them. */
public data class Usage
    @JvmOverloads
    constructor(
        val inputTokens: Long? = null,
        val outputTokens: Long? = null,
        val costUsd: Double? = null,
        val costSource: CostSource = CostSource.UNKNOWN,
        /** The provider's raw usage object. Over 16 KB the server blanks it. */
        val raw: Map<String, Any?>? = null,
    )

/** What went into the provider call. */
public data class LogInput
    @JvmOverloads
    constructor(
        val variables: Map<String, Any?>? = null,
        val messages: List<PromptMessage>? = null,
        val text: String? = null,
    ) {
        public fun isEmpty(): Boolean = variables == null && messages == null && text == null
    }

/** What came back. */
public data class LogOutput
    @JvmOverloads
    constructor(
        val content: String? = null,
        val toolCalls: List<Any?>? = null,
    ) {
        public fun isEmpty(): Boolean = content == null && toolCalls == null
    }

/** Which client sent the record. */
public data class SdkInfo(
    val name: String,
    val version: String,
) {
    public companion object {
        public val CURRENT: SdkInfo = SdkInfo(PromptOnConfig.SDK_NAME, PromptOnConfig.SDK_VERSION)
    }
}

/**
 * One monitoring log: what the app asked the provider for, what came back, and which pin produced
 * it. `POST /api/v1/logs` takes these in batches of at most 200.
 */
public data class LogRecord
    @JvmOverloads
    constructor(
        val useCase: String,
        val model: String,
        val status: LogStatus,
        val startedAt: Instant,
        /** Filled with a fresh UUIDv7 when absent. It is the idempotency key. */
        val id: String? = null,
        val kind: UseCaseKind? = null,
        val deploymentId: String? = null,
        val deploymentRevision: Int? = null,
        val prompt: String? = null,
        val promptVersionId: String? = null,
        val modelId: String? = null,
        val source: UseCaseSource? = null,
        val provider: String? = null,
        val modelUsed: String? = null,
        val upstreamProvider: String? = null,
        val params: Map<String, Any?>? = null,
        val input: LogInput? = null,
        val output: LogOutput? = null,
        val finishReason: String? = null,
        val stopKind: StopKind? = null,
        val error: LogError? = null,
        val usage: Usage? = null,
        val latencyMs: Long? = null,
        val traceId: String? = null,
        val sequence: Int? = null,
        val endUserRef: String? = null,
        val context: Map<String, Any?>? = null,
        val metadata: Map<String, Any?>? = null,
        val sdk: SdkInfo? = null,
    ) {
        init {
            require(useCase.isNotBlank()) { "a monitoring log needs a use_case" }
            require(model.isNotBlank()) { "a monitoring log needs a model" }
        }

        /** The wire shape. Top-level keys whose value is null are omitted. */
        public fun toJsonObject(): JsonObject {
            val fields = LinkedHashMap<String, JsonElement>()
            fields["id"] = JsonPrimitive(id ?: UuidV7.generate())
            fields["use_case"] = JsonPrimitive(useCase)
            fields["model"] = JsonPrimitive(model)
            fields["status"] = JsonPrimitive(status.wire)
            fields["started_at"] = JsonPrimitive(Iso8601.format(startedAt))
            put(fields, "kind", kind?.wire)
            put(fields, "deployment_id", deploymentId)
            deploymentRevision?.let { fields["deployment_revision"] = JsonPrimitive(it) }
            put(fields, "prompt", prompt)
            put(fields, "prompt_version_id", promptVersionId)
            put(fields, "model_id", modelId)
            put(fields, "source", source?.wire)
            put(fields, "provider", provider)
            put(fields, "model_used", modelUsed)
            put(fields, "upstream_provider", upstreamProvider)
            params?.let { fields["params"] = Ptn.toObject(it) }
            input?.takeIf { !it.isEmpty() }?.let { fields["input"] = inputJson(it) }
            output?.takeIf { !it.isEmpty() }?.let { fields["output"] = outputJson(it) }
            put(fields, "finish_reason", finishReason)
            put(fields, "stop_kind", stopKind?.wire)
            error?.let { fields["error"] = errorJson(it) }
            usage?.let { fields["usage"] = usageJson(it) }
            latencyMs?.let { fields["latency_ms"] = JsonPrimitive(it) }
            put(fields, "trace_id", traceId)
            sequence?.let { fields["sequence"] = JsonPrimitive(it) }
            put(fields, "end_user_ref", endUserRef)
            context?.let { fields["context"] = Ptn.toObject(it) }
            metadata?.let { fields["metadata"] = Ptn.toObject(it) }
            fields["sdk"] = sdkJson(sdk ?: SdkInfo.CURRENT)
            return JsonObject(fields)
        }

        private fun put(
            fields: MutableMap<String, JsonElement>,
            key: String,
            value: String?,
        ) {
            if (value != null) fields[key] = JsonPrimitive(value)
        }

        private fun inputJson(input: LogInput): JsonObject {
            val fields = LinkedHashMap<String, JsonElement>()
            input.variables?.let { fields["variables"] = Ptn.toObject(it) }
            input.messages?.let { messages ->
                fields["messages"] = JsonArray(messages.map { messageJson(it) })
            }
            input.text?.let { fields["text"] = JsonPrimitive(it) }
            return JsonObject(fields)
        }

        private fun outputJson(output: LogOutput): JsonObject {
            val fields = LinkedHashMap<String, JsonElement>()
            output.content?.let { fields["content"] = JsonPrimitive(it) }
            output.toolCalls?.let { calls -> fields["tool_calls"] = JsonArray(calls.map { Ptn.toElement(it) }) }
            return JsonObject(fields)
        }

        private fun errorJson(error: LogError): JsonObject {
            val fields = LinkedHashMap<String, JsonElement>()
            fields["kind"] = JsonPrimitive(error.kind.wire)
            error.status?.let { fields["status"] = JsonPrimitive(it) }
            error.message?.let { fields["message"] = JsonPrimitive(it) }
            return JsonObject(fields)
        }

        private fun usageJson(usage: Usage): JsonObject =
            JsonObject(
                mapOf(
                    "input_tokens" to (usage.inputTokens?.let { JsonPrimitive(it) } ?: JsonNull),
                    "output_tokens" to (usage.outputTokens?.let { JsonPrimitive(it) } ?: JsonNull),
                    "cost_usd" to (usage.costUsd?.let { JsonPrimitive(it) } ?: JsonNull),
                    "cost_source" to JsonPrimitive(usage.costSource.wire),
                    "raw" to (usage.raw?.let { Ptn.toObject(it) } ?: JsonNull),
                ),
            )

        private fun messageJson(message: PromptMessage): JsonObject {
            val fields = LinkedHashMap<String, JsonElement>()
            fields["role"] = JsonPrimitive(message.role)
            fields["content"] = JsonPrimitive(message.content)
            message.name?.let { fields["name"] = JsonPrimitive(it) }
            return JsonObject(fields)
        }

        private fun sdkJson(sdk: SdkInfo): JsonObject =
            JsonObject(
                mapOf(
                    "name" to JsonPrimitive(sdk.name),
                    "version" to JsonPrimitive(sdk.version),
                ),
            )
    }

/** What the provider call produced, as the wrapper records it. */
public data class Result
    @JvmOverloads
    constructor(
        val content: String? = null,
        val toolCalls: List<Any?>? = null,
        val finishReason: String? = null,
        /** Derived from [finishReason] when absent. */
        val stopKind: StopKind? = null,
        val usage: Usage? = null,
        val modelUsed: String? = null,
        val upstreamProvider: String? = null,
        /** Whether the call was billed to your own provider key; stored under `metadata.is_byok`. */
        val isByok: Boolean? = null,
    ) {
        public companion object {
            @JvmStatic
            public fun fromOpenAI(answer: Any?): Result =
                Result(
                    content = firstString(answer, "choices.0.message.content", "choices.0.text", "output_text"),
                    finishReason = firstString(answer, "choices.0.finish_reason", "finish_reason"),
                    usage =
                        Usage(
                            inputTokens = firstLong(answer, "usage.prompt_tokens", "usage.input_tokens"),
                            outputTokens = firstLong(answer, "usage.completion_tokens", "usage.output_tokens"),
                            costSource = CostSource.PROVIDER,
                            raw = nativeMap(read(answer, "usage")),
                        ),
                    modelUsed = firstString(answer, "model"),
                )

            @JvmStatic
            public fun fromAnthropic(answer: Any?): Result =
                Result(
                    content = anthropicContent(answer),
                    finishReason = firstString(answer, "stop_reason", "finish_reason"),
                    usage =
                        Usage(
                            inputTokens = firstLong(answer, "usage.input_tokens"),
                            outputTokens = firstLong(answer, "usage.output_tokens"),
                            costSource = CostSource.PROVIDER,
                            raw = nativeMap(read(answer, "usage")),
                        ),
                    modelUsed = firstString(answer, "model"),
                    upstreamProvider = "anthropic",
                )

            private fun firstString(
                value: Any?,
                vararg paths: String,
            ): String? = paths.firstNotNullOfOrNull { read(value, it)?.toString() }

            private fun firstLong(
                value: Any?,
                vararg paths: String,
            ): Long? = paths.firstNotNullOfOrNull { (read(value, it) as? Number)?.toLong() }

            private fun anthropicContent(answer: Any?): String? {
                val content = read(answer, "content")
                if (content is List<*>) {
                    return content.mapNotNull { read(it, "text")?.toString() }.joinToString("").ifBlank { null }
                }
                return content?.toString()
            }

            @Suppress("UNCHECKED_CAST")
            private fun nativeMap(value: Any?): Map<String, Any?>? = value as? Map<String, Any?>

            private fun read(
                value: Any?,
                path: String,
            ): Any? {
                var current = value
                for (segment in path.split('.')) {
                    if (current == null) return null
                    val index = segment.toIntOrNull()
                    current =
                        when {
                            index != null && current is List<*> -> current.getOrNull(index)
                            current is Map<*, *> -> current[segment]
                            else -> property(current, segment)
                        }
                }
                return current
            }

            private fun property(
                value: Any,
                name: String,
            ): Any? {
                val capitalized = name.replaceFirstChar { it.titlecase() }
                val methods = listOf(name, "get$capitalized", "is$capitalized")
                for (methodName in methods) {
                    val method = value.javaClass.methods.firstOrNull { it.name == methodName && it.parameterCount == 0 }
                    if (method != null) return runCatching { method.invoke(value) }.getOrNull()
                }
                return value.javaClass.fields.firstOrNull { it.name == name }?.let { field ->
                    runCatching { field.get(value) }.getOrNull()
                }
            }
        }
    }

/** Everything the wrapper cannot work out for itself. */
public data class TrackMeta
    @JvmOverloads
    constructor(
        /** Pre-issue an id when the app wants to store it before the call. */
        val id: String? = null,
        val variables: Map<String, Any?>? = null,
        /** The final messages, after the app attached its own history. */
        val inputMessages: List<PromptMessage>? = null,
        val inputText: String? = null,
        val endUserRef: String? = null,
        val traceId: String? = null,
        val sequence: Int? = null,
        /** Free-form tags kept for filtering in the log; use case selection never looks at them. */
        val context: Map<String, Any?> = emptyMap(),
        val metadata: Map<String, Any?> = emptyMap(),
        /** The params actually sent, layered over the use case's params. */
        val params: Map<String, Any?>? = null,
    )

/**
 * The recorder handed to the block of [UseCase.track].
 *
 * Set [result] with what the provider returned and, when the call failed in a way that is not an
 * exception, [error]. Anything the block throws is logged as `app` and rethrown unchanged.
 */
public class TrackCall internal constructor() {
    public var result: Result? = null
    public var error: LogError? = null

    /** Records a successful provider call. */
    public fun result(result: Result) {
        this.result = result
    }

    /** Records a failure, optionally keeping the usage and output that came with it. */
    @JvmOverloads
    public fun failed(
        error: LogError,
        result: Result? = null,
    ) {
        this.error = error
        if (result != null) this.result = result
    }
}
