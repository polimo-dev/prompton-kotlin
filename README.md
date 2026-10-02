# PromptOn Kotlin SDK

PromptOn is a control plane for the prompts and models your app uses. Every place your code calls an
LLM becomes a **prompt**, and for each prompt and environment PromptOn holds one **pin**: a
prompt version, one model, and its parameters.

This SDK fetches that prompt document, fills the pinned prompt with your call's variables, and
sends back a monitoring log after you have called the provider. **You call the provider yourself**,
with your own key and your own HTTP client — PromptOn is config-fetch, not a proxy, so it is never in
the request path and never sees your provider key. If PromptOn is down your app keeps running on the
last prompt document it received.

```
useCase("greeting")        ──▶  UseCase(model, params, provider options, pinned prompt)
useCase.messages(vars)     ──▶  messages PromptOn manages
useCase.text(vars)         ──▶  text prompt
useCase.track(meta) { … }  ──▶  monitoring log, sent in batches
```

- Kotlin 2.x on JVM 17 or newer, `java.net.http` for HTTP, `kotlinx.serialization` for JSON.
- Apache-2.0. Package `dev.polimo.prompton`, artifact `dev.polimo:prompton-sdk`.

## Install

Not published to Maven Central yet. Until it is, depend on the repository:

```kotlin
// settings.gradle.kts
includeBuild("../prompton-kotlin")
```

```kotlin
// build.gradle.kts
dependencies {
    implementation("dev.polimo:prompton-sdk:0.5.0")
}
```

## Quick start

```kotlin
val prompton = PromptOn() // reads PTN_HOST and PTN_API_KEY
val useCase = prompton.useCase("greeting") // memory-cached, no HTTP on this path
val variables = mapOf("name" to "Ada")
val managedMessages = useCase.messages(variables)
val finalMessages = managedMessages + conversationHistory + PromptMessage(role = "user", content = userText)

val answer = useCase.trackBlocking(
    TrackMeta(variables = variables, inputMessages = finalMessages),
) { call ->
    val reply = myOpenAiClient.chat(useCase.model!!, finalMessages, useCase.params)
    call.result(Result.fromOpenAI(reply))
    reply.text
}
```

`track` is the suspending twin of `trackBlocking`; so are `flush`, `useCaseRemote` and
`promptOnServer`. Close the instance on shutdown (`prompton.close()`) so the log queue drains.

A runnable version, including a fake provider and a committed bundle, is in
[`examples/`](examples/src/main/kotlin/dev/polimo/prompton/examples/GreetingExample.kt):

```sh
./gradlew :examples:run
```

## Configuration

Every value follows the same precedence: **an explicit argument > the environment variable > the
default**.

| Option | Environment variable | Default | What it does |
|---|---|---|---|
| `apiKey` | `PTN_API_KEY` | none | `ptn_<project>_…`, the runtime key. Without it the SDK makes no remote calls at all: it works from disk or bundle and says so once |
| `host` | `PTN_HOST` | `https://app.prompton.ai` | The SDK appends `/api/v1` itself |
| `environment` | `PTN_ENVIRONMENT` | `production` | Which environment this process reads. Also the guard on cached and bundled documents |
| `project` | `PTN_PROJECT` | read from the API key | Names the disk cache file and guards cached documents |
| `cacheTtl` | | 10 s | TTL/backoff base for `/prompts/{key}/render` cached server-render answers; runtime config fetch always uses the SDK fixed 10 s freshness and attempt gate |
| `pollingEnabled` | | `false` | Deprecated compatibility option; config is always fetched on demand per prompt key |
| `connectTimeout` / `requestTimeout` | | 5 s | HTTP timeouts |
| `startupFetchTimeout` | | 1 s | Deprecated compatibility option; config fetches use a one-second total budget |
| `diskCacheEnabled` | | `true` | Mirror every fetched prompt document to a local file |
| `diskCachePath` | | OS cache dir, `prompton/prompts-<project>-<environment>.json` | Where that file lives |
| `bundlePath` | | none | A prompt document JSON file committed into your repository |
| `log.flushInterval` | | 2 s | Time trigger for the monitoring-log queue |
| `log.flushSize` | | 100 | Size trigger |
| `log.flushBytes` | | 1 MB | Bytes trigger |
| `log.maxBufferSize` | | 10 000 | Queue cap; over it the oldest records are dropped and counted |
| `log.maxBatchSize` / `log.maxBatchBytes` | | 200 / 4 MB | One request's cap |
| `log.maxAttempts` | | 8 | How often one batch is retried before it is dropped and counted |
| `log.redact` | | none | `(JsonObject) -> JsonObject`, applied to every record last |
| `hashEndUser` | | `false` | Send `sha256(end_user_ref)` instead of the raw reference |
| `mode` | | `LIVE` | `LIVE`, `TEST` (no HTTP, records captured) or `OFFLINE` (disk and bundle only) |
| `payloadDefaults` | | full, 1.0, 256 KB | The policy for a prompt whose document carries none |
| `transport` | | `JdkHttpTransport` | Any `HttpTransport`, for your own client or a stub in tests |

