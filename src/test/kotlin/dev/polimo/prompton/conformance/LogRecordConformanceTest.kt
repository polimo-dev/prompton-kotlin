package dev.polimo.prompton.conformance

import dev.polimo.prompton.CostSource
import dev.polimo.prompton.ErrorKind
import dev.polimo.prompton.FakeClock
import dev.polimo.prompton.LogError
import dev.polimo.prompton.PayloadPolicy
import dev.polimo.prompton.PromptOn
import dev.polimo.prompton.PromptOnConfig
import dev.polimo.prompton.PromptOnMode
import dev.polimo.prompton.Result
import dev.polimo.prompton.SdkInfo
import dev.polimo.prompton.TrackMeta
import dev.polimo.prompton.Usage
import dev.polimo.prompton.UseCaseSource
import dev.polimo.prompton.assertJsonEquivalent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Checks the record builder against the golden monitoring logs in `conformance/log_record.json`.
 *
 * The goldens were produced by the reference implementation, so their `sdk` block names the Elixir
 * SDK; every other field must match byte for byte.
 */
class LogRecordConformanceTest {
    private val productionDocument: String =
        (Conformance.load("use_case")["documents"] as JsonObject)["production"].toString()

    @Test
    fun `chat success`() {
        val clock = FakeClock()
        val record =
            build(clock, UseCaseSource.REMOTE) { prompton ->
                val useCase = prompton.useCase("greeting")
                val messages = useCase.messages(mapOf("name" to "Ada"))
                useCase.trackBlocking(
                    TrackMeta(
                        id = "0198f2a1-1111-7000-8000-000000000001",
                        variables = mapOf("name" to "Ada"),
                        inputMessages = messages,
                        endUserRef = "user-42",
                        traceId = "oban:8842",
                        sequence = 1,
                        context = mapOf("language" to "en", "plan" to "pro"),
                        metadata = mapOf("job_id" to 8842, "attempt" to 1),
                    ),
                ) { call ->
                    clock.advanceMillis(842)
                    call.result(
                        Result(
                            content = "Hello, Ada! Lovely to see you.",
                            finishReason = "stop",
                            usage =
                                Usage(
                                    inputTokens = 38,
                                    outputTokens = 9,
                                    costUsd = 0.000112,
                                    costSource = CostSource.PROVIDER,
                                    raw =
                                        mapOf(
                                            "prompt_tokens" to 38,
                                            "completion_tokens" to 9,
                                            "total_tokens" to 47,
                                        ),
                                ),
                            modelUsed = "openai/gpt-4o-mini",
                            upstreamProvider = "OpenAI",
                            isByok = false,
                        ),
                    )
                }
            }
        assertJsonEquivalent(golden("chat/success"), record, "chat/success")
    }

    @Test
    fun `chat error without output`() {
        val clock = FakeClock()
        val record =
            build(clock, UseCaseSource.REMOTE) { prompton ->
                val useCase = prompton.useCase("greeting")
                val messages = useCase.messages(mapOf("name" to "Ada"))
                useCase.trackBlocking(
                    TrackMeta(
                        id = "0198f2a1-1111-7000-8000-000000000002",
                        variables = mapOf("name" to "Ada"),
                        inputMessages = messages,
                        traceId = "oban:8843",
                        sequence = 2,
                    ),
                ) { call ->
                    clock.advanceMillis(1503)
                    call.failed(
                        LogError(
                            kind = ErrorKind.RATE_LIMITED,
                            status = 429,
                            message = "rate limited by upstream provider",
                        ),
                    )
                }
            }
        assertJsonEquivalent(golden("chat/error_without_output"), record, "chat/error_without_output")
    }

    @Test
    fun `chat error with usage preserved`() {
        val clock = FakeClock()
        val record =
            build(clock, UseCaseSource.REMOTE) { prompton ->
                val useCase = prompton.useCase("greeting")
                val messages = useCase.messages(mapOf("name" to "Ada"))
                useCase.trackBlocking(
                    TrackMeta(
                        id = "0198f2a1-1111-7000-8000-000000000003",
                        variables = mapOf("name" to "Ada"),
                        inputMessages = messages,
                        traceId = "oban:8844",
                    ),
                ) { call ->
                    clock.advanceMillis(2310)
                    call.failed(
                        LogError(kind = ErrorKind.PARSE, message = "unexpected end of JSON input"),
                        Result(
                            content = "{\"greeting\": \"Hello, Ada!\"",
                            finishReason = "length",
                            usage =
                                Usage(
                                    inputTokens = 38,
                                    outputTokens = 512,
                                    costUsd = 0.000208,
                                    costSource = CostSource.PROVIDER,
                                ),
                        ),
                    )
                }
            }
        assertJsonEquivalent(golden("chat/error_with_usage_preserved"), record, "chat/error_with_usage_preserved")
    }

