package dev.polimo.prompton

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The caching rules: a 10-second window served from memory, background revalidation with
 * `If-None-Match`, and a refresh that can never block or fail a log.
 */
class SnapshotCacheTest {
    @TempDir
    lateinit var tempDir: Path

    private fun config(
        transport: HttpTransport,
        cacheTtl: kotlin.time.Duration = 10.seconds,
    ) = PromptOnConfig(
        apiKey = "ptn_fixture_secret",
        host = "https://renderon.test",
        environment = "production",
        project = "fixture",
        cacheTtl = cacheTtl,
        pollingEnabled = false,
        diskCacheEnabled = false,
        transport = transport,
    )

    private fun okResponse(
        body: String = SnapshotFixtures.useCaseDocument(),
        etag: String = SnapshotFixtures.PRODUCTION_ETAG,
    ) = HttpResponse(200, mapOf("etag" to etag, "last-modified" to "Fri, 04 Sep 2026 00:21:48 GMT"), body)

    @Test
    fun `inside the cache ttl every resolve is served from memory`() {
        val transport = StubTransport { okResponse() }
        val clock = FakeClock()
        PromptOn(config(transport), clock).use { prompton ->
            repeat(5) { assertEquals("openai/gpt-4o-mini", prompton.useCase("greeting").model) }
            assertEquals(1, transport.requestCount(), "one fetch for five resolves inside the TTL")
            assertStaysTrue("no background fetch inside the TTL") { transport.requestCount() == 1 }
        }
    }

    @Test
    fun `past the ttl the next resolve revalidates with if-none-match`() {
        val transport =
            StubTransport { request ->
                if (request.headers.containsKey("if-none-match")) HttpResponse(304) else okResponse()
            }
        val clock = FakeClock()
        PromptOn(config(transport), clock).use { prompton ->
            prompton.useCase("greeting")
            assertEquals(1, transport.requestCount())

            clock.advanceMillis(11_000)
            prompton.useCase("greeting")
            await("the background revalidation") { transport.requestCount() == 2 }

            val revalidation = transport.lastRequest()
            assertEquals(SnapshotFixtures.PRODUCTION_ETAG, revalidation.headers["if-none-match"])
            assertEquals("https://renderon.test/api/v1/renders?environment=production", revalidation.url)
            assertEquals("Bearer ptn_fixture_secret", revalidation.headers["authorization"])
            assertTrue(revalidation.headers["user-agent"]!!.startsWith("prompton-kotlin/"))

            await("the 304 to be absorbed") { !prompton.useCaseDocumentInfo().stale }
            assertEquals(UseCaseSource.REMOTE, prompton.useCaseDocumentInfo().source)
        }
    }

    @Test
    fun `a new document replaces the old one`() {
        val body = AtomicReference(SnapshotFixtures.useCaseDocument(temperature = 0.2))
        val etag = AtomicReference(SnapshotFixtures.PRODUCTION_ETAG)
        val transport = StubTransport { okResponse(body.get(), etag.get()) }
        val clock = FakeClock()
        PromptOn(config(transport), clock).use { prompton ->
            assertEquals(0.2, prompton.useCase("greeting").params["temperature"])

            body.set(SnapshotFixtures.useCaseDocument(temperature = 0.9))
            etag.set(SnapshotFixtures.UPDATED_ETAG)
            clock.advanceMillis(11_000)
            prompton.useCase("greeting")

            await("the new document") { prompton.useCaseDocumentInfo().etag == SnapshotFixtures.UPDATED_ETAG }
            assertEquals(0.9, prompton.useCase("greeting").params["temperature"])
        }
    }