```kotlin
val prompton = PromptOn(
    PromptOnConfig(
        environment = "staging",
        bundlePath = Path.of("config/prompts.staging.json"),
        hashEndUser = true,
        log = LogOptions(flushInterval = 5.seconds, redact = { record -> stripPii(record) }),
    ),
)
```

## Resilience

The single most important behaviour of the SDK: **a provider call never fails because PromptOn did.**
Config is stale in the worst case, not absent.

```
start      memory → disk cache → bundle, with no remote call
useCase    if the key is stale or missing: GET /prompts/{key}?environment=…
deadline   one second total; no retry
failure    keep serving the last valid value, even expired
```

- Inside the fixed 10-second config TTL every `useCase` call is a map lookup with no HTTP at all.
- After that fixed TTL, callers for the same key share one in-flight config fetch and its original deadline.
  Other keys are independent and are not serialized behind a slow key.
- A failed config attempt, including timeout, invalid payload or scope mismatch, starts the same
  10-second per-key gate. With no cached value, the SDK raises `UseCaseDocumentUnavailableException`.
- Fetched prompt documents are mirrored atomically to disk, with a metadata sidecar holding ETag,
  `Last-Modified`, project and environment.
- Commit `prompts.<environment>.json` (write it with `prompton.exportUseCaseDocument(path)`) and
  point `bundlePath` at it for cold starts without network.
- A document for another environment, another project, or an unsupported `schema_version` is refused
  with a warning.
- With no usable tier at all, the SDK raises `UseCaseDocumentUnavailableException`.

`prompton.useCaseDocumentInfo()` reports the most recently served document — ETag, source, age and
whether it is stale. `refreshBlocking` is kept only as a compatibility method and does not restore
bulk config polling.

`prompton.useCaseRemoteBlocking(useCase)` uses `/prompts/{key}/render`, caches the answer per use
case, prompt and environment for the cache TTL, and follows the same `Retry-After`/backoff rules.

## How it fails

| Situation | What the SDK does | What your call sees |
|---|---|---|
| Inside the fixed 10-second config TTL | Serves memory | The pinned configuration |
| Past the fixed TTL, fetch in flight | Serves the previous document | The previous configuration |
| Config fetch returns `304` | Keeps the document, marks it fresh | Unchanged |
| Config fetch returns `429` | Keeps serving and gates the next config attempt for the fixed 10 s window | Nothing; no error |
| Config fetch returns `5xx`, times out, DNS fails | Keeps serving and gates the next config attempt for the fixed 10 s window | Nothing; the document is marked stale |
| `/prompts/{key}/render` answers `429` or `5xx`, or is unreachable | Serves the cached answer and waits out `Retry-After` or the backoff | The previous answer; with nothing cached, a `PromptOnException` |
| Server unreachable at start-up | Loads disk, then bundle | The cached configuration, `source` `disk` or `bundle` |
| Use case document for the wrong environment or project | Refuses it, logs a warning, keeps looking | The next tier, or `UseCaseDocumentUnavailableException` |
| Corrupt or half-written cache file | Ignores it | The next tier |
| No tier has a document | Fails loudly | `UseCaseDocumentUnavailableException` |
| Unknown prompt key | Fails loudly | `UnknownUseCaseException` |
| Use case with no live deployment | Fails loudly | `UnresolvedUseCaseException` |
| Prompt name the revision does not pin | Fails loudly, never falls back to `default` | `UnknownPromptException` with `promptNames` |
| A variable the template needs is missing | Fails loudly | `MissingVariableException` with the name |
| No API key configured | Makes no remote call; logs it once | Disk or bundle configuration; monitoring logs are dropped |
| Monitoring log queue full | Drops the oldest and counts them | Nothing; `logStats().dropped` grows |
| `/logs` answers `429` or `5xx` | Retries the same batch with the same ids | Nothing; the ids make a resend a duplicate |
| `/logs` answers `413` | Splits the batch in half and resends | Nothing |
| `/logs` answers another `4xx` | Drops the batch and counts it | Nothing; a warning in the log |
| Your provider call throws | Logs `status: error`, `error.kind: app`, then rethrows | Your exception, unchanged |

**Never fall back to a hard-coded prompt.** An unknown prompt, an unresolved deployment or an
unpinned prompt name is a bug in the deployment or the call. Fail that call loudly instead.

## Prompt templates

The pinned prompt is a Liquid subset, rendered locally:

- Output: `{{ name }}`, `{{ user.name }}`, `{{ items[0] }}`. No HTML escaping.
- Tags: `for` (with `else`, `break`, `continue`, `forloop.*`), `if`/`elsif`/`else`, `unless`,
  `assign`.
- Filters: `size`, `join`, `default`.

```kotlin
Template.render("Hello {{ name }}", mapOf("name" to "Ada"))   // "Hello Ada"
Template.lint("{{ s | upcase }}")                             // [LintReason(disallowed_filter, upcase)]
Template.variables("{% for t in notes %}{{ t }}{% endfor %}") // ["notes"]
```

