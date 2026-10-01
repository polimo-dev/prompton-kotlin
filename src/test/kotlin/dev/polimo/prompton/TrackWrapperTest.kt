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
class TrackWrapperTest {
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
        prompton.putUseCaseDocument(SnapshotFixtures.useCaseDocument(), UseCaseSource.REMOTE)
        return prompton
    }

    private fun field(
        record: JsonObject,
        name: String,
    ): String? = (record[name] as? JsonPrimitive)?.content

    @Test
    fun `the block's value is returned unchanged`() {
        prompton().use { prompton ->
            val useCase = prompton.useCase("greeting")
            val answer =
                useCase.trackBlocking { call ->
                    call.result(Result(content = "Hello, Ada!", finishReason = "stop"))
                    listOf("Hello, Ada!")
                }
            assertEquals(listOf("Hello, Ada!"), answer)

            val record = prompton.capturedRecords().single()
            assertEquals("ok", field(record, "status"))
            assertEquals("stop", field(record, "stop_kind"))
            assertEquals("greeting", field(record, "prompt_key"))
            assertEquals("remote", field(record, "source"))
            assertEquals("Hello, Ada!", ((record["output"] as JsonObject)["content"] as JsonPrimitive).content)
        }
    }

    @Test
    fun `latency is measured from the clock`() {
        val clock = FakeClock()
        prompton(clock).use { prompton ->
            val useCase = prompton.useCase("greeting")
            useCase.trackBlocking { call ->
                clock.advanceMillis(1_234)
                call.result(Result(content = "hi"))
            }
            assertEquals("1234", field(prompton.capturedRecords().single(), "latency_ms"))
        }
    }

    @Test
    fun `an exception is logged as an app error and rethrown unchanged`() {
        prompton().use { prompton ->
            val useCase = prompton.useCase("greeting")
            val thrown =
                assertFailsWith<IllegalStateException> {
                    useCase.trackBlocking { error("the provider client blew up") }
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
            val useCase = prompton.useCase("greeting")
            useCase.trackBlocking { call ->
                call.failed(
                    LogError(ErrorKind.PARSE, message = "unexpected end of JSON input"),
                    Result(
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
    fun `the useCase's pin is recorded as evidence`() {
        prompton().use { prompton ->
            val useCase = prompton.useCase("greeting")
            useCase.trackBlocking { call -> call.result(Result(content = "hi")) }

            val record = prompton.capturedRecords().single()
            assertEquals("0198f2a1-0000-7000-8000-00000000d001", field(record, "deployment_id"))
            assertEquals("v2026.09.30-3", field(record, "deployment_revision"))
            assertEquals("default", field(record, "template"))
            assertEquals("0198f2a1-0000-7000-8000-00000000a001", field(record, "prompt_version_id"))
            assertEquals("openrouter", field(record, "provider"))
            assertEquals("openai/gpt-4o-mini", field(record, "model"))
            assertEquals("chat", field(record, "kind"))
        }
    }

    @Test
    fun `a named prompt's pin is recorded as evidence`() {
        prompton().use { prompton ->
            val useCase = prompton.useCase("greeting", prompt = "ko")
            useCase.trackBlocking { call -> call.result(Result(content = "안녕하세요")) }

            val record = prompton.capturedRecords().single()
            assertEquals("ko", field(record, "template"))
            assertEquals("0198f2a1-0000-7000-8000-00000000a002", field(record, "prompt_version_id"))
        }
    }

    @Test
    fun `a named prompt rendered from the base prompt is recorded as evidence`() {
        prompton().use { prompton ->
            val useCase = prompton.useCase("greeting")
            val messages = useCase.messages(mapOf("name" to "Ada"), prompt = "ko")
            useCase.trackBlocking(TrackMeta(variables = mapOf("name" to "Ada"), inputMessages = messages)) { call ->
                call.result(Result(content = "안녕하세요"))
            }

            val record = prompton.capturedRecords().single()
            assertEquals("ko", field(record, "template"))
            assertEquals("0198f2a1-0000-7000-8000-00000000a002", field(record, "prompt_version_id"))
        }
    }

    @Test
    fun `a named prompt render selection is cleared after one track`() {
        prompton().use { prompton ->
            val useCase = prompton.useCase("greeting")
            val messages = useCase.messages(mapOf("name" to "Ada"), prompt = "ko")
            useCase.trackBlocking(TrackMeta(inputMessages = messages)) { call ->
                call.result(Result(content = "안녕하세요"))
            }
            useCase.trackBlocking { call -> call.result(Result(content = "Hello again")) }

            val records = prompton.capturedRecords()
            assertEquals("ko", field(records[0], "template"))
            assertEquals("default", field(records[1], "template"))
        }
    }

    @Test
    fun `a failed named prompt render does not leak into the next track`() {
        prompton().use { prompton ->
            val useCase = prompton.useCase("greeting")
            assertFailsWith<MissingVariableException> {
                useCase.messages(prompt = "ko")
            }
            useCase.trackBlocking { call -> call.result(Result(content = "Hello")) }

            val record = prompton.capturedRecords().single()
            assertEquals("default", field(record, "template"))
            assertEquals("0198f2a1-0000-7000-8000-00000000a001", field(record, "prompt_version_id"))
        }
    }

    @Test
    fun `the suspending wrapper behaves like the blocking one`() =
        runBlocking {
            prompton().use { prompton ->
                val useCase = prompton.useCase("greeting")
                val answer =
                    useCase.track(TrackMeta(traceId = "job:1")) { call ->
                        call.result(Result(content = "Hello", finishReason = "stop"))
                        "Hello"
                    }
                assertEquals("Hello", answer)
                assertEquals("job:1", field(prompton.capturedRecords().single(), "trace_id"))
            }
        }

    @Test
    fun `tracked input keeps the tools and tool policies sent to the provider`() {
        prompton().use { prompton ->
            val useCase = prompton.useCase("greeting")
            val messages = useCase.messages(mapOf("name" to "Ada"))
            val tools =
                listOf(
                    mapOf(
                        "type" to "function",
                        "function" to mapOf("name" to "lookup_diary", "parameters" to mapOf("type" to "object")),
                    ),
                )

            useCase.trackBlocking(
                TrackMeta(
                    inputMessages = messages,
                    params =
                        mapOf(
                            "tools" to tools,
                            "tool_choice" to mapOf("type" to "function", "function" to mapOf("name" to "lookup_diary")),
                            "parallel_tool_calls" to false,
                        ),
                ),
            ) { call ->
                call.result(Result(content = "Hello"))
            }

            val input = prompton.capturedRecords().single()["input"] as JsonObject
            assertEquals(1, (input["tools"] as kotlinx.serialization.json.JsonArray).size)
            val choice = input["tool_choice"] as JsonObject
            assertEquals("function", (choice["type"] as JsonPrimitive).content)
            assertEquals("false", (input["parallel_tool_calls"] as JsonPrimitive).content)
        }
    }

    @Test
    fun `tracked input records the final messages composed by the app`() {
        prompton().use { prompton ->
            val useCase = prompton.useCase("greeting")
            val managed = useCase.messages(mapOf("name" to "Ada"))
            val finalMessages =
                managed +
                    listOf(
                        PromptMessage(role = "assistant", content = "Earlier answer."),
                        PromptMessage(role = "user", content = "Thanks, continue."),
                    )

            useCase.trackBlocking(
                TrackMeta(
                    variables = mapOf("name" to "Ada"),
                    inputMessages = finalMessages,
                ),
            ) { call ->
                call.result(Result(content = "Sure."))
            }

            val input = prompton.capturedRecords().single()["input"] as JsonObject
            val messages = input["messages"] as kotlinx.serialization.json.JsonArray
            assertEquals(4, messages.size)
            assertEquals("Earlier answer.", ((messages[2] as JsonObject)["content"] as JsonPrimitive).content)
            assertEquals("Thanks, continue.", ((messages[3] as JsonObject)["content"] as JsonPrimitive).content)
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
                prompton.log(
                    mapOf(
                        "prompt_key" to "greeting",
                        "status" to "ok",
                        "started_at" to "2026-09-04T09:00:00Z",
                    ),
                )
            }
            assertFailsWith<IllegalArgumentException> {
                LogRecord(
                    useCase = " ",
                    model = "m",
                    status = LogStatus.OK,
                    startedAt = java.time.Instant.now(),
                )
            }
        }
    }

    @Test
    fun `a manual log gets an id and a started_at when it has none`() {
        prompton().use { prompton ->
            prompton.log(mapOf("prompt_key" to "greeting", "model" to "m", "status" to "ok"))
            val record = prompton.capturedRecords().single()
            assertEquals(36, field(record, "id")!!.length)
            assertTrue(field(record, "started_at")!!.endsWith("Z"))
            assertEquals("prompton-kotlin", ((record["sdk"] as JsonObject)["name"] as JsonPrimitive).content)
        }
    }

    @Test
    fun `provider helpers extract common OpenAI and Anthropic shapes`() {
        val openai =
            Result.fromOpenAI(
                mapOf(
                    "model" to "gpt-4o-mini",
                    "choices" to
                        listOf(
                            mapOf(
                                "finish_reason" to "stop",
                                "message" to mapOf("content" to "Hello"),
                            ),
                        ),
                    "usage" to mapOf("prompt_tokens" to 3, "completion_tokens" to 2),
                ),
            )
        assertEquals("Hello", openai.content)
        assertEquals("stop", openai.finishReason)
        assertEquals(3L, openai.usage?.inputTokens)
        assertEquals(2L, openai.usage?.outputTokens)
        assertEquals("gpt-4o-mini", openai.modelUsed)

        val anthropic =
            Result.fromAnthropic(
                mapOf(
                    "model" to "claude-sonnet-4-5",
                    "stop_reason" to "end_turn",
                    "content" to listOf(mapOf("type" to "text", "text" to "Hi")),
                    "usage" to mapOf("input_tokens" to 4, "output_tokens" to 1),
                ),
            )
        assertEquals("Hi", anthropic.content)
        assertEquals("end_turn", anthropic.finishReason)
        assertEquals(4L, anthropic.usage?.inputTokens)
        assertEquals(1L, anthropic.usage?.outputTokens)
    }
}
