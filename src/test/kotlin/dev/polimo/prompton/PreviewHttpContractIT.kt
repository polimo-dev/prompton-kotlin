package dev.polimo.prompton

import dev.polimo.prompton.internal.Ptn
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.seconds

@EnabledIfEnvironmentVariable(named = "PTN_HTTP_CREDENTIALS", matches = ".+")
class PreviewHttpContractIT {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `preview tool prompt round trips and logs`() {
        val credentialsPath = Path.of(System.getenv("PTN_HTTP_CREDENTIALS"))
        val dir = credentialsPath.parent
        val credentials = Ptn.parseObject(Files.readString(credentialsPath))
        val expectedRender = Ptn.parseObject(Files.readString(dir.resolve("prompton-sdk-http-render.json")))
        val logRequest = Ptn.parseObject(Files.readString(dir.resolve("prompton-sdk-http-logs-request.json")))
        val transport = RecordingTransport()
        val key = Ptn.asString(credentials["prompt_key"])!!
        val environment = Ptn.asString(credentials["environment"])!!

        PromptOn(
            PromptOnConfig(
                apiKey = Ptn.asString(credentials["api_key"]),
                host = Ptn.asString(credentials["base_url"])!!,
                environment = environment,
                pollingEnabled = false,
                requestTimeout = 10.seconds,
                diskCachePath = tempDir.resolve("preview-prompts.json"),
                transport = transport,
            ),
        ).use { prompton ->
            val variables = variables()

            val local = prompton.useCase(key)
            assertEquals(key, local.key)
            assertProviderPayload(expectedRender, local.messages(variables), local.params)

            val remote = prompton.promptOnServerBlocking(key, variables = variables)
            assertMessages(expectedRender, remote.messages ?: emptyList())
            assertFalse(remote.providerPreparedRequest.isEmpty(), "remote render preserves request")
            assertEquals(
                Ptn.canonicalJson(expectedRender["request"]!!),
                Ptn.canonicalJson(Ptn.toObject(remote.providerPreparedRequest)),
            )
            val preparedBody = remote.providerPreparedRequest["body"] as Map<*, *>
            assertEquals(
                Ptn.canonicalJson(((expectedRender["request"] as JsonObject)["body"] as JsonObject)["messages"]!!),
                Ptn.canonicalJson(Ptn.toElement(preparedBody["messages"])),
            )
            assertEquals(
                Ptn.canonicalJson(((expectedRender["request"] as JsonObject)["body"] as JsonObject)["tools"]!!),
                Ptn.canonicalJson(Ptn.toElement(preparedBody["tools"])),
            )

            val generationId = UuidV7.generate()
            val log = LinkedHashMap(Ptn.toNativeMap((logRequest["logs"] as JsonArray)[0]))
            log["id"] = generationId
            log["prompt_key"] = key
            log.remove("sdk")
            if (log.containsKey("prompt")) {
                log["template"] = log.remove("prompt")
            }
            prompton.log(log)
            val flush = prompton.flushBlocking(10.seconds)
            assertEquals(1, flush.accepted, "generation log accepted count")
            assertEquals(0, flush.rejected, "generation log rejected count")

            val events =
                (logRequest["events"] as JsonArray).map { event ->
                    LinkedHashMap(Ptn.toNativeMap(event)).also {
                        it["event_id"] = "evt-kotlin-${UuidV7.generate()}"
                        it["generation_id"] = generationId
                        it.remove("sdk")
                    }
                }
            val eventsResult = prompton.logEvents(events, environment)
            assertEquals(2, eventsResult.accepted)
            assertEquals(0, eventsResult.duplicates)
            assertEquals(0, eventsResult.rejected.size)
        }
    }

    private fun assertProviderPayload(
        expectedRender: JsonObject,
        messages: List<PromptMessage>,
        params: Map<String, Any?>,
    ) {
        val requestBody = ((expectedRender["request"] as JsonObject)["body"] as JsonObject)
        assertMessages(expectedRender, messages)
        assertEquals(
            Ptn.canonicalJson(requestBody["tools"]!!),
            Ptn.canonicalJson(Ptn.toElement(params["tools"])),
        )
        assertEquals("auto", params["tool_choice"])
        assertEquals(false, params["parallel_tool_calls"])
        val tool = (params["tools"] as List<*>).first() as Map<*, *>
        assertFalse(tool.containsKey("output_schema"), "authoring output_schema is stripped from provider tool")
        assertFalse(tool.containsKey("output_examples"), "authoring output_examples is stripped from provider tool")
    }

    private fun assertMessages(
        expectedRender: JsonObject,
        messages: List<PromptMessage>,
    ) {
        val requestBody = ((expectedRender["request"] as JsonObject)["body"] as JsonObject)
        assertEquals(
            Ptn.canonicalJson(requestBody["messages"]!!),
            Ptn.canonicalJson(JsonArray(messages.map { messageElement(it) })),
        )
    }

    private fun messageElement(message: PromptMessage): JsonObject {
        val fields = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
        message.extra.forEach { (key, value) -> fields[key] = Ptn.toElement(value) }
        message.type?.let { fields["type"] = JsonPrimitive(it) }
        fields["role"] = JsonPrimitive(message.role)
        if (message.hasContent || message.type != "slot") fields["content"] = Ptn.toElement(message.contentValue)
        message.name?.let { fields["name"] = JsonPrimitive(it) }
        message.toolCallId?.let { fields["tool_call_id"] = JsonPrimitive(it) }
        if (message.hasToolCalls || message.toolCalls.isNotEmpty()) {
            fields["tool_calls"] = JsonArray(message.toolCalls.map { Ptn.toElement(it) })
        }
        return JsonObject(fields)
    }

    private fun variables(): Map<String, Any?> {
        val assistant = linkedMapOf<String, Any?>(
            "role" to "assistant",
            "content" to null,
            "tool_calls" to
                listOf(
                    mapOf(
                        "id" to "call_prior_1",
                        "type" to "function",
                        "function" to
                            mapOf(
                                "name" to "search_diaries",
                                "arguments" to "{\"query\":\"park walks\",\"limit\":1}",
                            ),
                    ),
                ),
        )
        val history =
            listOf(
                mapOf("role" to "user", "content" to "지난 산책 일기를 찾아줘"),
                assistant,
                mapOf(
                    "role" to "tool",
                    "name" to "search_diaries",
                    "tool_call_id" to "call_prior_1",
                    "content" to listOf(mapOf("type" to "text", "text" to "{\"entries\":[\"A prior park walk.\"]}")),
                ),
            )
        return mapOf("locale" to "ko-KR", "topic" to "park walks", "history" to history)
    }

    private class RecordingTransport : HttpTransport {
        private val delegate = JdkHttpTransport(5.seconds, 10.seconds)
        private val exchanges = mutableListOf<Pair<HttpRequest, HttpResponse>>()

        override fun execute(request: HttpRequest): HttpResponse =
            delegate.execute(request).also { exchanges.add(request to it) }

        fun lastJsonResponseFor(path: String): JsonObject =
            exchanges
                .asReversed()
                .firstOrNull { it.first.url.contains(path) }
                ?.second
                ?.body
                ?.let { Ptn.parseObject(it) }
                ?: error("no response recorded for $path")

        override fun close() = delegate.close()
    }
}