For chat calls, PromptOn returns only the messages managed in the editor, usually the system and
developer instructions. Conversation history and the current user message belong to your app:

```kotlin
val variables = mapOf("locale" to "ko-KR")
val managedMessages = useCase.messages(variables)
val finalMessages =
    managedMessages +
        loadConversationHistory(conversationId) +
        PromptMessage(role = "user", content = userText)

val reply = myOpenAiClient.chat(useCase.model!!, finalMessages, useCase.params)
useCase.trackBlocking(TrackMeta(variables = variables, inputMessages = finalMessages)) {
    it.result(Result(content = reply.text))
    reply.text
}
```

## Monitoring logs

Three entry points:

```kotlin
prompton.log(record)                              // enqueue one record you built yourself
prompton.flushBlocking()                          // send now and wait — shutdown, tests, scripts
useCase.trackBlocking(meta) { call -> … }         // time a provider call and log it
```

`log` validates `useCase`, `model`, `status` and `startedAt`, fills in a UUIDv7 `id` and the `sdk`
block, applies the payload policy, and returns immediately. Behind it a buffer batches on size, time
or bytes, sends at most 200 records and 4 MB per request under `{"logs": [...]}`, one batch per
environment, and retries the same ids on `429` and `5xx`.

Provider-side `%Req.TransportError{reason: :closed}` monitoring records with `error.kind:
"transport"` are retry noise from the upstream SDK. PromptOn omits that exact closed transport
error before payload redaction, buffering, test capture or submission. Other transport, timeout and
application errors are still logged.

| Field | Notes |
|---|---|
| `id` | UUIDv7, generated before the provider call; the idempotency key |
| `prompt_key`, `model`, `status`, `started_at` | Required |
| `kind` | `chat`, `text` or `embedding` |
| `deployment_id`, `deployment_revision`, `template`, `prompt_version_id` | The pin that produced the call |
| `source` | `remote`, `disk`, `bundle` or `manual` — where the configuration came from |
| `provider`, `model_used`, `upstream_provider` | Who actually served it |
| `params` | The prompt params, with your per-call overrides layered on |
| `input` | `{variables, messages}` or `{text}` |
| `output` | `{content, tool_calls}` |
| `finish_reason`, `stop_kind` | The provider's raw value and PromptOn's normalisation |
| `error` | `{kind, status, message}` on failures |
| `usage` | `{input_tokens, output_tokens, cost_usd, cost_source, raw}` |
| `latency_ms`, `trace_id`, `sequence`, `end_user_ref` | Timing and correlation |
| `context`, `metadata` | Free-form tags |
| `sdk` | `{name: "prompton-kotlin", version}` |

`Result.fromOpenAI(answer)` and `Result.fromAnthropic(answer)` extract common content,
finish-reason, usage-token and model fields from provider responses.

## Testing your app

```kotlin
val prompton = PromptOn(PromptOnConfig(mode = PromptOnMode.TEST))
prompton.putUseCaseDocument(File("prompts.production.json").readText())

myService.greet("Ada")

val record = prompton.capturedRecords().single() // nothing was sent anywhere
```

`PromptOnMode.TEST` makes no HTTP calls and captures every record for assertions;
`PromptOnMode.OFFLINE` reads the disk cache and bundle but never fetches or sends.

## Building this repository

```sh
./gradlew ktlintCheck        # lint
./gradlew test               # unit tests + the cross-language conformance suite
./gradlew assemble           # the jar
./gradlew :examples:run      # the runnable example
```

The tests in `src/test/resources/conformance/` are the cross-language contract: the same JSON cases
every PromptOn SDK runs, so that two languages talking to the same project cannot disagree about how
a prompt fills, which model a prompt document selects, or how a monitoring log is truncated.

`LiveFixtureIntegrationTest` runs against a real PromptOn server and is skipped unless `PTN_API_KEY`
is set:

```sh
PTN_HOST=http://localhost:4000 PTN_API_KEY=ptn_sdkfixture_… ./gradlew test
```

## Reference

- [Runtime API](https://docs.prompton.ai/api) — `GET /prompts/{key}`, `POST /prompts/{key}/render`,
  `POST /logs`
- [Agent reference](https://docs.prompton.ai/agent) — the whole contract on one page

## Prompt tools and trace events

Schema 7 prompt versions may include a `tools` block with OpenAI-compatible function tool definitions plus optional `tool_choice` and `parallel_tool_calls`. The SDK merges those into the provider params it returns and strips authoring-only `output_schema` / `output_examples` before the provider request body is built. The SDK never calls tools itself.

Use `logEvents` to submit observed tool attempts and completion events to the same monitoring endpoint when your application has executed or rejected tool calls. Events require `trace_id`, `event_kind`, and `status`; the SDK fills `event_id`, `observed_at`, SDK identity, and `metadata.sdk.version` when they are absent. Completion events whose `completion_output` is exactly the closed Req transport error are omitted after validation.
