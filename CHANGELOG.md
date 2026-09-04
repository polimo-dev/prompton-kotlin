# Changelog

All notable changes to the PromptOn Kotlin SDK.

## 0.1.0

Initial release.

- `PromptOn.resolve` reads the cached snapshot: use case (plus prompt name) to model, params,
  provider options and the pinned prompt version, with the merge order the contract defines.
- Snapshot store with three tiers — memory, an atomically written disk cache and an optional
  committed bundle — a 10-second cache, `If-None-Match` revalidation, `Retry-After` and exponential
  backoff, and an environment and project guard. A refresh never blocks or fails a generation.
- Liquid-subset template renderer (`for`, `if`/`elsif`/`else`, `unless`, `assign`, `break`,
  `continue`, `forloop.*`, the `size`, `join` and `default` filters), plus lint and detected
  variables.
- Monitoring logs: `log`, `flush` and a `generate` wrapper, UUIDv7 ids, the payload policy
  (sampling, truncation, hashing, redaction), batching with retries, `413` splitting and a bounded
  queue.
- `POST /resolve` client, both as the simple cached path and as an uncached smoke test.
- Test mode (no HTTP, records captured) and offline mode (disk and bundle only).
- The cross-language conformance suite runs as part of the test suite.
