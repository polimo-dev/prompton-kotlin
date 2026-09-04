# PromptOn Kotlin SDK

PromptOn is a control plane for the prompts and models your app uses. Every place your code calls an
LLM becomes a **use case**, and for each use case and environment PromptOn holds one **pin**: a
prompt version, one model, and its parameters.

This SDK fetches that configuration, renders the pinned prompt with your call's variables, and sends
back a monitoring log after you have called the provider. **You call the provider yourself**, with
your own key and your own HTTP client — PromptOn is config-fetch, not a proxy, so it is never in the
request path and never sees your provider key. If PromptOn is down your app keeps running on the
last snapshot it received.

```
resolve("greeting")  ──▶  Resolution(model, params, provider options, pinned prompt)
resolution.render(variables)  ──▶  messages
generate(resolution, meta) { call your provider }  ──▶  monitoring log, sent in batches
```

- Kotlin 2.x on JVM 17 or newer, `java.net.http` for HTTP, `kotlinx.serialization` for JSON.
- Apache-2.0. Package `dev.polimo.prompton`, artifact `dev.polimo:prompton-sdk`.

## Install

Not published to Maven Central yet. Until it is, depend on the repository — with
[Gradle's source dependencies](https://docs.gradle.org/current/userguide/composite_builds.html), a
git submodule and `includeBuild`, or a checkout next to your project:

```kotlin
// settings.gradle.kts
includeBuild("../prompton-kotlin")
```

```kotlin
// build.gradle.kts
dependencies {
    implementation("dev.polimo:prompton-sdk:0.1.0")
}
```

Once it is published the line will be the same without the `includeBuild`. Check Maven Central
before you assume: only an artifact published by **polimo-dev** whose documentation points at
PromptOn is this SDK.

## Quick start

```kotlin
val prompton = PromptOn()                                   // reads PTN_HOST and PTN_API_KEY
val resolution = prompton.resolve("greeting")               // memory-cached, no HTTP on this path
val messages = resolution.render(mapOf("name" to "Ada")).messages!!

val answer = prompton.generateBlocking(resolution, GenerationMeta(inputMessages = messages)) { call ->
    val reply = myOpenAiClient.chat(resolution.model!!, messages, resolution.effectiveParams)
    call.succeeded(ProviderOutcome(content = reply.text, finishReason = reply.finishReason))
    reply.text
}
```

`generate` is the suspending twin of `generateBlocking`; so are `refresh`, `flush`, `resolveRemote`
and `resolveOnServer`. Close the instance on shutdown (`prompton.close()`) so the log queue drains —
one process-wide shutdown hook is a backstop, and an instance you drop without closing has its
threads released when it is garbage collected.

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
| `cacheTtl` | | 10 s | How long a snapshot is served without revalidating |
| `pollingEnabled` | | `true` | A background thread revalidates every `cacheTtl` |
| `connectTimeout` / `requestTimeout` | | 5 s | HTTP timeouts |
| `startupFetchTimeout` | | 3 s | The budget for the one fetch a cold start may wait on |
| `diskCacheEnabled` | | `true` | Mirror every fetched snapshot to a local file |
| `diskCachePath` | | OS cache dir, `prompton/snapshot-<project>-<environment>.json` | Where that file lives |
| `bundlePath` | | none | A snapshot JSON file committed into your repository |
| `log.flushInterval` | | 2 s | Time trigger for the monitoring-log queue |
| `log.flushSize` | | 100 | Size trigger |
| `log.flushBytes` | | 1 MB | Bytes trigger |
| `log.maxBufferSize` | | 10 000 | Queue cap; over it the oldest records are dropped and counted |
| `log.maxBatchSize` / `log.maxBatchBytes` | | 200 / 4 MB | One request's cap (the server refuses over 200 records or 5 MB) |
| `log.maxAttempts` | | 8 | How often one batch is retried before it is dropped and counted |
| `log.redact` | | none | `(JsonObject) -> JsonObject`, applied to every record last |
| `hashEndUser` | | `false` | Send `sha256(end_user_ref)` instead of the raw reference |
| `mode` | | `LIVE` | `LIVE`, `TEST` (no HTTP, records captured) or `OFFLINE` (disk and bundle only) |
| `payloadDefaults` | | full, 1.0, 256 KB | The policy for a use case whose snapshot carries none |
| `transport` | | `JdkHttpTransport` | Any `HttpTransport`, for your own client or a stub in tests |

```kotlin
val prompton = PromptOn(
    PromptOnConfig(
        environment = "staging",
        bundlePath = Path.of("config/snapshot.staging.json"),
        hashEndUser = true,
        log = LogOptions(flushInterval = 5.seconds, redact = { record -> stripPii(record) }),
    ),
)
```

## Resilience

The single most important behaviour of the SDK: **a generation never fails because PromptOn did.**
Config is stale in the worst case, not absent.

```
start      memory → disk cache → bundle → remote
resolve    served from memory; past the cache TTL a background revalidation starts and this call
           returns anyway
refresh    GET /snapshot?environment=… with If-None-Match; 304 costs nothing
failure    keep serving the previous document, back off, try again
```

- **10-second memory cache.** Inside it every resolve is a map lookup with no HTTP at all. Past it
  the next resolve kicks off a revalidation in the background (and a poll loop does the same on its
  own schedule). A refresh in flight, or a refresh that fails, never touches the document being
  served.
- **Rate limits.** On `429` the SDK reads `Retry-After` (seconds or an HTTP date), falling back to
  `error.details.retry_after` and then to backoff, and does not contact the server again before it
  has elapsed. The caller sees nothing. Every path obeys that window — the poll loop, the
  stale-while-revalidate refresh, `refreshBlocking()` and the `POST /resolve` client — so a health
  check on a timer cannot hammer a server that asked for silence.
- **Backoff.** `5xx`, timeouts and transport errors double the wait from the cache TTL up to five
  minutes, resetting on the first success.
- **Disk cache, on by default.** Every fetched snapshot is written atomically (temp file, then
  rename) with a `<path>.meta.json` sidecar holding the ETag, `Last-Modified`, project and
  environment. Several processes on one host may share the file: a reader sees either the old file
  or the new one, and a partial or corrupt file is ignored rather than raised.
- **Bundle.** Commit `snapshot.<environment>.json` (write it with `prompton.exportSnapshot(path)`)
  and point `bundlePath` at it. A cold start with no disk cache and no network still resolves. In
  serverless runtimes it is the primary fallback, not a nicety.
- **Guards.** A document for another environment, another project, or an unsupported
  `schema_version` is refused with a warning, whichever tier it came from. A `staging` process never
  boots on a `production` bundle.
- **No external services, ever.** Memory, one local file and the bundled file are the only tiers.
  There is no database, no Redis, nothing to coordinate: each instance keeps its own copy, which
  `If-None-Match` polling makes cheap.
- **The one failure the caller does see** is the case where no tier has anything at all:
  `SnapshotUnavailableException`, whose message says PromptOn is unreachable and nothing is cached.

`prompton.snapshotInfo()` reports what is being served — ETag, source, age and whether it is stale —
and `prompton.refreshBlocking()` fetches once, now, for scripts and health checks. It returns whether
a document is in memory afterwards, and it stays silent while a `Retry-After` or a backoff window is
running; `prompton.refreshBlocking(force = true)` is the deliberate way through that window.

`prompton.resolveRemoteBlocking(useCase)` — the `POST /resolve` simple path — follows the same rules:
the answer is cached per use case, prompt and environment for the cache TTL and rendered locally, and
a `429`, a `5xx` or an unreachable server keeps the cached answer serving *and* stops the SDK calling
again until `Retry-After`, or the doubling backoff, has passed. With nothing cached that call fails,
and calls inside the window fail immediately instead of piling onto a server that is already
struggling. A `4xx` is about the request, not about load, so it is never held back.

## How it fails

| Situation | What the SDK does | What your call sees |
|---|---|---|
| Inside the cache TTL | Serves memory | The pinned configuration |
| Past the TTL, refresh in flight | Serves the previous document | The previous configuration |
| Refresh returns `304` | Keeps the document, marks it fresh | Unchanged |
| Refresh returns `429` | Waits out `Retry-After`, keeps serving | Nothing; no error |
| Refresh returns `5xx`, times out, DNS fails | Backs off ×2 up to 5 min, keeps serving | Nothing; the document is marked stale |
| `refreshBlocking()` inside that window | Skips the call, says so once | `true` while a document is cached; `force = true` calls anyway |
| `POST /resolve` answers `429` or `5xx`, or is unreachable | Serves the cached answer and waits out `Retry-After` or the backoff | The previous answer; with nothing cached, a `PromptOnException` saying it will not call again yet |
| Server unreachable at start-up | Loads disk, then bundle | The cached configuration, `resolution_source` `disk` or `bundle` |
| Snapshot for the wrong environment or project | Refuses it, logs a warning, keeps looking | The next tier, or `SnapshotUnavailableException` |
| Corrupt or half-written cache file | Ignores it | The next tier |
| No tier has a document | Fails loudly | `SnapshotUnavailableException` |
| Unknown use case key | Fails loudly | `UnknownUseCaseException` |
| Use case with no live deployment | Fails loudly | `UnresolvedUseCaseException` |
| Prompt name the revision does not pin | Fails loudly, never falls back to `default` | `UnknownPromptException` with `availablePrompts` |
| A variable the template needs is missing | Fails loudly | `MissingVariableException` with the name |
| No API key configured | Makes no remote call; logs it once | Disk or bundle configuration; monitoring logs are dropped |
| Monitoring log queue full | Drops the oldest and counts them | Nothing; `logStats().dropped` grows |
| `POST /generations` answers `429` or `5xx` | Retries the same batch with the same ids | Nothing; the ids make a resend a duplicate, never a double write |
| `POST /generations` answers `413` | Splits the batch in half and resends | Nothing |
| `POST /generations` answers another `4xx` | Drops the batch and counts it | Nothing; a warning in the log |
| `flushBlocking()` while the server is rate-limiting | Keeps the records queued rather than flushing into the window | `FlushResult.remaining` says how many are still waiting |
| `close()` while the server is rate-limiting | One last attempt regardless, then counts what would not go | `logStats().dropped` grows; a warning names the count |
| An instance dropped without `close()` | A cleaner releases its threads when it is collected | Nothing; one hook for the process, not one per instance |
| Your provider call throws | Logs `status: error`, `error.kind: app`, then rethrows | Your exception, unchanged |

**Never fall back to a hard-coded prompt.** An unknown use case, an unresolved deployment or an
unpinned prompt name is a bug in the deployment or the call. Fail that call loudly instead.

## Prompt templates

The pinned prompt is a Liquid subset, rendered locally:

- Output: `{{ name }}`, `{{ user.name }}`, `{{ items[0] }}`. No HTML escaping.
- Tags: `for` (with `else`, `break`, `continue`, `forloop.*`), `if`/`elsif`/`else`, `unless`,
  `assign`.
- Filters: `size`, `join`, `default`.
- Everything else — `include`, `capture`, `case`, `raw`, `comment`, `cycle` — is a parse error, and
  whitespace control (`{%-`, `-%}`) is rejected at commit time.

A variable is *missing* when its key is absent, which is an error at an output position, in a `for`
enumerable, in an `unless` condition and as an `assign` source. A key present with a `null` value is
not missing: it renders as the empty string and `default` replaces it.

```kotlin
Template.render("Hello {{ name }}", mapOf("name" to "Ada"))   // "Hello Ada"
Template.lint("{{ s | upcase }}")                             // [LintReason(disallowed_filter, upcase)]
Template.variables("{% for t in notes %}{{ t }}{% endfor %}") // ["notes"]
```

## Monitoring logs

Three entry points:

```kotlin
prompton.log(record)                                  // enqueue one record you built yourself
prompton.flushBlocking()                              // send now and wait — shutdown, tests, scripts
prompton.generateBlocking(resolution, meta) { call -> … }   // time a provider call and log it
```

`log` validates `useCase`, `model`, `status` and `startedAt`, fills in a UUIDv7 `id` and the `sdk`
block, applies the payload policy, and returns immediately. Behind it a buffer batches on size, time
or bytes, sends at most 200 records and 4 MB per request, one batch per environment, and retries the
same ids on `429` and `5xx`.

| Field | Notes |
|---|---|
| `id` | UUIDv7, generated before the provider call; the idempotency key. A resend is counted as a duplicate, never stored twice |
| `use_case`, `model`, `status`, `started_at` | Required |
| `kind` | `chat`, `text` or `embedding` |
| `deployment_id`, `deployment_revision`, `prompt`, `prompt_version_id` | The pin that produced the call, filled from the `Resolution` |
| `resolution_source` | `remote`, `disk`, `bundle` or `manual` — where the configuration came from |
| `provider`, `model_used`, `upstream_provider` | Who actually served it |
| `params` | The resolution's effective params, with your per-call overrides layered on |
| `input` | `{variables, messages}` or `{text}` |
| `output` | `{content, tool_calls}` |
| `finish_reason`, `stop_kind` | The provider's raw value and PromptOn's normalisation (`stop`, `length`, `tool_call`, `content_filter`, `other`) |
| `error` | `{kind, status, message}` on failures; `kind` is one of `http_4xx`, `http_5xx`, `rate_limited`, `timeout`, `transport`, `parse`, `app` |
| `usage` | `{input_tokens, output_tokens, cost_usd, cost_source, raw}` |
| `latency_ms`, `trace_id`, `sequence`, `end_user_ref` | Timing and correlation |
| `context`, `metadata` | Free-form tags; at most 2 KB and 4 KB or the server rejects the record |
| `sdk` | `{name: "prompton-kotlin", version}` |

Payloads follow the use case's `payload_policy` **before** anything leaves the process: sampling on
a hash of the id (errors and `length` truncations are always kept), then truncation that keeps the
head and tail of an oversized string, then `hash` or `none` mode, then the `error.message` cap, then
`end_user_ref` hashing, then your `redact` hook last.

**Do not log secrets.** No provider keys, no `PTN_API_KEY`, no user PII beyond `end_user_ref`.

## Testing your app

```kotlin
val prompton = PromptOn(PromptOnConfig(mode = PromptOnMode.TEST))
prompton.putSnapshot(File("snapshot.production.json").readText())

myService.greet("Ada")

val record = prompton.capturedRecords().single()   // nothing was sent anywhere
```

`PromptOnMode.TEST` makes no HTTP calls and captures every record for assertions;
`PromptOnMode.OFFLINE` reads the disk cache and bundle but never fetches or sends. For a stubbed
server, pass your own `HttpTransport`.

## Building this repository

```sh
./gradlew ktlintCheck        # lint
./gradlew test               # unit tests + the cross-language conformance suite
./gradlew assemble           # the jar
./gradlew :examples:run      # the runnable example
```

The tests in `src/test/resources/conformance/` are the cross-language contract: the same JSON cases
every PromptOn SDK runs, so that two languages talking to the same project cannot disagree about how
a prompt renders, which model a snapshot resolves to, or how a monitoring log is truncated.

`LiveFixtureIntegrationTest` runs against a real PromptOn server and is skipped unless `PTN_API_KEY`
is set:

```sh
PTN_HOST=http://localhost:4000 PTN_API_KEY=ptn_sdkfixture_… ./gradlew test
```

## Reference

- [Runtime API](https://docs.prompton.ai/api) — `GET /snapshot`, `POST /resolve`,
  `POST /generations`
- [Agent reference](https://docs.prompton.ai/agent) — the whole contract on one page
