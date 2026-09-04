package dev.polimo.prompton

import dev.polimo.prompton.internal.Ptn
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** The `POST /use-cases/{key}/prompt` client: the simple path, cached like the use case document. */
class UseCasePromptClientTest {
    private fun config(transport: HttpTransport) =
        PromptOnConfig(
            apiKey = "ptn_fixture_secret",
            host = "https://prompton.test",
            environment = "production",
            project = "fixture",
            cacheTtl = 10.seconds,
            pollingEnabled = false,
            diskCacheEnabled = false,
            transport = transport,
        )

    private val rawAnswer =
        """
        {
          "key": "greeting",
          "kind": "chat",
          "deployment": {"id": "0198f2a1-0000-7000-8000-00000000d001", "revision": 3},
          "prompt": "default",
          "prompt_names": ["default", "ko"],
          "source": "disk",
          "model_id": "0198f2a1-0000-7000-8000-00000000e001",
          "model": "openai/gpt-4o-mini",
          "provider": "openrouter",
          "params": {"temperature": 0.2, "max_tokens": 512},
          "provider_options": {"only": ["OpenAI"]},
          "prompt_version": {"id": "0198f2a1-0000-7000-8000-00000000a001", "number": 2},
          "messages": [
            {"role": "system", "content": "You are a friendly greeter."},
            {"role": "user", "content": "Say hello to {{ name }}."}
          ],
          "warnings": [],
          "etag": "sha256-aaaa"
        }
        """.trimIndent()

    private fun resolveTransport(body: AtomicReference<HttpResponse>) =
        StubTransport { request ->
            if (request.url.contains("/use-cases/") &&
                request.url.endsWith("/prompt")
            ) {
                body.get()
            } else {
                HttpResponse(304)
            }
        }

    @Test
    fun `the raw answer is fetched once and rendered locally`() {
        val response = AtomicReference(HttpResponse(200, emptyMap(), rawAnswer))
        val transport = resolveTransport(response)
        val clock = FakeClock()
        PromptOn(config(transport), clock).use { prompton ->
            val useCase = prompton.useCaseRemoteBlocking("greeting")
            assertEquals("greeting", useCase.key)
            assertEquals("openai/gpt-4o-mini", useCase.model)
            assertEquals(3, useCase.deploymentRevision)
            assertEquals(listOf("default", "ko"), useCase.promptNames)
            assertEquals(UseCaseSource.DISK, useCase.source)
            assertEquals("Say hello to Ada.", useCase.messages(mapOf("name" to "Ada"))[1].content)

            val body = Ptn.parseObject(transport.posts().single().body!!)
            assertNull(body["variables"], "the cached path asks for the raw template")
            assertNull(body["use_case"], "the use case key lives in the path")
            assertEquals("https://prompton.test/api/v1/use-cases/greeting/prompt", transport.posts().single().url)

            repeat(4) { prompton.useCaseRemoteBlocking("greeting") }
            assertEquals(1, transport.posts().size, "the answer is cached for the cache TTL")

            clock.advanceMillis(11_000)
            prompton.useCaseRemoteBlocking("greeting")
            assertEquals(2, transport.posts().size, "past the TTL it asks again")
        }
    }

    @Test
    fun `each use case prompt and environment is cached separately`() {
        val response = AtomicReference(HttpResponse(200, emptyMap(), rawAnswer))
        val transport = resolveTransport(response)
        PromptOn(config(transport), FakeClock()).use { prompton ->
            prompton.useCaseRemoteBlocking("greeting")
            prompton.useCaseRemoteBlocking("greeting", prompt = "ko")
            prompton.useCaseRemoteBlocking("greeting", environment = "staging")
            prompton.useCaseRemoteBlocking("greeting")
            assertEquals(3, transport.posts().size)
        }
    }

    @Test
    fun `a 429 serves the cached answer instead of failing`() {
        val attempts = AtomicInteger()
        val transport =
            StubTransport { request ->
                when {
                    !(request.url.contains("/use-cases/") && request.url.endsWith("/prompt")) -> HttpResponse(304)
                    attempts.incrementAndGet() == 1 -> HttpResponse(200, emptyMap(), rawAnswer)
                    else -> HttpResponse(429, mapOf("retry-after" to "30"), "")
                }
            }
        val clock = FakeClock()
        PromptOn(config(transport), clock).use { prompton ->
            prompton.useCaseRemoteBlocking("greeting")
            clock.advanceMillis(11_000)
            val useCase = prompton.useCaseRemoteBlocking("greeting")
            assertEquals("openai/gpt-4o-mini", useCase.model, "the cached answer keeps serving")
        }
    }

