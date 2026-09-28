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
    TEXT,
    EMBEDDING,
    ;

    public val wire: String get() = name.lowercase()

    public companion object {
        public fun fromWire(value: String?): UseCaseKind =
            entries.firstOrNull { it.wire == value?.trim()?.lowercase() } ?: CHAT
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
    val revision: Int?,
    val modelId: String?,
    val params: Map<String, Any?>,
    val providerOptions: Map<String, Any?>,
    val promptPins: Map<String, String>,
)

/** An immutable prompt version. */
public data class PromptVersion(
    val id: String,
    val promptId: String?,
    val number: Int?,
    val engine: TemplateEngine,
    val messages: List<PromptMessage>?,
    val tools: Map<String, Any?>,
    val textTemplate: String?,
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
