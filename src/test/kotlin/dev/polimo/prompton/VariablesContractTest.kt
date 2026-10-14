package dev.polimo.prompton

import dev.polimo.prompton.internal.Ptn
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VariablesContractTest {
    private val detail = """
        {"schema_version":1,"project":"p","environment":"production",
         "prompt":{"key":"hello","kind":"chat","variables":[
           {"name":"name","type":"string","required":true,"description":"Display name","example":"Ada"}]},
         "deployment":{"revision":"v2026.10.14-1","provider":"openrouter","model":"m","prompt_version":1},
         "prompt_version":{"prompt":"hello","number":1,"kind":"chat","engine":"liquid",
                           "messages":[{"role":"user","content":"Hello {{ name }}"}]},
         "model":{"provider":"openrouter","model":"m"}}
    """.trimIndent()

    @Test
    fun `schema one detail renders`() {
        val doc = PromptDocument.parse(detail)
        assertEquals(1, doc.schemaVersion)
        assertEquals(
            "Hello Ada",
            Resolver
                .resolve(doc, "hello")
                .messages(mapOf("name" to "Ada"))
                .single()
                .content,
        )
        val definition = VariableDefinition("name", "string", true, "Display name", "Ada")
        assertEquals(listOf(definition), doc.prompts.getValue("hello").variables)
        assertEquals(listOf(definition), Resolver.resolve(doc, "hello").variables)
        assertEquals(detail, doc.toJson())
    }

    @Test
    fun `old metadata is not an alias and empty variables stay empty`() {
        val old = PromptDocument.parse(detail.replace("\"variables\"", "\"input_schema\""))
        assertEquals(emptyList(), old.prompts.getValue("hello").variables)
        val empty = detail.replace(Regex("(?s)\"variables\":\\[.*?]"), "\"variables\":[]")
        assertEquals(
            emptyList(),
            PromptDocument
                .parse(empty)
                .prompts
                .getValue("hello")
                .variables,
        )
    }

    @Test
    fun `offline bundle retains the same variable definitions`() {
        val root = Ptn.parseObject(detail)
        val bundle = JsonObject(
            mapOf(
                "schema_version" to root.getValue("schema_version"),
                "project" to root.getValue("project"),
                "environment" to root.getValue("environment"),
                "prompts" to JsonObject(mapOf("hello" to root.getValue("prompt"))),
                "deployments" to JsonObject(mapOf("hello" to root.getValue("deployment"))),
                "prompt_versions" to JsonObject(mapOf("hello@1" to root.getValue("prompt_version"))),
                "models" to JsonObject(mapOf("openrouter:m" to root.getValue("model"))),
            ),
        ).toString()
        val doc = PromptDocument.parse(bundle)
        assertEquals(
            PromptDocument
                .parse(detail)
                .prompts
                .getValue("hello")
                .variables,
            doc.prompts.getValue("hello").variables,
        )
        assertEquals(bundle, doc.toJson())
    }

    @Test
    fun `only integer schema one is accepted`() {
        for (value in listOf("\"1\"", "1.0", "true", "null")) {
            assertFailsWith<PromptOnException> {
                PromptDocument.parse(detail.replace("\"schema_version\":1", "\"schema_version\":$value"))
            }
        }
    }

    @Test
    fun `rejects every previous schema version`() {
        for (version in listOf(0, 2, 3, 4, 5, 6, 7, 8, 9, 10)) {
            assertFailsWith<PromptOnException> {
                PromptDocument.parse(detail.replace("\"schema_version\":1", "\"schema_version\":$version"))
            }
        }
    }

    @Test
    fun `discovery index is not configuration`() {
        for (entries in listOf("[]", "[{\"key\":\"hello\",\"kind\":\"chat\"}]")) {
            assertFailsWith<PromptOnException> {
                PromptDocument.parse(
                    "{\"schema_version\":1,\"project\":\"p\",\"environment\":\"production\",\"prompts\":$entries}",
                )
            }
        }
    }
}
