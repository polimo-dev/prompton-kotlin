package dev.polimo.prompton

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The monitoring-log queue: flush triggers, batch caps, retries, splitting, dropping and draining. */
class LogBufferTest {
    private val startedAt: Instant = Instant.parse("2026-09-04T09:00:00Z")

    private fun config(
        transport: HttpTransport,
        log: LogOptions = LogOptions(flushInterval = 60.seconds, flushSize = 1_000, flushBytes = 100_000_000),
        environment: String = "production",
    ) = PromptOnConfig(
        apiKey = "ptn_fixture_secret",
        host = "https://prompton.test",
        environment = environment,
        project = "fixture",
        pollingEnabled = false,
        diskCacheEnabled = false,
        log = log,
        transport = transport,
    )

    private fun record(
        index: Int,
        status: LogStatus = LogStatus.OK,
    ) = LogRecord(
        useCase = "greeting",
        model = "openai/gpt-4o-mini",
        status = status,
        startedAt = startedAt,
        id = UuidV7.generate(),
        traceId = "trace-$index",
    )

    private fun accepted(count: Int) =
        HttpResponse(202, emptyMap(), """{"accepted":$count,"duplicates":0,"rejected":[]}""")

    /**
     * A transport that serves the snapshot on `GET` and hands every `POST /logs` to
     * [onPost], so a log assertion never counts the snapshot fetch.
     */
    private fun transport(onPost: (HttpRequest) -> HttpResponse): StubTransport =
        StubTransport { request ->
            if (request.method == "GET") {
                HttpResponse(
                    200,
                    mapOf("etag" to SnapshotFixtures.PRODUCTION_ETAG),
                    SnapshotFixtures.useCaseDocument(),
                )
            } else {
                onPost(request)
            }
        }

    private fun batchOf(request: HttpRequest): List<JsonObject> =
        (
            (
                dev.polimo.prompton.internal.Ptn
                    .parseObject(request.body!!)["logs"]
            ) as JsonArray
        ).map { it as JsonObject }

    private fun idsOf(request: HttpRequest): List<String> =
        batchOf(request).map { (it["id"] as JsonPrimitive).content }

    @Test
    fun `a batch is posted to the logs endpoint`() {
        val transport = transport { accepted(1) }
        PromptOn(config(transport), FakeClock()).use { prompton ->
            prompton.log(record(1))
            val result = prompton.flushBlocking()
            assertEquals(1, result.accepted)
            assertEquals(1, transport.postCount())
            assertEquals(
                "https://prompton.test/api/v1/logs?environment=production",
                transport.lastPost().url,
            )
            assertEquals("Bearer ptn_fixture_secret", transport.lastPost().headers["authorization"])
            assertEquals("application/json", transport.lastPost().headers["content-type"])
        }
    }

    @Test
    fun `a record carries an sdk block and a uuid v7 id`() {
        val transport = transport { accepted(1) }
        PromptOn(config(transport), FakeClock()).use { prompton ->
            prompton.log(record(1).copy(id = null))
            prompton.flushBlocking()

            val sent = batchOf(transport.lastPost()).single()
            val sdk = sent["sdk"] as JsonObject
            assertEquals("prompton-kotlin", (sdk["name"] as JsonPrimitive).content)
            assertEquals("0.2.0", (sdk["version"] as JsonPrimitive).content)
            val id = (sent["id"] as JsonPrimitive).content
            assertEquals('7', id[14], "the version nibble of a UUIDv7 is 7: $id")
            assertNotNull(UuidV7.timestampMillis(id))
        }
    }

    @Test
    fun `the size trigger flushes without being asked`() {
        val transport = transport { accepted(2) }
        val options = LogOptions(flushInterval = 60.seconds, flushSize = 2, flushBytes = 100_000_000)
        PromptOn(config(transport, options), FakeClock()).use { prompton ->
            prompton.log(record(1))
            assertStaysTrue("one record does not reach the flush size") { transport.postCount() == 0 }
            prompton.log(record(2))
            await("the size-triggered flush") { transport.postCount() == 1 }
            assertEquals(2, batchOf(transport.lastPost()).size)
        }
    }

