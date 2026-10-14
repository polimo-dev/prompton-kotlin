# Changelog

All notable changes to the PromptOn Kotlin SDK.

## 0.8.0

Breaking contract reset: prompt variable definitions are now `variables`, and only schema version 1 is accepted.

- Decode singular prompt details and keyed offline bundles by shape, without old schema-version branches or `input_schema` aliases.
- Keep runtime render values and native provider payloads unchanged.
- Reject lightweight prompt-list responses as configuration; cache and export retain the original detail bytes.

## 0.7.1

- Read schema v9 single-prompt documents from `GET /prompts/{key}` with singular `prompt`,
  `deployment`, `prompt_version` and `model` resources.
- Keep schema v8 bulk documents unchanged while normalizing schema v9 only at the decode boundary.
- Preserve the original schema v9 response bytes for ETag revalidation, disk cache entries and
  `exportUseCaseDocument`.

## 0.7.0

- Read schema v8 prompt documents whose public resource references are readable keys instead of
  UUIDs: deployments reference `provider` + `model` and `prompt_version`, prompt versions are keyed
  as `prompt@number`, and models are keyed as `provider:model`.
- Schema v8 local config and remote render results keep the deprecated compatibility id properties
  `deploymentId`, `modelId` and `promptVersionId` null while preserving readable
  `deploymentRevision`, `provider`, `model` and `promptVersionNumber`.
- Legacy schema v4-v7 cache and bundle documents still decode unchanged.

## 0.6.1

- Add native Decision prompt support: schema v6+ prompt-version `kind: "decision"`, local Decision rendering, provider request preparation, remote render/cache preservation and monitoring-log `input.decision`.
- Add `Prompt.decision`, `Prompt.request`, `PreparedRequest`, `PreparedRequestOptions`, `RenderedDecision` and `Result.fromDecisions` for typed Decision provider responses and usage.
- Reject missing or unsupported provider protocol metadata instead of preparing a chat-shaped request for Decision prompts.

## 0.6.0

- Message slots are no longer expanded by the SDK. A prompt document containing a `type: "slot"`
  chat message now fails during rendering and applications should compose conversation history and
  the current user message in app code before calling the provider.
- Documentation and examples now show logging the final app-composed `inputMessages` while keeping
  ordinary variables such as `history` available as normal template variables.
- Omit the exact closed Req transport retry noise from monitoring records and completion trace
  events while preserving all other transport, timeout and application errors.
- Monitoring logs now send readable resource references: `deployment_revision`, `provider`, `model`
  and `prompt_version_number`. `LogRecord` no longer has UUID resource properties for
  `deployment_id`, `prompt_version_id` or `model_id`; prompt documents and response objects still
  expose those internal ids for local resolution and debugging.


## 0.5.0

Demand-driven config fetch release.

- Removed startup and idle config polling. `prompt(key)` now fetches only that prompt key when its cached value is stale or missing.
- Added per-project/environment/prompt in-memory cache entries with a 10-second freshness TTL and a separate 10-second attempt gate.
- Added same-key single-flight config fetches, one-second total config-fetch deadline, no SDK retry, stale fallback after failures, and explicit cold-cache failure.
- Switched runtime config lookup to `GET /api/v1/prompts/{key}?environment=...` with per-key ETag validation. Bulk `GET /prompts` remains outside the normal runtime path.
- Bumped the SDK version to 0.5.0 for the behavior change.

## 0.4.2

Patch release aligning native tool prompts and monitoring events with the verified preview runtime contract.

- Preserve native chat messages, including explicit `null` content, tool-result messages, and empty `tool_calls` arrays, through local and remote rendering.
- Send monitoring-log identity as `prompt_key` and selected template evidence as `template`.
- Return `EventLogResult` from `logEvents`, parsing nested event acceptance, duplicate, and rejection counts from `/logs`.
- Preserve the server-rendered provider request on remote prompts with `providerPreparedRequest`.

## 0.4.1

Patch release correcting the SDK wire contract to the current PromptOn API.

- Fetch prompt documents from `GET /api/v1/prompts` and render through `POST /api/v1/prompts/{key}/render`.
- Decode canonical prompt documents with `prompts` and `template_pins`, and exercise `conformance/prompt.json` in the resolver tests.
- Send the render request field as `template` and read `template` / `template_names` from render responses while keeping legacy public `Prompt` property names.

## 0.2.0

Breaking vocabulary rename for the clean PromptOn API.

- Replaced the public call-site API with `PromptOn.prompt(key)` returning `Prompt`.
- Added `Prompt.messages(vars, template = ...)` for chat prompts and `Prompt.text(vars, template = ...)`
  for text prompts, with kind checks.
- Moved provider-call tracking to `Prompt.track(meta) { ... }` and `Prompt.trackBlocking(meta) { ... }`.
- Renamed monitoring record types to log vocabulary: `LogRecord`, `LogInput`, `LogOutput`,
  `LogError`, `LogStatus`, `TrackMeta`, `TrackCall` and `Result`.
- Added `Result.fromOpenAI(answer)` and `Result.fromAnthropic(answer)` helpers for common provider
  response shapes.
- Updated runtime endpoints to `GET /api/v1/prompts`, `POST /api/v1/prompts/{key}/render` and
  `POST /api/v1/logs`.
- Updated log batching to send `{"logs": [...]}`.
- Updated wire fields to `params`, `provider_options` and `source`.
- Updated prompt documents and conformance fixtures to schema version 4.
- Renamed the committed bundle convention to `prompts.<environment>.json`.
- Removed old public compatibility aliases.
