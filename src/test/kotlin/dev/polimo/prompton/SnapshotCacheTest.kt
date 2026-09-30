package dev.polimo.prompton

import com.sun.net.httpserver.HttpServer
import dev.polimo.prompton.internal.Ptn
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Demand config fetch: per-key TTL, attempt gate, stale fallback and single-flight. */
class SnapshotCacheTest {
    @TempDir
    lateinit var tempDir: Path

    private fun config(
        transport: HttpTransport,
        cacheTtl: kotlin.time.Duration = 10.seconds,
    ) = PromptOnConfig(
        apiKey = "ptn_fixture_secret",
        host = "https://prompton.test",
        environment = "production",
        project = "fixture",
        cacheTtl = cacheTtl,
        diskCacheEnabled = false,
        transport = transport,
    )

    private fun okResponse(
        body: String = SnapshotFixtures.useCaseDocument(),
        etag: String = SnapshotFixtures.PRODUCTION_ETAG,
    ) = HttpResponse(200, mapOf("etag" to etag, "last-modified" to "Fri, 04 Sep 2026 00:21:48 GMT"), body)

    @Test
    fun `startup and idle do not fetch until a key is resolved`() {
        val transport = StubTransport { okResponse() }
        PromptOn(config(transport), FakeClock()).use { prompton ->
            assertEquals(0, transport.requestCount())
            assertEquals("openai/gpt-4o-mini", prompton.useCase("greeting").model)
            assertEquals(1, transport.requestCount())
            assertEquals(
                "https://prompton.test/api/v1/prompts/greeting?environment=production",
                transport.lastRequest().url,
            )
        }
    }

    @Test
    fun `fresh cache hit does not fetch again within ttl`() {
        val transport = StubTransport { okResponse() }
        PromptOn(config(transport), FakeClock()).use { prompton ->
            repeat(5) { assertEquals("openai/gpt-4o-mini", prompton.useCase("greeting").model) }
            assertEquals(1, transport.requestCount())
        }
    }

    @Test
    fun `expired key fetches with its own etag and leaves other keys independent`() {
        val transport =
            StubTransport { request ->
                if (request.headers["if-none-match"] == SnapshotFixtures.PRODUCTION_ETAG) {
                    HttpResponse(304)
                } else {
                    okResponse(
                        etag =
                            if (request.url.contains("/summarize?")) {
                                "\"summarize-v1\""
                            } else {
                                SnapshotFixtures.PRODUCTION_ETAG
                            },
                    )
                }
            }
        val clock = FakeClock()
        PromptOn(config(transport, 40.milliseconds), clock).use { prompton ->
            prompton.useCase("greeting")
            prompton.useCase("summarize")
            clock.advanceMillis(10_100)
            prompton.useCase("greeting")

            assertEquals(3, transport.requestCount())
            assertEquals(SnapshotFixtures.PRODUCTION_ETAG, transport.lastRequest().headers["if-none-match"])
            assertTrue(transport.lastRequest().url.contains("/prompts/greeting?"))
        }
    }

    @Test
    fun `fetching another key does not mutate fresh cached key`() {
        val transport =
            StubTransport { request ->
                if (request.url.contains("/summarize?")) {
                    okResponse(
                        SnapshotFixtures.useCaseDocument(systemPrompt = "Updated greeter."),
                        etag = "\"summarize-v2\"",
                    )
                } else {
                    okResponse(
                        SnapshotFixtures.useCaseDocument(systemPrompt = "Original greeter."),
                        etag = "\"greeting-v1\"",
                    )
                }
            }
        PromptOn(config(transport, 10.seconds), FakeClock()).use { prompton ->
            assertEquals("Original greeter.", prompton.useCase("greeting").messageTemplates!![0].content)
            assertEquals(
                "Summarize:\\n{% for item in items %}- {{ item }}\\n{% endfor %}",
                prompton.useCase("summarize").textTemplate,
            )
            assertEquals(
                "Original greeter.",
                prompton.useCase("greeting").messageTemplates!![0].content,
                "a summarize fetch must not aggregate-merge and mutate greeting's fresh cache",
            )
            assertEquals(2, transport.requestCount())
        }
    }

