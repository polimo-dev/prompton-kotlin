package dev.polimo.prompton.internal

import dev.polimo.prompton.FlushResult
import dev.polimo.prompton.LogStats
import dev.polimo.prompton.PromptOnConfig
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** What one `POST /generations` call did. */
internal sealed interface BatchOutcome {
    /** `202`: the server took the batch, possibly rejecting individual records. */
    data class Accepted(
        val accepted: Int,
        val duplicates: Int,
        val rejected: List<JsonObject>,
    ) : BatchOutcome

    /** `429` or any `5xx`: resend the same batch with the same ids. */
    data class Retry(
        val after: Duration?,
        val reason: String,
    ) : BatchOutcome

    /** `413`: the batch is too big, split it in half. */
    data object TooLarge : BatchOutcome

    /** Any other `4xx`: the records are wrong, resending cannot fix them. */
    data class Rejected(
        val status: Int,
        val message: String,
    ) : BatchOutcome
}

/**
 * The monitoring-log queue.
 *
 * Records are enqueued without blocking the caller and leave in batches of at most
 * [LogOptions.maxBatchSize] records, one batch per environment. A batch that hits `429` or a `5xx`
 * is retried with the same ids — the id is the idempotency key, so a duplicate is counted, never
 * stored twice — a `413` batch is split in half, and any other `4xx` is dropped and counted, because
 * resending records the server called invalid only wastes the buffer.
 */
