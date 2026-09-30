# Changelog

All notable changes to the PromptOn Kotlin SDK.


## 0.5.0

Demand-driven config fetch release.

- Removed startup and idle config polling. `useCase(key)` now fetches only that prompt key when its cached value is stale or missing.
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

Patch release correcting the SDK wire contract to the current PromptOn runtime API.

- Fetch prompt documents from `GET /api/v1/prompts` and render through `POST /api/v1/prompts/{key}/render`.
- Decode canonical prompt documents with `prompts` and `template_pins`, and exercise `conformance/prompt.json` in the resolver tests.
- Send the render request field as `template` and read `template` / `template_names` from render responses while keeping legacy public `UseCase` property names.

## 0.2.0

Breaking vocabulary rename for the clean PromptOn runtime API.

- Replaced the public call-site API with `PromptOn.useCase(key)` returning `UseCase`.
- Added `UseCase.messages(vars, prompt = ...)` for chat prompts and `UseCase.text(vars, prompt = ...)`
  for text prompts, with kind checks.
- Moved provider-call tracking to `UseCase.track(meta) { ... }` and `UseCase.trackBlocking(meta) { ... }`.
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
