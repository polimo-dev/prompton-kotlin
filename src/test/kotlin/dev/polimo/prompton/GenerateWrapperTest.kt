package dev.polimo.prompton

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The convenience wrapper: it times the provider call, logs it, and gets out of the way. */
class GenerateWrapperTest {
    private fun prompton(clock: FakeClock = FakeClock()): PromptOn {
        val config =
            PromptOnConfig(
                apiKey = null,
                mode = PromptOnMode.TEST,
                environment = "production",
                project = "fixture",
                pollingEnabled = false,
                diskCacheEnabled = false,
            )
        val prompton = PromptOn(config, clock)
        prompton.putSnapshot(SnapshotFixtures.snapshot(), ResolutionSource.REMOTE)
        return prompton
    }

    private fun field(
        record: JsonObject,
        name: String,
    ): String? = (record[name] as? JsonPrimitive)?.content

    @Test
    fun `the block's value is returned unchanged`() {
        prompton().use { prompton ->
            val resolution = prompton.resolve("greeting")
            val answer =
                prompton.generateBlocking(resolution) { call ->
                    call.succeeded(ProviderOutcome(content = "Hello, Ada!", finishReason = "stop"))
                    listOf("Hello, Ada!")
                }
            assertEquals(listOf("Hello, Ada!"), answer)

            val record = prompton.capturedRecords().single()
            assertEquals("ok", field(record, "status"))
            assertEquals("stop", field(record, "stop_kind"))
            assertEquals("greeting", field(record, "use_case"))
            assertEquals("remote", field(record, "resolution_source"))
            assertEquals("Hello, Ada!", ((record["output"] as JsonObject)["content"] as JsonPrimitive).content)
        }
    }

    @Test
    fun `latency is measured from the clock`() {
        val clock = FakeClock()
        prompton(clock).use { prompton ->
            val resolution = prompton.resolve("greeting")
            prompton.generateBlocking(resolution) { call ->
                clock.advanceMillis(1_234)
                call.succeeded(ProviderOutcome(content = "hi"))
            }
            assertEquals("1234", field(prompton.capturedRecords().single(), "latency_ms"))
        }
    }

    @Test
    fun `an exception is logged as an app error and rethrown unchanged`() {
        prompton().use { prompton ->
            val resolution = prompton.resolve("greeting")
            val thrown =
                assertFailsWith<IllegalStateException> {
                    prompton.generateBlocking(resolution) { error("the provider client blew up") }
                }
            assertEquals("the provider client blew up", thrown.message)

            val record = prompton.capturedRecords().single()
            assertEquals("error", field(record, "status"))
            val error = record["error"] as JsonObject
            assertEquals("app", (error["kind"] as JsonPrimitive).content)
            assertTrue((error["message"] as JsonPrimitive).content.contains("the provider client blew up"))
            assertFalse(record.containsKey("output"))
        }
    }

    @Test
    fun `a recorded failure keeps the usage and output that came with it`() {
        prompton().use { prompton ->
            val resolution = prompton.resolve("greeting")
            prompton.generateBlocking(resolution) { call ->
                call.failed(
                    GenerationError(ErrorKind.PARSE, message = "unexpected end of JSON input"),
                    ProviderOutcome(
                        content = "{\"greeting\":",
                        finishReason = "length",
                        usage = Usage(inputTokens = 38, outputTokens = 512),
                    ),
                )
            }

            val record = prompton.capturedRecords().single()
            assertEquals("error", field(record, "status"))
            assertEquals("length", field(record, "stop_kind"))
            assertEquals("parse", ((record["error"] as JsonObject)["kind"] as JsonPrimitive).content)
            assertEquals("38", ((record["usage"] as JsonObject)["input_tokens"] as JsonPrimitive).content)
        }
    }

    @Test
    fun `the resolution's pin is recorded as evidence`() {
        prompton().use { prompton ->
            val resolution = prompton.resolve("greeting")
            prompton.generateBlocking(resolution) { call -> call.succeeded(ProviderOutcome(content = "hi")) }

            val record = prompton.capturedRecords().single()
            assertEquals("0198f2a1-0000-7000-8000-00000000d001", field(record, "deployment_id"))
            assertEquals("3", field(record, "deployment_revision"))
            assertEquals("default", field(record, "prompt"))
            assertEquals("0198f2a1-0000-7000-8000-00000000a001", field(record, "prompt_version_id"))
            assertEquals("openrouter", field(record, "provider"))
            assertEquals("openai/gpt-4o-mini", field(record, "model"))
            assertEquals("chat", field(record, "kind"))
        }
    }

    @Test
    fun `the suspending wrapper behaves like the blocking one`() =
        runBlocking {
            prompton().use { prompton ->
                val resolution = prompton.resolve("greeting")
                val answer =
                    prompton.generate(resolution, GenerationMeta(traceId = "job:1")) { call ->
                        call.succeeded(ProviderOutcome(content = "Hello", finishReason = "stop"))
                        "Hello"
                    }
                assertEquals("Hello", answer)
                assertEquals("job:1", field(prompton.capturedRecords().single(), "trace_id"))
            }
        }

    @Test
    fun `error kinds map from provider http statuses`() {
        assertEquals(ErrorKind.RATE_LIMITED, ErrorKind.ofStatus(429))
        assertEquals(ErrorKind.HTTP_4XX, ErrorKind.ofStatus(400))
        assertEquals(ErrorKind.HTTP_5XX, ErrorKind.ofStatus(502))
        assertEquals(ErrorKind.APP, ErrorKind.fromWire("nonsense"))
        assertEquals(ErrorKind.TIMEOUT, ErrorKind.fromWire("timeout"))
    }

    @Test
    fun `logging a record requires the fields the server requires`() {
        prompton().use { prompton ->
            assertFailsWith<IllegalArgumentException> {
                prompton.log(mapOf("model" to "m", "status" to "ok", "started_at" to "2026-09-04T09:00:00Z"))
            }
            assertFailsWith<IllegalArgumentException> {
                prompton.log(mapOf("use_case" to "greeting", "status" to "ok", "started_at" to "2026-09-04T09:00:00Z"))
            }
            assertFailsWith<IllegalArgumentException> {
                GenerationRecord(
                    useCase = " ",
                    model = "m",
                    status = GenerationStatus.OK,
                    startedAt = java.time.Instant.now(),
                )
            }
        }
    }

    @Test
    fun `a manual log gets an id and a started_at when it has none`() {
        prompton().use { prompton ->
            prompton.log(mapOf("use_case" to "greeting", "model" to "m", "status" to "ok"))
            val record = prompton.capturedRecords().single()
            assertEquals(36, field(record, "id")!!.length)
            assertTrue(field(record, "started_at")!!.endsWith("Z"))
            assertEquals("prompton-kotlin", ((record["sdk"] as JsonObject)["name"] as JsonPrimitive).content)
        }
    }
}
