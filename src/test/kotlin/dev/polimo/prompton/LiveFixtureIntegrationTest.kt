package dev.polimo.prompton

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Runs against a live PromptOn server seeded with the `sdkfixture` project.
 *
 * Set `PTN_API_KEY` (and `PTN_HOST` when the server is not on `http://localhost:4000`) to enable it;
 * without the key the whole class is skipped, so `./gradlew test` stays green offline.
 */
@EnabledIfEnvironmentVariable(named = "PTN_API_KEY", matches = ".+")
class LiveFixtureIntegrationTest {
    @TempDir
    lateinit var tempDir: Path

    private val host: String = System.getenv("PTN_HOST") ?: "http://localhost:4000"
    private val apiKey: String = System.getenv("PTN_API_KEY") ?: ""

    /** Wraps the real transport so the test can assert on statuses the SDK saw. */
    private class RecordingTransport(
        private val delegate: HttpTransport,
    ) : HttpTransport {
        val exchanges: MutableList<Pair<HttpRequest, HttpResponse>> =
            Collections.synchronizedList(mutableListOf())

        override fun execute(request: HttpRequest): HttpResponse =
            delegate.execute(request).also { exchanges.add(request to it) }

        override fun close() = delegate.close()

        fun statuses(pathFragment: String): List<Int> =
            synchronized(exchanges) {
                exchanges.filter { it.first.url.contains(pathFragment) }.map { it.second.status }
            }
    }

    private fun transport() = RecordingTransport(JdkHttpTransport(5.seconds, 10.seconds))

    private fun config(
        transport: HttpTransport,
        environment: String = "production",
    ) = PromptOnConfig(
        apiKey = apiKey,
        host = host,
        environment = environment,
        cacheTtl = 10.seconds,
        pollingEnabled = false,
        diskCachePath = tempDir.resolve("snapshot-$environment.json"),
        transport = transport,
    )

    @Test
    fun `the snapshot is fetched once and revalidated with a 304`() {
        val transport = transport()
        val clock = FakeClock(instant = Instant.now())
        PromptOn(config(transport), clock).use { prompton ->
            assertTrue(prompton.refreshBlocking())
            val info = prompton.snapshotInfo()
            assertEquals("sdkfixture", info.project)
            assertEquals("production", info.environment)
            assertEquals(ResolutionSource.REMOTE, info.source)
            assertNotNull(info.etag)
            assertTrue(info.etag!!.contains("sha256-"), info.etag!!)
            assertTrue(info.useCases >= 3, "expected the fixture's three use cases, got ${info.useCases}")

            val before = transport.statuses("/snapshot").size
            clock.advanceMillis(11_000)
            prompton.resolve("greeting")
            await("the revalidation") { transport.statuses("/snapshot").size > before }
            settle()

            val statuses = transport.statuses("/snapshot")
            assertEquals(200, statuses.first(), "the first fetch carries the document")
            assertTrue(
                statuses.drop(1).all { it == 304 },
                "every revalidation of an unchanged snapshot is a 304, got $statuses",
            )
            assertEquals(info.etag, prompton.snapshotInfo().etag, "a 304 keeps the document it already had")
            assertEquals(ResolutionSource.REMOTE, prompton.snapshotInfo().source)

            // The disk tier now holds the same document, so a second process starts warm.
            assertTrue(
                java.nio.file.Files
                    .exists(tempDir.resolve("snapshot-production.json")),
            )
        }
    }

    @Test
    fun `local resolution matches the server for the default prompt`() {
        assertMatchesServer("greeting", prompt = null, variables = mapOf("name" to "Ada"))
    }

    @Test
    fun `local resolution matches the server for a named prompt`() {
        assertMatchesServer("greeting", prompt = "ko", variables = mapOf("name" to "아다"))
    }

    @Test
    fun `local resolution matches the server for a text use case`() {
        assertMatchesServer("summarize", prompt = null, variables = mapOf("items" to listOf("alpha", "beta", "gamma")))
    }

