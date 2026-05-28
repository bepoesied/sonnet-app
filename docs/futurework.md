
## Targeted Library Progress Hydration

- Avoid hydrating `GET /api/books/:id/progress` for every book during library refresh; it blocks load and creates excessive requests/inserts.
- Prefer local `playback_progress` for library progress and completion when present, falling back to the book summary only when no local progress exists.
- If broader hydration is needed later, make it targeted: visible rows, downloaded books, stale rows only, or a throttled background pass with cancellation and rate limits.
# Future Work

## Progress Sync WorkManager Safety Net

- Add a constrained one-time WorkManager job when playback progress is saved with `pendingSync = true`.
- Use `NetworkType.CONNECTED` and exponential backoff so sync can complete after process death or offline playback without polling.
- Keep foreground playback and pause-triggered sync as the primary fast path; WorkManager should only provide eventual consistency.
- Use unique work with a non-spamming policy such as `KEEP` so repeated progress writes do not enqueue redundant jobs.