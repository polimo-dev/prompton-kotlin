# Changelog

All notable changes to the PromptOn Kotlin SDK.

## 0.2.0

Breaking vocabulary rename for the clean PromptOn runtime API.

- Replaced the public call-site API with `PromptOn.useCase(key)` returning `UseCase`.
- Added `UseCase.messages(vars, prompt = ...)` for chat use cases and `UseCase.text(vars, prompt = ...)`
  for text use cases, with kind checks.
- Moved provider-call tracking to `UseCase.track(meta) { ... }` and `UseCase.trackBlocking(meta) { ... }`.
- Renamed monitoring record types to log vocabulary: `LogRecord`, `LogInput`, `LogOutput`,
  `LogError`, `LogStatus`, `TrackMeta`, `TrackCall` and `Result`.
- Added `Result.fromOpenAI(answer)` and `Result.fromAnthropic(answer)` helpers for common provider
  response shapes.
- Updated runtime endpoints to `GET /api/v1/use-cases`, `POST /api/v1/use-cases/{key}/prompt` and
  `POST /api/v1/logs`.
- Updated log batching to send `{"logs": [...]}`.
- Updated wire fields to `params`, `provider_options` and `source`.
- Updated use case documents and conformance fixtures to schema version 4.
- Renamed the committed bundle convention to `use-cases.<environment>.json`.
- Removed old public compatibility aliases.