    @Test
    fun `local resolution matches the server for an embedding use case`() {
        val transport = transport()
        PromptOn(config(transport), FakeClock(instant = Instant.now())).use { prompton ->
            prompton.refreshBlocking()
            val local = prompton.resolve("embed")
            val server = prompton.resolveOnServerBlocking("embed")

            assertEquals(UseCaseKind.EMBEDDING, local.kind)
            assertEquals(server.model, local.model)
            assertEquals(server.modelId, local.modelId)
            assertEquals(server.provider, local.provider)
            assertNull(local.prompt)
            assertNull(server.prompt)
            assertEquals(emptyList(), local.availablePrompts)
            assertEquals(emptyList(), server.prompts)
            assertNull(server.messages)
            assertNull(server.text)
        }
    }

    @Test
    fun `staging pins something else than production`() {
        val transport = transport()
        PromptOn(config(transport, environment = "staging"), FakeClock(instant = Instant.now())).use { prompton ->
            prompton.refreshBlocking()
            assertEquals("staging", prompton.snapshotInfo().environment)
            val local = prompton.resolve("greeting")
            val server = prompton.resolveOnServerBlocking("greeting", environment = "staging")
            assertEquals(server.effectiveParams, local.effectiveParams)
            assertEquals(server.prompts, local.availablePrompts)
            assertNotEquals(
                prompton.resolveOnServerBlocking("greeting", environment = "production").deploymentId,
                server.deploymentId,
                "staging and production are different pins",
            )
        }
    }

    @Test
    fun `the error cases answer the way the contract says`() {
        val transport = transport()
        PromptOn(config(transport), FakeClock(instant = Instant.now())).use { prompton ->
            prompton.refreshBlocking()

            assertFailsWith<UnknownUseCaseException> { prompton.resolve("does_not_exist") }
            assertFailsWith<UnknownUseCaseException> { prompton.resolveOnServerBlocking("does_not_exist") }

            val localUnknownPrompt =
                assertFailsWith<UnknownPromptException> { prompton.resolve("greeting", prompt = "fr") }
            val serverUnknownPrompt =
                assertFailsWith<UnknownPromptException> { prompton.resolveOnServerBlocking("greeting", "fr") }
            assertEquals(listOf("default", "ko"), localUnknownPrompt.availablePrompts)
            assertEquals(localUnknownPrompt.availablePrompts, serverUnknownPrompt.availablePrompts)

            val localMissing =
                assertFailsWith<MissingVariableException> { prompton.resolve("greeting").render(emptyMap()) }
            val serverMissing =
                assertFailsWith<MissingVariableException> {
                    prompton.resolveOnServerBlocking("greeting", variables = emptyMap())
                }
            assertEquals("name", localMissing.variable)
            assertEquals("name", serverMissing.variable)

            val unknownEnvironment =
                assertFailsWith<PromptOnApiException> {
                    prompton.resolveOnServerBlocking("greeting", environment = "nope")
                }
            assertEquals(404, unknownEnvironment.status)
        }
    }