    @Test
    fun `embedding success from the disk tier`() {
        val clock = FakeClock()
        val text = "PromptOn is the control plane for your app's LLM prompts."
        val record =
            build(clock, UseCaseSource.DISK) { prompton ->
                // The golden record was generated against the SDK's default payload policy, so this
                // case overrides the document's `hash` policy for `embed` and keeps the variables raw.
                val useCase =
                    prompton.useCase("embed").copy(payloadPolicy = PayloadPolicy.DEFAULT).also {
                        it.owner = prompton
                    }
                useCase.trackBlocking(
                    TrackMeta(
                        id = "0198f2a1-1111-7000-8000-000000000004",
                        variables = mapOf("text" to text),
                        traceId = "ingest:2026-09-04:batch-7",
                        metadata = mapOf("chunk" to 12),
                    ),
                ) { call ->
                    clock.advanceMillis(96)
                    call.result(
                        Result(
                            usage =
                                Usage(
                                    inputTokens = 14,
                                    outputTokens = 0,
                                    costUsd = 1.2e-06,
                                    costSource = CostSource.CATALOG,
                                ),
                        ),
                    )
                }
            }
        assertJsonEquivalent(golden("embedding/success"), record, "embedding/success")
    }

    @Test
    fun `a hand assembled record is passed through untouched`() {
        val expected = rawGolden("text/manual_log_with_input_text")
        val clock = FakeClock()
        PromptOn(config(PromptOnMode.TEST), clock).use { prompton ->
            prompton.log(Conformance.nativeMap(expected)!!)
            val captured = prompton.capturedRecords().single()
            assertJsonEquivalent(expected, captured, "text/manual_log_with_input_text")
        }
    }

    @Test
    fun `the batch envelope wraps records under logs`() {
        val envelope = (Conformance.load("log_record")["batch_envelope"] as JsonObject)["request"] as JsonObject
        assertEquals(setOf("logs"), envelope.keys)
        val records = envelope["logs"] as JsonArray
        assertEquals(5, records.size)
        assertTrue(records.all { (it as JsonObject).containsKey("id") })

        val endpoint = Conformance.load("log_record")["endpoint"] as JsonObject
        assertEquals(200, Conformance.native(endpoint["max_records_per_request"]).toString().toInt())
        assertEquals(202, Conformance.native(endpoint["success_status"]).toString().toInt())
        assertEquals(200, PromptOnConfig().log.maxBatchSize)
    }

    // -----------------------------------------------------------------------

    private fun config(mode: PromptOnMode) =
        PromptOnConfig(
            apiKey = null,
            mode = mode,
            diskCacheEnabled = false,
            pollingEnabled = false,
            project = "conformance",
        )

    private fun build(
        clock: FakeClock,
        source: UseCaseSource,
        block: (PromptOn) -> Unit,
    ): JsonObject =
        PromptOn(config(PromptOnMode.TEST), clock).use { prompton ->
            prompton.putUseCaseDocument(productionDocument, source)
            block(prompton)
            prompton.capturedRecords().single()
        }

    /** The golden record with its `sdk` block replaced by this SDK's own. */
    private fun golden(name: String): JsonObject {
        val fields = LinkedHashMap<String, JsonElement>(rawGolden(name))
        fields["sdk"] =
            JsonObject(
                mapOf(
                    "name" to JsonPrimitive(SdkInfo.CURRENT.name),
                    "version" to JsonPrimitive(SdkInfo.CURRENT.version),
                ),
            )
        return JsonObject(fields)
    }

    private fun rawGolden(name: String): JsonObject =
        Conformance
            .cases("log_record", "records")
            .first { Conformance.string(it, "name") == name }["record"] as JsonObject
}
