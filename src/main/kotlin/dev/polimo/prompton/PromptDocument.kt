package dev.polimo.prompton

import dev.polimo.prompton.internal.Ptn
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A prompt document whose `schema_version` this SDK cannot read. */
public class UnsupportedSchemaVersionException(
    public val schemaVersion: Int,
) : PromptOnException(
        "unsupported prompt document schema_version $schemaVersion; " +
            "this SDK reads version ${PromptDocument.SCHEMA_VERSION}",
    )

/**
 * A decoded PromptOn prompt document: either a bundle or one demand-fetched prompt key.
 *
 * Only schema version 1 is accepted; detail responses and offline bundles are distinguished by shape.
 */
public class PromptDocument internal constructor(
    public val schemaVersion: Int,
    public val project: String?,
    public val environment: String?,
    public val prompts: Map<String, PromptEntry>,
    public val deployments: Map<String, Deployment>,
    public val promptVersions: Map<String, PromptVersion>,
    public val models: Map<String, ModelEntry>,
    public val warnings: List<String>,
    /** The exact bytes the document was decoded from, mirrored to the disk cache unchanged. */
    internal val source: String,
) {
    /** The template names the live deployment of [promptKey] pins, sorted. */
    public fun templateNames(promptKey: String): List<String> =
        deployments[promptKey]
            ?.templatePins
            ?.keys
            ?.sorted()
            .orEmpty()

    /** The document as JSON text, for writing a bundle. */
    public fun toJson(): String = source

    public companion object {
        public const val SCHEMA_VERSION: Int = 1

        /** Decodes a PromptOn prompt document body. */
        public fun parse(json: String): PromptDocument = decode(Ptn.parseObject(json), json)

        internal fun decode(
            root: JsonObject,
            source: String,
        ): PromptDocument {
            val warnings = mutableListOf<String>()
            val schemaVersion =
                schemaVersionOf(root["schema_version"])
                    ?: throw PromptOnException("prompt document schema_version must be integer 1")
            if (schemaVersion != SCHEMA_VERSION) throw UnsupportedSchemaVersionException(schemaVersion)
            val singular = listOf("prompt", "deployment", "prompt_version", "model").any(root::containsKey)
            val normalized = if (singular) normalizeSingleDocument(root) else root

            val promptsRaw =
                Ptn.asObject(normalized["prompts"])
                    ?: throw PromptOnException(
                        "prompt document requires keyed prompts; a discovery index is not configuration",
                    )
            for (field in listOf("deployments", "prompt_versions", "models")) {
                if (normalized[field] !is JsonObject) {
                    throw PromptOnException("prompt document $field is required")
                }
            }

            val prompts =
                promptsRaw.entries.associate { (key, value) ->
                    key to decodePrompt(key, Ptn.asObject(value), warnings)
                }

            val deployments =
                Ptn
                    .asObject(normalized["deployments"])
                    ?.entries
                    ?.associate { (key, value) -> key to decodeDeployment(key, Ptn.asObject(value)) }
                    .orEmpty()

            val promptVersions =
                Ptn
                    .asObject(normalized["prompt_versions"])
                    ?.entries
                    ?.mapNotNull { (id, value) ->
                        Ptn.asObject(value)?.let { id to decodePromptVersion(id, it) }
                    }?.toMap()
                    .orEmpty()

            val models =
                Ptn
                    .asObject(normalized["models"])
                    ?.entries
                    ?.mapNotNull { (id, value) ->
                        Ptn.asObject(value)?.let { model ->
                            id to decodeModel(id, model)
                        }
                    }?.toMap()
                    .orEmpty()

            return PromptDocument(
                schemaVersion = schemaVersion,
                project = Ptn.asString(normalized["project"]),
                environment = Ptn.asString(normalized["environment"]),
                prompts = prompts,
                deployments = deployments,
                promptVersions = promptVersions,
                models = models,
                warnings = warnings,
                source = source,
            )
        }

        private fun normalizeSingleDocument(root: JsonObject): JsonObject {
            if (
                root.containsKey("prompts") ||
                root.containsKey("deployments") ||
                root.containsKey("prompt_versions") ||
                root.containsKey("models")
            ) {
                throw PromptOnException("single prompt document must use singular resources")
            }

            val prompt = Ptn.asObject(root["prompt"])
                ?: throw PromptOnException("single prompt document prompt is required")
            val deployment = Ptn.asObject(root["deployment"])
                ?: throw PromptOnException("single prompt document deployment is required")
            val version = Ptn.asObject(root["prompt_version"])
                ?: throw PromptOnException("single prompt document prompt version is required")
            val model = Ptn.asObject(root["model"])
                ?: throw PromptOnException("single prompt document model is required")

            val key = requiredString(prompt, "key", "prompt key")
            val versionPrompt = requiredString(version, "prompt", "prompt version prompt")
            if (versionPrompt != key) {
                throw PromptOnException("prompt version prompt $versionPrompt must match prompt key $key")
            }
            val versionNumber = strictPositiveInt(version["number"], "prompt version number")
            val deploymentVersion = strictPositiveInt(deployment["prompt_version"], "deployment prompt_version")
            if (deploymentVersion != versionNumber) {
                throw PromptOnException("deployment prompt_version must match prompt version number $versionNumber")
            }

            val deploymentProvider = requiredString(deployment, "provider", "deployment provider")
            val deploymentModel = requiredString(deployment, "model", "deployment model")
            val modelProvider = requiredString(model, "provider", "model provider")
            val modelName = requiredString(model, "model", "model name")
            if (deploymentProvider != modelProvider || deploymentModel != modelName) {
                throw PromptOnException("deployment provider/model must match model provider/model")
            }

            return JsonObject(
                mapOf(
                    "schema_version" to root.getValue("schema_version"),
                    "project" to (root["project"] ?: kotlinx.serialization.json.JsonNull),
                    "environment" to (root["environment"] ?: kotlinx.serialization.json.JsonNull),
                    "prompts" to JsonObject(mapOf(key to prompt)),
                    "deployments" to JsonObject(mapOf(key to deployment)),
                    "prompt_versions" to JsonObject(mapOf("$key@$versionNumber" to version)),
                    "models" to JsonObject(mapOf(readableModelKey(modelProvider, modelName)!! to model)),
                ),
            )
        }

        private fun schemaVersionOf(value: JsonElement?): Int? {
            if (value !is JsonPrimitive || value.isString) return null
            return value.content.toIntOrNull()
        }

        private fun decodePrompt(
            key: String,
            raw: JsonObject?,
            warnings: MutableList<String>,
        ): PromptEntry {
            val kindText = Ptn.asString(raw?.get("kind"))
            if (kindText != null && PromptKind.entries.none { it.wire == kindText }) {
                warnings += "unknown_kind: $kindText"
            }
            return PromptEntry(
                id = null,
                key = key,
                kind = PromptKind.fromWire(kindText),
                variables = decodeVariables(raw),
                defaultParams = nativeMap(raw?.get("default_params")),
                payloadPolicy = decodePayloadPolicy(Ptn.asObject(raw?.get("payload_policy"))),
            )
        }

        private fun decodeVariables(raw: JsonObject?): List<VariableDefinition> =
            Ptn
                .asArray(raw?.get("variables"))
                ?.mapNotNull { element ->
                    val entry = Ptn.asObject(element) ?: return@mapNotNull null
                    val name = Ptn.asString(entry["name"]) ?: return@mapNotNull null
                    VariableDefinition(
                        name = name,
                        type = Ptn.asString(entry["type"]) ?: "string",
                        required = Ptn.toNative(entry["required"]) == true,
                        description = Ptn.asString(entry["description"]),
                        example = Decisions.freeze(Ptn.toNative(entry["example"])),
                    )
                }.orEmpty()

        private fun decodePayloadPolicy(raw: JsonObject?): PayloadPolicy? {
            if (raw == null) return null
            val sampleRate = (Ptn.toNative(raw["sample_rate"]) as? Number)?.toDouble() ?: 1.0
            return PayloadPolicy(
                mode = PayloadMode.fromWire(Ptn.asString(raw["mode"])),
                sampleRate = sampleRate.coerceIn(0.0, 1.0),
                maxBytes = Ptn.asInt(raw["max_bytes"])?.takeIf { it > 0 } ?: PayloadPolicy.DEFAULT_MAX_BYTES,
                retentionDays = Ptn.asInt(raw["retention_days"]),
                encrypt = Ptn.toNative(raw["encrypt"]) == true,
            )
        }

        private fun decodeDeployment(
            key: String,
            raw: JsonObject?,
        ): Deployment =
            Deployment(
                id = null,
                promptKey = key,
                revision = revisionOf(raw?.get("revision")),
                modelId = readableModelKey(
                    requiredString(raw, "provider", "deployment provider"),
                    requiredString(raw, "model", "deployment model"),
                ),
                api = Ptn.asString(raw?.get("api")),
                requestPath = Ptn.asString(raw?.get("request_path")),
                params = nativeMap(raw?.get("params")),
                providerOptions = nativeMap(raw?.get("provider_options")),
                templatePins = mapOf(
                    "default" to "$key@${strictPositiveInt(raw?.get("prompt_version"), "deployment prompt_version")}",
                ),
            )

        private fun revisionOf(value: JsonElement?): String? =
            when {
                value == null || value is kotlinx.serialization.json.JsonNull -> null
                value is JsonPrimitive && value.isString -> value.content
                else -> throw PromptOnException("deployment revision must be a string")
            }

        private fun decodePromptVersion(
            id: String,
            raw: JsonObject,
        ): PromptVersion {
            val promptTemplateId = requiredString(raw, "prompt", "prompt version prompt")
            val number = strictPositiveInt(raw["number"], "prompt version number")
            val expectedId = "$promptTemplateId@$number"
            if (id != expectedId) {
                throw PromptOnException("prompt version key $id must match embedded prompt and number $expectedId")
            }
            return PromptVersion(
                id = id,
                promptTemplateId = promptTemplateId,
                number = number,
                kind = PromptKind.fromWireOrNull(Ptn.asString(raw["kind"])),
                engine = TemplateEngine.fromWire(Ptn.asString(raw["engine"])),
                messages =
                    Ptn.asArray(raw["messages"])?.mapNotNull { element ->
                        val message = Ptn.asObject(element) ?: return@mapNotNull null
                        val native = nativeMap(element)
                        val contentElement = message["content"]

                        @Suppress("UNCHECKED_CAST")
                        val calls = native["tool_calls"] as? List<Map<String, Any?>> ?: emptyList()
                        PromptMessage(
                            role = Ptn.asString(message["role"]) ?: "user",
                            content = Ptn.asString(message["content"]) ?: "",
                            name = Ptn.asString(message["name"]),
                            type = Ptn.asString(message["type"]),
                            contentValue = Ptn.toNative(contentElement),
                            hasContent = message.containsKey("content"),
                            toolCallId = Ptn.asString(message["tool_call_id"]),
                            toolCalls = calls,
                            hasToolCalls = message.containsKey("tool_calls"),
                            extra = native - setOf("role", "type", "content", "name", "tool_call_id", "tool_calls"),
                        )
                    },
                decision =
                    Ptn.asObject(raw["decision"])?.let { decision ->
                        DecisionTemplate(
                            state = Decisions.freeze(Ptn.toNative(decision["state"])),
                            questions = Decisions.freezeMap(nativeMap(decision["questions"])),
                        )
                    },
                tools = nativeMap(raw["tools"]),
                textTemplate = Ptn.asString(raw["text_template"]),
            )
        }

        private fun decodeModel(
            id: String,
            raw: JsonObject,
        ): ModelEntry {
            val provider = requiredString(raw, "provider", "model provider")
            val modelId = requiredString(raw, "model", "model name")
            val expectedId = "$provider:$modelId"
            if (id != expectedId) {
                throw PromptOnException("model key $id must match embedded provider and model $expectedId")
            }
            return ModelEntry(
                id = id,
                provider = provider,
                modelId = modelId,
                displayName = Ptn.asString(raw["display_name"]),
                metadata = nativeMap(raw["metadata"]),
                providerOptions = nativeMap(raw["provider_options"]),
                capabilities = Ptn.asArray(raw["capabilities"])?.mapNotNull { Ptn.asString(it) }.orEmpty(),
                status = Ptn.asString(raw["status"]),
            )
        }

        private fun requiredString(
            raw: JsonObject?,
            key: String,
            label: String,
        ): String = Ptn.asString(raw?.get(key)) ?: throw PromptOnException("$label is required")

        private fun strictPositiveInt(
            value: JsonElement?,
            label: String,
        ): Int {
            if (value is JsonPrimitive && !value.isString) {
                val text = value.content
                if (INTEGER_PATTERN.matches(text)) {
                    val number = text.toLongOrNull()
                    if (number != null && number > 0 && number <= Int.MAX_VALUE) {
                        return number.toInt()
                    }
                }
            }
            throw PromptOnException("$label must be a positive JSON integer")
        }

        private val INTEGER_PATTERN = Regex("-?(0|[1-9][0-9]*)")

        private fun nativeMap(element: JsonElement?): Map<String, Any?> = Ptn.toNativeMap(element)

        private fun readableModelKey(
            provider: String?,
            model: String?,
        ): String? =
            if (provider == null || model == null) null else "$provider:$model"
    }
}
