package dev.polimo.prompton

/**
 * What to use for one call: the model, the params, the provider options and the pinned prompt.
 *
 * [messageTemplates] and [textTemplate] are the raw templates; [messages] and [text] substitute
 * this call's variables into them.
 */
public data class UseCase(
    val key: String,
    val kind: UseCaseKind,
    val environment: String,
    val deploymentId: String?,
    val deploymentRevision: Int?,
    /** The chosen prompt name, `null` for an embedding use case. */
    val prompt: String?,
    /** Every prompt name the live revision pins, sorted. */
    val promptNames: List<String>,
    /** The provider-side model string to send to the provider, unchanged. */
    val model: String?,
    /** The catalog id of the model. */
    val modelId: String?,
    val provider: String?,
    val params: Map<String, Any?>,
    val providerOptions: Map<String, Any?>,
    val promptVersionId: String?,
    val promptVersionNumber: Int?,
    val engine: TemplateEngine,
    val messageTemplates: List<PromptMessage>?,
    val textTemplate: String?,
    val inputSchema: List<InputVariable>,
    val payloadPolicy: PayloadPolicy?,
    val source: UseCaseSource,
    val etag: String?,
    val warnings: List<String>,
) {
    internal var owner: PromptOn? = null
    private val nextTrackSelection = ThreadLocal<UseCase?>()

    /**
     * Renders a chat prompt with this call's variables.
     *
     * @throws MissingVariableException when a variable the template reads was not supplied
     * @throws PromptOnException when this use case is not a chat use case
     */
    @JvmOverloads
    public fun messages(
        variables: Map<String, Any?>? = null,
        prompt: String? = null,
    ): List<PromptMessage> {
        val selected = selected(prompt)
        if (selected.kind != UseCaseKind.CHAT) {
            throw PromptOnException("use case '${selected.key}' is ${selected.kind.wire}, not chat")
        }
        val templates =
            selected.messageTemplates ?: throw PromptOnException("use case '${selected.key}' has no chat prompt")
        val rendered = Template.renderMessages(templates, variables, selected.engine)
        rememberForTrack(selected)
        return rendered
    }

    /**
     * Renders a text prompt with this call's variables.
     *
     * @throws MissingVariableException when a variable the template reads was not supplied
     * @throws PromptOnException when this use case is not a text use case
     */
    @JvmOverloads
    public fun text(
        variables: Map<String, Any?>? = null,
        prompt: String? = null,
    ): String {
        val selected = selected(prompt)
        if (selected.kind != UseCaseKind.TEXT) {
            throw PromptOnException("use case '${selected.key}' is ${selected.kind.wire}, not text")
        }
        val template = selected.textTemplate ?: throw PromptOnException("use case '${selected.key}' has no text prompt")
        val rendered = Template.render(template, variables, selected.engine)
        rememberForTrack(selected)
        return rendered
    }

    /** Times a provider call, records a monitoring log, and returns the block's value unchanged. */
    @JvmOverloads
    public fun <T> trackBlocking(
        meta: TrackMeta = TrackMeta(),
        block: (TrackCall) -> T,
    ): T {
        val selected = takeTrackSelection()
        return attachedOwner().trackBlocking(selected, meta, block)
    }

    /** Suspending [trackBlocking]. */
    @JvmOverloads
    public suspend fun <T> track(
        meta: TrackMeta = TrackMeta(),
        block: suspend (TrackCall) -> T,
    ): T {
        val selected = takeTrackSelection()
        return attachedOwner().track(selected, meta, block)
    }

    private fun rememberForTrack(selected: UseCase) {
        nextTrackSelection.set(selected)
    }

    private fun takeTrackSelection(): UseCase {
        val selected = nextTrackSelection.get()
        nextTrackSelection.remove()
        return selected ?: this
    }

    private fun selected(prompt: String?): UseCase {
        if (prompt == null || prompt == this.prompt) return this
        return attachedOwner().useCase(key, prompt)
    }

    private fun attachedOwner(): PromptOn =
        owner ?: throw PromptOnException("this use case is not attached to a PromptOn client")
}

