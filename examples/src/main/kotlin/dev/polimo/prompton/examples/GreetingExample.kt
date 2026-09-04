package dev.polimo.prompton.examples

import dev.polimo.prompton.GenerationMeta
import dev.polimo.prompton.PromptMessage
import dev.polimo.prompton.PromptOn
import dev.polimo.prompton.PromptOnConfig
import dev.polimo.prompton.PromptOnMode
import dev.polimo.prompton.ProviderOutcome
import dev.polimo.prompton.Usage
import java.nio.file.Files
import java.nio.file.Path

/**
 * Resolve a use case, render its prompt, call a provider, log what happened.
 *
 * Run it with `./gradlew :examples:run`. With `PTN_API_KEY` set it fetches the live snapshot and
 * sends the monitoring log; without one it runs offline on the committed
 * `examples/snapshot.production.json` bundle — which is the same thing your app does when PromptOn
 * is unreachable.
 */
fun main() {
    val apiKey = System.getenv("PTN_API_KEY")
    val config =
        PromptOnConfig(
            apiKey = apiKey,
            mode = if (apiKey.isNullOrBlank()) PromptOnMode.OFFLINE else PromptOnMode.LIVE,
            bundlePath = bundlePath(),
            diskCacheEnabled = false,
        )

    PromptOn(config).use { prompton ->
        // The bundle answers immediately, so nothing waits on the network. Asking for one fetch up
        // front only makes the example print `remote` instead of `bundle`.
        if (config.mode == PromptOnMode.LIVE) prompton.refreshBlocking()

        // 1. Which model, params and prompt version this call should use.
        val resolution = prompton.resolve("greeting")
        println("use case      : ${resolution.useCase} (${resolution.kind.wire})")
        println("model         : ${resolution.model} via ${resolution.provider}")
        println("params        : ${resolution.effectiveParams}")
        println("pin           : deployment ${resolution.deploymentRevision}, prompt '${resolution.prompt}'")
        println("configuration : ${resolution.source.wire}")

        // 2. This call's variables go into the pinned template.
        val variables = mapOf("name" to "Ada")
        val messages = resolution.render(variables).messages.orEmpty()
        println("messages      : $messages")

        // 3. Your provider, your key, your HTTP client. PromptOn is never in this path.
        val answer =
            prompton.generateBlocking(
                resolution,
                GenerationMeta(
                    variables = variables,
                    inputMessages = messages,
                    endUserRef = "user-42",
                    traceId = "example:1",
                    context = mapOf("language" to "en"),
                ),
            ) { call ->
                val reply = fakeProvider(resolution.model.orEmpty(), messages)
                call.succeeded(
                    ProviderOutcome(
                        content = reply.content,
                        finishReason = reply.finishReason,
                        usage = Usage(inputTokens = reply.inputTokens, outputTokens = reply.outputTokens),
                        modelUsed = resolution.model,
                    ),
                )
                reply.content
            }
        println("answer        : $answer")

        // 4. The monitoring log left the app in a batch; flush before exit.
        val flushed = prompton.flushBlocking()
        println("logs          : accepted ${flushed.accepted}, remaining ${flushed.remaining}")
    }
}

private data class FakeReply(
    val content: String,
    val finishReason: String,
    val inputTokens: Long,
    val outputTokens: Long,
)

/** Stands in for an OpenAI, Anthropic or OpenRouter client so the example needs no provider key. */
private fun fakeProvider(
    model: String,
    messages: List<PromptMessage>,
): FakeReply {
    require(model.isNotBlank()) { "the snapshot pinned no model" }
    val name = messages.last().content.substringAfter("Say hello to ").removeSuffix(".")
    return FakeReply(
        content = "Hello, $name! Lovely to see you.",
        finishReason = "stop",
        inputTokens = messages.sumOf { it.content.length / 4 }.toLong(),
        outputTokens = 9,
    )
}

private fun bundlePath(): Path {
    val candidates = listOf(Path.of("snapshot.production.json"), Path.of("examples/snapshot.production.json"))
    return candidates.firstOrNull { Files.isRegularFile(it) } ?: candidates.first()
}