    @Test
    fun `a rate-limited prompt is not asked again before retry-after`() {
        val attempts = AtomicInteger()
        val transport =
            StubTransport { request ->
                when {
                    !(request.url.contains("/use-cases/") && request.url.endsWith("/prompt")) -> HttpResponse(304)
                    attempts.incrementAndGet() == 1 -> HttpResponse(200, emptyMap(), rawAnswer)
                    else -> HttpResponse(429, mapOf("retry-after" to "30"), "")
                }
            }
        val clock = FakeClock()
        PromptOn(config(transport), clock).use { prompton ->
            prompton.useCaseRemoteBlocking("greeting")
            clock.advanceMillis(11_000)

            repeat(10) {
                assertEquals("openai/gpt-4o-mini", prompton.useCaseRemoteBlocking("greeting").model)
            }
            assertEquals(2, transport.posts().size, "one 429, then silence for Retry-After")

            clock.advanceMillis(31_000)
            prompton.useCaseRemoteBlocking("greeting")
            assertEquals(3, transport.posts().size, "past Retry-After it asks once more")
        }
    }

    @Test
    fun `an unreachable prompt backs off instead of asking on every call`() {
        val attempts = AtomicInteger()
        val transport =
            StubTransport { request ->
                when {
                    !(request.url.contains("/use-cases/") && request.url.endsWith("/prompt")) -> HttpResponse(304)
                    attempts.incrementAndGet() == 1 -> HttpResponse(200, emptyMap(), rawAnswer)
                    else -> throw java.io.IOException("connection refused")
                }
            }
        val clock = FakeClock()
        PromptOn(config(transport), clock).use { prompton ->
            prompton.useCaseRemoteBlocking("greeting")

            clock.advanceMillis(11_000)
            repeat(5) { prompton.useCaseRemoteBlocking("greeting") }
            assertEquals(2, transport.posts().size, "one failed attempt, then the backoff holds")

            clock.advanceMillis(5_000)
            prompton.useCaseRemoteBlocking("greeting")
            assertEquals(2, transport.posts().size, "still inside the ten second window")

            clock.advanceMillis(6_000)
            prompton.useCaseRemoteBlocking("greeting")
            assertEquals(3, transport.posts().size, "the backoff has passed")

            clock.advanceMillis(11_000)
            repeat(3) { prompton.useCaseRemoteBlocking("greeting") }
            assertEquals(3, transport.posts().size, "the second backoff doubles to twenty seconds")
        }
    }

    @Test
    fun `an unreachable server serves the cached answer instead of failing`() {
        val attempts = AtomicInteger()
        val transport =
            StubTransport { request ->
                when {
                    !(request.url.contains("/use-cases/") && request.url.endsWith("/prompt")) -> HttpResponse(304)
                    attempts.incrementAndGet() == 1 -> HttpResponse(200, emptyMap(), rawAnswer)
                    else -> throw java.io.IOException("connection refused")
                }
            }
        val clock = FakeClock()
        PromptOn(config(transport), clock).use { prompton ->
            prompton.useCaseRemoteBlocking("greeting")
            clock.advanceMillis(11_000)
            assertEquals("openai/gpt-4o-mini", prompton.useCaseRemoteBlocking("greeting").model)
        }
    }

    @Test
    fun `with nothing cached the failure reaches the caller`() {
        val transport = StubTransport { request ->
            if (request.url.contains("/use-cases/") &&
                request.url.endsWith("/prompt")
            ) {
                HttpResponse(503, emptyMap(), "")
            } else {
                HttpResponse(304)
            }
        }
        val clock = FakeClock()
        PromptOn(config(transport), clock).use { prompton ->
            val error = assertFailsWith<PromptOnApiException> { prompton.useCaseRemoteBlocking("greeting") }
            assertEquals(503, error.status)

            repeat(3) {
                val blocked = assertFailsWith<PromptOnException> { prompton.useCaseRemoteBlocking("greeting") }
                assertTrue(blocked.message!!.contains("nothing is cached"), blocked.message!!)
            }
            assertEquals(1, transport.posts().size, "a failing server is not called again inside the window")

            clock.advanceMillis(11_000)
            assertFailsWith<PromptOnApiException> { prompton.useCaseRemoteBlocking("greeting") }
            assertEquals(2, transport.posts().size, "past the backoff it tries once more")
        }
    }

    @Test
    fun `a client error is not treated as a load problem`() {
        val notFound =
            """{"error":{"code":"not_found","message":"unknown use case: nope","details":{"key":"nope"}}}"""
        val transport =
            StubTransport { request ->
                if (request.url.contains("/use-cases/") &&
                    request.url.endsWith("/prompt")
                ) {
                    HttpResponse(404, emptyMap(), notFound)
                } else {
                    HttpResponse(304)
                }
            }
        PromptOn(config(transport), FakeClock()).use { prompton ->
            repeat(3) {
                val error = assertFailsWith<UnknownUseCaseException> { prompton.useCaseRemoteBlocking("nope") }
                assertEquals("nope", error.useCase)
            }
            assertEquals(3, transport.posts().size, "a 4xx is about the request, so every call asks")
        }
    }

