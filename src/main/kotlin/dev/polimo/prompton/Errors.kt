package dev.polimo.prompton

/** Base class of every error the SDK raises on purpose. */
public open class PromptOnException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * No prompt document is available from any tier: PromptOn is unreachable and neither the disk
 * cache nor a bundled document is usable for this project and environment.
 */
public class PromptDocumentUnavailableException(
    public val environment: String,
    message: String =
        "no PromptOn prompt document for environment '$environment': the server is unreachable and " +
            "nothing is cached on disk or bundled",
) : PromptOnException(message)

/** The prompt document has no prompt with this key. */
public class UnknownPromptException(
    public val promptKey: String,
) : PromptOnException("unknown prompt: $promptKey")

/** The prompt exists but has no live deployment in this environment. */
public class UnresolvedPromptException(
    public val promptKey: String,
) : PromptOnException("prompt '$promptKey' has no live deployment in this environment")

/**
 * The live deployment pins no prompt version under the requested template name. There is never a silent
 * fallback to `default`.
 */
public class UnknownTemplateException(
    public val promptKey: String,
    public val template: String,
    public val templateNames: List<String>,
) : PromptOnException(
        "the live deployment of '$promptKey' pins no template named \"$template\" — " +
            "available templates: ${templateNames.joinToString(", ")}",
    )

/** The template reads a variable the call did not supply. */
public class MissingVariableException(
    public val variable: String,
) : PromptOnException("missing variable: $variable")

/** The template uses a construct outside the allowed Liquid subset, or is malformed. */
public class TemplateParseException(
    public val reason: String,
) : PromptOnException("template parse error: $reason")

/** The template parsed but rendering failed for another reason. */
public class TemplateRenderException(
    public val reason: String,
) : PromptOnException("template render error: $reason")

/** A PromptOn HTTP call answered with an error status. */
public class PromptOnApiException(
    public val status: Int,
    public val code: String?,
    message: String,
    public val details: Map<String, Any?> = emptyMap(),
) : PromptOnException("PromptOn API error $status${code?.let { " ($it)" } ?: ""}: $message")

/** The SDK could not prepare a provider-native request from the deployed prompt metadata. */
public class PreparedRequestException(
    message: String,
) : PromptOnException(message)