    @Test
    fun `a failing refresh keeps serving the previous document`() {
        val attempts = AtomicInteger()
        val transport =
            StubTransport { _ ->
                if (attempts.incrementAndGet() == 1) okResponse() else HttpResponse(500, emptyMap(), "boom")
            }
        val clock = FakeClock()
        PromptOn(config(transport), clock).use { prompton ->
            prompton.useCase("greeting")

            clock.advanceMillis(11_000)
            assertEquals("openai/gpt-4o-mini", prompton.useCase("greeting").model)
            await("the failed refresh") { transport.requestCount() == 2 }
            settle()

            assertEquals("openai/gpt-4o-mini", prompton.useCase("greeting").model)
            assertTrue(prompton.useCaseDocumentInfo().stale, "the entry is marked stale after a failed refresh")
            assertNotNull(prompton.useCaseDocumentInfo().etag)
        }
    }

    @Test
    fun `a transport that throws never reaches the caller`() {
        val attempts = AtomicInteger()
        val transport =
            StubTransport { _ ->
                if (attempts.incrementAndGet() == 1) okResponse() else throw java.io.IOException("connection reset")
            }
        val clock = FakeClock()
        PromptOn(config(transport), clock).use { prompton ->
            prompton.useCase("greeting")
            clock.advanceMillis(11_000)
            assertEquals("openai/gpt-4o-mini", prompton.useCase("greeting").model)
            await("the failed refresh") { transport.requestCount() == 2 }
            assertEquals("openai/gpt-4o-mini", prompton.useCase("greeting").model)
        }
    }

    @Test
    fun `a 429 pauses the poller for retry-after and the caller sees no error`() {
        val attempts = AtomicInteger()
        val transport =
            StubTransport { _ ->
                if (attempts.incrementAndGet() == 1) {
                    okResponse()
                } else {
                    HttpResponse(429, mapOf("retry-after" to "30"), errorBody("rate_limited", "slow down"))
                }
            }
        val clock = FakeClock()
        PromptOn(config(transport), clock).use { prompton ->
            prompton.useCase("greeting")

            clock.advanceMillis(11_000)
            prompton.useCase("greeting")
            await("the rate-limited refresh") { transport.requestCount() == 2 }
            settle()

            clock.advanceMillis(11_000)
            assertEquals("openai/gpt-4o-mini", prompton.useCase("greeting").model)
            assertStaysTrue("no request before Retry-After elapses") { transport.requestCount() == 2 }

            clock.advanceMillis(31_000)
            prompton.useCase("greeting")
            await("the retry after the pause") { transport.requestCount() == 3 }
        }
    }

    @Test
    fun `a 429 without a header honours error details retry_after`() {
        val attempts = AtomicInteger()
        val transport =
            StubTransport { _ ->
                if (attempts.incrementAndGet() == 1) {
                    okResponse()
                } else {
                    HttpResponse(
                        429,
                        emptyMap(),
                        """{"error":{"code":"rate_limited","message":"slow down","details":{"retry_after":45}}}""",
                    )
                }
            }
        val clock = FakeClock()
        PromptOn(config(transport), clock).use { prompton ->
            prompton.useCase("greeting")
            clock.advanceMillis(11_000)
            prompton.useCase("greeting")
            await("the rate-limited refresh") { transport.requestCount() == 2 }
            settle()

            clock.advanceMillis(40_000)
            prompton.useCase("greeting")
            assertStaysTrue("still inside the 45 second pause") { transport.requestCount() == 2 }

            clock.advanceMillis(6_000)
            prompton.useCase("greeting")
            await("the retry after 45 seconds") { transport.requestCount() == 3 }
        }
    }

