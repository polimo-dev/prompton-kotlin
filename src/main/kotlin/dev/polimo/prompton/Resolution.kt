package dev.polimo.prompton

/**
 * What to use for one call: the model, the params, the provider options and the pinned prompt.
 *
 * [messages] and [textTemplate] are the **raw templates**; [render] substitutes this call's
 * variables into them. Resolve and render are two steps, not one — only the server's
 * `POST /resolve` does both at once, and only when `variables` are supplied.
 */
public data class Resolution(
    val useCase: String,
    val kind: UseCaseKind,
    val environment: String,
    val deploymentId: String?,
    val deploymentRevision: Int?,
    /** The chosen prompt name, `null` for an embedding use case. */
    val prompt: String?,
    /** Every prompt name the live revision pins, sorted. */
    val availablePrompts: List<String>,
    /** The provider-side model string to send to the provider, unchanged. */
    val model: String?,
    /** The catalog id of the model. */
    val modelId: String?,
    val provider: String?,
    val effectiveParams: Map<String, Any?>,
    val effectiveProviderOptions: Map<String, Any?>,
    val promptVersionId: String?,
    val promptVersionNumber: Int?,
    val engine: TemplateEngine,
    val messages: List<PromptMessage>?,
    val textTemplate: String?,
    val inputSchema: List<InputVariable>,
    val payloadPolicy: PayloadPolicy?,
    val source: ResolutionSource,
    val etag: String?,
    val warnings: List<String>,
) {
    /**
     * Renders the pinned prompt with this call's variables.
     *
     * @throws MissingVariableException when a variable the template reads was not supplied
     */
    @JvmOverloads
    public fun render(variables: Map<String, Any?>? = null): RenderedPrompt =
        when {
            messages != null -> RenderedPrompt(Template.renderMessages(messages, variables, engine), null)
            textTemplate != null -> RenderedPrompt(null, Template.render(textTemplate, variables, engine))
            else -> RenderedPrompt(null, null)
        }
}

/** The pure resolution algorithm: snapshot + use case (+ prompt name) to a [Resolution]. */
public object Resolver {
    public const val DEFAULT_PROMPT: String = "default"

    /**
     * Resolves [useCaseKey] against [snapshot].
     *
     * @throws UnknownUseCaseException the snapshot has no such use case
     * @throws UnresolvedUseCaseException the use case has no live deployment in this environment
     * @throws UnknownPromptException the deployment pins no prompt of that name
     */
    @JvmOverloads
    public fun resolve(
        snapshot: SnapshotDocument,
        useCaseKey: String,
        prompt: String? = null,
        source: ResolutionSource = ResolutionSource.REMOTE,
        etag: String? = null,
    ): Resolution {
        val useCase = snapshot.useCases[useCaseKey] ?: throw UnknownUseCaseException(useCaseKey)
        val deployment = snapshot.deployments[useCaseKey] ?: throw UnresolvedUseCaseException(useCaseKey)
        val availablePrompts = deployment.promptPins.keys.sorted()

        val promptName: String?
        val versionId: String?
        if (useCase.kind == UseCaseKind.EMBEDDING) {
            promptName = null
            versionId = null
        } else {
            promptName = prompt ?: DEFAULT_PROMPT
            versionId =
                deployment.promptPins[promptName]
                    ?: throw UnknownPromptException(useCaseKey, promptName, availablePrompts)
        }

        val warnings = mutableListOf<String>()
        val version =
            versionId?.let { id ->
                snapshot.promptVersions[id] ?: run {
                    warnings += "missing_prompt_version: $id"
                    null
                }
            }
        val model =
            deployment.modelId?.let { id ->
                snapshot.models[id] ?: run {
                    warnings += "missing_model: $id"
                    null
                }
            }

        return Resolution(
            useCase = useCaseKey,
            kind = useCase.kind,
            environment = snapshot.environment ?: "",
            deploymentId = deployment.id,
            deploymentRevision = deployment.revision,
            prompt = promptName,
            availablePrompts = if (useCase.kind == UseCaseKind.EMBEDDING) emptyList() else availablePrompts,
            model = model?.modelId,
            modelId = model?.id,
            provider = model?.provider,
            effectiveParams = mergeShallow(useCase.defaultParams, deployment.params),
            effectiveProviderOptions = mergeShallow(model?.providerOptions, deployment.providerOptions),
            promptVersionId = version?.id,
            promptVersionNumber = version?.number,
            engine = version?.engine ?: TemplateEngine.LIQUID,
            messages = if (useCase.kind == UseCaseKind.CHAT) version?.messages else null,
            textTemplate = if (useCase.kind == UseCaseKind.TEXT) version?.textTemplate else null,
            inputSchema = useCase.inputSchema,
            payloadPolicy = useCase.payloadPolicy,
            source = source,
            etag = etag,
            warnings = warnings,
        )
    }

    /**
     * Shallow merge, right side wins. A nested map on the right replaces the left side whole, and
     * an override value of `null` is kept as `null` rather than deleting the key — apps rely on
     * sending `"only": null` to clear a provider restriction.
     */
    public fun mergeShallow(
        base: Map<String, Any?>?,
        override: Map<String, Any?>?,
    ): Map<String, Any?> {
        val merged = LinkedHashMap<String, Any?>(base ?: emptyMap())
        override?.forEach { (key, value) -> merged[key] = value }
        return merged
    }
}
