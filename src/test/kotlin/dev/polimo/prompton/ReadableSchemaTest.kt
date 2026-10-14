package dev.polimo.prompton

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReadableSchemaTest {
    @Test
    fun `offline bundle uses readable resource references without public ids`() {
        val document =
            PromptDocument.parse(
                """
                {"schema_version": 1, "project": "p", "environment": "production",
                 "prompts": {
                   "greeting": {"kind": "chat", "default_params": {"temperature": 0.5}},
                   "classify": {"kind": "decision", "default_params": {}}
                 },
                 "deployments": {
                   "greeting": {"revision": "v2026.10.14-1", "provider": "openrouter",
                                "model": "shared/model", "prompt_version": 2,
                                "params": {"temperature": 0.2}, "provider_options": {"allow_fallbacks": false},
                                "api": "chat_completions", "request_path": "/api/v1/chat/completions"},
                   "classify": {"revision": "v2026.10.14-2", "provider": "anthropic",
                                "model": "shared/model", "prompt_version": 2,
                                "params": {}, "provider_options": {},
                                "api": "decisions", "request_path": "/api/v1/decisions"}
                 },
                 "prompt_versions": {
                   "greeting@2": {"prompt": "greeting", "number": 2, "kind": "chat", "engine": "liquid",
                                  "messages": [{"role": "user", "content": "Hello {{ name }}"}]},
                   "classify@2": {"prompt": "classify", "number": 2, "kind": "decision", "engine": "liquid",
                                   "decision": {"state": {"text": "{{ text }}"},
                                                "questions": {"ok": {"type": "noul", "instructions": "Accept?"}}}}
                 },
                 "models": {
                   "openrouter:shared/model": {"provider": "openrouter", "model": "shared/model",
                                               "display_name": "OpenRouter shared", "provider_options": {"only": ["OpenAI"]},
                                               "metadata": {}, "capabilities": ["tools"], "status": "active"},
                   "anthropic:shared/model": {"provider": "anthropic", "model": "shared/model",
                                              "display_name": "Anthropic shared", "provider_options": {},
                                              "metadata": {}, "capabilities": [], "status": "active"}
                 }}
                """.trimIndent(),
            )

        val greeting = Resolver.resolve(document, "greeting")
        assertEquals(1, document.schemaVersion)
        assertNull(greeting.deploymentId)
        assertNull(greeting.modelId)
        assertNull(greeting.promptVersionId)
        assertEquals(2, greeting.promptVersionNumber)
        assertEquals("openrouter", greeting.provider)
        assertEquals("shared/model", greeting.model)
        assertEquals("Hello Ada", greeting.messages(mapOf("name" to "Ada")).single().content)

        val classify = Resolver.resolve(document, "classify")
        assertNull(classify.modelId)
        assertNull(classify.promptVersionId)
        assertEquals(2, classify.promptVersionNumber)
        assertEquals("anthropic", classify.provider)
        assertEquals(PromptKind.DECISION, classify.kind)
        assertEquals(mapOf("text" to "great"), classify.decision(mapOf("text" to "great")).state)
    }

    @Test
    fun `offline bundle warnings use readable missing references`() {
        val document =
            PromptDocument.parse(
                """
                {"schema_version": 1, "project": "p", "environment": "production",
                 "prompts": {"greeting": {"kind": "chat", "default_params": {}}},
                 "deployments": {"greeting": {"revision": "v2026.10.14-1", "provider": "openrouter",
                                              "model": "missing:model", "prompt_version": 3,
                                              "params": {}, "provider_options": {}}},
                 "prompt_versions": {}, "models": {}}
                """.trimIndent(),
            )
        val prompt = Resolver.resolve(document, "greeting")
        assertEquals(
            listOf("missing_prompt_version: greeting@3", "missing_model: openrouter:missing:model"),
            prompt.warnings,
        )
        assertNull(prompt.modelId)
        assertNull(prompt.promptVersionId)
    }

    @Test
    fun `offline bundle rejects prompt version keys that do not match embedded prompt and number`() {
        assertFailsWith<PromptOnException> {
            PromptDocument.parse(
                """
                {"schema_version": 1, "project": "p", "environment": "production",
                 "prompts": {"greeting": {"kind": "chat", "default_params": {}}},
                 "deployments": {"greeting": {"revision": "v2026.10.14-1", "provider": "openrouter",
                                              "model": "m", "prompt_version": 2,
                                              "params": {}, "provider_options": {}}},
                 "prompt_versions": {"greeting@2": {"prompt": "other", "number": 2,
                                                       "kind": "chat", "engine": "liquid",
                                                       "messages": [{"role": "user", "content": "Hi"}]}},
                 "models": {"openrouter:m": {"provider": "openrouter", "model": "m",
                                               "metadata": {}, "provider_options": {},
                                               "capabilities": [], "status": "active"}}}
                """.trimIndent(),
            )
        }
    }

    @Test
    fun `offline bundle rejects model keys that do not match embedded provider and model`() {
        assertFailsWith<PromptOnException> {
            PromptDocument.parse(
                """
                {"schema_version": 1, "project": "p", "environment": "production",
                 "prompts": {"greeting": {"kind": "chat", "default_params": {}}},
                 "deployments": {"greeting": {"revision": "v2026.10.14-1", "provider": "openrouter",
                                              "model": "m", "prompt_version": 2,
                                              "params": {}, "provider_options": {}}},
                 "prompt_versions": {"greeting@2": {"prompt": "greeting", "number": 2,
                                                       "kind": "chat", "engine": "liquid",
                                                       "messages": [{"role": "user", "content": "Hi"}]}},
                 "models": {"openrouter:m": {"provider": "other", "model": "m",
                                               "metadata": {}, "provider_options": {},
                                               "capabilities": [], "status": "active"}}}
                """.trimIndent(),
            )
        }
    }

    @Test
    fun `offline bundle preserves model names containing colons`() {
        val document =
            PromptDocument.parse(
                """
                {"schema_version": 1, "project": "p", "environment": "production",
                 "prompts": {"greeting": {"kind": "chat", "default_params": {}}},
                 "deployments": {"greeting": {"revision": "v2026.10.14-1", "provider": "openrouter",
                                              "model": "vendor:model:with:colon", "prompt_version": 2,
                                              "params": {}, "provider_options": {}}},
                 "prompt_versions": {"greeting@2": {"prompt": "greeting", "number": 2,
                                                       "kind": "chat", "engine": "liquid",
                                                       "messages": [{"role": "user", "content": "Hi"}]}},
                 "models": {"openrouter:vendor:model:with:colon": {"provider": "openrouter",
                                                                      "model": "vendor:model:with:colon",
                                                                      "metadata": {}, "provider_options": {},
                                                                      "capabilities": [], "status": "active"}}}
                """.trimIndent(),
            )

        assertEquals("vendor:model:with:colon", Resolver.resolve(document, "greeting").model)
    }

    @Test
    fun `offline bundle rejects invalid deployment prompt version values`() {
        val invalidFragments =
            listOf(
                "",
                ", \"prompt_version\": true",
                ", \"prompt_version\": 2.5",
                ", \"prompt_version\": \"2\"",
                ", \"prompt_version\": 0",
                ", \"prompt_version\": -1",
            )
        for (fragment in invalidFragments) {
            assertFailsWith<PromptOnException> {
                PromptDocument.parse(
                    """
                    {"schema_version": 1, "project": "p", "environment": "production",
                     "prompts": {"greeting": {"kind": "chat", "default_params": {}}},
                     "deployments": {"greeting": {"revision": "v2026.10.14-1", "provider": "openrouter",
                                                  "model": "m"$fragment,
                                                  "params": {}, "provider_options": {}}},
                     "prompt_versions": {"greeting@2": {"prompt": "greeting", "number": 2,
                                                           "kind": "chat", "engine": "liquid",
                                                           "messages": [{"role": "user", "content": "Hi"}]}},
                     "models": {"openrouter:m": {"provider": "openrouter", "model": "m",
                                                   "metadata": {}, "provider_options": {},
                                                   "capabilities": [], "status": "active"}}}
                    """.trimIndent(),
                )
            }
        }
    }

    @Test
    fun `offline bundle does not accept legacy deployment aliases`() {
        assertFailsWith<PromptOnException> {
            PromptDocument.parse(
                """
                {"schema_version": 1, "project": "p", "environment": "production",
                 "prompts": {"greeting": {"kind": "chat", "default_params": {}}},
                 "deployments": {"greeting": {"revision": "v2026.10.14-1", "model_id": "openrouter:m",
                                              "template_pins": {"default": "greeting@2"},
                                              "params": {}, "provider_options": {}}},
                 "prompt_versions": {"greeting@2": {"prompt": "greeting", "number": 2,
                                                       "kind": "chat", "engine": "liquid",
                                                       "messages": [{"role": "user", "content": "Hi"}]}},
                 "models": {"openrouter:m": {"provider": "openrouter", "model": "m",
                                               "metadata": {}, "provider_options": {},
                                               "capabilities": [], "status": "active"}}}
                """.trimIndent(),
            )
        }
    }

    @Test
    fun `single detail single prompt chat documents resolve from singular resources`() {
        val document = PromptDocument.parse(singleDetailChat())
        val prompt = Resolver.resolve(document, "support_reply")
        val request = prompt.request(mapOf("question" to "pricing", "plan" to "pro"))

        assertEquals(1, document.schemaVersion)
        assertEquals("support_reply", prompt.key)
        assertEquals("openrouter", prompt.provider)
        assertEquals("anthropic/claude-sonnet-4", prompt.model)
        assertEquals(1, prompt.promptVersionNumber)
        assertNull(prompt.deploymentId)
        assertNull(prompt.modelId)
        assertNull(prompt.promptVersionId)
        assertEquals("chat_completions", request.api)
        assertEquals("/api/v1/chat/completions", request.path)
        assertEquals("anthropic/claude-sonnet-4", request.body["model"])
        assertTrue(request.body.containsKey("usage"))
        assertEquals(
            "Reply to pricing for pro.",
            prompt.messages(mapOf("question" to "pricing", "plan" to "pro"))[1].content,
        )
    }

    @Test
    fun `single detail single prompt Decision documents prepare Decision requests`() {
        val document = PromptDocument.parse(singleDetailDecision())
        val prompt = Resolver.resolve(document, "decision_triage")
        val request =
            prompt.request(
                mapOf("ticket" to "T-1", "plan" to "team", "region" to "eu"),
                PreparedRequestOptions(sessionId = "session-1", user = "user-1"),
            )

        assertEquals(PromptKind.DECISION, prompt.kind)
        assertEquals("decisions", request.api)
        assertEquals("/api/v1/systemone", request.path)
        assertEquals("typesafe/jev-1.13", request.body["model"])
        assertEquals(
            mapOf(
                "ticket" to "T-1",
                "plan" to "team",
                "region" to "eu",
                "facts" to listOf(0L, false, null, mapOf("literal" to "kept")),
            ),
            request.body["state"],
        )
        assertEquals("session-1", request.body["session_id"])
        assertEquals("user-1", request.body["user"])
        assertTrue(!request.body.containsKey("messages"))
        assertTrue(!request.body.containsKey("usage"))
    }

    @Test
    fun `single detail rejects incomplete mixed or mismatched single prompt documents`() {
        assertFailsWith<PromptOnException> {
            PromptDocument.parse(
                """{"schema_version": 1, "project": "p", "environment": "production", "prompts": {}}""",
            )
        }
        assertFailsWith<PromptOnException> {
            PromptDocument.parse(
                """
                {"schema_version": 1, "project": "p", "environment": "production",
                 "prompt": {"key": "greeting", "kind": "chat"},
                 "deployment": {"provider": "openrouter", "model": "m", "prompt_version": 2},
                 "prompt_version": {"prompt": "other", "number": 2, "kind": "chat", "engine": "liquid",
                                    "messages": [{"role": "user", "content": "Hi"}]},
                 "model": {"provider": "openrouter", "model": "m"}}
                """.trimIndent(),
            )
        }
        assertFailsWith<PromptOnException> {
            PromptDocument.parse(
                """
                {"schema_version": 1, "project": "p", "environment": "production",
                 "prompt": {"key": "greeting", "kind": "chat"},
                 "deployment": {"provider": "openrouter", "model": "m", "prompt_version": 3},
                 "prompt_version": {"prompt": "greeting", "number": 2, "kind": "chat", "engine": "liquid",
                                    "messages": [{"role": "user", "content": "Hi"}]},
                 "model": {"provider": "openrouter", "model": "m"}}
                """.trimIndent(),
            )
        }
        assertFailsWith<PromptOnException> {
            PromptDocument.parse(
                """
                {"schema_version": 1, "project": "p", "environment": "production",
                 "prompt": {"key": "greeting", "kind": "chat"},
                 "deployment": {"provider": "openrouter", "model": "m", "prompt_version": 2},
                 "prompt_version": {"prompt": "greeting", "number": 2, "kind": "chat", "engine": "liquid",
                                    "messages": [{"role": "user", "content": "Hi"}]},
                 "model": {"provider": "other", "model": "m"}}
                """.trimIndent(),
            )
        }
    }

    private fun singleDetailChat(): String =
        """
        {"schema_version":1,"project":"heydiary-10242","environment":"production",
         "prompt":{"key":"support_reply","kind":"chat",
                   "variables":[{"name":"plan","required":false,"type":"string"},
                                   {"name":"question","required":false,"type":"string"}],
                   "default_params":{},"payload_policy":{"mode":"full","sample_rate":1.0,
                   "max_bytes":262144,"retention_days":30,"encrypt":true}},
         "deployment":{"revision":"v2026.10.14-1","provider":"openrouter",
                       "model":"anthropic/claude-sonnet-4","prompt_version":1,
                       "params":{},"provider_options":{"only":["Anthropic"]},
                       "api":"chat_completions","request_path":"/api/v1/chat/completions"},
         "prompt_version":{"prompt":"support_reply","number":1,"kind":"chat","engine":"liquid",
                           "messages":[{"role":"system","content":"Keep opaque 019916f4-12b0-7e6c-a1d3-8c9f2b4e6a57."},
                                       {"role":"user","content":"Reply to {{ question }} for {{ plan }}."}],
                           "decision":null,"text_template":null},
         "model":{"provider":"openrouter","model":"anthropic/claude-sonnet-4",
                  "display_name":"Claude Sonnet 4","metadata":{},
                  "provider_options":{},"capabilities":["tools","streaming"],"status":"active"}}
        """.trimIndent()

    private fun singleDetailDecision(): String =
        """
        {"schema_version":1,"project":"heydiary-10242","environment":"production",
         "prompt":{"key":"decision_triage","kind":"decision",
                   "variables":[{"name":"plan","required":false,"type":"string"},
                                   {"name":"region","required":false,"type":"string"},
                                   {"name":"ticket","required":false,"type":"string"}],
                   "default_params":{},"payload_policy":{"mode":"full","sample_rate":1.0,
                   "max_bytes":262144,"retention_days":30,"encrypt":true}},
         "deployment":{"revision":"v2026.10.14-1","provider":"openrouter",
                       "model":"typesafe/jev-1.13","prompt_version":1,
                       "params":{},"provider_options":{"only":["Anthropic"]},
                       "api":"decisions","request_path":"/api/v1/systemone"},
         "prompt_version":{"prompt":"decision_triage","number":1,"kind":"decision","engine":"liquid",
                           "messages":[],"text_template":null,
                           "decision":{"state":{"ticket":"{{ ticket }}","plan":"{{ plan }}",
                                                "region":"{{ region }}",
                                                "facts":[0,false,null,{"literal":"kept"}]},
                                       "questions":{"eligible":{"type":"noul",
                                         "instructions":"Is this eligible?",
                                         "criteria":{"true":"Eligible","false":"Not eligible"}}}}},
         "model":{"provider":"openrouter","model":"typesafe/jev-1.13",
                  "display_name":"Jev","metadata":{},"provider_options":{},
                  "capabilities":["decisions"],"status":"active"}}
        """.trimIndent()
}
