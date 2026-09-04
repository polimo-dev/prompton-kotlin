package dev.polimo.prompton

import dev.polimo.prompton.internal.GenerationsClient
import dev.polimo.prompton.internal.LogBuffer
import dev.polimo.prompton.internal.Payload
import dev.polimo.prompton.internal.PayloadOptions
import dev.polimo.prompton.internal.PromptOnClock
import dev.polimo.prompton.internal.PromptOnLifecycle
import dev.polimo.prompton.internal.PromptOnResources
import dev.polimo.prompton.internal.Ptn
import dev.polimo.prompton.internal.PtnLog
import dev.polimo.prompton.internal.SnapshotManager
import dev.polimo.prompton.internal.wireHeaders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.nio.file.Path
import java.time.Instant
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/** What the snapshot store is currently serving. */
public data class SnapshotInfo(
    val etag: String?,
    val lastModified: String?,
    val source: ResolutionSource?,
    val project: String?,
    val environment: String?,
    val fetchedAt: Instant?,
    val stale: Boolean,
    val ageSeconds: Long?,
    val useCases: Int,
)

/** What one [PromptOn.flush] achieved. */
public data class FlushResult(
    val accepted: Long,
    val duplicates: Long,
    val rejected: Long,
    val dropped: Long,
    val remaining: Int,
)

/** Counters for the monitoring-log queue. */
public data class LogStats(
    val queued: Int,
    val accepted: Long,
    val duplicates: Long,
    val rejected: Long,
    val dropped: Long,
    val batchesSent: Long,
)

/** The answer `POST /api/v1/resolve` gives: the resolve algorithm run on the server. */
public data class ServerResolution(
    val useCase: String,
    val kind: UseCaseKind,
    val deploymentId: String?,
    val deploymentRevision: Int?,
    val prompt: String?,
    val prompts: List<String>,
    val model: String?,
    val modelId: String?,
    val provider: String?,
    val effectiveParams: Map<String, Any?>,
    val effectiveProviderOptions: Map<String, Any?>,
    val promptVersionId: String?,
    val promptVersionNumber: Int?,
    val messages: List<PromptMessage>?,
    val text: String?,
    val warnings: List<String>,
    val etag: String?,
)

/**
 * The PromptOn SDK.
 *
 * ```kotlin
 * val prompton = PromptOn()
 * val resolution = prompton.resolve("greeting")
 * val messages = resolution.render(mapOf("name" to "Ada")).messages!!
 * val answer = prompton.generate(resolution, GenerationMeta(inputMessages = messages)) { call ->
 *     val reply = myProvider.chat(resolution.model!!, messages, resolution.effectiveParams)
 *     call.succeeded(ProviderOutcome(content = reply.text, finishReason = reply.finishReason))
 *     reply.text
 * }
 * ```
 *
 * One instance owns one snapshot store and one monitoring-log queue, and is safe to share across
 * threads. Close it on shutdown so the queue drains.
 */
