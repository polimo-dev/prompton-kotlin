package dev.polimo.prompton.internal

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger

/** Environment variable access, with one seam so tests can pretend. */
internal object Env {
    @Volatile
    var reader: (String) -> String? = { System.getenv(it) }

    fun get(name: String): String? = reader(name)?.takeIf { it.isNotBlank() }
}

/** The SDK's logging, on `java.util.logging` so it needs no dependency. */
internal object PtnLog {
    private val logger: Logger = Logger.getLogger("dev.polimo.prompton")
    private val saidOnce = ConcurrentHashMap.newKeySet<String>()
    private val lastSaid = ConcurrentHashMap<String, Long>()

    fun info(message: String) {
        logger.log(Level.INFO, message)
    }

    fun warn(message: String) {
        logger.log(Level.WARNING, message)
    }

    fun warn(
        message: String,
        error: Throwable,
    ) {
        logger.log(Level.WARNING, message, error)
    }

    /** Says something at most once for the life of the process. */
    fun once(
        key: String,
        message: String,
    ) {
        if (saidOnce.add(key)) logger.log(Level.INFO, message)
    }

    /** Says something at most once per [intervalMillis]; noisy failure modes stay readable. */
    fun throttled(
        key: String,
        intervalMillis: Long,
        message: () -> String,
    ) {
        val now = System.currentTimeMillis()
        val previous = lastSaid[key]
        if (previous == null || now - previous >= intervalMillis) {
            lastSaid[key] = now
            logger.log(Level.WARNING, message())
        }
    }

    internal fun resetForTests() {
        saidOnce.clear()
        lastSaid.clear()
    }
}

/** Time, behind a seam so tests can fix `started_at` and `latency_ms`. */
internal interface PromptOnClock {
    fun now(): Instant

    fun nanoTime(): Long

    companion object {
        val SYSTEM: PromptOnClock =
            object : PromptOnClock {
                override fun now(): Instant = Instant.now()

                override fun nanoTime(): Long = System.nanoTime()
            }
    }
}
