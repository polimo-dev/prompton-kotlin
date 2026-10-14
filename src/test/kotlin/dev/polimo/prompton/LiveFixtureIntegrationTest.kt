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
        diskCachePath = tempDir.resolve("prompts-$environment.json"),
        transport = transport,
    )

    @Test
    fun `the requested prompt is fetched once and revalidated with a 304`() {
        val transport = transport()
        val clock = FakeClock(instant = Instant.now())
        PromptOn(config(transport), clock).use { prompton ->
            prompton.prompt("greeting")
            val info = prompton.promptDocumentInfo()
            assertEquals("sdkfixture", info.project)
            assertEquals("production", info.environment)
            assertEquals(PromptSource.REMOTE, info.source)
            assertNotNull(info.etag)
            assertTrue(info.etag!!.contains("sha256-"), info.etag!!)
            assertTrue(info.prompts >= 1, "expected at least the requested prompt, got ${info.prompts}")

            val before = transport.statuses("/prompts/greeting").size
            clock.advanceMillis(11_000)
            prompton.prompt("greeting")
            await("the revalidation") { transport.statuses("/prompts/greeting").size > before }
            settle()

            val statuses = transport.statuses("/prompts/greeting")
            assertEquals(200, statuses.first(), "the first fetch carries the requested prompt document")
            assertTrue(
                statuses.drop(1).all { it == 304 },
                "every revalidation of an unchanged prompt is a 304, got $statuses",
            )
            assertEquals(info.etag, prompton.promptDocumentInfo().etag, "a 304 keeps the document it already had")
            assertEquals(PromptSource.REMOTE, prompton.promptDocumentInfo().source)

            // The disk tier now holds the same document, so a second process starts warm.
            assertTrue(
                java.nio.file.Files
                    .exists(tempDir.resolve("prompts-production.json")),
            )
        }
    }

    @Test
    fun `local prompt matches the server for the default prompt`() {
        assertMatchesServer("greeting", template = null, variables = mapOf("name" to "Ada"))
    }

    @Test
    fun `local prompt matches the server for a named prompt`() {
        assertMatchesServer("greeting", template = "ko", variables = mapOf("name" to "아다"))
    }

    @Test
    fun `local prompt matches the server for a text prompt`() {
        assertMatchesServer(
            "summarize",
            template = null,
            variables = mapOf("items" to listOf("alpha", "beta", "gamma")),
        )
    }

    @Test
    fun `local prompt matches the server for an embedding prompt`() {
        val transport = transport()
        PromptOn(config(transport), FakeClock(instant = Instant.now())).use { prompton ->
            val local = prompton.prompt("embed")
            val server = prompton.promptOnServerBlocking("embed")

            assertEquals(PromptKind.EMBEDDING, local.kind)
            assertEquals(server.model, local.model)
            assertEquals(server.modelId, local.modelId)
            assertEquals(server.provider, local.provider)
            assertNull(local.template)
            assertNull(server.template)
            assertEquals(emptyList(), local.templateNames)
            assertEquals(emptyList(), server.templateNames)
            assertNull(server.messages)
            assertNull(server.text)
        }
    }

    @Test
    fun `staging pins something else than production`() {
        val transport = transport()
        PromptOn(config(transport, environment = "staging"), FakeClock(instant = Instant.now())).use { prompton ->
            val local = prompton.prompt("greeting")
            assertEquals("staging", prompton.promptDocumentInfo().environment)
            val server = prompton.promptOnServerBlocking("greeting", environment = "staging")
            assertEquals(server.params, local.params)
            assertEquals(server.templateNames, local.templateNames)
            assertNotEquals(
                prompton.promptOnServerBlocking("greeting", environment = "production").deploymentId,
                server.deploymentId,
                "staging and production are different pins",
            )
        }
    }

    @Test
    fun `the error cases answer the way the contract says`() {
        val transport = transport()
        PromptOn(config(transport), FakeClock(instant = Instant.now())).use { prompton ->
            prompton.prompt("greeting")

            assertFailsWith<PromptDocumentUnavailableException> { prompton.prompt("does_not_exist") }
            val serverUnknownPrompt =
                assertFailsWith<UnknownPromptException> { prompton.promptOnServerBlocking("does_not_exist") }
            assertEquals("does_not_exist", serverUnknownPrompt.promptKey)

            val localUnknownPrompt =
                assertFailsWith<UnknownTemplateException> { prompton.prompt("greeting", template = "fr") }
            val serverUnknownTemplate =
                assertFailsWith<UnknownTemplateException> { prompton.promptOnServerBlocking("greeting", "fr") }
            assertEquals(listOf("default", "ko"), localUnknownPrompt.templateNames)
            assertEquals(localUnknownPrompt.templateNames, serverUnknownTemplate.templateNames)

            val localMissing =
                assertFailsWith<MissingVariableException> { prompton.prompt("greeting").messages(emptyMap()) }
            val serverMissing =
                assertFailsWith<MissingVariableException> {
                    prompton.promptOnServerBlocking("greeting", variables = emptyMap())
                }
            assertEquals("name", localMissing.variable)
            assertEquals("name", serverMissing.variable)

            val unknownEnvironment =
                assertFailsWith<PromptOnApiException> {
                    prompton.promptOnServerBlocking("greeting", environment = "nope")
                }
            assertEquals(404, unknownEnvironment.status)
        }
    }

    @Test
    fun `a batch of monitoring logs is accepted and a resend is a duplicate`() {
        val transport = transport()
        PromptOn(config(transport), FakeClock(instant = Instant.now())).use { prompton ->
            val prompt = prompton.prompt("greeting")
            val messages = prompt.messages(mapOf("name" to "Ada"))

            val ids = listOf(UuidV7.generate(), UuidV7.generate())
            val records =
                listOf(
                    LogRecord(
                        promptKey = prompt.key,
                        model = prompt.model!!,
                        status = LogStatus.OK,
                        startedAt = Instant.now(),
                        id = ids[0],
                        kind = prompt.kind,
                        deploymentRevision = prompt.deploymentRevision,
                        template = prompt.template,
                        promptVersionNumber = prompt.promptVersionNumber,
                        source = prompt.source,
                        provider = prompt.provider,
                        params = prompt.params,
                        input = LogInput(variables = mapOf("name" to "Ada"), messages = messages),
                        output = LogOutput(content = "Hello, Ada!"),
                        finishReason = "stop",
                        stopKind = StopKind.STOP,
                        usage = Usage(inputTokens = 38, outputTokens = 9, costSource = CostSource.UNKNOWN),
                        latencyMs = 842,
                        traceId = "prompton-kotlin-integration",
                    ),
                    LogRecord(
                        promptKey = prompt.key,
                        model = prompt.model!!,
                        status = LogStatus.ERROR,
                        startedAt = Instant.now(),
                        id = ids[1],
                        kind = prompt.kind,
                        error = LogError(ErrorKind.RATE_LIMITED, 429, "rate limited by upstream provider"),
                        latencyMs = 1503,
                        traceId = "prompton-kotlin-integration",
                    ),
                )

            records.forEach { prompton.log(it) }
            val first = prompton.flushBlocking()
            assertEquals(2, first.accepted, "the fixture server should accept both records")
            assertEquals(0, first.rejected)
            assertEquals(0, first.remaining)
            assertEquals(listOf(202), transport.statuses("/logs"))

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
            val prompt = prompton.prompt("greeting")
            val messages = prompt.messages(mapOf("name" to "Ada"))

            val answer =
                prompt.trackBlocking(
                    TrackMeta(
                        variables = mapOf("name" to "Ada"),
                        inputMessages = messages,
                        traceId = "prompton-kotlin-wrapper",
                    ),
                ) { call ->
                    // A fake provider: the SDK never calls one for you.
                    call.result(Result(content = "Hello, Ada!", finishReason = "stop"))
                    "Hello, Ada!"
                }
            assertEquals("Hello, Ada!", answer)

            val result = prompton.flushBlocking()
            assertEquals(1, result.accepted)
        }
    }

    private fun assertMatchesServer(
        promptKey: String,
        template: String?,
        variables: Map<String, Any?>,
    ) {
        val transport = transport()
        PromptOn(config(transport), FakeClock(instant = Instant.now())).use { prompton ->
            val local = prompton.prompt(promptKey, template)
            val server = prompton.promptOnServerBlocking(promptKey, template, variables = variables)

            assertEquals(server.kind, local.kind, "kind")
            assertEquals(server.model, local.model, "model")
            assertEquals(server.modelId, local.modelId, "model_id")
            assertEquals(server.provider, local.provider, "provider")
            assertEquals(server.deploymentId, local.deploymentId, "deployment id")
            assertEquals(server.deploymentRevision, local.deploymentRevision, "deployment revision")
            assertEquals(server.template, local.template, "template")
            assertEquals(server.templateNames, local.templateNames, "template_names")
            assertEquals(server.promptVersionId, local.promptVersionId, "prompt version id")
            assertEquals(server.promptVersionNumber, local.promptVersionNumber, "prompt version number")
            assertEquals(server.params, local.params, "params")
            assertEquals(
                server.providerOptions,
                local.providerOptions,
                "provider options",
            )

            val messages = if (local.kind == PromptKind.CHAT) local.messages(variables) else null
            val text = if (local.kind == PromptKind.TEXT) local.text(variables) else null
            assertEquals(server.messages, messages, "rendered messages")
            assertEquals(server.text, text, "rendered text")
        }
    }
}
