package dev.polimo.prompton.internal

import dev.polimo.prompton.HttpRequest
import dev.polimo.prompton.HttpResponse
import dev.polimo.prompton.HttpTransport
import dev.polimo.prompton.PromptOnConfig
import dev.polimo.prompton.PromptOnMode
import dev.polimo.prompton.ResolutionSource
import dev.polimo.prompton.SnapshotDocument
import dev.polimo.prompton.SnapshotUnavailableException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import java.time.Duration as JavaDuration

/**
 * The three snapshot tiers and the rules that keep a generation running when PromptOn is not.
 *
 * Memory is what every resolve reads. Past the cache TTL a background revalidation refreshes it with
 * `If-None-Match`; while that is in flight, and if it fails, the previous document keeps serving.
 * On start-up the disk cache and then the bundle fill memory before the first fetch returns, and a
 * document for another environment or project is never used.
 */
internal class SnapshotManager(
    private val config: PromptOnConfig,
    private val transport: HttpTransport?,
    private val clock: PromptOnClock,
) : AutoCloseable {
    private val current = AtomicReference<SnapshotEntry?>()
    private val refreshing = AtomicBoolean(false)
    private val nextAttemptAt = AtomicReference(Instant.EPOCH)
    private val failures = AtomicInteger(0)
    private val fetchLock = ReentrantLock()
    private val startupFetch = AtomicReference<java.util.concurrent.Future<*>?>()

    private val remoteEnabled: Boolean =
        config.mode == PromptOnMode.LIVE && transport != null && !config.apiKey.isNullOrBlank()

    private val scheduler: ScheduledExecutorService? =
        if (config.mode == PromptOnMode.TEST) {
            null
        } else {
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "prompton-snapshot").apply { isDaemon = true }
            }
        }

    fun start() {
        if (config.mode != PromptOnMode.TEST) loadLocalTiers()
        if (!remoteEnabled) {
            if (config.mode == PromptOnMode.LIVE) {
                PtnLog.once(
                    "no-api-key",
                    "[PromptOn] no API key configured — running from ${describeSource()} only, " +
                        "nothing will be fetched or sent",
                )
            }
            return
        }
        startupFetch.set(scheduler?.submit { runCatching { fetchOnce(config.startupFetchTimeout) } })
        if (config.pollingEnabled) {
            val period = config.cacheTtl.inWholeMilliseconds.coerceAtLeast(1_000)
            scheduler?.scheduleWithFixedDelay(
                { runCatching { maybeRefresh() } },
                period,
                period,
                TimeUnit.MILLISECONDS,
            )
        }
    }

    /**
     * The document every resolve reads.
     *
     * With a document in memory this never blocks: past the TTL it starts a background
     * revalidation and returns the document it has. With no document at all — a cold start with an
     * empty disk cache and no bundle — it waits for the start-up fetch, which carries its own short
     * timeout, and fails with [SnapshotUnavailableException] if that found nothing either.
     */
    fun entry(): SnapshotEntry {
        current.get()?.let { entry ->
            maybeRefresh()
            return entry
        }
        awaitStartupFetch()
        current.get()?.let { return it }
        maybeRefresh()
        throw SnapshotUnavailableException(config.environment)
    }

    private fun awaitStartupFetch() {
        val future = startupFetch.getAndSet(null) ?: return
        try {
            future.get(config.startupFetchTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            future.cancel(true)
        }
    }

    fun currentOrNull(): SnapshotEntry? = current.get()

    /** Stale-while-revalidate: past the TTL the next call kicks off a background refresh and returns. */
    fun maybeRefresh() {
        if (!remoteEnabled) return
        val entry = current.get()
        val now = clock.now()
        if (entry != null && JavaDuration.between(entry.validatedAt, now) < config.cacheTtl.toJavaDuration()) return
        if (now.isBefore(nextAttemptAt.get())) return
        if (!refreshing.compareAndSet(false, true)) return
        val submitted =
            scheduler?.submit {
                try {
                    fetchOnce(config.requestTimeout)
                } finally {
                    refreshing.set(false)
                }
            }
        if (submitted == null) refreshing.set(false)
    }

    /** A blocking "fetch once now", for scripts and for [dev.polimo.prompton.PromptOn.refreshBlocking]. */
    fun refreshNow(): Boolean {
        startupFetch.set(null)
        if (config.mode == PromptOnMode.OFFLINE) return loadLocalTiers()
        if (!remoteEnabled) return false
        return fetchOnce(config.requestTimeout)
    }

    fun putDocument(
        document: SnapshotDocument,
        source: ResolutionSource,
        etag: String? = null,
    ) {
        val now = clock.now()
        current.set(
            SnapshotEntry(
                document = document,
                etag = etag,
                lastModified = null,
                source = source,
                fetchedAt = now,
                validatedAt = now,
                staleSince = if (source == ResolutionSource.REMOTE) null else now,
            ),
        )
    }

    fun export(path: Path) {
        val entry = current.get() ?: throw SnapshotUnavailableException(config.environment)
        SnapshotFiles.write(path, entry.document.toJson(), metaOf(entry))
    }

    override fun close() {
        val scheduler = this.scheduler ?: return
        scheduler.shutdown()
        // A fetch in flight may be halfway through writing the disk cache; interrupting it there
        // leaves a temp file behind, so give it a moment to finish before forcing the issue.
        if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) scheduler.shutdownNow()
    }

    // -----------------------------------------------------------------------

    private fun loadLocalTiers(): Boolean {
        val candidates =
            listOfNotNull(
                config.resolvedDiskCachePath?.let { it to ResolutionSource.DISK },
                config.bundlePath?.let { it to ResolutionSource.BUNDLE },
            )
        for ((path, source) in candidates) {
            val entry = SnapshotFiles.read(path, source, config.environment, config.project)
            if (entry != null) {
                current.set(entry)
                PtnLog.info("[PromptOn] loaded the snapshot from ${source.wire} ($path), etag=${entry.etag}")
                return true
            }
        }
        return false
    }

    private fun fetchOnce(timeout: Duration): Boolean {
        if (!remoteEnabled) return false
        fetchLock.withLock {
            val previous = current.get()
            val response =
                try {
                    transport!!.execute(
                        HttpRequest(
                            method = "GET",
                            url = snapshotUrl(),
                            headers = requestHeaders(previous?.etag),
                            timeout = timeout,
                        ),
                    )
                } catch (e: Exception) {
                    recordFailure("transport: ${e.javaClass.simpleName}: ${e.message}", null)
                    return false
                }

            return when {
                response.status == 200 -> handleOk(response)
                response.status == 304 -> handleNotModified(previous)
                response.status == 429 -> {
                    recordFailure("rate limited (429)", retryAfterOf(response))
                    false
                }

                response.status in 400..499 -> {
                    recordFailure("HTTP ${response.status}: ${errorMessageOf(response)}", null)
                    false
                }

                else -> {
                    recordFailure("HTTP ${response.status}", retryAfterOf(response))
                    false
                }
            }
        }
    }

    private fun handleOk(response: HttpResponse): Boolean {
        val document =
            try {
                SnapshotDocument.parse(response.body)
            } catch (e: RuntimeException) {
                recordFailure("undecodable snapshot: ${e.message}", null)
                return false
            }
        if (document.environment != config.environment) {
            recordFailure(
                "the server answered with environment '${document.environment}' " +
                    "but this process reads '${config.environment}'",
                null,
            )
            return false
        }
        if (config.project != null && document.project != null && document.project != config.project) {
            recordFailure(
                "the server answered with project '${document.project}' but this process reads '${config.project}'",
                null,
            )
            return false
        }
        document.warnings.forEach { PtnLog.warn("[PromptOn] snapshot decoded with a warning: $it") }

        val now = clock.now()
        val entry =
            SnapshotEntry(
                document = document,
                etag = response.header("etag"),
                lastModified = response.header("last-modified"),
                source = ResolutionSource.REMOTE,
                fetchedAt = now,
                validatedAt = now,
                staleSince = null,
            )
        current.set(entry)
        failures.set(0)
        nextAttemptAt.set(now)
        config.resolvedDiskCachePath?.let { SnapshotFiles.write(it, response.body, metaOf(entry)) }
        return true
    }

    private fun handleNotModified(previous: SnapshotEntry?): Boolean {
        val now = clock.now()
        failures.set(0)
        nextAttemptAt.set(now)
        if (previous == null) return false
        current.set(
            previous.copy(source = ResolutionSource.REMOTE, validatedAt = now, staleSince = null),
        )
        return true
    }

    private fun recordFailure(
        reason: String,
        retryAfter: Duration?,
    ) {
        val attempt = failures.incrementAndGet()
        val delay = retryAfter ?: backoff(attempt)
        val now = clock.now()
        nextAttemptAt.set(now.plusMillis(delay.inWholeMilliseconds))
        current.getAndUpdate { entry -> entry?.let { if (it.staleSince == null) it.copy(staleSince = now) else it } }
        PtnLog.throttled("snapshot-fetch", 60_000) {
            "[PromptOn] snapshot refresh failed (attempt $attempt): $reason — " +
                "serving the ${describeSource()} document, next try in ${delay.inWholeSeconds}s"
        }
    }

    private fun backoff(attempt: Int): Duration {
        val base = config.cacheTtl.inWholeMilliseconds.coerceAtLeast(1_000)
        val exponent = (attempt - 1).coerceIn(0, 20)
        val millis = base shl exponent
        return minOf(millis, BACKOFF_CAP.inWholeMilliseconds).milliseconds
    }

    private fun snapshotUrl(): String =
        "${config.baseUrl}/snapshot?environment=" +
            URLEncoder.encode(config.environment, StandardCharsets.UTF_8)

    private fun requestHeaders(etag: String?): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        headers["accept"] = "application/json"
        headers["user-agent"] = config.userAgent
        config.apiKey?.let { headers["authorization"] = "Bearer $it" }
        etag?.let { headers["if-none-match"] = it }
        return headers
    }

    private fun metaOf(entry: SnapshotEntry): Map<String, String?> =
        mapOf(
            "etag" to entry.etag,
            "last_modified" to entry.lastModified,
            "environment" to entry.document.environment,
            "project" to entry.document.project,
            "fetched_at" to Iso8601.format(entry.fetchedAt),
        )

    private fun describeSource(): String = current.get()?.source?.wire ?: "no cached"

    companion object {
        val BACKOFF_CAP: Duration = 5.minutes

        /** `Retry-After` in seconds or as an HTTP date, else `error.details.retry_after`. */
        fun retryAfterOf(response: HttpResponse): Duration? {
            val header = response.header("retry-after")?.trim()
            if (!header.isNullOrEmpty()) {
                header.toLongOrNull()?.let { return it.seconds }
                HttpDate.parseOrNull(header)?.let { instant ->
                    return JavaDuration
                        .between(Instant.now(), instant)
                        .toMillis()
                        .coerceAtLeast(0)
                        .milliseconds
                }
            }
            val details = errorDetails(response) ?: return null
            return (Ptn.asInt(details["retry_after"]) ?: return null).seconds
        }

        fun errorMessageOf(response: HttpResponse): String =
            try {
                val error = Ptn.asObject(Ptn.parseObject(response.body)["error"])
                Ptn.asString(error?.get("message")) ?: response.body.take(200)
            } catch (_: RuntimeException) {
                response.body.take(200)
            }

        private fun errorDetails(response: HttpResponse) =
            try {
                Ptn.asObject(Ptn.asObject(Ptn.parseObject(response.body)["error"])?.get("details"))
            } catch (_: RuntimeException) {
                null
            }
    }
}