    @Test
    fun `the time trigger flushes without being asked`() {
        val transport = transport { accepted(1) }
        val options = LogOptions(flushInterval = 100.milliseconds, flushSize = 1_000, flushBytes = 100_000_000)
        PromptOn(config(transport, options), FakeClock()).use { prompton ->
            prompton.log(record(1))
            await("the time-triggered flush") { transport.postCount() == 1 }
        }
    }

    @Test
    fun `the bytes trigger flushes without being asked`() {
        val transport = transport { accepted(1) }
        val options = LogOptions(flushInterval = 60.seconds, flushSize = 1_000, flushBytes = 200)
        PromptOn(config(transport, options), FakeClock()).use { prompton ->
            prompton.log(record(1))
            await("the bytes-triggered flush") { transport.postCount() == 1 }
        }
    }

    @Test
    fun `a batch never exceeds two hundred records`() {
        val transport = transport { accepted(200) }
        PromptOn(config(transport), FakeClock()).use { prompton ->
            repeat(250) { prompton.log(record(it)) }
            prompton.flushBlocking()

            assertEquals(2, transport.postCount())
            val sizes = transport.posts().map { batchOf(it).size }
            assertEquals(listOf(200, 50), sizes)
        }
    }

    @Test
    fun `each environment gets its own batch`() {
        val transport = transport { accepted(2) }
        PromptOn(config(transport), FakeClock()).use { prompton ->
            prompton.log(record(1))
            prompton.log(record(2))
            prompton.log(record(3), environment = "staging")
            prompton.log(record(4), environment = "staging")
            prompton.flushBlocking()

            val urls = transport.posts().map { it.url }
            assertEquals(2, urls.size)
            assertTrue(urls[0].endsWith("environment=production"), urls[0])
            assertTrue(urls[1].endsWith("environment=staging"), urls[1])
        }
    }

    @Test
    fun `accepted records are never resent when part of a batch is rejected`() {
        val transport =
            transport {
                HttpResponse(
                    202,
                    emptyMap(),
                    """{"accepted":1,"duplicates":0,"rejected":[
                       {"index":1,"id":"x","code":"invalid_request","message":"started_at too old"}]}""",
                )
            }
        PromptOn(config(transport), FakeClock()).use { prompton ->
            prompton.log(record(1))
            prompton.log(record(2))
            val result = prompton.flushBlocking()

            assertEquals(1, result.accepted)
            assertEquals(1, result.rejected)
            assertEquals(0, result.remaining)
            assertEquals(1, transport.postCount())

            prompton.flushBlocking()
            assertEquals(1, transport.postCount(), "nothing is resent after a partial acceptance")
        }
    }

    @Test
    fun `a 429 retries the same batch with the same ids after retry-after`() {
        val attempts = AtomicInteger()
        val transport =
            transport {
                if (attempts.incrementAndGet() == 1) {
                    HttpResponse(429, mapOf("retry-after" to "30"), "")
                } else {
                    accepted(2)
                }
            }
        val clock = FakeClock()
        PromptOn(config(transport), clock).use { prompton ->
            prompton.log(record(1))
            prompton.log(record(2))
            val firstIds = run {
                prompton.flushBlocking()
                idsOf(transport.lastPost())
            }
            assertEquals(1, transport.postCount())

            prompton.flushBlocking()
            assertEquals(1, transport.postCount(), "no resend before Retry-After elapses")

            clock.advanceMillis(31_000)
            val result = prompton.flushBlocking()
            assertEquals(2, transport.postCount())
            assertEquals(firstIds, idsOf(transport.lastPost()), "the retry keeps the same ids")
            assertEquals(2, result.accepted)
        }
    }