/** The internal selection algorithm: use case document + key (+ prompt name) to a [UseCase]. */
internal object Resolver {
    internal const val DEFAULT_PROMPT: String = "default"

    /**
     * Selects [useCaseKey] from [document].
     *
     * @throws UnknownUseCaseException the use case document has no such use case
     * @throws UnresolvedUseCaseException the use case has no live deployment in this environment
     * @throws UnknownPromptException the deployment pins no prompt of that name
     */
    @JvmOverloads
    internal fun resolve(
        document: UseCaseDocument,
        useCaseKey: String,
        prompt: String? = null,
        source: UseCaseSource = UseCaseSource.REMOTE,
        etag: String? = null,
    ): UseCase {
        val useCase = document.useCases[useCaseKey] ?: throw UnknownUseCaseException(useCaseKey)
        val deployment = document.deployments[useCaseKey] ?: throw UnresolvedUseCaseException(useCaseKey)
        val promptNames = deployment.promptPins.keys.sorted()

        val promptName: String?
        val versionId: String?
        if (useCase.kind == UseCaseKind.EMBEDDING) {
            promptName = null
            versionId = null
        } else {
            promptName = prompt ?: DEFAULT_PROMPT
            versionId =
                deployment.promptPins[promptName]
                    ?: throw UnknownPromptException(useCaseKey, promptName, promptNames)
        }

        val warnings = mutableListOf<String>()
        val version =
            versionId?.let { id ->
                document.promptVersions[id] ?: run {
                    warnings += "missing_prompt_version: $id"
                    null
                }
            }
        val model =
            deployment.modelId?.let { id ->
                document.models[id] ?: run {
                    warnings += "missing_model: $id"
                    null
                }
            }

        return UseCase(
            key = useCaseKey,
            kind = useCase.kind,
            environment = document.environment ?: "",
            deploymentId = deployment.id,
            deploymentRevision = deployment.revision,
            prompt = promptName,
            promptNames = if (useCase.kind == UseCaseKind.EMBEDDING) emptyList() else promptNames,
            model = model?.modelId,
            modelId = model?.id,
            provider = model?.provider,
            params = mergeTools(mergeShallow(useCase.defaultParams, deployment.params), version?.tools),
            providerOptions = mergeShallow(model?.providerOptions, deployment.providerOptions),
            promptVersionId = version?.id,
            promptVersionNumber = version?.number,
            engine = version?.engine ?: TemplateEngine.LIQUID,
            messageTemplates = if (useCase.kind == UseCaseKind.CHAT) version?.messages else null,
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
     * an override value of `null` is kept as `null` rather than deleting the key.
     */
    internal fun mergeShallow(
        base: Map<String, Any?>?,
        override: Map<String, Any?>?,
    ): Map<String, Any?> {
        val merged = LinkedHashMap<String, Any?>(base ?: emptyMap())
        override?.forEach { (key, value) -> merged[key] = value }
        return merged
    }

    internal fun mergeTools(
        params: Map<String, Any?>,
        tools: Map<String, Any?>?,
    ): Map<String, Any?> {
        val merged = LinkedHashMap(params)
        val provider = providerToolParams(tools)
        provider.forEach { (key, value) ->
            if (merged.containsKey(key) && merged[key] != value) {
                throw PromptOnException("prompt tools conflict with params.$key")
            }
            merged[key] = value
        }
        return merged
    }

    private fun providerToolParams(tools: Map<String, Any?>?): Map<String, Any?> {
        if (tools.isNullOrEmpty()) return emptyMap()
        val out = LinkedHashMap<String, Any?>()

        @Suppress("UNCHECKED_CAST")
        val definitions = tools["definitions"] as? List<Map<String, Any?>>
        if (!definitions.isNullOrEmpty()) {
            out["tools"] = definitions.map { it - setOf("output_schema", "output_examples") }
        }
        if (tools.containsKey("tool_choice")) out["tool_choice"] = tools["tool_choice"]
        if (tools.containsKey("parallel_tool_calls")) out["parallel_tool_calls"] = tools["parallel_tool_calls"]
        return out
    }
}
