package dev.polimo.prompton

import dev.polimo.prompton.internal.TemplateAst
import dev.polimo.prompton.internal.TemplateLint
import dev.polimo.prompton.internal.TemplateParser
import dev.polimo.prompton.internal.TemplateRenderer

/** Which renderer a prompt version is written for. */
public enum class TemplateEngine {
    /** The Liquid subset PromptOn allows. */
    LIQUID,

    /** No parsing at all: the source is the output. For prompts whose text contains `{{` or `{%`. */
    RAW,

    ;

    public val wire: String get() = name.lowercase()

    public companion object {
        public fun fromWire(value: String?): TemplateEngine =
            entries.firstOrNull { it.wire == value?.trim()?.lowercase() } ?: LIQUID
    }
}

/** One reason [Template.lint] rejected a template. */
public data class LintReason(
    /** `whitespace_control`, `disallowed_tag`, `disallowed_filter` or `parse`. */
    val kind: String,
    val value: String,
)

/**
 * Prompt template rendering: the Liquid subset PromptOn allows.
 *
 * Tags: `for` (with `else`, `break`, `continue` and `forloop.*`), `if`/`elsif`/`else`, `unless`,
 * `assign`. Filters: `size`, `join`, `default`. Everything else — `include`, `capture`, `case`,
 * `raw`, `comment`, `cycle`, `render`, `tablerow`, `increment`, `liquid` — is a parse error, and
 * whitespace control (`{%-`, `-%}`) is rejected by [lint].
 *
 * A variable is *missing* when its key is absent from the variables map. A key present with a
 * `null` value is not missing: it renders as the empty string and `default` replaces it. Missing is
 * an error at output positions, in a `for` enumerable, in an `unless` condition and as an `assign`
 * source; it is not checked in a branch that does not execute.
 */
public object Template {
    internal const val MESSAGE_SLOT_ERROR: String =
        "Message slots are not supported; compose conversation history in app code."

    /** The tag names the subset allows. */
    public val allowedTags: List<String> = TemplateParser.ALLOWED_TAGS.sorted()

    /** The filter names the subset allows. */
    public val allowedFilters: List<String> = TemplateRenderer.ALLOWED_FILTERS.sorted()

    /**
     * Renders [source] with [variables].
     *
     * @throws MissingVariableException when a variable the template reads was not supplied
     * @throws TemplateParseException when the template is outside the allowed subset
     * @throws TemplateRenderException when rendering fails for another reason
     */
    @JvmOverloads
    public fun render(
        source: String,
        variables: Map<String, Any?>? = null,
        engine: TemplateEngine = TemplateEngine.LIQUID,
    ): String {
        if (engine == TemplateEngine.RAW) return source
        val nodes = TemplateParser.parse(source)
        return TemplateRenderer.render(nodes, variables ?: emptyMap())
    }

    /** Renders the `content` of every message, leaving `role` and `name` untouched. */
    @JvmOverloads
    public fun renderMessages(
        messages: List<PromptMessage>,
        variables: Map<String, Any?>? = null,
        engine: TemplateEngine = TemplateEngine.LIQUID,
    ): List<PromptMessage> =
        buildList {
            val vars = variables ?: emptyMap()
            for (message in messages) {
                if (message.type == "slot") {
                    throw TemplateRenderException(MESSAGE_SLOT_ERROR)
                } else if (message.hasContent && message.contentValue is String) {
                    val rendered = render(message.content, vars, engine)
                    add(message.copy(content = rendered, contentValue = rendered, hasContent = true))
                } else {
                    add(message)
                }
            }
        }

    /**
     * The static whitelist check the PromptOn server runs when a prompt version is committed. An
     * empty list means the template is allowed.
     */
    public fun lint(source: String): List<LintReason> = TemplateLint.lint(source)

    /**
     * The top-level input variables the template reads, sorted and deduplicated. Loop variables,
     * `assign` targets and `forloop` are excluded.
     */
    public fun variables(source: String): List<String> = TemplateAst.variables(source)
}