    @Test
    fun `a flush inside a rate-limit pause keeps the records instead of losing them`() {
        val transport = transport { HttpResponse(429, mapOf("retry-after" to "120"), "") }
        val clock = FakeClock()
        PromptOn(config(transport), clock).use { prompton ->
            repeat(3) { prompton.log(record(it)) }
            val first = prompton.flushBlocking()
            assertEquals(1, transport.postCount())
            assertEquals(3, first.remaining, "the batch is still queued")

            val second = prompton.flushBlocking()
            assertEquals(1, transport.postCount(), "no request inside the Retry-After window")
            assertEquals(3, second.remaining)
            assertEquals(0L, prompton.logStats().dropped, "held is not dropped")
            assertEquals(3, prompton.logStats().queued)
        }
    }

    @Test
    fun `close makes one last attempt and counts what it could not send`() {
        val transport = transport { HttpResponse(429, mapOf("retry-after" to "120"), "") }
        val clock = FakeClock()
        val prompton = PromptOn(config(transport), clock)
        repeat(3) { prompton.log(record(it)) }
        prompton.flushBlocking()
        assertEquals(1, transport.postCount())
        assertEquals(3, prompton.logStats().queued)

        prompton.close()

        assertEquals(2, transport.postCount(), "shutdown tries once more even inside the pause")
        assertEquals(3L, prompton.logStats().dropped, "what could not be sent is counted, not silent")
        assertEquals(0, prompton.logStats().queued)
    }

    @Test
    fun `a record logged after close is dropped, not thrown at the caller`() {
        val transport = transport { accepted(1) }
        val prompton = PromptOn(config(transport), FakeClock())
        prompton.close()

        prompton.log(record(1))

        assertEquals(1L, prompton.logStats().dropped, "a late record is counted, not raised")
        assertEquals(0, prompton.logStats().queued)
    }

    @Test
    fun `a 5xx retries with exponential backoff`() {
        val attempts = AtomicInteger()
        val transport =
            transport {
                if (attempts.incrementAndGet() <= 2) HttpResponse(503, emptyMap(), "") else accepted(1)
            }
        val clock = FakeClock()
        PromptOn(config(transport), clock).use { prompton ->
            prompton.log(record(1))
            prompton.flushBlocking()
            assertEquals(1, transport.postCount())

            clock.advanceMillis(1_000)
            prompton.flushBlocking()
            assertEquals(2, transport.postCount(), "the first backoff is one second")

            clock.advanceMillis(1_000)
            prompton.flushBlocking()
            assertEquals(2, transport.postCount(), "the second backoff is two seconds")

            clock.advanceMillis(1_000)
            val result = prompton.flushBlocking()
            assertEquals(3, transport.postCount())
            assertEquals(1, result.accepted)
        }
    }

    @Test
    fun `a transport failure is retried like a 5xx`() {
        val attempts = AtomicInteger()
        val transport =
            transport {
                if (attempts.incrementAndGet() == 1) throw java.io.IOException("connection reset") else accepted(1)
            }
        val clock = FakeClock()
        PromptOn(config(transport), clock).use { prompton ->
            prompton.log(record(1))
            prompton.flushBlocking()
            clock.advanceMillis(2_000)
            val result = prompton.flushBlocking()
            assertEquals(1, result.accepted)
        }
    }

    @Test
    fun `a 413 splits the batch in half`() {
        val transport =
            transport { request ->
                if (batchOf(request).size > 2) HttpResponse(413, emptyMap(), "") else accepted(2)
            }
        PromptOn(config(transport), FakeClock()).use { prompton ->
            repeat(4) { prompton.log(record(it)) }
            val result = prompton.flushBlocking()

            val sizes = transport.posts().map { batchOf(it).size }
            assertEquals(listOf(4, 2, 2), sizes)
            assertEquals(4, result.accepted)
        }
    }

    @Test
    fun `any other 4xx drops the batch without retrying`() {
        val transport = transport { HttpResponse(400, emptyMap(), """{"error":{"message":"bad batch"}}""") }
        PromptOn(config(transport), FakeClock()).use { prompton ->
            prompton.log(record(1))
            val result = prompton.flushBlocking()
            assertEquals(1, result.dropped)
            assertEquals(0, result.remaining)

            prompton.flushBlocking()
            assertEquals(1, transport.postCount(), "a 4xx batch is never resent")
            assertEquals(1, prompton.logStats().dropped)
        }
    }