    @Test
    fun `the smoke-test path sends the variables and is never cached`() {
        val rendered = rawAnswer.replace("Say hello to {{ name }}.", "Say hello to Ada.")
        val transport = StubTransport { request ->
            if (request.url.contains("/use-cases/") &&
                request.url.endsWith("/prompt")
            ) {
                HttpResponse(200, emptyMap(), rendered)
            } else {
                HttpResponse(304)
            }
        }
        PromptOn(config(transport), FakeClock()).use { prompton ->
            repeat(3) {
                val server = prompton.promptOnServerBlocking("greeting", variables = mapOf("name" to "Ada"))
                assertEquals("greeting", server.key)
                assertEquals(UseCaseSource.DISK, server.source)
                assertEquals("Say hello to Ada.", server.messages!![1].content)
                assertEquals("sha256-aaaa", server.etag)
            }
            assertEquals(3, transport.posts().size, "the smoke test always asks the server")
            val body = Ptn.parseObject(transport.posts().last().body!!)
            assertEquals("Ada", ((body["variables"] as JsonObject)["name"] as JsonPrimitive).content)
            assertNull(body["use_case"], "the use case key lives in the path")
            assertNull(body["key"], "the use case key lives in the path")
        }
    }

    @Test
    fun `use case prompt errors map onto the sdk's own exceptions`() {
        val body = AtomicReference<HttpResponse>()
        val transport = StubTransport { request ->
            if (request.url.contains("/use-cases/") &&
                request.url.endsWith("/prompt")
            ) {
                body.get()
            } else {
                HttpResponse(304)
            }
        }
        PromptOn(config(transport), FakeClock()).use { prompton ->
            body.set(
                HttpResponse(
                    404,
                    emptyMap(),
                    """{"error":{"code":"not_found","message":"unknown use case: nope",
                       "details":{"key":"nope"}}}""",
                ),
            )
            val unknownUseCase = assertFailsWith<UnknownUseCaseException> { prompton.promptOnServerBlocking("nope") }
            assertEquals("nope", unknownUseCase.useCase)

            body.set(
                HttpResponse(
                    404,
                    emptyMap(),
                    """{"error":{"code":"not_found","message":"unknown use case",
                       "details":{"reason":"unknown_use_case"}}}""",
                ),
            )
            val reasonOnly = assertFailsWith<UnknownUseCaseException> { prompton.promptOnServerBlocking("missing") }
            assertEquals("missing", reasonOnly.useCase)

            body.set(
                HttpResponse(
                    404,
                    emptyMap(),
                    """{"error":{"code":"not_found","message":"no live deployment",
                       "details":{"reason":"unresolved"}}}""",
                ),
            )
            assertFailsWith<UnresolvedUseCaseException> { prompton.promptOnServerBlocking("draft") }

            body.set(
                HttpResponse(
                    404,
                    emptyMap(),
                    """{"error":{"code":"not_found","message":"no prompt named fr",
                       "details":{"reason":"unknown_prompt","key":"greeting","prompt":"fr",
                       "prompt_names":["default","ko"]}}}""",
                ),
            )
            val unknownPrompt =
                assertFailsWith<UnknownPromptException> { prompton.promptOnServerBlocking("greeting", "fr") }
            assertEquals("greeting", unknownPrompt.useCase)
            assertEquals(listOf("default", "ko"), unknownPrompt.promptNames)

            body.set(
                HttpResponse(
                    404,
                    emptyMap(),
                    """{"error":{"code":"not_found","message":"no prompt named fr",
                       "details":{"reason":"unknown_prompt","prompt":"fr",
                       "prompt_names":["default","ko"]}}}""",
                ),
            )
            val fallbackUnknownPrompt =
                assertFailsWith<UnknownPromptException> { prompton.promptOnServerBlocking("path-key", "fr") }
            assertEquals("path-key", fallbackUnknownPrompt.useCase)

            body.set(
                HttpResponse(
                    400,
                    emptyMap(),
                    """{"error":{"code":"invalid_request","message":"missing variable: name",
                       "details":{"missing_variable":"name"}}}""",
                ),
            )
            val missing =
                assertFailsWith<MissingVariableException> {
                    prompton.promptOnServerBlocking("greeting", variables = emptyMap())
                }
            assertEquals("name", missing.variable)

            body.set(HttpResponse(401, emptyMap(), """{"error":{"code":"unauthorized","message":"invalid key"}}"""))
            val unauthorized = assertFailsWith<PromptOnApiException> { prompton.promptOnServerBlocking("greeting") }
            assertEquals(401, unauthorized.status)
            assertTrue(unauthorized.message!!.contains("invalid key"))
        }
    }
}