    @Test
    fun `a batch of monitoring logs is accepted and a resend is a duplicate`() {
        val transport = transport()
        PromptOn(config(transport), FakeClock(instant = Instant.now())).use { prompton ->
            prompton.refreshBlocking()
            val resolution = prompton.resolve("greeting")
            val messages = resolution.render(mapOf("name" to "Ada")).messages!!

            val ids = listOf(UuidV7.generate(), UuidV7.generate())
            val records =
                listOf(
                    GenerationRecord(
                        useCase = resolution.useCase,
                        model = resolution.model!!,
                        status = GenerationStatus.OK,
                        startedAt = Instant.now(),
                        id = ids[0],
                        kind = resolution.kind,
                        deploymentId = resolution.deploymentId,
                        deploymentRevision = resolution.deploymentRevision,
                        prompt = resolution.prompt,
                        promptVersionId = resolution.promptVersionId,
                        modelId = resolution.modelId,
                        resolutionSource = resolution.source,
                        provider = resolution.provider,
                        params = resolution.effectiveParams,
                        input = GenerationInput(variables = mapOf("name" to "Ada"), messages = messages),
                        output = GenerationOutput(content = "Hello, Ada!"),
                        finishReason = "stop",
                        stopKind = StopKind.STOP,
                        usage = Usage(inputTokens = 38, outputTokens = 9, costSource = CostSource.UNKNOWN),
                        latencyMs = 842,
                        traceId = "prompton-kotlin-integration",
                    ),
                    GenerationRecord(
                        useCase = resolution.useCase,
                        model = resolution.model!!,
                        status = GenerationStatus.ERROR,
                        startedAt = Instant.now(),
                        id = ids[1],
                        kind = resolution.kind,
                        error = GenerationError(ErrorKind.RATE_LIMITED, 429, "rate limited by upstream provider"),
                        latencyMs = 1503,
                        traceId = "prompton-kotlin-integration",
                    ),
                )

            records.forEach { prompton.log(it) }
            val first = prompton.flushBlocking()
            assertEquals(2, first.accepted, "the fixture server should accept both records")
            assertEquals(0, first.rejected)
            assertEquals(0, first.remaining)
            assertEquals(listOf(202), transport.statuses("/generations"))

            records.forEach { prompton.log(it) }
            val resend = prompton.flushBlocking()
            assertEquals(0, resend.accepted)
            assertEquals(2, resend.duplicates, "the id is the idempotency key")
        }
    }

    @Test
    fun `the wrapper logs a real round trip`() {
        val transport = transport()
        PromptOn(config(transport), FakeClock(instant = Instant.now())).use { prompton ->
            prompton.refreshBlocking()
            val resolution = prompton.resolve("greeting")
            val messages = resolution.render(mapOf("name" to "Ada")).messages!!

            val answer =
                prompton.generateBlocking(
                    resolution,
                    GenerationMeta(
                        variables = mapOf("name" to "Ada"),
                        inputMessages = messages,
                        traceId = "prompton-kotlin-wrapper",
                    ),
                ) { call ->
                    // A fake provider: the SDK never calls one for you.
                    call.succeeded(ProviderOutcome(content = "Hello, Ada!", finishReason = "stop"))
                    "Hello, Ada!"
                }
            assertEquals("Hello, Ada!", answer)

            val result = prompton.flushBlocking()
            assertEquals(1, result.accepted)
        }
    }

    private fun assertMatchesServer(
        useCase: String,
        prompt: String?,
        variables: Map<String, Any?>,
    ) {
        val transport = transport()
        PromptOn(config(transport), FakeClock(instant = Instant.now())).use { prompton ->
            prompton.refreshBlocking()
            val local = prompton.resolve(useCase, prompt)
            val server = prompton.resolveOnServerBlocking(useCase, prompt, variables = variables)

            assertEquals(server.kind, local.kind, "kind")
            assertEquals(server.model, local.model, "model")
            assertEquals(server.modelId, local.modelId, "model_id")
            assertEquals(server.provider, local.provider, "provider")
            assertEquals(server.deploymentId, local.deploymentId, "deployment id")
            assertEquals(server.deploymentRevision, local.deploymentRevision, "deployment revision")
            assertEquals(server.prompt, local.prompt, "prompt")
            assertEquals(server.prompts, local.availablePrompts, "prompts")
            assertEquals(server.promptVersionId, local.promptVersionId, "prompt version id")
            assertEquals(server.promptVersionNumber, local.promptVersionNumber, "prompt version number")
            assertEquals(server.effectiveParams, local.effectiveParams, "effective params")
            assertEquals(
                server.effectiveProviderOptions,
                local.effectiveProviderOptions,
                "effective provider options",
            )

            val rendered = local.render(variables)
            assertEquals(server.messages, rendered.messages, "rendered messages")
            assertEquals(server.text, rendered.text, "rendered text")
        }
    }
}