internal class LogBuffer(
    private val config: PromptOnConfig,
    private val clock: PromptOnClock,
    private val send: (environment: String, records: List<JsonObject>) -> BatchOutcome,
) : AutoCloseable {
    private data class Entry(
        val record: JsonObject,
        val bytes: Int,
        val environment: String,
    )

    private data class Batch(
        val entries: List<Entry>,
        val environment: String,
        val attempts: Int,
    ) {
        val bytes: Int get() = entries.sumOf { it.bytes }
    }

    private val lock = ReentrantLock()
    private val queue = ArrayDeque<Entry>()
    private val retryQueue = ArrayDeque<Batch>()
    private var queuedBytes = 0L
    private var pausedUntil: Instant = Instant.EPOCH
    private var timer: ScheduledFuture<*>? = null

    private val accepted = AtomicLong()
    private val duplicates = AtomicLong()
    private val rejected = AtomicLong()
    private val dropped = AtomicLong()
    private val batchesSent = AtomicLong()

    private val worker: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "prompton-logs").apply { isDaemon = true }
        }

    fun enqueue(
        record: JsonObject,
        environment: String,
    ) {
        val bytes = Ptn.jsonSize(record)
        if (bytes > config.log.maxBatchBytes) {
            dropped.incrementAndGet()
            PtnLog.warn(
                "[PromptOn] dropping a monitoring log of $bytes bytes: one record cannot exceed " +
                    "the ${config.log.maxBatchBytes}-byte request limit",
            )
            return
        }

        var immediate = false
        lock.withLock {
            queue.addLast(Entry(record, bytes, environment))
            queuedBytes += bytes
            var evicted = 0
            while (queue.size > config.log.maxBufferSize) {
                val oldest = queue.removeFirst()
                queuedBytes -= oldest.bytes
                evicted += 1
            }
            if (evicted > 0) {
                dropped.addAndGet(evicted.toLong())
                PtnLog.throttled("log-buffer-full", 60_000) {
                    "[PromptOn] the monitoring-log buffer is full — dropped $evicted of the oldest records"
                }
            }
            immediate = queue.size >= config.log.flushSize || queuedBytes >= config.log.flushBytes
        }

        if (immediate) {
            worker.submit { runCatching { drain(Long.MAX_VALUE) } }
        } else {
            scheduleTimer()
        }
    }

    /** Sends what is queued and waits for the result. */
    fun flush(timeout: Duration = 10.seconds): FlushResult {
        val future = worker.submit<FlushResult> { drain(timeout.inWholeMilliseconds) }
        return try {
            future.get(timeout.inWholeMilliseconds + 1_000, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            PtnLog.warn("[PromptOn] flush did not finish in time: ${e.message}")
            FlushResult(0, 0, 0, 0, pending())
        }
    }

    fun stats(): LogStats =
        LogStats(
            queued = pending(),
            accepted = accepted.get(),
            duplicates = duplicates.get(),
            rejected = rejected.get(),
            dropped = dropped.get(),
            batchesSent = batchesSent.get(),
        )

    fun pending(): Int = lock.withLock { queue.size + retryQueue.sumOf { it.entries.size } }

    override fun close() {
        runCatching { flush(5.seconds) }
        worker.shutdownNow()
    }

    // -----------------------------------------------------------------------

    private fun scheduleTimer() {
        lock.withLock {
            if (timer?.isDone == false) return
            timer =
                worker.schedule(
                    { runCatching { drain(Long.MAX_VALUE) } },
                    config.log.flushInterval.inWholeMilliseconds,
                    TimeUnit.MILLISECONDS,
                )
        }
    }

    private fun drain(budgetMillis: Long): FlushResult {
        val deadline = clock.now().plusMillis(budgetMillis.coerceAtMost(ONE_HOUR_MILLIS))
        var totalAccepted = 0L
        var totalDuplicates = 0L
        var totalRejected = 0L
        var totalDropped = 0L

        while (true) {
            val now = clock.now()
            if (now.isAfter(deadline)) break
            if (now.isBefore(pausedUntilSnapshot())) break
            val batch = nextBatch() ?: break

            val outcome =
                try {
                    batchesSent.incrementAndGet()
                    send(batch.environment, batch.entries.map { it.record })
                } catch (e: Exception) {
                    BatchOutcome.Retry(null, "transport: ${e.javaClass.simpleName}: ${e.message}")
                }

            when (outcome) {
                is BatchOutcome.Accepted -> {
                    totalAccepted += outcome.accepted
                    totalDuplicates += outcome.duplicates
                    totalRejected += outcome.rejected.size
                    accepted.addAndGet(outcome.accepted.toLong())
                    duplicates.addAndGet(outcome.duplicates.toLong())
                    rejected.addAndGet(outcome.rejected.size.toLong())
                    if (outcome.rejected.isNotEmpty()) {
                        PtnLog.warn(
                            "[PromptOn] the server rejected ${outcome.rejected.size} monitoring log(s): " +
                                outcome.rejected.take(3).joinToString { Ptn.canonicalJson(it) },
                        )
                    }
                    resetPause()
                }

                is BatchOutcome.Retry -> {
                    val attempts = batch.attempts + 1
                    if (attempts >= config.log.maxAttempts) {
                        totalDropped += batch.entries.size
                        dropped.addAndGet(batch.entries.size.toLong())
                        PtnLog.warn(
                            "[PromptOn] giving up on ${batch.entries.size} monitoring log(s) after " +
                                "$attempts attempts (${outcome.reason})",
                        )
                    } else {
                        val delay = outcome.after ?: backoff(attempts)
                        lock.withLock {
                            retryQueue.addFirst(batch.copy(attempts = attempts))
                            pausedUntil = clock.now().plusMillis(delay.inWholeMilliseconds)
                        }
                        PtnLog.throttled("log-retry", 30_000) {
                            "[PromptOn] retrying ${batch.entries.size} monitoring log(s) in " +
                                "${delay.inWholeSeconds}s (${outcome.reason})"
                        }
                        scheduleRetry(delay)
                    }
                    break
                }

                BatchOutcome.TooLarge -> {
                    if (batch.entries.size == 1) {
                        totalDropped += 1
                        dropped.incrementAndGet()
                        PtnLog.warn(
                            "[PromptOn] dropping one ${batch.bytes}-byte monitoring log the server " +
                                "answered 413 for",
                        )
                    } else {
                        val half = batch.entries.size / 2
                        lock.withLock {
                            retryQueue.addFirst(batch.copy(entries = batch.entries.drop(half)))
                            retryQueue.addFirst(batch.copy(entries = batch.entries.take(half)))
                        }
                        PtnLog.warn(
                            "[PromptOn] splitting a ${batch.entries.size}-record monitoring-log batch " +
                                "the server answered 413 for",
                        )
                    }
                }

                is BatchOutcome.Rejected -> {
                    totalDropped += batch.entries.size
                    dropped.addAndGet(batch.entries.size.toLong())
                    PtnLog.throttled("log-4xx", 60_000) {
                        "[PromptOn] dropping ${batch.entries.size} monitoring log(s): the server answered " +
                            "${outcome.status} ${outcome.message}"
                    }
                }
            }
        }

        if (pending() > 0) scheduleTimer()
        return FlushResult(totalAccepted, totalDuplicates, totalRejected, totalDropped, pending())
    }

    private fun pausedUntilSnapshot(): Instant = lock.withLock { pausedUntil }

    private fun resetPause() {
        lock.withLock { pausedUntil = Instant.EPOCH }
    }

    private fun scheduleRetry(delay: Duration) {
        worker.schedule(
            { runCatching { drain(Long.MAX_VALUE) } },
            delay.inWholeMilliseconds.coerceAtLeast(1),
            TimeUnit.MILLISECONDS,
        )
    }

    private fun nextBatch(): Batch? =
        lock.withLock {
            retryQueue.removeFirstOrNull()?.let { return@withLock it }
            val first = queue.firstOrNull() ?: return@withLock null
            val entries = mutableListOf<Entry>()
            var bytes = 0
            while (queue.isNotEmpty() && entries.size < config.log.maxBatchSize) {
                val head = queue.first()
                if (head.environment != first.environment) break
                if (entries.isNotEmpty() && bytes + head.bytes > config.log.maxBatchBytes) break
                queue.removeFirst()
                queuedBytes -= head.bytes
                bytes += head.bytes
                entries += head
            }
            Batch(entries, first.environment, 0)
        }

    private companion object {
        const val ONE_HOUR_MILLIS = 3_600_000L
    }

    private fun backoff(attempts: Int): Duration {
        val millis = 1_000L shl (attempts - 1).coerceIn(0, 20)
        return minOf(millis, 5.minutes.inWholeMilliseconds).milliseconds
    }
}
