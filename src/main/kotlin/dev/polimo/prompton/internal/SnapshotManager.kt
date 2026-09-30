package dev.polimo.prompton.internal

import dev.polimo.prompton.HttpRequest
import dev.polimo.prompton.HttpResponse
import dev.polimo.prompton.HttpTransport
import dev.polimo.prompton.PromptOnConfig
import dev.polimo.prompton.PromptOnMode
import dev.polimo.prompton.UseCaseDocument
import dev.polimo.prompton.UseCaseDocumentUnavailableException
import dev.polimo.prompton.UseCaseSource
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import java.time.Duration as JavaDuration

/** Demand-driven, per-prompt configuration store. */
internal class SnapshotManager(
    private val config: PromptOnConfig,
    private val transport: HttpTransport?,
    private val clock: PromptOnClock,
) : AutoCloseable {
    private data class FetchOutcome(
        val ok: Boolean,
        val entry: SnapshotEntry?,
        val reason: String?,
        val deadline: Instant,
    )

    private class KeyState {
        var entry: SnapshotEntry? = null
        var lastAttemptAt: Instant? = null
        var inFlight: CompletableFuture<FetchOutcome>? = null
        var inFlightDeadline: Instant? = null
    }

    private val states = ConcurrentHashMap<String, KeyState>()
    private val localDocument = AtomicReference<SnapshotEntry?>()
    private val lastServed = AtomicReference<SnapshotEntry?>()
    private val remoteEnabled: Boolean =
        config.mode == PromptOnMode.LIVE && transport != null && !config.apiKey.isNullOrBlank()
    private val diskLock = Any()
    private val executor =
        Executors.newCachedThreadPool(ConfigFetchThreadFactory())

    fun start() {
        if (config.mode != PromptOnMode.TEST) loadLocalTiers()
        if (!remoteEnabled && config.mode == PromptOnMode.LIVE) {
            PtnLog.once(
                "no-api-key",
                "[PromptOn] no API key configured — resolving from ${describeSource()} only, " +
                    "nothing will be fetched or sent",
            )
        }
    }

    fun entry(key: String): SnapshotEntry {
        val state = states.computeIfAbsent(key) { KeyState() }
        val local = localFor(key)
        val future: CompletableFuture<FetchOutcome>?
        val deadline: Instant?
        val now = clock.now()
        synchronized(state) {
            if (state.entry == null && local != null) {
                state.entry = local
                lastServed.set(local)
            }
            state.entry?.let { entry ->
                if (isFresh(entry, now)) {
                    lastServed.set(entry)
                    return entry
                }
            }
            if (!remoteEnabled) return cachedOrThrow(state.entry)
            val existingDeadline = state.inFlightDeadline
            if (state.inFlight != null && existingDeadline != null && !now.isBefore(existingDeadline)) {
                state.inFlight = null
                state.inFlightDeadline = null
            }
            if (state.inFlight == null && attemptDue(state.lastAttemptAt, now)) {
                startFetchLocked(key, state, now)
            }
            future = state.inFlight
            deadline = state.inFlightDeadline
            if (future == null || deadline == null) return cachedOrThrow(state.entry)
        }

        awaitSharedFetch(future!!, deadline!!)
        synchronized(state) {
            return cachedOrThrow(state.entry)
        }
    }

    fun currentOrNull(): SnapshotEntry? = lastServed.get()

    fun currentFor(key: String): SnapshotEntry? {
        states[key]?.let { state ->
            synchronized(state) {
                state.entry?.let { return it }
            }
        }
        return localFor(key)
    }

    /** Bulk refresh is intentionally disabled in the demand-driven runtime. */
    @Suppress("UNUSED_PARAMETER")
    fun refreshNow(force: Boolean = false): Boolean = currentOrNull() != null

    fun putDocument(
        document: UseCaseDocument,
        source: UseCaseSource,
        etag: String? = null,
    ) {
        val now = clock.now()
        val entry =
            SnapshotEntry(
                document = document,
                etag = etag,
                lastModified = null,
                source = source,
                fetchedAt = now,
                validatedAt = now,
                staleSince = if (source == UseCaseSource.REMOTE) null else now,
            )
        localDocument.set(entry)
        lastServed.set(entry)
        states.clear()
    }

    fun export(path: Path) {
        val entry = lastServed.get() ?: throw UseCaseDocumentUnavailableException(config.environment)
        SnapshotFiles.write(path, entry.document.toJson(), metaOf(entry))
    }

    override fun close() {
        executor.shutdownNow()
    }

    private fun cachedOrThrow(entry: SnapshotEntry?): SnapshotEntry {
        entry?.let {
            lastServed.set(it)
            return it
        }
        throw UseCaseDocumentUnavailableException(config.environment)
    }

    private fun isFresh(
        entry: SnapshotEntry,
        now: Instant,
    ): Boolean =
        entry.source == UseCaseSource.REMOTE &&
            entry.staleSince == null &&
            JavaDuration.between(entry.validatedAt, now) < CONFIG_TTL.toJavaDuration()

    private fun attemptDue(
        lastAttemptAt: Instant?,
        now: Instant,
    ): Boolean =
        lastAttemptAt == null ||
            JavaDuration.between(lastAttemptAt, now) >= CONFIG_TTL.toJavaDuration()

    private fun startFetchLocked(
        key: String,
        state: KeyState,
        startedAt: Instant,
    ) {
        val deadline = startedAt.plusMillis(fetchTimeout().inWholeMilliseconds)
        val previous = state.entry
        state.lastAttemptAt = startedAt
        val future = CompletableFuture<FetchOutcome>()
        state.inFlight = future
        state.inFlightDeadline = deadline
        executor.execute {
            val outcome =
                try {
                    fetch(key, previous, deadline)
                } catch (t: Throwable) {
                    FetchOutcome(false, null, t.message ?: "unknown fetch failure", deadline)
                }
            finishFetch(key, state, future, outcome, null)
            future.complete(outcome)
        }
    }

    private fun finishFetch(
        key: String,
        state: KeyState,
        future: CompletableFuture<FetchOutcome>,
        outcome: FetchOutcome?,
        error: Throwable?,
    ) {
        val completedAt = clock.now()
        synchronized(state) {
            if (state.inFlight === future) {
                state.inFlight = null
                state.inFlightDeadline = null
            }
            val resolved =
                outcome ?: FetchOutcome(
                    ok = false,
                    entry = null,
                    reason = error?.message ?: "unknown fetch failure",
                    deadline = completedAt,
                )
            if (completedAt.isAfter(resolved.deadline)) {
                markStale(state, completedAt)
                return
            }
            val entry = resolved.entry
            if (resolved.ok && entry != null) {
                state.entry = entry
                lastServed.set(entry)
                config.resolvedDiskCachePath?.let { persist(key, entry, it) }
                return
            }
            markStale(state, completedAt)
            PtnLog.throttled("prompt-document-fetch-$key", 60_000) {
                "[PromptOn] prompt '$key' config fetch failed: ${resolved.reason} — " +
                    "serving the cached value until the next demand attempt is due"
            }
        }
    }

    private fun awaitSharedFetch(
        future: CompletableFuture<FetchOutcome>,
        deadline: Instant,
    ) {
        val waitMillis = JavaDuration.between(clock.now(), deadline).toMillis()
        if (waitMillis <= 0) return
        try {
            future.get(waitMillis, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: Exception) {
        }
    }

    private fun fetch(
        key: String,
        previous: SnapshotEntry?,
        deadline: Instant,
    ): FetchOutcome {
        val response =
            try {
                transport!!.execute(
                    HttpRequest(
                        method = "GET",
                        url = promptUrl(key),
                        headers = requestHeaders(previous?.etag),
                        timeout = fetchTimeout(),
                    ),
                )
            } catch (e: Exception) {
                return FetchOutcome(false, null, "transport: ${e.javaClass.simpleName}: ${e.message}", deadline)
            }

        if (clock.now().isAfter(deadline)) return FetchOutcome(false, null, "deadline exceeded", deadline)
        return when (response.status) {
            200 -> handleOk(key, response, deadline)
            304 -> {
                if (previous == null) {
                    FetchOutcome(false, null, "304 without a cached value", deadline)
                } else {
                    FetchOutcome(
                        true,
                        previous.copy(source = UseCaseSource.REMOTE, validatedAt = clock.now(), staleSince = null),
                        null,
                        deadline,
                    )
                }
            }

            else -> FetchOutcome(false, null, "HTTP ${response.status}: ${errorMessageOf(response)}", deadline)
        }
    }

    private fun handleOk(
        key: String,
        response: HttpResponse,
        deadline: Instant,
    ): FetchOutcome {
        val document =
            try {
                UseCaseDocument.parse(response.body)
            } catch (e: RuntimeException) {
                return FetchOutcome(false, null, "undecodable prompt document: ${e.message}", deadline)
            }
        val mismatch = mismatch(document, key)
        if (mismatch != null) return FetchOutcome(false, null, mismatch, deadline)
        document.warnings.forEach { PtnLog.warn("[PromptOn] prompt document decoded with a warning: $it") }

        val now = clock.now()
        return FetchOutcome(
            true,
            SnapshotEntry(
                document = document,
                etag = response.header("etag"),
                lastModified = response.header("last-modified"),
                source = UseCaseSource.REMOTE,
                fetchedAt = now,
                validatedAt = now,
                staleSince = null,
            ),
            null,
            deadline,
        )
    }

    private fun markStale(
        state: KeyState,
        now: Instant,
    ) {
        state.entry = state.entry?.let { if (it.staleSince == null) it.copy(staleSince = now) else it }
    }

    private fun fetchTimeout(): Duration = minOf(config.requestTimeout, 1.seconds)

    private fun promptUrl(key: String): String =
        "${config.baseUrl}/prompts/${URLEncoder.encode(key, StandardCharsets.UTF_8).replace("+", "%20")}" +
            "?environment=${URLEncoder.encode(config.environment, StandardCharsets.UTF_8)}"

    private fun requestHeaders(etag: String?): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        headers["accept"] = "application/json"
        headers["user-agent"] = config.userAgent
        config.apiKey?.let { headers["authorization"] = "Bearer $it" }
        etag?.let { headers["if-none-match"] = it }
        return headers
    }

    private fun localFor(key: String): SnapshotEntry? =
        localDocument.get()?.takeIf { it.document.useCases.containsKey(key) }

    private fun loadLocalTiers(): Boolean {
        val diskLoaded = config.resolvedDiskCachePath?.let { loadDiskEntries(it) } ?: false
        if (!diskLoaded) {
            config.resolvedDiskCachePath?.let { path ->
                SnapshotFiles.read(path, UseCaseSource.DISK, config.environment, config.project)?.let { entry ->
                    localDocument.set(entry)
                    lastServed.set(entry)
                    PtnLog.info("[PromptOn] loaded the prompt document from disk ($path), etag=${entry.etag}")
                    return true
                }
            }
        }
        config.bundlePath?.let { path ->
            SnapshotFiles.read(path, UseCaseSource.BUNDLE, config.environment, config.project)?.let { entry ->
                localDocument.set(entry)
                lastServed.compareAndSet(null, entry)
                PtnLog.info("[PromptOn] loaded the prompt document from bundle ($path), etag=${entry.etag}")
            }
        }
        return diskLoaded || localDocument.get() != null
    }

    private fun loadDiskEntries(path: Path): Boolean {
        val root = readJsonObject(path) ?: return false
        val entries = Ptn.asObject(root["entries"]) ?: return false
        var loaded = false
        for ((key, value) in entries) {
            val entryObject = Ptn.asObject(value) ?: continue
            val body = Ptn.asString(entryObject["body"]) ?: continue
            val meta = Ptn.toNativeMap(entryObject["meta"])
            val document =
                try {
                    UseCaseDocument.parse(body)
                } catch (_: RuntimeException) {
                    continue
                }
            val mismatch = mismatch(document, key)
            if (mismatch != null) {
                PtnLog.warn("[PromptOn] ignoring the disk prompt document for '$key' at $path: $mismatch")
                continue
            }
            val fetchedAt = Iso8601.parseOrNull(meta["fetched_at"] as? String) ?: Instant.EPOCH
            val snapshot =
                SnapshotEntry(
                    document = document,
                    etag = meta["etag"] as? String,
                    lastModified = meta["last_modified"] as? String,
                    source = UseCaseSource.DISK,
                    fetchedAt = fetchedAt,
                    validatedAt = fetchedAt,
                    staleSince = clock.now(),
                )
            val state = states.computeIfAbsent(key) { KeyState() }
            synchronized(state) { state.entry = snapshot }
            lastServed.compareAndSet(null, snapshot)
            loaded = true
        }
        if (loaded) PtnLog.info("[PromptOn] loaded per-prompt disk cache entries from $path")
        return loaded
    }

    private fun mismatch(
        document: UseCaseDocument,
        requestedKey: String?,
    ): String? {
        if (document.environment != null && document.environment != config.environment) {
            return "the server answered with environment '${document.environment}' " +
                "but this process reads '${config.environment}'"
        }
        if (config.project != null && document.project != null && document.project != config.project) {
            return "the server answered with project '${document.project}' but this process reads '${config.project}'"
        }
        if (requestedKey != null && !document.useCases.containsKey(requestedKey)) {
            return "the server answered without requested prompt '$requestedKey'"
        }
        return null
    }

    private fun persist(
        key: String,
        entry: SnapshotEntry,
        path: Path,
    ) {
        synchronized(diskLock) {
            val root = readJsonObject(path)?.let { Ptn.toNativeMap(it).toMutableMap() } ?: mutableMapOf()

            @Suppress("UNCHECKED_CAST")
            val entries = (root["entries"] as? Map<String, Any?>)?.toMutableMap() ?: mutableMapOf()
            root["prompton_sdk_cache_version"] = 1
            root["entries"] = entries
            entries[key] = mapOf("body" to entry.document.toJson(), "meta" to metaOf(entry))
            SnapshotFiles.write(
                path,
                Ptn.canonicalJson(Ptn.toElement(root)),
                mapOf(
                    "environment" to config.environment,
                    "project" to config.project,
                    "fetched_at" to Iso8601.format(entry.fetchedAt),
                ),
            )
        }
    }

    private fun readJsonObject(path: Path): kotlinx.serialization.json.JsonObject? =
        try {
            if (!Files.isRegularFile(path)) {
                null
            } else {
                Ptn.parseObject(String(Files.readAllBytes(path), StandardCharsets.UTF_8))
            }
        } catch (_: RuntimeException) {
            null
        } catch (_: java.io.IOException) {
            null
        }

    private fun metaOf(entry: SnapshotEntry): Map<String, String?> =
        mapOf(
            "etag" to entry.etag,
            "last_modified" to entry.lastModified,
            "environment" to entry.document.environment,
            "project" to entry.document.project,
            "fetched_at" to Iso8601.format(entry.fetchedAt),
        )

    private fun describeSource(): String = localDocument.get()?.source?.wire ?: "no cached"

    companion object {
        private val CONFIG_TTL: Duration = 10.seconds
        val BACKOFF_CAP: Duration = 5.minutes

        /** Exponential backoff x2 from [base] (at least a second), capped at [BACKOFF_CAP]. */
        fun backoffFrom(
            base: Duration,
            attempt: Int,
        ): Duration {
            val floor = base.inWholeMilliseconds.coerceAtLeast(1_000)
            val exponent = (attempt - 1).coerceIn(0, 20)
            return minOf(floor shl exponent, BACKOFF_CAP.inWholeMilliseconds).milliseconds
        }

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

    private class ConfigFetchThreadFactory : ThreadFactory {
        private val count = AtomicLong()

        override fun newThread(runnable: Runnable): Thread =
            Thread(runnable, "prompton-config-fetch-${count.incrementAndGet()}").apply { isDaemon = true }
    }
}
