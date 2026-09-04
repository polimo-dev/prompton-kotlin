# Changelog

All notable changes to the PromptOn Kotlin SDK.

## 0.1.0

Initial release.

- `PromptOn.resolve` reads the cached snapshot: use case (plus prompt name) to model, params,
  provider options and the pinned prompt version, with the merge order the contract defines.
- Snapshot store with three tiers — memory, an atomically written disk cache and an optional
  committed bundle — a 10-second cache, `If-None-Match` revalidation, `Retry-After` and exponential
  backoff, and an environment and project guard. A refresh never blocks or fails a generation.
  Every path obeys the rate-limit window, `refreshBlocking()` included; `refreshBlocking(force = true)`
  is the explicit way past it, and the call reports whether a document is in memory afterwards.
- Liquid-subset template renderer (`for`, `if`/`elsif`/`else`, `unless`, `assign`, `break`,
  `continue`, `forloop.*`, the `size`, `join` and `default` filters), plus lint and detected
  variables.
- Monitoring logs: `log`, `flush` and a `generate` wrapper, UUIDv7 ids, the payload policy
  (sampling, truncation, hashing, redaction), batching with retries, `413` splitting and a bounded
  queue.
- `POST /resolve` client, both as the simple cached path and as an uncached smoke test. While the
  server answers `429` or `5xx`, or cannot be reached, the cached answer keeps serving and the SDK
  waits out `Retry-After` (else a doubling backoff) instead of calling again on every invocation;
  with nothing cached the call fails and the window still holds. A `4xx` is never held back.
- Test mode (no HTTP, records captured) and offline mode (disk and bundle only).
- `close()` makes one last best-effort send even inside a rate-limit pause and counts whatever it
  cannot deliver in `logStats().dropped`; a flush inside the pause keeps the records queued and
  reports them as `remaining`.
- One process-wide JVM shutdown hook instead of one per instance, and a cleaner that releases the
  threads of an instance the app dropped without closing it.
- The cross-language conformance suite runs as part of the test suite.