    @Test
    fun `failures back off by doubling from the cache ttl up to five minutes`() {
        val attempts = AtomicInteger()
        val transport =
            StubTransport { _ ->
                if (attempts.incrementAndGet() == 1) okResponse() else HttpResponse(503, emptyMap(), "")
            }
        val clock = FakeClock()
        PromptOn(config(transport), clock).use { prompton ->
            prompton.useCase("greeting")

            // The cache TTL brings the first (failing) refresh.
            clock.advanceMillis(10_000)
            prompton.useCase("greeting")
            await("the first refresh") { transport.requestCount() == 2 }
            settle()

            // From there each failure doubles the wait, from the TTL up to the five-minute cap.
            var expected = 3
            for (gapSeconds in listOf(10L, 20L, 40L, 80L, 160L, 300L, 300L)) {
                clock.advanceMillis(gapSeconds * 1000 - 1)
                prompton.useCase("greeting")
                assertStaysTrue("no retry before ${gapSeconds}s", 100) {
                    transport.requestCount() == expected - 1
                }
                clock.advanceMillis(1)
                prompton.useCase("greeting")
                await("the retry after ${gapSeconds}s") { transport.requestCount() == expected }
                settle()
                expected += 1
            }
        }
    }

    @Test
    fun `a slow refresh never blocks a resolve`() {
        val attempts = AtomicInteger()
        val transport =
            StubTransport { _ ->
                if (attempts.incrementAndGet() > 1) Thread.sleep(1_500)
                okResponse()
            }
        val clock = FakeClock()
        PromptOn(config(transport), clock).use { prompton ->
            prompton.useCase("greeting")
            clock.advanceMillis(11_000)

            val startedAt = System.nanoTime()
            repeat(20) { prompton.useCase("greeting") }
            val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000
            assertTrue(elapsedMillis < 500, "20 resolves took ${elapsedMillis}ms while a refresh was in flight")
        }
    }

    @Test
    fun `fetch once now does not call a server that is rate-limiting`() {
        val attempts = AtomicInteger()
        val transport =
            StubTransport { _ ->
                if (attempts.incrementAndGet() == 1) {
                    okResponse()
                } else {
                    HttpResponse(429, mapOf("retry-after" to "60"), errorBody("rate_limited", "slow down"))
                }
            }
        val clock = FakeClock()
        PromptOn(config(transport), clock).use { prompton ->
            prompton.useCase("greeting")
            await("the start-up fetch") { transport.requestCount() == 1 }

            clock.advanceMillis(11_000)
            prompton.refreshBlocking()
            assertEquals(2, transport.requestCount(), "the refresh ran and was rate-limited")

            repeat(5) { assertTrue(prompton.refreshBlocking(), "the cached document keeps serving") }
            assertEquals(2, transport.requestCount(), "a rate-limited server is not asked again")
            assertEquals("openai/gpt-4o-mini", prompton.useCase("greeting").model)

            prompton.refreshBlocking(force = true)
            assertEquals(3, transport.requestCount(), "force is the deliberate way through the window")

            clock.advanceMillis(61_000)
            prompton.refreshBlocking()
            assertEquals(4, transport.requestCount(), "past Retry-After it asks again")
        }
    }

    @Test
    fun `with nothing cached anywhere useCase fails with a clear message`() {
        val transport = StubTransport { HttpResponse(503, emptyMap(), "") }
        PromptOn(config(transport), FakeClock()).use { prompton ->
            val error = assertFailsWith<UseCaseDocumentUnavailableException> { prompton.useCase("greeting") }
            assertTrue(error.message!!.contains("unreachable"), error.message!!)
            assertTrue(error.message!!.contains("production"), error.message!!)
        }
    }

    @Test
    fun `fetch once now is synchronous and export writes a bundle`() {
        val transport = StubTransport { okResponse() }
        val bundle = tempDir.resolve("prompts.production.json")
        PromptOn(config(transport).copy(pollingEnabled = false), FakeClock()).use { prompton ->
            assertTrue(prompton.refreshBlocking())
            prompton.exportUseCaseDocument(bundle)
        }
        val exported = UseCaseDocument.parse(
            java.nio.file.Files
                .readString(bundle),
        )
        assertEquals("production", exported.environment)
        assertEquals("fixture", exported.project)
        assertTrue(
            java.nio.file.Files
                .exists(tempDir.resolve("prompts.production.json.meta.json")),
        )
    }

    private fun errorBody(
        code: String,
        message: String,
    ) = """{"error":{"code":"$code","message":"$message","details":{}}}"""
}
