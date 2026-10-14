package dev.polimo.prompton

/**
 * What to use for one call: the model, the params, the provider options and the pinned prompt.
 *
 * [messageTemplates] and [textTemplate] are the raw templates; [messages] and [text] substitute
 * this call's variables into them.
 */
public data class Prompt(
    val key: String,
    val kind: PromptKind,
    val environment: String,
    val deploymentId: String?,
    val deploymentRevision: String?,
    /** The chosen template name, `null` for an embedding prompt. */
    val template: String?,
    /** Every template name the live revision pins, sorted. */
    val templateNames: List<String>,
    /** The provider-side model string to send to the provider, unchanged. */
    val model: String?,
    /** The catalog id of the model. */
    val modelId: String?,
    val provider: String?,
    val api: String? = null,
    val requestPath: String? = null,
    val params: Map<String, Any?>,
    val providerOptions: Map<String, Any?>,
    val providerPreparedRequest: Map<String, Any?> = emptyMap(),
    val promptVersionId: String?,
    val promptVersionNumber: Int?,
    val engine: TemplateEngine,
    val messageTemplates: List<PromptMessage>?,
    val decisionTemplate: DecisionTemplate? = null,
    val textTemplate: String?,
    val variables: List<VariableDefinition>,
    val payloadPolicy: PayloadPolicy?,
    val source: PromptSource,
    val etag: String?,
    val warnings: List<String>,
    /** Immutable prompt-version kind used for provider request preparation. */
    val pinnedKind: PromptKind? = kind,
) {
    internal var owner: PromptOn? = null
    private val nextTrackSelection = ThreadLocal<Prompt?>()

    /**
     * Renders a chat prompt with this call's variables.
     *
     * @throws MissingVariableException when a variable the template reads was not supplied
     * @throws PromptOnException when this prompt is not a chat prompt
     */
    @JvmOverloads
    public fun messages(
        variables: Map<String, Any?>? = null,
        template: String? = null,
    ): List<PromptMessage> {
        val selected = selected(template)
        if (selected.kind != PromptKind.CHAT) {
            throw PromptOnException("prompt '${selected.key}' is ${selected.kind.wire}, not chat")
        }
        val templates =
            selected.messageTemplates ?: throw PromptOnException("prompt '${selected.key}' has no chat prompt")
        val rendered = Template.renderMessages(templates, variables, selected.engine)
        rememberForTrack(selected)
        return rendered
    }

    /**
     * Renders a text prompt with this call's variables.
     *
     * @throws MissingVariableException when a variable the template reads was not supplied
     * @throws PromptOnException when this prompt is not a text prompt
     */
    @JvmOverloads
    public fun text(
        variables: Map<String, Any?>? = null,
        template: String? = null,
    ): String {
        val selected = selected(template)
        if (selected.kind != PromptKind.TEXT) {
            throw PromptOnException("prompt '${selected.key}' is ${selected.kind.wire}, not text")
        }
        val template = selected.textTemplate ?: throw PromptOnException("prompt '${selected.key}' has no text prompt")
        val rendered = Template.render(template, variables, selected.engine)
        rememberForTrack(selected)
        return rendered
    }

    /**
     * Renders a native Decision prompt with this call's variables.
     *
     * @throws MissingVariableException when a variable the template reads was not supplied
     * @throws PreparedRequestException when the Decision shape is invalid
     * @throws PromptOnException when this prompt is not a Decision prompt
     */
    @JvmOverloads
    public fun decision(
        variables: Map<String, Any?>? = null,
        prompt: String? = null,
    ): RenderedDecision {
        val selected = selected(prompt)
        if (selected.kind != PromptKind.DECISION) {
            throw PromptOnException("prompt '${selected.key}' is ${selected.kind.wire}, not decision")
        }
        val template =
            selected.decisionTemplate ?: throw PromptOnException("prompt '${selected.key}' has no Decision prompt")
        val rendered = Decisions.render(template, variables, selected.engine)
        rememberForTrack(selected)
        return rendered
    }

    /**
     * Prepares the provider POST request for this prompt. The SDK does not send it.
     *
     * Chat requests include rendered messages. Decision requests include rendered state/questions
     * and accept only Decisions metadata (`sessionId`, `trace`, `user`) as params.
     */
    @JvmOverloads
    public fun request(
        variables: Map<String, Any?>? = null,
        options: PreparedRequestOptions = PreparedRequestOptions(),
    ): PreparedRequest {
        val selected = selected(options.prompt)
        val request = RequestBuilder.prepare(selected, variables ?: emptyMap(), options)
        rememberForTrack(selected)
        return request
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

    private fun rememberForTrack(selected: Prompt) {
        nextTrackSelection.set(selected)
    }

    private fun takeTrackSelection(): Prompt {
        val selected = nextTrackSelection.get()
        nextTrackSelection.remove()
        return selected ?: this
    }

    private fun selected(template: String?): Prompt {
        if (template == null || template == this.template) return this
        return attachedOwner().prompt(key, template)
    }

    private fun attachedOwner(): PromptOn =
        owner ?: throw PromptOnException("this prompt is not attached to a PromptOn client")
}

/** The internal selection algorithm: prompt document + key (+ template name) to a [Prompt]. */
internal object Resolver {
    internal const val DEFAULT_TEMPLATE: String = "default"

    /**
     * Selects [promptKey] from [document].
     *
     * @throws UnknownPromptException the prompt document has no such prompt
     * @throws UnresolvedPromptException the prompt has no live deployment in this environment
     * @throws UnknownPromptException the deployment pins no prompt of that name
     */
    @JvmOverloads
    internal fun resolve(
        document: PromptDocument,
        promptKey: String,
        template: String? = null,
        source: PromptSource = PromptSource.REMOTE,
        etag: String? = null,
    ): Prompt {
        val promptEntry = document.prompts[promptKey] ?: throw UnknownPromptException(promptKey)
        val deployment = document.deployments[promptKey] ?: throw UnresolvedPromptException(promptKey)
        val templateNames = if (promptEntry.kind ==
            PromptKind.EMBEDDING
        ) {
            emptyList()
        } else {
            deployment.templatePins.keys.sorted()
        }

        val templateName: String?
        val versionId: String?
        if (promptEntry.kind == PromptKind.EMBEDDING) {
            templateName = null
            versionId = null
        } else {
            templateName = template ?: DEFAULT_TEMPLATE
            versionId =
                deployment.templatePins[templateName]
                    ?: throw UnknownTemplateException(promptKey, templateName, templateNames)
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
        val runtimeKind = version?.kind ?: promptEntry.kind

        return Prompt(
            key = promptKey,
            kind = runtimeKind,
            environment = document.environment ?: "",
            deploymentId = deployment.id,
            deploymentRevision = deployment.revision,
            template = templateName,
            templateNames = if (promptEntry.kind == PromptKind.EMBEDDING) emptyList() else templateNames,
            model = model?.modelId,
            modelId = null,
            provider = model?.provider,
            api = deployment.api,
            requestPath = deployment.requestPath,
            params = mergeTools(mergeShallow(promptEntry.defaultParams, deployment.params), version?.tools),
            providerOptions = mergeShallow(model?.providerOptions, deployment.providerOptions),
            promptVersionId = null,
            promptVersionNumber = version?.number,
            engine = version?.engine ?: TemplateEngine.LIQUID,
            messageTemplates = if (runtimeKind == PromptKind.CHAT) version?.messages else null,
            decisionTemplate = if (runtimeKind == PromptKind.DECISION) version?.decision else null,
            textTemplate = if (runtimeKind == PromptKind.TEXT) version?.textTemplate else null,
            variables = promptEntry.variables,
            payloadPolicy = promptEntry.payloadPolicy,
            source = source,
            etag = etag,
            warnings = warnings,
            pinnedKind = version?.kind,
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

internal object RequestBuilder {
    private val PROTECTED_FIELDS =
        setOf(
            "model",
            "messages",
            "state",
            "questions",
            "provider",
            "usage",
            "api",
            "request_path",
            "method",
            "path",
            "body",
        )
    private val DECISION_FIELDS = setOf("session_id", "trace", "user")

    fun prepare(
        prompt: Prompt,
        variables: Map<String, Any?>,
        options: PreparedRequestOptions,
    ): PreparedRequest {
        val api = expectApi(prompt)
        val path = expectRequestPath(prompt)
        return when (api) {
            "chat_completions" -> prepareChat(prompt, variables, options, api, path)
            "decisions" -> prepareDecision(prompt, variables, options, api, path)
            else -> throw PreparedRequestException(
                "prompt ${prompt.key} uses unsupported deployment api ${prompt.api}",
            )
        }
    }

    private fun prepareChat(
        prompt: Prompt,
        variables: Map<String, Any?>,
        options: PreparedRequestOptions,
        api: String,
        path: String,
    ): PreparedRequest {
        if (options.sessionId != null || options.trace != null || options.user != null) {
            throw PreparedRequestException("Decisions metadata options require the Decisions API")
        }
        if (prompt.pinnedKind != PromptKind.CHAT) {
            throw PreparedRequestException(
                "prompt ${prompt.key} cannot prepare chat_completions because its pinned prompt version kind is ${prompt.pinnedKind?.wire ?: "missing"}",
            )
        }
        val messages =
            prompt.messageTemplates ?: throw PreparedRequestException("prompt ${prompt.key} has no chat prompt")
        val body = LinkedHashMap<String, Any?>()
        body["model"] = expectModel(prompt)
        body["messages"] = Template.renderMessages(messages, variables, prompt.engine).map { messageMap(it) }
        body.putAll(requestParams(Resolver.mergeShallow(prompt.params, options.params), api, emptyMap()))
        if (prompt.provider == "openrouter") body["usage"] = mapOf("include" to true)
        val provider = providerOptions(prompt, options)
        if (provider.isNotEmpty()) body["provider"] = provider
        return PreparedRequest(api = api, method = "POST", path = path, body = body)
    }

    private fun prepareDecision(
        prompt: Prompt,
        variables: Map<String, Any?>,
        options: PreparedRequestOptions,
        api: String,
        path: String,
    ): PreparedRequest {
        if (prompt.pinnedKind != PromptKind.DECISION) {
            throw PreparedRequestException(
                "prompt ${prompt.key} cannot prepare decisions because its pinned prompt version kind is ${prompt.pinnedKind?.wire ?: "missing"}",
            )
        }
        val decision = prompt.decision(variables)
        val params = decisionParams(prompt.params, options)
        val body = LinkedHashMap<String, Any?>()
        body["model"] = expectModel(prompt)
        body["state"] = decision.state
        body["questions"] = decision.questions
        body.putAll(params)
        val provider = providerOptions(prompt, options)
        if (provider.isNotEmpty()) body["provider"] = provider
        return PreparedRequest(api = api, method = "POST", path = path, body = body)
    }

    private fun expectApi(prompt: Prompt): String {
        val api = prompt.api
        if (api != "chat_completions" && api != "decisions") {
            throw PreparedRequestException(
                "prompt ${prompt.key} cannot prepare a provider request because its deployment has no supported api metadata",
            )
        }
        if (api == "chat_completions" && prompt.pinnedKind != PromptKind.CHAT) {
            throw PreparedRequestException(
                "prompt ${prompt.key} cannot prepare chat_completions because its pinned prompt version kind is ${prompt.pinnedKind?.wire ?: "missing"}",
            )
        }
        if (api == "decisions" && prompt.pinnedKind != PromptKind.DECISION) {
            throw PreparedRequestException(
                "prompt ${prompt.key} cannot prepare decisions because its pinned prompt version kind is ${prompt.pinnedKind?.wire ?: "missing"}",
            )
        }
        return api
    }

    private fun expectRequestPath(prompt: Prompt): String {
        val path = prompt.requestPath
        if (path.isNullOrEmpty()) {
            throw PreparedRequestException(
                "prompt ${prompt.key} cannot prepare a provider request because its deployment has no request_path metadata",
            )
        }
        if (!path.startsWith("/") || path.startsWith("//") ||
            Regex("^[a-z][a-z0-9+.-]*:", RegexOption.IGNORE_CASE).containsMatchIn(path)
        ) {
            throw PreparedRequestException(
                "prompt ${prompt.key} has invalid request_path $path; expected an origin-relative path",
            )
        }
        val expected = expectedPaths(prompt.provider, prompt.api)
        if (expected.isEmpty()) {
            throw PreparedRequestException(
                "prompt ${prompt.key} cannot prepare a provider request for provider ${prompt.provider} and api ${prompt.api}",
            )
        }
        if (path !in expected) {
            throw PreparedRequestException(
                "prompt ${prompt.key} has request_path $path, expected ${expected.joinToString(
                    " or ",
                )} for provider ${prompt.provider} and api ${prompt.api}",
            )
        }
        return path
    }

    private fun expectedPaths(
        provider: String?,
        api: String?,
    ): List<String> =
        when (provider to api) {
            "openrouter" to "chat_completions" -> listOf("/api/v1/chat/completions")
            "openrouter" to "decisions" -> listOf("/api/v1/systemone", "/api/alpha/decisions")
            "openai" to "chat_completions" -> listOf("/v1/chat/completions")
            "groq" to "chat_completions" -> listOf("/openai/v1/chat/completions")
            else -> emptyList()
        }

    private fun expectModel(prompt: Prompt): String =
        prompt.model?.takeIf { it.isNotBlank() }
            ?: throw PreparedRequestException(
                "prompt ${prompt.key} cannot prepare a provider request because its deployment model is missing",
            )

    private fun requestParams(
        params: Map<String, Any?>,
        api: String,
        canonical: Map<String, Any?>,
    ): Map<String, Any?> {
        val protected = params.keys.filter { it in PROTECTED_FIELDS && canonical[it] != params[it] }.sorted()
        if (protected.isNotEmpty()) {
            throw PreparedRequestException("$api request params cannot override protected field ${protected.first()}")
        }
        val out = LinkedHashMap<String, Any?>()
        params.forEach { (key, value) ->
            if (key !in canonical && value != null) out[key] = value
        }
        return out
    }

    private fun decisionParams(
        base: Map<String, Any?>,
        options: PreparedRequestOptions,
    ): Map<String, Any?> {
        val merged = LinkedHashMap<String, Any?>()
        base.forEach { (key, value) -> merged[key] = value }
        options.params?.forEach { (key, value) -> merged[key] = value }
        options.sessionId?.let { merged["session_id"] = it }
        options.trace?.let { merged["trace"] = it }
        options.user?.let { merged["user"] = it }

        val protected = merged.keys.filter { it in PROTECTED_FIELDS }.sorted()
        if (protected.isNotEmpty()) {
            throw PreparedRequestException(
                "decisions request params cannot override protected field ${protected.first()}",
            )
        }
        val unsupported = merged.keys.filter { it !in DECISION_FIELDS }.sorted()
        if (unsupported.isNotEmpty()) {
            val unsupportedNames = unsupported.joinToString(", ")
            throw PreparedRequestException(
                "Decisions requests support only session_id, trace, user and providerOptions; unsupported parameter(s): $unsupportedNames",
            )
        }
        merged.forEach { (key, value) ->
            when (key) {
                "session_id", "user" -> {
                    if (value !is String || value.length > 256) {
                        throw PreparedRequestException("Decisions $key must be a string of 256 characters or fewer")
                    }
                }

                "trace" -> {
                    if (value !is Map<*, *>) throw PreparedRequestException("Decisions trace must be an object")
                }
            }
        }
        return merged
    }

    private fun providerOptions(
        prompt: Prompt,
        options: PreparedRequestOptions,
    ): Map<String, Any?> {
        val provider = Resolver.mergeShallow(prompt.providerOptions, options.providerOptions)
        if (prompt.provider != "openrouter" && provider.isNotEmpty()) {
            throw PreparedRequestException(
                "prompt ${prompt.key} has provider options, but provider options are only supported for OpenRouter requests",
            )
        }
        if (!Decisions.json(provider)) throw PreparedRequestException("provider options must contain JSON values")
        return provider
    }

    private fun messageMap(message: PromptMessage): Map<String, Any?> {
        val fields = LinkedHashMap<String, Any?>()
        fields.putAll(message.extra)
        message.type?.let { fields["type"] = it }
        if (message.role.isNotBlank()) fields["role"] = message.role
        if (message.hasContent) fields["content"] = message.contentValue
        message.name?.let { fields["name"] = it }
        message.toolCallId?.let { fields["tool_call_id"] = it }
        if (message.hasToolCalls || message.toolCalls.isNotEmpty()) fields["tool_calls"] = message.toolCalls
        return fields
    }
}
