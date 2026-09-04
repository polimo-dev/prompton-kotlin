package dev.polimo.prompton.internal

import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** ISO 8601 timestamps in the shape the PromptOn ingest expects (UTC, microsecond precision). */
internal object Iso8601 {
    private val FORMATTER: DateTimeFormatter =
        DateTimeFormatter
            .ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'")
            .withZone(ZoneOffset.UTC)

    fun format(instant: Instant): String = FORMATTER.format(instant)

    fun parseOrNull(text: String?): Instant? {
        if (text.isNullOrBlank()) return null
        return try {
            Instant.parse(text)
        } catch (_: DateTimeParseException) {
            try {
                java.time.OffsetDateTime
                    .parse(text)
                    .toInstant()
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }
}

/** RFC 7231 `HTTP-date` parsing, used for `Last-Modified` and date-form `Retry-After` headers. */
internal object HttpDate {
    private val FORMATTER: DateTimeFormatter = DateTimeFormatter.RFC_1123_DATE_TIME

    fun parseOrNull(text: String?): Instant? {
        if (text.isNullOrBlank()) return null
        return try {
            java.time.ZonedDateTime
                .parse(text, FORMATTER)
                .toInstant()
        } catch (_: DateTimeParseException) {
            Iso8601.parseOrNull(text)
        }
    }
}

/**
 * UTF-8 aware byte-budget truncation.
 *
 * Over the budget a string keeps its head and its tail and loses the middle:
 * `<head>\n…[truncated N bytes]…\n<tail>` where `N = originalBytes - limit`. The budget left after
 * the marker is split 60% head / 40% tail and each side is trimmed back to a character boundary,
 * so the result is never longer than the cap and never contains half a character.
 */
internal object Utf8 {
    data class Result(
        val text: String,
        val truncated: Boolean,
    )

    fun byteSize(text: String): Int = text.toByteArray(StandardCharsets.UTF_8).size

    fun truncate(
        text: String,
        limit: Int,
    ): Result {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size <= limit) return Result(text, false)

        val marker = "\n…[truncated ${bytes.size - limit} bytes]…\n"
        val markerBytes = marker.toByteArray(StandardCharsets.UTF_8)
        if (markerBytes.size > limit) {
            val head = trimTrailingPartial(bytes.copyOfRange(0, maxOf(limit, 0)))
            return Result(decode(head), true)
        }

        val budget = limit - markerBytes.size
        val headCount = budget * 6 / 10
        val tailCount = budget - headCount
        val head = trimTrailingPartial(bytes.copyOfRange(0, headCount))
        val tail = trimLeadingPartial(bytes.copyOfRange(bytes.size - tailCount, bytes.size))
        return Result(decode(head) + marker + decode(tail), true)
    }

    private fun decode(bytes: ByteArray): String = String(bytes, StandardCharsets.UTF_8)

    private fun trimTrailingPartial(bytes: ByteArray): ByteArray {
        var current = bytes
        var tries = 3
        while (current.isNotEmpty() && !isValidUtf8(current)) {
            if (tries == 0) return ByteArray(0)
            current = current.copyOfRange(0, current.size - 1)
            tries -= 1
        }
        return current
    }

    private fun trimLeadingPartial(bytes: ByteArray): ByteArray {
        var start = 0
        while (start < bytes.size) {
            val b = bytes[start].toInt() and 0xFF
            if (b in 0x80..0xBF) start += 1 else break
        }
        return bytes.copyOfRange(start, bytes.size)
    }

    private fun isValidUtf8(bytes: ByteArray): Boolean {
        val decoder =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(java.nio.ByteBuffer.wrap(bytes))
            true
        } catch (_: CharacterCodingException) {
            false
        }
    }
}
