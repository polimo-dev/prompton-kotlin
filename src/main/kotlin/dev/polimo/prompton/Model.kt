package dev.polimo.prompton

/** One message of a chat prompt. */
public data class PromptMessage
    @JvmOverloads
    constructor(
        val role: String = "",
        val content: String,
        val name: String? = null,
        val type: String? = null,
        val contentValue: Any? = content,
        val hasContent: Boolean = true,
        val toolCallId: String? = null,
        val toolCalls: List<Map<String, Any?>> = emptyList(),
        val hasToolCalls: Boolean = false,
        val extra: Map<String, Any?> = emptyMap(),
    )

/** What a prompt asks the provider for. */
public enum class UseCaseKind {
    CHAT,
    DECISION,
    TEXT,
    EMBEDDING,
    ;

    public val wire: String get() = name.lowercase()

    public companion object {
        public fun fromWire(value: String?): UseCaseKind = fromWireOrNull(value) ?: CHAT

        public fun fromWireOrNull(value: String?): UseCaseKind? =
            entries.firstOrNull { it.wire == value?.trim()?.lowercase() }
    }
}

/** Where the prompt document behind a call came from. */
public enum class UseCaseSource {
    REMOTE,
    DISK,
    BUNDLE,
    MANUAL,
    ;

    public val wire: String get() = name.lowercase()

    public companion object {
        public fun fromWire(value: String?): UseCaseSource? =
            entries.firstOrNull { it.wire == value?.trim()?.lowercase() }
    }
}

/** How much of a monitoring log's payload is stored. */
public enum class PayloadMode {
    FULL,
    HASH,
    NONE,
    ;

    public val wire: String get() = name.lowercase()

    public companion object {
        public fun fromWire(value: String?): PayloadMode =
            entries.firstOrNull { it.wire == value?.trim()?.lowercase() } ?: FULL
    }
}

/** The prompt's payload policy, as the prompt document carries it. */
public data class PayloadPolicy
    @JvmOverloads
    constructor(
        val mode: PayloadMode = PayloadMode.FULL,
        val sampleRate: Double = 1.0,
        val maxBytes: Int = DEFAULT_MAX_BYTES,
        val retentionDays: Int? = null,
        val encrypt: Boolean = false,
    ) {
        public companion object {
            public const val DEFAULT_MAX_BYTES: Int = 262_144

            public val DEFAULT: PayloadPolicy = PayloadPolicy()
        }
    }

/** One declared input variable of a prompt. */
public data class InputVariable
    @JvmOverloads
    constructor(
        val name: String,
        val type: String = "string",
        val required: Boolean = false,
        val description: String? = null,
    )

/** A prompt as the prompt document describes it. */
public data class UseCaseEntry(
    val id: String?,
    val key: String,
    val kind: UseCaseKind,
    val inputSchema: List<InputVariable>,
    val defaultParams: Map<String, Any?>,
    val payloadPolicy: PayloadPolicy?,
)

/** A live deployment revision: one model plus one pinned prompt version per prompt name. */
public data class Deployment(
    val id: String?,
    val useCaseKey: String,
    val revision: String?,
    val modelId: String?,
    val api: String? = null,
    val requestPath: String? = null,
    val params: Map<String, Any?>,
    val providerOptions: Map<String, Any?>,
    val promptPins: Map<String, String>,
)

/** An immutable prompt version. */
public data class PromptVersion(
    val id: String,
    val promptId: String?,
    val number: Int?,
    val kind: UseCaseKind? = null,
    val engine: TemplateEngine,
    val messages: List<PromptMessage>?,
    val decision: DecisionTemplate? = null,
    val tools: Map<String, Any?>,
    val textTemplate: String?,
)

/** A native Decision prompt template. String leaves are rendered with the prompt version engine. */
public data class DecisionTemplate(
    val state: Any?,
    val questions: Map<String, Any?>,
)

/** A rendered native Decision prompt ready for the Decisions provider API. */
public data class RenderedDecision(
    val state: Any?,
    val questions: Map<String, Any?>,
) {
    public fun asMap(): Map<String, Any?> = mapOf("state" to state, "questions" to questions)
}

/** Provider request prepared by the SDK. The SDK never sends this request. */
public data class PreparedRequest(
    val api: String,
    val method: String,
    val path: String,
    val body: Map<String, Any?>,
)

/** Per-call overrides for [UseCase.request]. */
public data class PreparedRequestOptions
    @JvmOverloads
    constructor(
        val prompt: String? = null,
        val params: Map<String, Any?>? = null,
        val providerOptions: Map<String, Any?>? = null,
        val sessionId: String? = null,
        val trace: Map<String, Any?>? = null,
        val user: String? = null,
    )

/** A model catalog entry. */
public data class ModelEntry(
    val id: String,
    val provider: String?,
    val modelId: String?,
    val displayName: String?,
    val metadata: Map<String, Any?>,
    val providerOptions: Map<String, Any?>,
    val capabilities: List<String>,
    val status: String?,
)
