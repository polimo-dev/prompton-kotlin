package dev.polimo.prompton.conformance

import dev.polimo.prompton.MissingVariableException
import dev.polimo.prompton.PromptMessage
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

/** Runs every case of `conformance/use_case.json`: the use case algorithm the server prompt endpoint runs. */
class UseCaseConformanceTest {
    @Test
    fun `use case cases`() {
        val file = Conformance.load("use_case")
        val documents =
            (file["documents"] as JsonObject).entries.associate { (name, document) ->
                name to UseCaseDocument.parse(document.toString())
            }

        var executed = 0
        for (case in Conformance.cases("use_case")) {
            val name = Conformance.string(case, "name")!!
            val document = documents.getValue(Conformance.string(case, "document_ref")!!)
            val key = Conformance.string(case, "use_case")!!
            val prompt = Conformance.string(case, "prompt")
            val hasVariables = case.containsKey("variables")
            val variables = Conformance.nativeMap(case["variables"])
            val expect = case["expect"] as JsonObject

            val actual =
                try {
                    val useCase = Resolver.resolve(document, key, prompt)
                    describe(useCase, hasVariables, variables)
                } catch (e: UnknownUseCaseException) {
                    buildJsonObject {
                        put("error", "unknown_use_case")
                        put("key", e.useCase)
                    }
                } catch (e: UnresolvedUseCaseException) {
                    buildJsonObject { put("error", "unresolved") }
                } catch (e: UnknownPromptException) {
                    buildJsonObject {
                        put("error", "unknown_prompt")
                        put("key", e.useCase)
                        put("prompt", e.prompt)
                        put("prompt_names", JsonArray(e.promptNames.map { JsonPrimitive(it) }))
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
        assertEquals(15, executed)
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
        fields["prompt"] = nullable(useCase.prompt)
        fields["prompt_names"] = JsonArray(useCase.promptNames.map { JsonPrimitive(it) })
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

    private fun messageJson(message: PromptMessage): JsonObject =
        buildJsonObject {
            put("role", message.role)
            put("content", message.content)
            message.name?.let { put("name", it) }
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
