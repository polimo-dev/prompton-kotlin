package dev.polimo.prompton

import dev.polimo.prompton.internal.Ptn
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** A snapshot document whose `schema_version` this SDK cannot read. */
public class UnsupportedSchemaVersionException(
    public val schemaVersion: Int,
) : PromptOnException("unsupported snapshot schema_version $schemaVersion; this SDK reads version 3")

/**
 * A decoded `GET /snapshot` document (schema v3): everything live in one environment.
 *
 * Decoding is lenient about additions — a version above 3 decodes the fields this SDK knows and
 * records a warning — and strict about regressions: v1 and v2 documents are refused outright.
 */
public class SnapshotDocument internal constructor(
    public val schemaVersion: Int,
    public val project: String?,
    public val environment: String?,
    public val useCases: Map<String, UseCase>,
    public val deployments: Map<String, Deployment>,
    public val promptVersions: Map<String, PromptVersion>,
    public val models: Map<String, ModelEntry>,
    public val warnings: List<String>,
    /** The exact bytes the document was decoded from, mirrored to the disk cache unchanged. */
    internal val source: String,
) {
    /** The prompt names the live deployment of [useCaseKey] pins, sorted. */
    public fun promptNames(useCaseKey: String): List<String> =
        deployments[useCaseKey]
            ?.promptPins
            ?.keys
            ?.sorted()
            .orEmpty()

    /** The document as JSON text, for writing a bundle. */
    public fun toJson(): String = source

    public companion object {
        public const val SCHEMA_VERSION: Int = 3

        /** Decodes a `GET /snapshot` body. */
        public fun parse(json: String): SnapshotDocument = decode(Ptn.parseObject(json), json)

        internal fun decode(
            root: JsonObject,
            source: String,
        ): SnapshotDocument {
            val warnings = mutableListOf<String>()
            val version = Ptn.asInt(root["schema_version"]) ?: Ptn.asInt(root["version"])
            val schemaVersion =
                when {
                    version == null -> throw PromptOnException("snapshot schema_version is required")
                    version == SCHEMA_VERSION -> version
                    version > SCHEMA_VERSION -> {
                        warnings += "unknown_schema_version: $version"
                        version
                    }

                    else -> throw UnsupportedSchemaVersionException(version)
                }

            val useCasesRaw =
                Ptn.asObject(root["use_cases"])
                    ?: throw PromptOnException("snapshot use_cases is required")

            val useCases =
                useCasesRaw.entries.associate { (key, value) ->
                    key to decodeUseCase(key, Ptn.asObject(value), warnings)
                }

            val deployments =
                Ptn
                    .asObject(root["deployments"])
                    ?.entries
                    ?.associate { (key, value) -> key to decodeDeployment(key, Ptn.asObject(value)) }
                    .orEmpty()

            val promptVersions =
                Ptn
                    .asObject(root["prompt_versions"])
                    ?.entries
                    ?.mapNotNull { (id, value) -> Ptn.asObject(value)?.let { id to decodePromptVersion(id, it) } }
                    ?.toMap()
                    .orEmpty()

            val models =
                Ptn
                    .asObject(root["models"])
                    ?.entries
                    ?.mapNotNull { (id, value) -> Ptn.asObject(value)?.let { id to decodeModel(id, it) } }
                    ?.toMap()
                    .orEmpty()

            return SnapshotDocument(
                schemaVersion = schemaVersion,
                project = Ptn.asString(root["project"]),
                environment = Ptn.asString(root["environment"]),
                useCases = useCases,
                deployments = deployments,
                promptVersions = promptVersions,
                models = models,
                warnings = warnings,
                source = source,
            )
        }

        private fun decodeUseCase(
            key: String,
            raw: JsonObject?,
            warnings: MutableList<String>,
        ): UseCase {
            val kindText = Ptn.asString(raw?.get("kind"))
            if (kindText != null && UseCaseKind.entries.none { it.wire == kindText }) {
                warnings += "unknown_kind: $kindText"
            }
            return UseCase(
                id = Ptn.asString(raw?.get("id")),
                key = key,
                kind = UseCaseKind.fromWire(kindText),
                inputSchema = decodeInputSchema(raw),
                defaultParams = nativeMap(raw?.get("default_params")),
                payloadPolicy = decodePayloadPolicy(Ptn.asObject(raw?.get("payload_policy"))),
            )
        }

        private fun decodeInputSchema(raw: JsonObject?): List<InputVariable> =
            Ptn
                .asArray(raw?.get("input_schema"))
                ?.mapNotNull { element ->
                    val entry = Ptn.asObject(element) ?: return@mapNotNull null
                    val name = Ptn.asString(entry["name"]) ?: return@mapNotNull null
                    InputVariable(
                        name = name,
                        type = Ptn.asString(entry["type"]) ?: "string",
                        required = Ptn.toNative(entry["required"]) == true,
                        description = Ptn.asString(entry["description"]),
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
                id = Ptn.asString(raw?.get("id")),
                useCaseKey = Ptn.asString(raw?.get("use_case_key")) ?: key,
                revision = Ptn.asInt(raw?.get("revision")),
                modelId = Ptn.asString(raw?.get("model_id")),
                params = nativeMap(raw?.get("params")),
                providerOptions = nativeMap(raw?.get("provider_options")),
                promptPins =
                    Ptn
                        .asObject(raw?.get("prompt_pins"))
                        ?.entries
                        ?.mapNotNull { (name, value) -> Ptn.asString(value)?.let { name to it } }
                        ?.toMap()
                        .orEmpty(),
            )

        private fun decodePromptVersion(
            id: String,
            raw: JsonObject,
        ): PromptVersion =
            PromptVersion(
                id = Ptn.asString(raw["id"]) ?: id,
                promptId = Ptn.asString(raw["prompt_id"]),
                number = Ptn.asInt(raw["number"]),
                engine = TemplateEngine.fromWire(Ptn.asString(raw["engine"])),
                messages =
                    Ptn.asArray(raw["messages"])?.mapNotNull { element ->
                        val message = Ptn.asObject(element) ?: return@mapNotNull null
                        PromptMessage(
                            role = Ptn.asString(message["role"]) ?: "user",
                            content = Ptn.asString(message["content"]) ?: "",
                            name = Ptn.asString(message["name"]),
                        )
                    },
                textTemplate = Ptn.asString(raw["text_template"]),
            )

        private fun decodeModel(
            id: String,
            raw: JsonObject,
        ): ModelEntry =
            ModelEntry(
                id = Ptn.asString(raw["id"]) ?: id,
                provider = Ptn.asString(raw["provider"]),
                modelId = Ptn.asString(raw["model_id"]),
                displayName = Ptn.asString(raw["display_name"]),
                metadata = nativeMap(raw["metadata"]),
                providerOptions = nativeMap(raw["provider_options"]),
                capabilities = Ptn.asArray(raw["capabilities"])?.mapNotNull { Ptn.asString(it) }.orEmpty(),
                status = Ptn.asString(raw["status"]),
            )

        private fun nativeMap(element: JsonElement?): Map<String, Any?> = Ptn.toNativeMap(element)
    }
}