    @Test
    fun `failed fetch starts attempt gate and returns stale`() {
        val calls = AtomicInteger()
        val transport =
            StubTransport {
                if (calls.incrementAndGet() == 1) okResponse() else HttpResponse(503, emptyMap(), "")
            }
        val clock = FakeClock()
        PromptOn(config(transport, 80.milliseconds), clock).use { prompton ->
            prompton.useCase("greeting")
            clock.advanceMillis(10_100)
            assertEquals("openai/gpt-4o-mini", prompton.useCase("greeting").model)
            assertEquals(2, calls.get())
            assertTrue(prompton.useCaseDocumentInfo().stale)

            repeat(5) { prompton.useCase("greeting") }
            assertEquals(2, calls.get())
        }
    }

    @Test
    fun `timeout falls back to expired cache within one second and ignores late response`() {
        val transport =
            StubTransport {
                Thread.sleep(1_500)
                okResponse(SnapshotFixtures.useCaseDocument(systemPrompt = "Late prompt."))
            }
        PromptOn(
            PromptOnConfig(
                apiKey = "ptn_fixture_secret",
                host = "https://prompton.test",
                environment = "production",
                project = "fixture",
                diskCacheEnabled = false,
                bundlePath = writeBundle(),
                requestTimeout = 5.seconds,
                transport = transport,
            ),
        ).use { prompton ->
            val started = System.nanoTime()
            assertEquals("You are a friendly greeter.", prompton.useCase("greeting").messageTemplates!![0].content)
            val elapsed = (System.nanoTime() - started) / 1_000_000
            assertTrue(elapsed < 1_250, "stale fallback waited ${elapsed}ms")
            Thread.sleep(700)
            assertEquals("You are a friendly greeter.", prompton.useCase("greeting").messageTemplates!![0].content)
        }
    }

    @Test
    fun `cold failure without fallback is explicit and gated`() {
        val transport = StubTransport { HttpResponse(503, emptyMap(), "") }
        PromptOn(config(transport), FakeClock()).use { prompton ->
            assertFailsWith<UseCaseDocumentUnavailableException> { prompton.useCase("greeting") }
            assertEquals(1, transport.requestCount())
            assertFailsWith<UseCaseDocumentUnavailableException> { prompton.useCase("greeting") }
            assertEquals(1, transport.requestCount())
        }
    }

    @Test
    fun `same key concurrent callers share one fetch`() {
        val transport =
            StubTransport {
                Thread.sleep(150)
                okResponse()
            }
        PromptOn(config(transport), FakeClock()).use { prompton ->
            runConcurrent(24) {
                assertEquals("openai/gpt-4o-mini", prompton.useCase("greeting").model)
            }
            assertEquals(1, transport.requestCount())
        }
    }

    @Test
    fun `different keys do not wait behind each other`() {
        val transport =
            StubTransport { request ->
                if (request.url.contains("/greeting?")) Thread.sleep(900)
                okResponse(SnapshotFixtures.twoUseCases(), etag = "\"${request.url}\"")
            }
        PromptOn(config(transport), FakeClock()).use { prompton ->
            val pool = Executors.newFixedThreadPool(2)
            try {
                val slow = pool.submit<String> { prompton.useCase("greeting").model!! }
                Thread.sleep(50)
                val started = System.nanoTime()
                assertEquals("openai/gpt-4o-mini", prompton.useCase("summarize").model)
                val elapsed = (System.nanoTime() - started) / 1_000_000
                assertTrue(elapsed < 400, "summarize waited behind greeting for ${elapsed}ms")
                assertEquals("openai/gpt-4o-mini", slow.get(3, TimeUnit.SECONDS))
            } finally {
                pool.shutdownNow()
            }
        }
    }

    @Test
    fun `scope mismatch is rejected and bundle fallback keeps serving`() {
        val transport = StubTransport { okResponse(SnapshotFixtures.useCaseDocument(environment = "staging")) }
        val clock = FakeClock()
        PromptOn(
            PromptOnConfig(
                apiKey = "ptn_fixture_secret",
                host = "https://prompton.test",
                environment = "production",
                project = "fixture",
                cacheTtl = 50.milliseconds,
                diskCacheEnabled = false,
                bundlePath = writeBundle(),
                transport = transport,
            ),
            clock,
        ).use { prompton ->
            clock.advanceMillis(10_100)
            assertEquals(UseCaseSource.BUNDLE, prompton.useCase("greeting").source)
            assertTrue(prompton.useCaseDocumentInfo().stale)
        }
    }

