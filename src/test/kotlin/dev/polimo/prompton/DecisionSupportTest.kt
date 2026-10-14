package dev.polimo.prompton

import dev.polimo.prompton.internal.Ptn
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class DecisionSupportTest {
    @TempDir
    lateinit var tempDir: Path

    private fun config(
        transport: HttpTransport? = null,
        bundle: Path? = null,
        diskCache: Path? = null,
        mode: PromptOnMode = PromptOnMode.LIVE,
    ) = PromptOnConfig(
        apiKey = if (transport == null) null else "ptn_fixture_secret",
        mode = mode,
        host = "https://prompton.test",
        environment = "production",
        project = "fixture",
        cacheTtl = 10.seconds,
        pollingEnabled = false,
        diskCacheEnabled = diskCache != null,
        diskCachePath = diskCache,
        bundlePath = bundle,
        transport = transport,
    )

    @Test
    fun `Decision prompts resolve and prepare native provider requests from an injected document`() {
        PromptOn(config(mode = PromptOnMode.TEST), FakeClock()).use { prompton ->
            prompton.putUseCaseDocument(decisionDocument(), UseCaseSource.MANUAL)
            val useCase = prompton.useCase("sentiment")

            assertEquals(UseCaseKind.DECISION, useCase.kind)
            assertEquals("decisions", useCase.api)
            assertEquals("/api/v1/systemone", useCase.requestPath)
            assertEquals("typesafe/jev-1.13", useCase.model)

            val rendered = useCase.decision(mapOf("diary" to "A bright walk."))
            assertEquals(mapOf("diary" to "A bright walk.", "static_key" to "literal"), rendered.state)
            assertEquals("Classify A bright walk..", (rendered.questions["mood"] as Map<*, *>)["instructions"])
            assertEquals("__proto__", rendered.questions.keys.last())

            @Suppress("UNCHECKED_CAST")
            val rawTemplateState = useCase.decisionTemplate!!.state as MutableMap<String, Any?>
            assertFailsWith<RuntimeException> { rawTemplateState["diary"] = "poisoned" }

            @Suppress("UNCHECKED_CAST")
            val mutableRenderedState = rendered.state as MutableMap<String, Any?>
            mutableRenderedState["diary"] = "changed after render"
            val renderedAgain = useCase.decision(mapOf("diary" to "A bright walk.")).state as Map<*, *>
            assertEquals("A bright walk.", renderedAgain["diary"])

            val request =
                useCase.request(
                    mapOf("diary" to "A bright walk."),
                    PreparedRequestOptions(
                        providerOptions = mapOf("only" to null),
                        sessionId = "session-1",
                        trace = mapOf("trace_id" to "trace-1"),
                        user = "user-1",
                    ),
                )

            assertEquals("decisions", request.api)
            assertEquals("POST", request.method)
            assertEquals("/api/v1/systemone", request.path)
            assertEquals("typesafe/jev-1.13", request.body["model"])
            assertEquals("session-1", request.body["session_id"])
            assertEquals(mapOf("trace_id" to "trace-1"), request.body["trace"])
            assertEquals("user-1", request.body["user"])
            assertEquals(mapOf("only" to null), request.body["provider"])
            assertFalse(request.body.containsKey("messages"))
            assertFalse(request.body.containsKey("usage"))
        }
    }

    @Test
    fun `Decision documents load through remote disk and bundle tiers`() {
        val disk = tempDir.resolve("prompts.production.json")
        Files.writeString(disk, decisionDocument().replace("typesafe/jev-1.13", "typesafe/from-disk"))
        PromptOn(config(diskCache = disk), FakeClock()).use { prompton ->
            assertEquals("typesafe/from-disk", prompton.useCase("sentiment").model)
        }

        val bundle = tempDir.resolve("bundle.json")
        Files.writeString(bundle, decisionDocument().replace("typesafe/jev-1.13", "typesafe/from-bundle"))
        PromptOn(config(bundle = bundle), FakeClock()).use { prompton ->
            assertEquals("typesafe/from-bundle", prompton.useCase("sentiment").model)
        }

        val transport = StubTransport { HttpResponse(200, mapOf("etag" to "\"decision\""), decisionDocument()) }
        PromptOn(config(transport), FakeClock()).use { prompton ->
            val useCase = prompton.useCase("sentiment")
            assertEquals(UseCaseSource.REMOTE, useCase.source)
            assertEquals("typesafe/jev-1.13", useCase.model)
            assertTrue(transport.lastRequest().url.contains("/prompts/sentiment?environment=production"))
        }
    }

    @Test
    fun `prepared request rejects malformed Decision metadata and payloads`() {
        PromptOn(config(mode = PromptOnMode.TEST), FakeClock()).use { prompton ->
            prompton.putUseCaseDocument(decisionDocument(), UseCaseSource.MANUAL)
            val useCase = prompton.useCase("sentiment")

            assertFailsWith<PreparedRequestException> {
                useCase.request(mapOf("diary" to "hi"), PreparedRequestOptions(params = mapOf("temperature" to null)))
            }
            assertFailsWith<PreparedRequestException> {
                useCase.request(mapOf("diary" to "hi"), PreparedRequestOptions(params = mapOf("user" to null)))
            }
            assertFailsWith<PreparedRequestException> {
                useCase.request(mapOf("diary" to "hi"), PreparedRequestOptions(params = mapOf("state" to "bad")))
            }
        }

        PromptOn(config(mode = PromptOnMode.TEST), FakeClock()).use { prompton ->
            prompton.putUseCaseDocument(
                decisionDocument().replace(
                    "\"strong\"",
                    "null",
                ),
                UseCaseSource.MANUAL,
            )
            assertFailsWith<PreparedRequestException> {
                prompton.useCase("sentiment").request(mapOf("diary" to "hi"))
            }
        }

        assertFailsWith<UnsupportedSchemaVersionException> {
            UseCaseDocument.parse(decisionDocument().replace("\"schema_version\": 1", "\"schema_version\": 5"))
        }

        PromptOn(config(), FakeClock()).use { prompton ->
            prompton.putUseCaseDocument(decisionDocumentWithoutPinnedKind(), UseCaseSource.MANUAL)
            val useCase = prompton.useCase("sentiment")
            assertEquals(UseCaseKind.DECISION, useCase.kind)
            assertFailsWith<PreparedRequestException> {
                useCase.request(mapOf("diary" to "hi"))
            }
        }
    }

    @Test
    fun `Decision provider results and inputDecision are captured in monitoring logs`() {
        PromptOn(config(mode = PromptOnMode.TEST), FakeClock()).use { prompton ->
            prompton.putUseCaseDocument(decisionDocument(), UseCaseSource.MANUAL)
            val useCase = prompton.useCase("sentiment")
            val inputDecision = useCase.decision(mapOf("diary" to "A bright walk."))
            val answers =
                mapOf(
                    "mood" to
                        mapOf(
                            "type" to "choice",
                            "choice" to "positive",
                            "probabilities" to mapOf("positive" to 0.9, "negative" to 0.1),
                        ),
                    "urgent" to mapOf("type" to "noul", "noul" to 0, "confidence" to 0.7),
                    "intensity" to mapOf("type" to "score", "score" to 4),
                )
            val raw =
                mapOf(
                    "model" to "typesafe/jev-1.13",
                    "provider" to "OpenRouter",
                    "answers" to answers,
                    "usage" to mapOf("input_tokens" to 12, "output_tokens" to 5, "cost" to 0),
                )

            useCase.trackBlocking(TrackMeta(inputDecision = inputDecision)) { call ->
                call.result(Result.fromDecisions(raw))
                "ok"
            }

            val byok =
                Result.fromDecisions(
                    raw +
                        mapOf(
                            "usage" to
                                mapOf(
                                    "input_tokens" to 0,
                                    "output_tokens" to 0,
                                    "cost" to 0,
                                    "is_byok" to true,
                                    "cost_details" to mapOf("upstream_inference_cost" to 0.01),
                                ),
                        ),
                )
            assertEquals(0.01, byok.usage?.costUsd)
            assertTrue(byok.isByok == true)

            val record = prompton.capturedRecords().single()
            assertEquals("decision", (record["kind"] as JsonPrimitive).content)
            assertEquals("sentiment", (record["prompt_key"] as JsonPrimitive).content)
            assertEquals("default", (record["template"] as JsonPrimitive).content)
            assertEquals("1", (record["prompt_version_number"] as JsonPrimitive).content)
            assertFalse(record.containsKey("deployment_id"))
            assertFalse(record.containsKey("prompt_version_id"))
            assertFalse(record.containsKey("model_id"))
            val input = record["input"] as JsonObject
            assertEquals(
                Ptn.canonicalJson(Ptn.toObject(inputDecision.asMap())),
                Ptn.canonicalJson(input["decision"]!!),
            )
            val output = record["output"] as JsonObject
            assertEquals(
                Ptn.canonicalJson(Ptn.toObject(answers)),
                (output["content"] as JsonPrimitive).content,
            )
            val usage = record["usage"] as JsonObject
            assertEquals("12", (usage["input_tokens"] as JsonPrimitive).content)
            assertEquals("5", (usage["output_tokens"] as JsonPrimitive).content)
            assertEquals("0.0", (usage["cost_usd"] as JsonPrimitive).content)
        }
    }

    @Test
    fun `malformed Decision provider responses are rejected`() {
        assertFailsWith<PromptOnException> { Result.fromDecisions(mapOf("answers" to emptyMap<String, Any?>())) }
        assertFailsWith<PromptOnException> {
            Result.fromDecisions(
                mapOf(
                    "model" to "typesafe/jev-1.13",
                    "answers" to mapOf("urgent" to mapOf("type" to "noul", "noul" to 0)),
                    "usage" to mapOf("input_tokens" to 1.5, "output_tokens" to 1),
                ),
            )
        }
        assertFailsWith<PromptOnException> {
            Result.fromDecisions(
                mapOf(
                    "model" to 123,
                    "answers" to mapOf("urgent" to mapOf("type" to "noul", "noul" to 0)),
                    "usage" to mapOf("input_tokens" to 1, "output_tokens" to 1),
                ),
            )
        }
        assertFailsWith<PromptOnException> {
            Result.fromDecisions(
                mapOf(
                    "model" to "typesafe/jev-1.13",
                    "answers" to mapOf("urgent" to mapOf("type" to "noul", "noul" to false)),
                    "usage" to mapOf("input_tokens" to 1, "output_tokens" to 1),
                ),
            )
        }
    }

    @Test
    fun `remote Decision rendering preserves native decision and prepared request through cache`() {
        val decision =
            mapOf(
                "state" to "hello",
                "questions" to mapOf("accept" to mapOf("type" to "noul", "instructions" to "Accept?")),
            )
        val decisionQuestions = mapOf("accept" to mapOf("type" to "noul", "instructions" to "Accept?"))
        val request =
            mapOf(
                "api" to "decisions",
                "method" to "post",
                "path" to "/api/v1/systemone",
                "body" to mapOf("model" to "typesafe/jev-1.13") + decision,
            )
        val rawAnswer =
            Ptn.canonicalJson(
                Ptn.toObject(
                    mapOf(
                        "key" to "sentiment",
                        "kind" to "decision",
                        "model" to "typesafe/jev-1.13",
                        "provider" to "openrouter",
                        "api" to "decisions",
                        "request_path" to "/api/v1/systemone",
                        "decision" to decision,
                        "request" to request,
                    ),
                ),
            )
        val transport = StubTransport { request ->
            if (request.url.endsWith("/prompts/sentiment/render")) {
                HttpResponse(200, emptyMap(), rawAnswer)
            } else {
                HttpResponse(304)
            }
        }

        PromptOn(config(transport), FakeClock()).use { prompton ->
            val first = prompton.promptOnServerBlocking("sentiment", variables = emptyMap())
            assertEquals(RenderedDecision("hello", decisionQuestions), first.decision)
            assertEquals("decisions", first.providerPreparedRequest["api"])
            assertEquals("POST", first.providerPreparedRequest["method"])

            val cached = prompton.useCaseRemoteBlocking("sentiment")
            assertEquals(UseCaseKind.DECISION, cached.kind)
            assertEquals("hello", cached.decision().state)

            @Suppress("UNCHECKED_CAST")
            val preparedRequest = cached.providerPreparedRequest as MutableMap<String, Any?>
            assertFailsWith<RuntimeException> { preparedRequest["api"] = "poisoned" }

            @Suppress("UNCHECKED_CAST")
            val preparedBody = cached.providerPreparedRequest["body"] as MutableMap<String, Any?>
            assertFailsWith<RuntimeException> { preparedBody["model"] = "poisoned" }

            val cachedAgain = prompton.useCaseRemoteBlocking("sentiment")
            assertEquals("decisions", cachedAgain.providerPreparedRequest["api"])
            assertEquals("typesafe/jev-1.13", (cachedAgain.providerPreparedRequest["body"] as Map<*, *>)["model"])
            assertEquals(2, transport.posts().size, "server smoke path plus one cached raw Decision fetch")
        }
    }

    private fun decisionDocumentWithoutPinnedKind(): String {
        val document = decisionDocument()
        val promptVersions = document.indexOf("\"prompt_versions\"")
        return document.substring(0, promptVersions) +
            document.substring(promptVersions).replaceFirst("\"kind\": \"decision\",\n", "")
    }

    private fun decisionDocument(): String =
        """
        {
          "schema_version": 1,
          "project": "fixture",
          "environment": "production",
          "prompts": {
            "sentiment": {
              "kind": "decision",
              "default_params": {},
              "payload_policy": {
                "mode": "full",
                "sample_rate": 1.0,
                "max_bytes": 262144
              },
              "variables": [
                {
                  "name": "diary",
                  "type": "string",
                  "required": true
                }
              ],
              "key": "sentiment"
            }
          },
          "deployments": {
            "sentiment": {
              "revision": "v2026.09.30-5",
              "api": "decisions",
              "request_path": "/api/v1/systemone",
              "params": {},
              "provider_options": {},
              "provider": "openrouter",
              "model": "typesafe/jev-1.13",
              "prompt_version": 1
            }
          },
          "prompt_versions": {
            "sentiment@1": {
              "number": 1,
              "kind": "decision",
              "engine": "liquid",
              "messages": [],
              "text_template": null,
              "decision": {
                "state": {
                  "diary": "{{ diary }}",
                  "static_key": "literal"
                },
                "questions": {
                  "mood": {
                    "type": "choice",
                    "instructions": "Classify {{ diary }}.",
                    "criteria": {
                      "positive": "The diary feels upbeat.",
                      "negative": "The diary feels difficult."
                    }
                  },
                  "urgent": {
                    "type": "noul",
                    "instructions": "Is action needed?",
                    "criteria": {
                      "true": "Needs follow up.",
                      "false": "No follow up."
                    }
                  },
                  "intensity": {
                    "type": "score",
                    "instructions": "Rate intensity.",
                    "criteria": [
                      "calm",
                      "strong"
                    ]
                  },
                  "__proto__": {
                    "type": "noul",
                    "instructions": "Read {{ diary }}"
                  }
                }
              },
              "prompt": "sentiment"
            }
          },
          "models": {
            "openrouter:typesafe/jev-1.13": {
              "provider": "openrouter",
              "display_name": "Jev",
              "metadata": {
                "output_modalities": [
                  "decisions"
                ]
              },
              "provider_options": {},
              "capabilities": [
                "decisions"
              ],
              "status": "active",
              "model": "typesafe/jev-1.13"
            }
          }
        }
        """.trimIndent()
}
