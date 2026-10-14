package dev.polimo.prompton

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReadableSchemaTest {
    @Test
    fun `schema 8 uses readable resource references without public ids`() {
        val document =
            UseCaseDocument.parse(
                """
                {"schema_version": 8, "project": "p", "environment": "production",
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
        assertEquals(8, document.schemaVersion)
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
        assertEquals(UseCaseKind.DECISION, classify.kind)
        assertEquals(mapOf("text" to "great"), classify.decision(mapOf("text" to "great")).state)
    }

    @Test
    fun `schema 8 warnings use readable missing references`() {
        val document =
            UseCaseDocument.parse(
                """
                {"schema_version": 8, "project": "p", "environment": "production",
                 "prompts": {"greeting": {"kind": "chat", "default_params": {}}},
                 "deployments": {"greeting": {"revision": "v2026.10.14-1", "provider": "openrouter",
                                              "model": "missing:model", "prompt_version": 3,
                                              "params": {}, "provider_options": {}}},
                 "prompt_versions": {}, "models": {}}
                """.trimIndent(),
            )
        val pin = Resolver.resolve(document, "greeting")
        assertEquals(
            listOf("missing_prompt_version: greeting@3", "missing_model: openrouter:missing:model"),
            pin.warnings,
        )
        assertNull(pin.modelId)
        assertNull(pin.promptVersionId)
    }

    @Test
    fun `schema 9 is still unsupported until the contract changes again`() {
        val error =
            assertFailsWith<UnsupportedSchemaVersionException> {
                UseCaseDocument.parse(
                    """{"schema_version": 9, "project": "p", "environment": "production", "prompts": {}}""",
                )
            }
        assertTrue(error.message!!.contains("this SDK reads version 8"))
    }
}
