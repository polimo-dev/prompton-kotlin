package dev.polimo.prompton

/** Base class of every error the SDK raises on purpose. */
public open class PromptOnException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * No use case document is available from any tier: PromptOn is unreachable and neither the disk
 * cache nor a bundled document is usable for this project and environment.
 */
public class UseCaseDocumentUnavailableException(
    public val environment: String,
    message: String =
        "no PromptOn use case document for environment '$environment': the server is unreachable and " +
            "nothing is cached on disk or bundled",
) : PromptOnException(message)

/** The use case document has no use case with this key. */
public class UnknownUseCaseException(
    public val useCase: String,
) : PromptOnException("unknown use case: $useCase")

/** The use case exists but has no live deployment in this environment. */
public class UnresolvedUseCaseException(
    public val useCase: String,
) : PromptOnException("use case '$useCase' has no live deployment in this environment")

/**
 * The live deployment pins no prompt version under the requested name. There is never a silent
 * fallback to `default`.
 */
public class UnknownPromptException(
    public val useCase: String,
    public val prompt: String,
    public val promptNames: List<String>,
) : PromptOnException(
        "the live deployment of '$useCase' pins no prompt named \"$prompt\" — " +
            "available prompts: ${promptNames.joinToString(", ")}",
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