public class PromptOn internal constructor(
    public val config: PromptOnConfig,
    internal val clock: PromptOnClock,
) : AutoCloseable {
    @JvmOverloads
    public constructor(config: PromptOnConfig = PromptOnConfig()) : this(config, PromptOnClock.SYSTEM)

    private val transport: HttpTransport? =
        when {
            config.mode == PromptOnMode.TEST -> config.transport
            config.mode == PromptOnMode.OFFLINE -> null
            else -> config.transport ?: JdkHttpTransport(config.connectTimeout, config.requestTimeout)
        }

    private val snapshots = SnapshotManager(config, transport, clock)

    private val generations = GenerationsClient(config, transport)

    private val buffer: LogBuffer? =
        if (config.mode == PromptOnMode.TEST) null else LogBuffer(config, clock, generations::post)

    private val captured: MutableList<JsonObject> = Collections.synchronizedList(mutableListOf())
    private val resolveCache = ConcurrentHashMap<String, Pair<Instant, ServerResolution>>()
    private val resolveNextAttempt = ConcurrentHashMap<String, Instant>()
    private val resolveFailures = ConcurrentHashMap<String, Int>()

    private val lifecycle: PromptOnLifecycle.Registration

    init {
        snapshots.start()
        // One process-wide shutdown hook flushes whatever is still alive; the cleaner releases the
        // threads of an instance the app dropped without closing it.
        lifecycle =
            PromptOnLifecycle.register(
                owner = this,
                resources = PromptOnResources(snapshots, buffer, transport),
                flushAtShutdown = config.mode == PromptOnMode.LIVE,
                ownsThreads = config.mode != PromptOnMode.TEST,
            )
    }

    // -------------------------------------------------------------------
    // Configuration

    /**
     * Resolves [useCase] from the cached snapshot.
     *
     * Reads memory, and past the cache TTL starts a background revalidation that never blocks
     * this call. Nothing here talks to PromptOn on the request path.
     */
    @JvmOverloads
    public fun resolve(
        useCase: String,
        prompt: String? = null,
    ): Resolution {
        val entry = snapshots.entry()
        return Resolver.resolve(entry.document, useCase, prompt, entry.source, entry.etag)
    }

    /** Renders a resolution's pinned prompt with this call's variables. */
    @JvmOverloads
    public fun render(
        resolution: Resolution,
        variables: Map<String, Any?>? = null,
    ): RenderedPrompt = resolution.render(variables)

    /** The prompt names the live deployment of [useCase] pins. */
    public fun promptNames(useCase: String): List<String> = snapshots.entry().document.promptNames(useCase)

    /** The snapshot document currently in memory. */
    public fun snapshot(): SnapshotDocument = snapshots.entry().document

    public fun snapshotInfo(): SnapshotInfo {
        val entry = snapshots.currentOrNull()
        return SnapshotInfo(
            etag = entry?.etag,
            lastModified = entry?.lastModified,
            source = entry?.source,
            project = entry?.document?.project,
            environment = entry?.document?.environment,
            fetchedAt = entry?.fetchedAt,
            stale = entry == null || entry.source != ResolutionSource.REMOTE || entry.staleSince != null,
            ageSeconds =
                entry?.let {
                    java.time.Duration
                        .between(it.fetchedAt, clock.now())
                        .seconds
                        .coerceAtLeast(0)
                },
            useCases = entry?.document?.useCases?.size ?: 0,
        )
    }

    /**
     * Fetches the snapshot once, now, and waits for it. Returns whether a document is in memory
     * afterwards — a refresh that failed while the cached document keeps serving still returns true.
     *
     * While PromptOn is rate-limiting or a backoff is running this returns without calling the
     * server — the same window the poller obeys — so a health check on a timer cannot hammer a
     * server that asked for silence. Pass `force = true` to fetch anyway.
     */
    @JvmOverloads
    public fun refreshBlocking(force: Boolean = false): Boolean = snapshots.refreshNow(force)

    /** Suspending [refreshBlocking]. */
    @JvmOverloads
    public suspend fun refresh(force: Boolean = false): Boolean =
        withContext(Dispatchers.IO) { snapshots.refreshNow(force) }

    /** Writes the current document to [path], for committing as a bundle. */
    public fun exportSnapshot(path: Path) {
        snapshots.export(path)
    }

    /** Injects a snapshot document, for tests and for offline bootstrapping. */
    @JvmOverloads
    public fun putSnapshot(
        json: String,
        source: ResolutionSource = ResolutionSource.MANUAL,
    ) {
        snapshots.putDocument(SnapshotDocument.parse(json), source)
    }

    // -------------------------------------------------------------------
    // The /resolve client

    /**
     * Resolves through `POST /resolve` instead of the snapshot: the simple path for a low-traffic
     * call site, and a smoke test for a deployment.
     *
     * The answer is cached for the same cache TTL per use case, prompt and environment, and the
     * template is rendered locally, so this is not a per-request round trip. When PromptOn
     * answers `429` or `5xx`, or cannot be reached, the cached answer keeps serving.
     */
    @JvmOverloads
    public fun resolveRemoteBlocking(
        useCase: String,
        prompt: String? = null,
        environment: String? = null,
    ): Resolution {
        val server = serverResolution(useCase, prompt, environment)
        return toResolution(server, environment ?: config.environment)
    }

    /** Suspending [resolveRemoteBlocking]. */
    @JvmOverloads
    public suspend fun resolveRemote(
        useCase: String,
        prompt: String? = null,
        environment: String? = null,
    ): Resolution = withContext(Dispatchers.IO) { resolveRemoteBlocking(useCase, prompt, environment) }

    /**
     * Calls `POST /resolve` with [variables] and returns the server's answer verbatim, rendered
     * server-side. Never cached — use it to smoke-test a deployment, not on a hot path.
     */
    @JvmOverloads
    public fun resolveOnServerBlocking(
        useCase: String,
        prompt: String? = null,
        environment: String? = null,
        variables: Map<String, Any?>? = null,
    ): ServerResolution = postResolve(useCase, prompt, environment, variables)

    /** Suspending [resolveOnServerBlocking]. */
    @JvmOverloads
    public suspend fun resolveOnServer(
        useCase: String,
        prompt: String? = null,
        environment: String? = null,
        variables: Map<String, Any?>? = null,
    ): ServerResolution =
        withContext(Dispatchers.IO) { resolveOnServerBlocking(useCase, prompt, environment, variables) }

    // -------------------------------------------------------------------
    // Monitoring logs

    /** Enqueues one monitoring log the app built itself and returns immediately. */
    @JvmOverloads
    public fun log(
        record: GenerationRecord,
        environment: String = config.environment,
    ) {
        enqueue(record.toJsonObject(), record.useCase, environment)
    }

    /**
     * Enqueues one monitoring log given as a plain map, for apps that assemble the wire shape
     * themselves. `id`, `started_at` and `sdk` are filled in when absent.
     */
    @JvmOverloads
    public fun log(
        record: Map<String, Any?>,
        environment: String = config.environment,
    ) {
        val fields = LinkedHashMap(Ptn.toObject(record))
        require(fields.containsKey("use_case")) { "a monitoring log needs a use_case" }
        require(fields.containsKey("model")) { "a monitoring log needs a model" }
        require(fields.containsKey("status")) { "a monitoring log needs a status" }
        if (!fields.containsKey("id")) fields["id"] = Ptn.toElement(UuidV7.generate())
        if (!fields.containsKey("started_at")) fields["started_at"] = Ptn.toElement(clock.now())
        require(Ptn.asString(fields["started_at"]) != null) { "a monitoring log needs a started_at" }
        if (!fields.containsKey("sdk")) fields["sdk"] = Ptn.toElement(SdkInfo.CURRENT.asMap())
        enqueue(JsonObject(fields), Ptn.asString(fields["use_case"]), environment)
    }

    /** Sends the queue now and waits for the result. */
    @JvmOverloads
    public fun flushBlocking(timeout: Duration = 10.seconds): FlushResult =
        buffer?.flush(timeout) ?: FlushResult(0, 0, 0, 0, 0)

    /** Suspending [flushBlocking]. */
    @JvmOverloads
    public suspend fun flush(timeout: Duration = 10.seconds): FlushResult =
        withContext(Dispatchers.IO) { flushBlocking(timeout) }

    public fun logStats(): LogStats = buffer?.stats() ?: LogStats(0, 0, 0, 0, 0, 0)

    /** In [PromptOnMode.TEST] the records that would have been sent, in order. */
    public fun capturedRecords(): List<JsonObject> = synchronized(captured) { captured.toList() }

    /** Clears what [capturedRecords] returns. */
    public fun clearCapturedRecords() {
        synchronized(captured) { captured.clear() }
    }

    // -------------------------------------------------------------------
    // The wrapper

    /**
     * Times a provider call and logs it.
     *
     * The block gets a [GenerationCall] to record what the provider returned. Whatever the block
     * returns is returned unchanged, and whatever it throws is logged as an `app` error and
     * rethrown unchanged.
     */
    @JvmOverloads
    public fun <T> generateBlocking(
        resolution: Resolution,
        meta: GenerationMeta = GenerationMeta(),
        block: (GenerationCall) -> T,
    ): T {
        val call = GenerationCall()
        val id = meta.id ?: UuidV7.generate()
        val startedAt = clock.now()
        val started = clock.nanoTime()
        try {
            val result = block(call)
            logGeneration(resolution, meta, call.outcome, call.error, id, startedAt, elapsedMillis(started))
            return result
        } catch (e: Throwable) {
            val error = GenerationError(ErrorKind.APP, message = e.toString())
            logGeneration(resolution, meta, call.outcome, error, id, startedAt, elapsedMillis(started))
            throw e
        }
    }

    /** Suspending [generateBlocking]. */
    @JvmOverloads
    public suspend fun <T> generate(
        resolution: Resolution,
        meta: GenerationMeta = GenerationMeta(),
        block: suspend (GenerationCall) -> T,
    ): T {
        val call = GenerationCall()
        val id = meta.id ?: UuidV7.generate()
        val startedAt = clock.now()
        val started = clock.nanoTime()
        try {
            val result = block(call)
            logGeneration(resolution, meta, call.outcome, call.error, id, startedAt, elapsedMillis(started))
            return result
        } catch (e: Throwable) {
            val error = GenerationError(ErrorKind.APP, message = e.toString())
            logGeneration(resolution, meta, call.outcome, error, id, startedAt, elapsedMillis(started))
            throw e
        }
    }

    override fun close() {
        lifecycle.closeNow()
    }

    // -------------------------------------------------------------------

    private fun elapsedMillis(startedNanos: Long): Long = (clock.nanoTime() - startedNanos) / 1_000_000

    internal fun logGeneration(
        resolution: Resolution,
        meta: GenerationMeta,
        outcome: ProviderOutcome?,
        error: GenerationError?,
        id: String,
        startedAt: Instant,
        latencyMs: Long,
    ) {
        val metadata = LinkedHashMap<String, Any?>(meta.metadata)
        outcome?.isByok?.let { metadata["is_byok"] = it }

        val record =
            GenerationRecord(
                useCase = resolution.useCase,
                model = resolution.model ?: "",
                status = if (error != null) GenerationStatus.ERROR else GenerationStatus.OK,
                startedAt = startedAt,
                id = id,
                kind = resolution.kind,
                deploymentId = resolution.deploymentId,
                deploymentRevision = resolution.deploymentRevision,
                prompt = resolution.prompt,
                promptVersionId = resolution.promptVersionId,
                resolutionSource = resolution.source,
                provider = resolution.provider,
                modelUsed = outcome?.modelUsed,
                upstreamProvider = outcome?.upstreamProvider,
                params = Resolver.mergeShallow(resolution.effectiveParams, meta.params),
                input =
                    GenerationInput(
                        variables = meta.variables,
                        messages = meta.inputMessages,
                        text = meta.inputText,
                    ),
                output =
                    outcome?.let { GenerationOutput(content = it.content, toolCalls = it.toolCalls) },
                finishReason = outcome?.finishReason,
                stopKind = stopKindOf(outcome),
                error = error,
                usage = outcome?.usage ?: Usage(),
                latencyMs = latencyMs,
                traceId = meta.traceId,
                sequence = meta.sequence,
                endUserRef = meta.endUserRef,
                context = meta.context,
                metadata = metadata,
            )

        enqueue(
            record.toJsonObject(),
            resolution.useCase,
            resolution.environment.ifBlank { config.environment },
            resolution.payloadPolicy,
        )
    }

    private fun stopKindOf(outcome: ProviderOutcome?): StopKind? {
        if (outcome == null) return null
        outcome.stopKind?.let { return StopKind.normalize(it.wire) }
        return outcome.finishReason?.let { StopKind.normalize(it) }
    }

    private fun enqueue(
        record: JsonObject,
        useCase: String?,
        environment: String,
        policy: PayloadPolicy? = null,
    ) {
        val effectivePolicy = policy ?: policyFor(useCase)
        val options =
            PayloadOptions(
                defaults = config.payloadDefaults,
                hashEndUser = config.hashEndUser,
                redact = config.log.redact,
                onRedactFailure = { error ->
                    PtnLog.warn("[PromptOn] the redact hook threw; dropping the payload", error)
                },
            )
        val prepared =
            try {
                Payload.apply(record, effectivePolicy, options)
            } catch (e: RuntimeException) {
                PtnLog.warn("[PromptOn] dropping a monitoring log the SDK could not prepare", e)
                return
            }

        if (config.mode == PromptOnMode.TEST) {
            synchronized(captured) { captured.add(prepared) }
            return
        }
        if (config.mode == PromptOnMode.OFFLINE) {
            PtnLog.once("offline-logs", "[PromptOn] offline mode: monitoring logs are not sent")
            return
        }
        if (config.apiKey.isNullOrBlank()) {
            PtnLog.once(
                "no-api-key-logs",
                "[PromptOn] no API key configured — monitoring logs are dropped instead of sent",
            )
            return
        }
        buffer?.enqueue(prepared, environment)
    }

    private fun policyFor(useCase: String?): PayloadPolicy? {
        if (useCase == null) return null
        return snapshots
            .currentOrNull()
            ?.document
            ?.useCases
            ?.get(useCase)
            ?.payloadPolicy
    }

    // -------------------------------------------------------------------
    // HTTP

    private fun requireTransport(): HttpTransport =
        transport ?: throw PromptOnException(
            "this PromptOn instance makes no remote calls (mode=${config.mode}, apiKey present=" +
                "${!config.apiKey.isNullOrBlank()})",
        )

    private fun headers(json: Boolean): Map<String, String> = config.wireHeaders(json)

    /**
     * The cached `/resolve` answer, refreshed at most once per cache TTL and never while the server
     * is asking for silence.
     *
     * A `429`, a `5xx` or an unreachable server keeps serving the cached answer *and* starts a
     * window — `Retry-After`, else exponential backoff x2 from the TTL up to five minutes — during
     * which the SDK does not call `/resolve` again for this key. Without that, every caller past the
     * TTL would issue another request at a control plane that is already rate-limiting.
     */
    private fun serverResolution(
        useCase: String,
        prompt: String?,
        environment: String?,
    ): ServerResolution {
        val key = "${environment ?: config.environment}|$useCase|${prompt ?: Resolver.DEFAULT_PROMPT}"
        val now = clock.now()
        val entry = resolveCache[key]
        if (entry != null &&
            java.time.Duration
                .between(entry.first, now) < config.cacheTtl.toJavaDuration()
        ) {
            return entry.second
        }
        val cached = entry?.second
        val blockedUntil = resolveNextAttempt[key]
        if (blockedUntil != null && now.isBefore(blockedUntil)) {
            cached?.let { return it }
            throw PromptOnException(
                "PromptOn answered /resolve with an error for '$useCase' and nothing is cached: " +
                    "not calling again before $blockedUntil",
            )
        }

        val response =
            try {
                postResolveResponse(useCase, prompt, environment, null)
            } catch (e: Exception) {
                pauseResolve(key, now, cached, null, "/resolve is unreachable (${e.message})")
                cached?.let { return it }
                throw e
            }

        if (response.status == 200) {
            val answer = parseServerResolution(Ptn.parseObject(response.body))
            resolveCache[key] = now to answer
            resolveNextAttempt.remove(key)
            resolveFailures.remove(key)
            return answer
        }
        if (response.status == 429 || response.status >= 500) {
            pauseResolve(
                key,
                now,
                cached,
                SnapshotManager.retryAfterOf(response),
                "/resolve answered ${response.status}",
            )
            cached?.let { return it }
        }
        throw resolveError(useCase, response)
    }

    /**
     * Stops calling `/resolve` for this key until the window has passed, and keeps whatever was
     * cached alive for at least that long. Only `429`, `5xx` and transport failures land here — a
     * `4xx` is about the request, not about load, and repeating it is the caller's business.
     */
    private fun pauseResolve(
        key: String,
        now: Instant,
        cached: ServerResolution?,
        retryAfter: Duration?,
        reason: String,
    ) {
        val attempt = resolveFailures.merge(key, 1, Int::plus) ?: 1
        val delay = retryAfter ?: SnapshotManager.backoffFrom(config.cacheTtl, attempt)
        if (cached != null) resolveCache[key] = now to cached
        resolveNextAttempt[key] = now.plusMillis(delay.inWholeMilliseconds)
        PtnLog.throttled("resolve-degraded", 60_000) {
            "[PromptOn] $reason — not calling it again for ${delay.inWholeSeconds}s" +
                if (cached != null) "; the cached answer keeps serving" else ""
        }
    }

    private fun postResolve(
        useCase: String,
        prompt: String?,
        environment: String?,
        variables: Map<String, Any?>?,
    ): ServerResolution {
        val response = postResolveResponse(useCase, prompt, environment, variables)
        if (response.status != 200) throw resolveError(useCase, response)
        return parseServerResolution(Ptn.parseObject(response.body))
    }

    private fun postResolveResponse(
        useCase: String,
        prompt: String?,
        environment: String?,
        variables: Map<String, Any?>?,
    ): HttpResponse {
        val request = LinkedHashMap<String, Any?>()
        request["use_case"] = useCase
        request["environment"] = environment ?: config.environment
        prompt?.let { request["prompt"] = it }
        variables?.let { request["variables"] = it }

        return requireTransport().execute(
            HttpRequest(
                method = "POST",
                url = "${config.baseUrl}/resolve",
                headers = headers(json = true),
                body = Ptn.canonicalJson(Ptn.toObject(request)),
            ),
        )
    }

    private fun resolveError(
        useCase: String,
        response: HttpResponse,
    ): PromptOnException {
        val error = runCatching { Ptn.asObject(Ptn.parseObject(response.body)["error"]) }.getOrNull()
        val code = Ptn.asString(error?.get("code"))
        val message = Ptn.asString(error?.get("message")) ?: response.body.take(200)
        val details = Ptn.asObject(error?.get("details"))
        val reason = Ptn.asString(details?.get("reason"))
        return when {
            details?.get("missing_variable") != null ->
                MissingVariableException(Ptn.asString(details["missing_variable"])!!)

            reason == "unresolved" -> UnresolvedUseCaseException(useCase)
            reason == "unknown_prompt" ->
                UnknownPromptException(
                    useCase,
                    Ptn.asString(details?.get("prompt")) ?: Resolver.DEFAULT_PROMPT,
                    Ptn.asArray(details?.get("available_prompts"))?.mapNotNull { Ptn.asString(it) }.orEmpty(),
                )

            details?.get("use_case") != null -> UnknownUseCaseException(useCase)
            else ->
                PromptOnApiException(
                    response.status,
                    code,
                    message,
                    Ptn.toNativeMap(details),
                )
        }
    }

    private fun parseServerResolution(body: JsonObject): ServerResolution {
        val deployment = Ptn.asObject(body["deployment"])
        val version = Ptn.asObject(body["prompt_version"])

        fun map(key: String): Map<String, Any?> = Ptn.toNativeMap(body[key])
        return ServerResolution(
            useCase = Ptn.asString(body["use_case"]) ?: "",
            kind = UseCaseKind.fromWire(Ptn.asString(body["kind"])),
            deploymentId = Ptn.asString(deployment?.get("id")),
            deploymentRevision = Ptn.asInt(deployment?.get("revision")),
            prompt = Ptn.asString(body["prompt"]),
            prompts = Ptn.asArray(body["prompts"])?.mapNotNull { Ptn.asString(it) }.orEmpty(),
            model = Ptn.asString(body["model"]),
            modelId = Ptn.asString(body["model_id"]),
            provider = Ptn.asString(body["provider"]),
            effectiveParams = map("effective_params"),
            effectiveProviderOptions = map("effective_provider_options"),
            promptVersionId = Ptn.asString(version?.get("id")),
            promptVersionNumber = Ptn.asInt(version?.get("number")),
            messages =
                Ptn.asArray(body["messages"])?.mapNotNull { element ->
                    val message = Ptn.asObject(element) ?: return@mapNotNull null
                    PromptMessage(
                        role = Ptn.asString(message["role"]) ?: "user",
                        content = Ptn.asString(message["content"]) ?: "",
                        name = Ptn.asString(message["name"]),
                    )
                },
            text = Ptn.asString(body["text"]),
            warnings = Ptn.asArray(body["warnings"])?.mapNotNull { Ptn.asString(it) }.orEmpty(),
            etag = Ptn.asString(body["etag"]),
        )
    }

    private fun toResolution(
        server: ServerResolution,
        environment: String,
    ): Resolution =
        Resolution(
            useCase = server.useCase,
            kind = server.kind,
            environment = environment,
            deploymentId = server.deploymentId,
            deploymentRevision = server.deploymentRevision,
            prompt = server.prompt,
            availablePrompts = server.prompts,
            model = server.model,
            modelId = server.modelId,
            provider = server.provider,
            effectiveParams = server.effectiveParams,
            effectiveProviderOptions = server.effectiveProviderOptions,
            promptVersionId = server.promptVersionId,
            promptVersionNumber = server.promptVersionNumber,
            engine = TemplateEngine.LIQUID,
            messages = server.messages,
            textTemplate = server.text,
            inputSchema = emptyList(),
            payloadPolicy = policyFor(server.useCase),
            source = ResolutionSource.REMOTE,
            etag = server.etag,
            warnings = server.warnings,
        )
}

private fun SdkInfo.asMap(): Map<String, Any?> = mapOf("name" to name, "version" to version)
