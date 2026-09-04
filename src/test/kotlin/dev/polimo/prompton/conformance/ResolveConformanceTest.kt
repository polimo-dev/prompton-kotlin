package dev.polimo.prompton.conformance

import dev.polimo.prompton.MissingVariableException
import dev.polimo.prompton.PromptMessage
import dev.polimo.prompton.Resolution
import dev.polimo.prompton.Resolver
import dev.polimo.prompton.SnapshotDocument
import dev.polimo.prompton.UnknownPromptException
import dev.polimo.prompton.UnknownUseCaseException
import dev.polimo.prompton.UnresolvedUseCaseException
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

/** Runs every case of `conformance/resolve.json`: the algorithm `POST /api/v1/resolve` runs server-side. */
class ResolveConformanceTest {
    @Test
    fun `resolve cases`() {
        val file = Conformance.load("resolve")
        val snapshots =
            (file["snapshots"] as JsonObject).entries.associate { (name, document) ->
                name to SnapshotDocument.parse(document.toString())
            }

        var executed = 0
        for (case in Conformance.cases("resolve")) {
            val name = Conformance.string(case, "name")!!
            val snapshot = snapshots.getValue(Conformance.string(case, "snapshot_ref")!!)
            val useCase = Conformance.string(case, "use_case")!!
            val prompt = Conformance.string(case, "prompt")
            val hasVariables = case.containsKey("variables")
            val variables = Conformance.nativeMap(case["variables"])
            val expect = case["expect"] as JsonObject

            val actual =
                try {
                    val resolution = Resolver.resolve(snapshot, useCase, prompt)
                    describe(resolution, hasVariables, variables)
                } catch (e: UnknownUseCaseException) {
                    buildJsonObject { put("error", "unknown_use_case") }
                } catch (e: UnresolvedUseCaseException) {
                    buildJsonObject { put("error", "unresolved") }
                } catch (e: UnknownPromptException) {
                    buildJsonObject {
                        put("error", "unknown_prompt")
                        put("prompt", e.prompt)
                        put("available_prompts", JsonArray(e.availablePrompts.map { JsonPrimitive(it) }))
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
        resolution: Resolution,
        hasVariables: Boolean,
        variables: Map<String, Any?>?,
    ): JsonObject {
        val fields = LinkedHashMap<String, JsonElement>()
        fields["deployment_id"] = nullable(resolution.deploymentId)
        fields["revision"] = resolution.deploymentRevision?.let { JsonPrimitive(it) } ?: JsonNull
        fields["kind"] = JsonPrimitive(resolution.kind.wire)
        fields["prompt"] = nullable(resolution.prompt)
        fields["prompts"] = JsonArray(resolution.availablePrompts.map { JsonPrimitive(it) })
        fields["model"] = nullable(resolution.model)
        fields["model_id"] = nullable(resolution.modelId)
        fields["provider"] = nullable(resolution.provider)
        fields["effective_params"] = jsonOf(resolution.effectiveParams)
        fields["effective_provider_options"] = jsonOf(resolution.effectiveProviderOptions)
        fields["prompt_version"] =
            resolution.promptVersionId?.let { id ->
                buildJsonObject {
                    put("id", id)
                    put("number", resolution.promptVersionNumber)
                }
            } ?: JsonNull
        fields["warnings"] = JsonArray(resolution.warnings.map { JsonPrimitive(it) })

        if (resolution.kind == UseCaseKind.CHAT && resolution.messages != null) {
            val messages =
                if (hasVariables) resolution.render(variables).messages!! else resolution.messages!!
            fields["messages"] = JsonArray(messages.map { messageJson(it) })
        }
        if (resolution.kind == UseCaseKind.TEXT && resolution.textTemplate != null) {
            val text = if (hasVariables) resolution.render(variables).text!! else resolution.textTemplate!!
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