    @Test
    fun `restart loads each persisted prompt from its own disk entry when remote fails`() {
        val cache = tempDir.resolve("snapshot.json")
        val transport =
            StubTransport { request ->
                if (request.url.contains("/summarize?")) {
                    okResponse(
                        SnapshotFixtures.useCaseDocument(systemPrompt = "Updated greeter."),
                        etag = "\"summarize-v2\"",
                    )
                } else {
                    okResponse(
                        SnapshotFixtures.useCaseDocument(systemPrompt = "Original greeter."),
                        etag = "\"greeting-v1\"",
                    )
                }
            }
        PromptOn(config(transport).copy(diskCacheEnabled = true, diskCachePath = cache), FakeClock()).use { prompton ->
            assertEquals("Original greeter.", prompton.useCase("greeting").messageTemplates!![0].content)
            assertEquals(
                "Summarize:\\n{% for item in items %}- {{ item }}\\n{% endfor %}",
                prompton.useCase("summarize").textTemplate,
            )
        }

        val failing = StubTransport { HttpResponse(503, emptyMap(), "") }
        PromptOn(config(failing).copy(diskCacheEnabled = true, diskCachePath = cache), FakeClock()).use { prompton ->
            assertEquals("Original greeter.", prompton.useCase("greeting").messageTemplates!![0].content)
            assertEquals(
                "Summarize:\\n{% for item in items %}- {{ item }}\\n{% endfor %}",
                prompton.useCase("summarize").textTemplate,
            )
            val entries = Ptn.asObject(Ptn.parseObject(Files.readString(cache))["entries"])!!
            assertEquals(setOf("greeting", "summarize"), entries.keys)
        }
    }

    @Test
    fun `refresh does not restore bulk polling`() {
        val transport = StubTransport { okResponse() }
        PromptOn(config(transport), FakeClock()).use { prompton ->
            assertEquals(false, prompton.refreshBlocking())
            assertEquals(0, transport.requestCount())
        }
    }

    @Test
    fun `default transport enforces one second config timeout against a slow local server`() {
        SlowServer(1_500, SnapshotFixtures.useCaseDocument()).use { server ->
            PromptOn(
                PromptOnConfig(
                    apiKey = "ptn_fixture_secret",
                    host = server.host,
                    environment = "production",
                    project = "fixture",
                    diskCacheEnabled = false,
                    bundlePath = writeBundle(),
                    requestTimeout = 5.seconds,
                ),
            ).use { prompton ->
                val started = System.nanoTime()
                assertEquals("openai/gpt-4o-mini", prompton.useCase("greeting").model)
                val elapsed = (System.nanoTime() - started) / 1_000_000
                assertTrue(elapsed < 1_250, "default transport waited ${elapsed}ms")
            }
        }
    }

    private fun writeBundle(): Path {
        val path = tempDir.resolve("prompts.production.json")
        SnapshotFilesForTest.write(path, SnapshotFixtures.useCaseDocument())
        return path
    }

    private fun runConcurrent(
        threads: Int,
        block: () -> Unit,
    ) {
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            repeat(threads) {
                pool.execute {
                    try {
                        start.await()
                        block()
                    } finally {
                        done.countDown()
                    }
                }
            }
            start.countDown()
            assertTrue(done.await(10, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }
    }
}

private object SnapshotFilesForTest {
    fun write(
        path: Path,
        body: String,
    ) {
        Files
            .createDirectories(path.parent)
        Files
            .writeString(path, body)
        Files
            .writeString(
                path.resolveSibling("${path.fileName}.meta.json"),
                """{"environment":"production","project":"fixture"}""",
            )
    }
}

private class SlowServer(
    private val delayMillis: Long,
    private val body: String,
) : AutoCloseable {
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    val host: String
        get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/") { exchange ->
            Thread.sleep(delayMillis)
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("content-type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    override fun close() {
        server.stop(0)
    }
}
