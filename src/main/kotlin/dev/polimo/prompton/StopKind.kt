package dev.polimo.prompton

/**
 * PromptOn's normalised stop reason.
 *
 * Providers each spell the reason a log ended differently; the SDK and the server both map
 * the raw `finish_reason` onto these five values with the same table, so the truncation rate means
 * the same thing whichever provider produced the call.
 */
public enum class StopKind {
    STOP,
    LENGTH,
    TOOL_CALL,
    CONTENT_FILTER,
    OTHER,
    ;

    /** The wire value (`stop`, `length`, `tool_call`, `content_filter`, `other`). */
    public val wire: String get() = name.lowercase()

    /**
     * Whether the output was cut off. Only [LENGTH] counts: a `tool_calls` finish is not a
     * truncation, and the truncation rate, the evaluator and the alerts all depend on that.
     */
    public val isTruncated: Boolean get() = this == LENGTH

    public companion object {
        private val STOP_REASONS = setOf("stop", "end_turn", "stop_sequence")
        private val LENGTH_REASONS = setOf("length", "max_tokens")
        private val TOOL_CALL_REASONS = setOf("tool_call", "tool_calls", "tool_use")
        private val CONTENT_FILTER_REASONS = setOf("content_filter")

        /**
         * Normalises a provider `finish_reason` (or an already normalised `stop_kind`) into a
         * [StopKind]. Comparison lowercases and trims, so Google's `STOP` and `MAX_TOKENS` map
         * correctly, and normalisation is idempotent: feeding a `stop_kind` back in returns itself.
         *
         * Google's `SAFETY` and `RECITATION` map to [OTHER], not [CONTENT_FILTER]: only the literal
         * string `content_filter` lands there.
         */
        public fun normalize(finishReason: String?): StopKind {
            val reason = finishReason?.trim()?.lowercase() ?: return OTHER
            return when (reason) {
                in STOP_REASONS -> STOP
                in LENGTH_REASONS -> LENGTH
                in TOOL_CALL_REASONS -> TOOL_CALL
                in CONTENT_FILTER_REASONS -> CONTENT_FILTER
                else -> OTHER
            }
        }

        /** Whether a raw `finish_reason` means the output was cut off. */
        public fun isTruncated(finishReason: String?): Boolean = normalize(finishReason) == LENGTH

        /** Parses a wire value back into an enum constant, or `null` when it is not one of the five. */
        public fun fromWire(value: String?): StopKind? =
            entries.firstOrNull { it.wire == value?.trim()?.lowercase() }
    }
}