    @Test
    fun `a batch is dropped after the attempt limit`() {
        val transport = transport { HttpResponse(503, emptyMap(), "") }
        val options =
            LogOptions(flushInterval = 60.seconds, flushSize = 1_000, flushBytes = 100_000_000, maxAttempts = 3)
        val clock = FakeClock()
        PromptOn(config(transport, options), clock).use { prompton ->
            prompton.log(record(1))
            repeat(4) {
                prompton.flushBlocking()
                clock.advanceMillis(600_000)
            }
            assertEquals(1, prompton.logStats().dropped)
            assertEquals(0, prompton.logStats().queued)
            assertEquals(3, transport.postCount(), "three attempts, then the batch is dropped")
        }
    }

    @Test
    fun `the queue is bounded and drops the oldest`() {
        val transport = transport { accepted(3) }
        val options =
            LogOptions(flushInterval = 60.seconds, flushSize = 1_000, flushBytes = 100_000_000, maxBufferSize = 3)
        PromptOn(config(transport, options), FakeClock()).use { prompton ->
            repeat(5) { prompton.log(record(it)) }
            assertEquals(3, prompton.logStats().queued)
            assertEquals(2, prompton.logStats().dropped)

            prompton.flushBlocking()
            val traceIds =
                batchOf(transport.lastPost()).map { (it["trace_id"] as JsonPrimitive).content }
            assertEquals(listOf("trace-2", "trace-3", "trace-4"), traceIds, "the oldest records are the ones dropped")
        }
    }

    @Test
    fun `closing drains what is queued`() {
        val transport = transport { accepted(2) }
        val prompton = PromptOn(config(transport), FakeClock())
        prompton.log(record(1))
        prompton.log(record(2))
        assertEquals(0, transport.postCount())
        prompton.close()
        assertEquals(1, transport.postCount(), "close drains the queue")
        assertEquals(2, batchOf(transport.lastPost()).size)
    }

    @Test
    fun `the redact hook runs last and can strip the payload`() {
        val transport = transport { accepted(1) }
        val options =
            LogOptions(
                flushInterval = 60.seconds,
                flushSize = 1_000,
                flushBytes = 100_000_000,
                redact = { record -> JsonObject(record.filterKeys { it != "input" }) },
            )
        PromptOn(config(transport, options), FakeClock()).use { prompton ->
            prompton.log(
                record(1).copy(input = LogInput(variables = mapOf("secret" to "hunter2"))),
            )
            prompton.flushBlocking()

            val sent = batchOf(transport.lastPost()).single()
            assertFalse(sent.containsKey("input"), "the redact hook removed the input")
            assertFalse(transport.lastPost().body!!.contains("hunter2"))
        }
    }

    @Test
    fun `hash_end_user replaces the reference with its digest`() {
        val transport = transport { accepted(1) }
        PromptOn(config(transport).copy(hashEndUser = true), FakeClock()).use { prompton ->
            prompton.log(record(1).copy(endUserRef = "user-42"))
            prompton.flushBlocking()

            val sent = batchOf(transport.lastPost()).single()
            val hashed = (sent["end_user_ref"] as JsonPrimitive).content
            assertEquals(64, hashed.length)
            assertFalse(transport.lastPost().body!!.contains("user-42"))
        }
    }

    @Test
    fun `test mode captures records instead of sending them`() {
        val transport = StubTransport { error("test mode must not make requests") }
        PromptOn(config(transport).copy(mode = PromptOnMode.TEST), FakeClock()).use { prompton ->
            prompton.log(record(1))
            prompton.log(record(2))

            assertEquals(2, prompton.capturedRecords().size)
            assertEquals(0, transport.postCount())
            assertEquals("greeting", (prompton.capturedRecords()[0]["use_case"] as JsonPrimitive).content)

            prompton.clearCapturedRecords()
            assertEquals(0, prompton.capturedRecords().size)
        }
    }
}
