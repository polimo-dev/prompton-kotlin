package dev.polimo.prompton.conformance

import dev.polimo.prompton.MissingVariableException
import dev.polimo.prompton.PromptMessage
import dev.polimo.prompton.PromptOnException
import dev.polimo.prompton.Resolver
import dev.polimo.prompton.UnknownPromptException
import dev.polimo.prompton.UnknownUseCaseException
import dev.polimo.prompton.UnresolvedUseCaseException
import dev.polimo.prompton.UseCase
import dev.polimo.prompton.UseCaseDocument
import dev.polimo.prompton.UseCaseKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/** Runs every case of `conformance/render.json`: the prompt algorithm the server prompt endpoint runs. */
class UseCaseConformanceTest {
    @Test
    fun `prompt cases`() {
        val file = Conformance.load("prompt")
        val documents =
            (file["documents"] as JsonObject).entries.associate { (name, document) ->
                name to UseCaseDocument.parse(document.toString())
            }

        var executed = 0
        for (case in Conformance.cases("prompt")) {
            val name = Conformance.string(case, "name")!!
            val document = documents.getValue(Conformance.string(case, "document_ref")!!)
            val key = Conformance.string(case, "prompt_key")!!
            val prompt = Conformance.string(case, "template")
            val hasVariables = case.containsKey("variables")
            val variables = Conformance.nativeMap(case["variables"])
            val expect = case["expect"] as JsonObject

            val actual =
                try {
                    val useCase = Resolver.resolve(document, key, prompt)
                    describe(useCase, hasVariables, variables)
                } catch (e: UnknownUseCaseException) {
                    buildJsonObject {
                        put("error", "unknown_prompt")
                        put("key", e.useCase)
                    }
                } catch (e: UnresolvedUseCaseException) {
                    buildJsonObject { put("error", "unresolved") }
                } catch (e: UnknownPromptException) {
                    buildJsonObject {
                        put("error", "unknown_template")
                        put("key", e.useCase)
                        put("template", e.prompt)
                        put("template_names", JsonArray(e.promptNames.map { JsonPrimitive(it) }))
                    }
                } catch (e: MissingVariableException) {
                    buildJsonObject {
                        put("error", "missing_variable")
                        put("variable", e.variable)
                    }
                }

            assertEquals(expect, actual, name)
            executed += 1
        }
        assertEquals(16, executed)
    }

    @Test
    fun `native tool messages survive rendering as whole provider maps`() {
        val document = UseCaseDocument.parse(
            """
            {"schema_version": 7, "project": "p", "environment": "production",
             "prompts": {"tool_chat": {"id": "u1", "kind": "chat", "default_params": {}}},
             "deployments": {"tool_chat": {"id": "d1", "revision": "v2026.09.30-1", "model_id": "m1",
                                          "params": {}, "provider_options": {},
                                          "template_pins": {"default": "v1"}}},
             "prompt_versions": {"v1": {"id": "v1", "number": 1, "engine": "liquid",
                "messages": [
                  {"role":"system","content":"Continue with {{ input }}."},
                  {"role":"assistant","tool_calls":[{"id":"call_search","type":"function","function":{"name":"search","arguments":"{\"q\":\"diary\"}"}}],"content":null},
                  {"role":"tool","tool_call_id":"call_search","content":[{"type":"text","text":"found"}]},
                  {"role":"user","content":"Next: {{ input }}"}
                ]}},
             "models": {"m1": {"id": "m1", "provider": "openrouter", "model_id": "openai/gpt-4o-mini",
                               "provider_options": {}, "capabilities": ["tools"], "status": "active"}}
            }
            """.trimIndent(),
        )
        val actual =
            JsonArray(
                Resolver.resolve(document, "tool_chat").messages(mapOf("input" to "continue")).map { messageJson(it) },
            )
        val expected =
            dev.polimo.prompton.internal.Ptn.parseObject(
                """
                {"messages":[
                  {"role":"system","content":"Continue with continue."},
                  {"role":"assistant","tool_calls":[{"id":"call_search","type":"function","function":{"name":"search","arguments":"{\"q\":\"diary\"}"}}],"content":null},
                  {"role":"tool","tool_call_id":"call_search","content":[{"type":"text","text":"found"}]},
                  {"role":"user","content":"Next: continue"}
                ]}
                """.trimIndent(),
            )["messages"]!!
        assertEquals(
            dev.polimo.prompton.internal.Ptn
                .canonicalJson(expected),
            dev.polimo.prompton.internal.Ptn
                .canonicalJson(actual),
        )
    }

