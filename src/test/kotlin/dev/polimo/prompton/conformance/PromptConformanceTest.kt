package dev.polimo.prompton.conformance

import dev.polimo.prompton.MissingVariableException
import dev.polimo.prompton.Prompt
import dev.polimo.prompton.PromptDocument
import dev.polimo.prompton.PromptKind
import dev.polimo.prompton.PromptMessage
import dev.polimo.prompton.PromptOnException
import dev.polimo.prompton.Resolver
import dev.polimo.prompton.UnknownPromptException
import dev.polimo.prompton.UnknownTemplateException
import dev.polimo.prompton.UnresolvedPromptException
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
class PromptConformanceTest {
    @Test
    fun `prompt cases`() {
        val file = Conformance.load("prompt")
        val documents =
            (file["documents"] as JsonObject).entries.associate { (name, document) ->
                name to PromptDocument.parse(document.toString())
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
                    val prompt = Resolver.resolve(document, key, prompt)
                    describe(prompt, hasVariables, variables)
                } catch (e: UnknownPromptException) {
                    buildJsonObject {
                        put("error", "unknown_prompt")
                        put("key", e.promptKey)
                    }
                } catch (e: UnresolvedPromptException) {
                    buildJsonObject { put("error", "unresolved") }
                } catch (e: UnknownTemplateException) {
                    buildJsonObject {
                        put("error", "unknown_template")
                        put("key", e.promptKey)
                        put("template", e.template)
                        put("template_names", JsonArray(e.templateNames.map { JsonPrimitive(it) }))
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
        val document = PromptDocument.parse(
            """
            {
              "schema_version": 1,
              "project": "p",
              "environment": "production",
              "prompts": {
                "tool_chat": {
                  "kind": "chat",
                  "default_params": {},
                  "key": "tool_chat"
                }
              },
              "deployments": {
                "tool_chat": {
                  "revision": "v2026.09.30-1",
                  "params": {},
                  "provider_options": {},
                  "provider": "openrouter",
                  "model": "openai/gpt-4o-mini",
                  "prompt_version": 1
                }
              },
              "prompt_versions": {
                "tool_chat@1": {
                  "number": 1,
                  "engine": "liquid",
                  "messages": [
                    {
                      "role": "system",
                      "content": "Continue with {{ input }}."
                    },
                    {
                      "role": "assistant",
                      "tool_calls": [
                        {
                          "id": "call_search",
                          "type": "function",
                          "function": {
                            "name": "search",
                            "arguments": "{\"q\":\"diary\"}"
                          }
                        }
                      ],
                      "content": null
                    },
                    {
                      "role": "tool",
                      "tool_call_id": "call_search",
                      "content": [
                        {
                          "type": "text",
                          "text": "found"
                        }
                      ]
                    },
                    {
                      "role": "user",
                      "content": "Next: {{ input }}"
                    }
                  ],
                  "prompt": "tool_chat",
                  "kind": "chat"
                }
              },
              "models": {
                "openrouter:openai/gpt-4o-mini": {
                  "provider": "openrouter",
                  "provider_options": {},
                  "capabilities": [
                    "tools"
                  ],
                  "status": "active",
                  "model": "openai/gpt-4o-mini"
                }
              }
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
        val document = PromptDocument.parse(
            """
            {
              "schema_version": 1,
              "project": "p",
              "environment": "production",
              "prompts": {
                "tool_chat": {
                  "kind": "chat",
                  "default_params": {},
                  "key": "tool_chat"
                }
              },
              "deployments": {
                "tool_chat": {
                  "revision": "v2026.09.30-1",
                  "params": {},
                  "provider_options": {},
                  "provider": "openrouter",
                  "model": "openai/gpt-4o-mini",
                  "prompt_version": 1
                }
              },
              "prompt_versions": {
                "tool_chat@1": {
                  "number": 1,
                  "engine": "liquid",
                  "messages": [
                    {
                      "role": "user",
                      "content": "hi"
                    }
                  ],
                  "tools": {
                    "definitions": [
                      {
                        "type": "function",
                        "function": {
                          "name": "search"
                        },
                        "output_schema": {
                          "type": "object"
                        },
                        "output_examples": [
                          {
                            "ok": true
                          }
                        ]
                      }
                    ],
                    "tool_choice": {
                      "type": "function",
                      "function": {
                        "name": "search"
                      }
                    },
                    "parallel_tool_calls": false
                  },
                  "prompt": "tool_chat",
                  "kind": "chat"
                }
              },
              "models": {
                "openrouter:openai/gpt-4o-mini": {
                  "provider": "openrouter",
                  "provider_options": {},
                  "capabilities": [
                    "tools"
                  ],
                  "status": "active",
                  "model": "openai/gpt-4o-mini"
                }
              }
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
        val document = PromptDocument.parse(
            """
            {
              "schema_version": 1,
              "project": "p",
              "environment": "production",
              "prompts": {
                "tool_chat": {
                  "kind": "chat",
                  "default_params": {},
                  "key": "tool_chat"
                }
              },
              "deployments": {
                "tool_chat": {
                  "revision": "v2026.09.30-1",
                  "params": {
                    "parallel_tool_calls": true
                  },
                  "provider_options": {},
                  "provider": "openrouter",
                  "model": "openai/gpt-4o-mini",
                  "prompt_version": 1
                }
              },
              "prompt_versions": {
                "tool_chat@1": {
                  "number": 1,
                  "engine": "liquid",
                  "messages": [
                    {
                      "role": "user",
                      "content": "hi"
                    }
                  ],
                  "tools": {
                    "parallel_tool_calls": false
                  },
                  "prompt": "tool_chat",
                  "kind": "chat"
                }
              },
              "models": {
                "openrouter:openai/gpt-4o-mini": {
                  "provider": "openrouter",
                  "provider_options": {},
                  "capabilities": [
                    "tools"
                  ],
                  "status": "active",
                  "model": "openai/gpt-4o-mini"
                }
              }
            }
            """.trimIndent(),
        )
        assertFailsWith<PromptOnException> { Resolver.resolve(document, "tool_chat") }
    }

    private fun describe(
        prompt: Prompt,
        hasVariables: Boolean,
        variables: Map<String, Any?>?,
    ): JsonObject {
        val fields = LinkedHashMap<String, JsonElement>()
        fields["deployment_id"] = nullable(prompt.deploymentId)
        fields["key"] = JsonPrimitive(prompt.key)
        fields["revision"] = prompt.deploymentRevision?.let { JsonPrimitive(it) } ?: JsonNull
        fields["kind"] = JsonPrimitive(prompt.kind.wire)
        fields["template"] = nullable(prompt.template)
        fields["template_names"] = JsonArray(prompt.templateNames.map { JsonPrimitive(it) })
        fields["model"] = nullable(prompt.model)
        fields["model_id"] = nullable(prompt.modelId)
        fields["provider"] = nullable(prompt.provider)
        fields["params"] = jsonOf(prompt.params)
        fields["provider_options"] = jsonOf(prompt.providerOptions)
        fields["prompt_version"] =
            prompt.promptVersionNumber?.let { number ->
                buildJsonObject {
                    put("id", JsonNull)
                    put("number", number)
                }
            } ?: JsonNull
        fields["warnings"] = JsonArray(prompt.warnings.map { JsonPrimitive(it) })
        fields["source"] = JsonPrimitive(prompt.source.wire)

        if (prompt.kind == PromptKind.CHAT && prompt.messageTemplates != null) {
            val messages =
                if (hasVariables) prompt.messages(variables) else prompt.messageTemplates!!
            fields["messages"] = JsonArray(messages.map { messageJson(it) })
        }
        if (prompt.kind == PromptKind.TEXT && prompt.textTemplate != null) {
            val text = if (hasVariables) prompt.text(variables) else prompt.textTemplate!!
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
