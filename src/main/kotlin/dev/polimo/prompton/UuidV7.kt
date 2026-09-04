package dev.polimo.prompton

import java.security.SecureRandom

/**
 * RFC 9562 UUIDv7 generator.
 *
 * A monitoring log id is the idempotency key the app issues before it calls the provider, so it has
 * to exist client-side and it has to sort by time. The PromptOn ingest column is a UUIDv7 type: a
 * v4 id passes request validation and then fails on write, coming back in `rejected` with
 * "record could not be stored".
 *
 * Layout: 48 bits of unix milliseconds, the version nibble 7, 12 random bits, the variant bits 10,
 * then 62 random bits. Rendered as lowercase hex with dashes.
 */
public object UuidV7 {
    private val RANDOM = SecureRandom()

    /** A new UUIDv7 for the current time. */
    public fun generate(): String = generate(System.currentTimeMillis())

    /** A new UUIDv7 stamped with [unixMillis]. */
    public fun generate(unixMillis: Long): String {
        require(unixMillis >= 0) { "unixMillis must not be negative" }
        val random = ByteArray(10)
        RANDOM.nextBytes(random)

        var high = (unixMillis and 0xFFFF_FFFF_FFFFL) shl 16
        high = high or 0x7000L
        high = high or ((random[0].toLong() and 0x0F) shl 8)
        high = high or (random[1].toLong() and 0xFF)

        var low = 0L
        for (i in 2 until 10) {
            low = (low shl 8) or (random[i].toLong() and 0xFF)
        }
        low = (low and 0x3FFF_FFFF_FFFF_FFFFL) or (1L shl 63)

        return format(high, low)
    }

    /** The unix milliseconds stamped into a UUIDv7 string, or `null` when it is not one. */
    public fun timestampMillis(uuid: String): Long? {
        val hex = uuid.replace("-", "")
        if (hex.length != 32) return null
        return hex.substring(0, 12).toLongOrNull(16)
    }

    private fun format(
        high: Long,
        low: Long,
    ): String {
        val sb = StringBuilder(36)
        appendHex(sb, high ushr 32, 8)
        sb.append('-')
        appendHex(sb, (high ushr 16) and 0xFFFF, 4)
        sb.append('-')
        appendHex(sb, high and 0xFFFF, 4)
        sb.append('-')
        appendHex(sb, low ushr 48, 4)
        sb.append('-')
        appendHex(sb, low and 0xFFFF_FFFF_FFFFL, 12)
        return sb.toString()
    }

    private fun appendHex(
        sb: StringBuilder,
        value: Long,
        width: Int,
    ) {
        val hex = java.lang.Long.toHexString(value)
        repeat(width - hex.length) { sb.append('0') }
        sb.append(hex)
    }
}