    @Test
    fun `prompt tools become provider params and strip authoring metadata`() {
        val document = UseCaseDocument.parse(
            """
            {"schema_version": 7, "project": "p", "environment": "production",
             "prompts": {"tool_chat": {"id": "u1", "kind": "chat", "default_params": {}}},
             "deployments": {"tool_chat": {"id": "d1", "revision": "v2026.09.30-1", "model_id": "m1",
                                          "params": {}, "provider_options": {},
                                          "template_pins": {"default": "v1"}}},
             "prompt_versions": {"v1": {"id": "v1", "number": 1, "engine": "liquid",
                "messages": [{"role":"user","content":"hi"}],
                "tools": {"definitions": [{"type":"function","function":{"name":"search"},
                                             "output_schema":{"type":"object"},"output_examples":[{"ok":true}]}],
                          "tool_choice": {"type":"function","function":{"name":"search"}},
                          "parallel_tool_calls": false}}},
             "models": {"m1": {"id": "m1", "provider": "openrouter", "model_id": "openai/gpt-4o-mini",
                               "provider_options": {}, "capabilities": ["tools"], "status": "active"}}
            }
            """.trimIndent(),
        )
        val params = Resolver.resolve(document, "tool_chat").params

        @Suppress("UNCHECKED_CAST")
        val tool = (params["tools"] as List<Map<String, Any?>>).single()
        assertFalse(tool.containsKey("output_schema"))
        assertFalse(tool.containsKey("output_examples"))
        assertEquals(mapOf("type" to "function", "function" to mapOf("name" to "search")), tool)
        assertEquals(false, params["parallel_tool_calls"])
    }

    @Test
    fun `prompt tools conflict with different legacy provider params`() {
        val document = UseCaseDocument.parse(
            """
            {"schema_version": 7, "project": "p", "environment": "production",
             "prompts": {"tool_chat": {"id": "u1", "kind": "chat", "default_params": {}}},
             "deployments": {"tool_chat": {"id": "d1", "revision": "v2026.09.30-1", "model_id": "m1",
                                          "params": {"parallel_tool_calls": true}, "provider_options": {},
                                          "template_pins": {"default": "v1"}}},
             "prompt_versions": {"v1": {"id": "v1", "number": 1, "engine": "liquid",
                "messages": [{"role":"user","content":"hi"}],
                "tools": {"parallel_tool_calls": false}}},
             "models": {"m1": {"id": "m1", "provider": "openrouter", "model_id": "openai/gpt-4o-mini",
                               "provider_options": {}, "capabilities": ["tools"], "status": "active"}}
            }
            """.trimIndent(),
        )
        assertFailsWith<PromptOnException> { Resolver.resolve(document, "tool_chat") }
    }

    private fun describe(
        useCase: UseCase,
        hasVariables: Boolean,
        variables: Map<String, Any?>?,
    ): JsonObject {
        val fields = LinkedHashMap<String, JsonElement>()
        fields["deployment_id"] = nullable(useCase.deploymentId)
        fields["key"] = JsonPrimitive(useCase.key)
        fields["revision"] = useCase.deploymentRevision?.let { JsonPrimitive(it) } ?: JsonNull
        fields["kind"] = JsonPrimitive(useCase.kind.wire)
        fields["template"] = nullable(useCase.prompt)
        fields["template_names"] = JsonArray(useCase.promptNames.map { JsonPrimitive(it) })
        fields["model"] = nullable(useCase.model)
        fields["model_id"] = nullable(useCase.modelId)
        fields["provider"] = nullable(useCase.provider)
        fields["params"] = jsonOf(useCase.params)
        fields["provider_options"] = jsonOf(useCase.providerOptions)
        fields["prompt_version"] =
            useCase.promptVersionId?.let { id ->
                buildJsonObject {
                    put("id", id)
                    put("number", useCase.promptVersionNumber)
                }
            } ?: JsonNull
        fields["warnings"] = JsonArray(useCase.warnings.map { JsonPrimitive(it) })
        fields["source"] = JsonPrimitive(useCase.source.wire)

        if (useCase.kind == UseCaseKind.CHAT && useCase.messageTemplates != null) {
            val messages =
                if (hasVariables) useCase.messages(variables) else useCase.messageTemplates!!
            fields["messages"] = JsonArray(messages.map { messageJson(it) })
        }
        if (useCase.kind == UseCaseKind.TEXT && useCase.textTemplate != null) {
            val text = if (hasVariables) useCase.text(variables) else useCase.textTemplate!!
            fields["text"] = JsonPrimitive(text)
        }
        return JsonObject(fields)
    }

    private fun messageJson(message: PromptMessage): JsonObject {
        val fields = LinkedHashMap<String, JsonElement>()
        fields.putAll(message.extra.mapValues { element(it.value) })
        message.type?.let { fields["type"] = JsonPrimitive(it) }
        if (message.role.isNotBlank()) fields["role"] = JsonPrimitive(message.role)
        if (message.hasContent) fields["content"] = element(message.contentValue)
        message.name?.let { fields["name"] = JsonPrimitive(it) }
        message.toolCallId?.let { fields["tool_call_id"] = JsonPrimitive(it) }
        if (message.hasToolCalls || message.toolCalls.isNotEmpty()) {
            fields["tool_calls"] = JsonArray(
                message.toolCalls.map { element(it) },
            )
        }
        return JsonObject(fields)
    }

    private fun nullable(value: String?): JsonElement = value?.let { JsonPrimitive(it) } ?: JsonNull

    private fun jsonOf(map: Map<String, Any?>): JsonObject =
        JsonObject(map.entries.associate { (key, value) -> key to element(value) })

    private fun element(value: Any?): JsonElement =
        when (value) {
            null -> JsonNull
            is String -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is List<*> -> JsonArray(value.map { element(it) })
            is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to element(v) })
            else -> JsonPrimitive(value.toString())
        }
}
