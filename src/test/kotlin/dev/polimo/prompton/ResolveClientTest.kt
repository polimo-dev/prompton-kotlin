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

/** The `POST /resolve` client: the simple path, cached like the snapshot and degrading the same way. */
class ResolveClientTest {
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
          "use_case": "greeting",
          "kind": "chat",
          "deployment": {"id": "0198f2a1-0000-7000-8000-00000000d001", "revision": 3},
          "prompt": "default",
          "prompts": ["default", "ko"],
          "model_id": "0198f2a1-0000-7000-8000-00000000e001",
          "model": "openai/gpt-4o-mini",
          "provider": "openrouter",
          "effective_params": {"temperature": 0.2, "max_tokens": 512},
          "effective_provider_options": {"only": ["OpenAI"]},
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
            if (request.url.endsWith("/resolve")) body.get() else HttpResponse(304)
        }

    @Test
    fun `the raw answer is fetched once and rendered locally`() {
        val response = AtomicReference(HttpResponse(200, emptyMap(), rawAnswer))
        val transport = resolveTransport(response)
        val clock = FakeClock()
        PromptOn(config(transport), clock).use { prompton ->
            val resolution = prompton.resolveRemoteBlocking("greeting")
            assertEquals("openai/gpt-4o-mini", resolution.model)
            assertEquals(3, resolution.deploymentRevision)
            assertEquals(listOf("default", "ko"), resolution.availablePrompts)
            assertEquals("Say hello to Ada.", resolution.render(mapOf("name" to "Ada")).messages!![1].content)

            val body = Ptn.parseObject(transport.posts().single().body!!)
            assertEquals("greeting", (body["use_case"] as JsonPrimitive).content)
            assertNull(body["variables"], "the cached path asks for the raw template")

            repeat(4) { prompton.resolveRemoteBlocking("greeting") }
            assertEquals(1, transport.posts().size, "the answer is cached for the cache TTL")

            clock.advanceMillis(11_000)
            prompton.resolveRemoteBlocking("greeting")
            assertEquals(2, transport.posts().size, "past the TTL it asks again")
        }
    }

    @Test
    fun `each use case prompt and environment is cached separately`() {
        val response = AtomicReference(HttpResponse(200, emptyMap(), rawAnswer))
        val transport = resolveTransport(response)
        PromptOn(config(transport), FakeClock()).use { prompton ->
            prompton.resolveRemoteBlocking("greeting")
            prompton.resolveRemoteBlocking("greeting", prompt = "ko")
            prompton.resolveRemoteBlocking("greeting", environment = "staging")
            prompton.resolveRemoteBlocking("greeting")
            assertEquals(3, transport.posts().size)
        }
    }

    @Test
    fun `a 429 serves the cached answer instead of failing`() {
        val attempts = AtomicInteger()
        val transport =
            StubTransport { request ->
                when {
                    !request.url.endsWith("/resolve") -> HttpResponse(304)
                    attempts.incrementAndGet() == 1 -> HttpResponse(200, emptyMap(), rawAnswer)
                    else -> HttpResponse(429, mapOf("retry-after" to "30"), "")
                }
            }
        val clock = FakeClock()
        PromptOn(config(transport), clock).use { prompton ->
            prompton.resolveRemoteBlocking("greeting")
            clock.advanceMillis(11_000)
            val resolution = prompton.resolveRemoteBlocking("greeting")
            assertEquals("openai/gpt-4o-mini", resolution.model, "the cached answer keeps serving")
        }
    }

    @Test
    fun `an unreachable server serves the cached answer instead of failing`() {
        val attempts = AtomicInteger()
        val transport =
            StubTransport { request ->
                when {
                    !request.url.endsWith("/resolve") -> HttpResponse(304)
                    attempts.incrementAndGet() == 1 -> HttpResponse(200, emptyMap(), rawAnswer)
                    else -> throw java.io.IOException("connection refused")
                }
            }
        val clock = FakeClock()
        PromptOn(config(transport), clock).use { prompton ->
            prompton.resolveRemoteBlocking("greeting")
            clock.advanceMillis(11_000)
            assertEquals("openai/gpt-4o-mini", prompton.resolveRemoteBlocking("greeting").model)
        }
    }

    @Test
    fun `with nothing cached the failure reaches the caller`() {
        val transport = StubTransport { request ->
            if (request.url.endsWith("/resolve")) HttpResponse(503, emptyMap(), "") else HttpResponse(304)
        }
        PromptOn(config(transport), FakeClock()).use { prompton ->
            val error = assertFailsWith<PromptOnApiException> { prompton.resolveRemoteBlocking("greeting") }
            assertEquals(503, error.status)
        }
    }

    @Test
    fun `the smoke-test path sends the variables and is never cached`() {
        val rendered = rawAnswer.replace("Say hello to {{ name }}.", "Say hello to Ada.")
        val transport = StubTransport { request ->
            if (request.url.endsWith("/resolve")) HttpResponse(200, emptyMap(), rendered) else HttpResponse(304)
        }
        PromptOn(config(transport), FakeClock()).use { prompton ->
            repeat(3) {
                val server = prompton.resolveOnServerBlocking("greeting", variables = mapOf("name" to "Ada"))
                assertEquals("Say hello to Ada.", server.messages!![1].content)
                assertEquals("sha256-aaaa", server.etag)
            }
            assertEquals(3, transport.posts().size, "the smoke test always asks the server")
            val body = Ptn.parseObject(transport.posts().last().body!!)
            assertEquals("Ada", ((body["variables"] as JsonObject)["name"] as JsonPrimitive).content)
        }
    }

    @Test
    fun `resolve errors map onto the sdk's own exceptions`() {
        val body = AtomicReference<HttpResponse>()
        val transport = StubTransport { request ->
            if (request.url.endsWith("/resolve")) body.get() else HttpResponse(304)
        }
        PromptOn(config(transport), FakeClock()).use { prompton ->
            body.set(
                HttpResponse(
                    404,
                    emptyMap(),
                    """{"error":{"code":"not_found","message":"unknown use case: nope",
                       "details":{"use_case":"nope"}}}""",
                ),
            )
            assertFailsWith<UnknownUseCaseException> { prompton.resolveOnServerBlocking("nope") }

            body.set(
                HttpResponse(
                    404,
                    emptyMap(),
                    """{"error":{"code":"not_found","message":"no live deployment",
                       "details":{"reason":"unresolved"}}}""",
                ),
            )
            assertFailsWith<UnresolvedUseCaseException> { prompton.resolveOnServerBlocking("draft") }

            body.set(
                HttpResponse(
                    404,
                    emptyMap(),
                    """{"error":{"code":"not_found","message":"no prompt named fr",
                       "details":{"reason":"unknown_prompt","prompt":"fr",
                       "available_prompts":["default","ko"]}}}""",
                ),
            )
            val unknownPrompt =
                assertFailsWith<UnknownPromptException> { prompton.resolveOnServerBlocking("greeting", "fr") }
            assertEquals(listOf("default", "ko"), unknownPrompt.availablePrompts)

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
                    prompton.resolveOnServerBlocking("greeting", variables = emptyMap())
                }
            assertEquals("name", missing.variable)

            body.set(HttpResponse(401, emptyMap(), """{"error":{"code":"unauthorized","message":"invalid key"}}"""))
            val unauthorized = assertFailsWith<PromptOnApiException> { prompton.resolveOnServerBlocking("greeting") }
            assertEquals(401, unauthorized.status)
            assertTrue(unauthorized.message!!.contains("invalid key"))
        }
    }
}
