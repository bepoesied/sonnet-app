# 06 Progress Sync And Reconciliation

## Goal

Make local progress durable first, then sync to Sonnet in a way that follows `MOBILE.md` exactly.

## Contract Rules To Preserve

- Local playback must keep working offline.
- Keep newest local state per book while offline.
- On reconnect, sync using local `updated_at`.
- Newest timestamp wins per book.
- Strictly older updates may be ignored by the server and still return success.
- Progress updates mark the book incomplete unless a completion endpoint is used.

## Scope

- Persist progress locally during playback intervals.
- Sync to server during playback intervals when possible.
- Sync on pause.
- Continue syncing during background playback intervals.
- Fetch remote progress when opening a downloaded book.
- Offer a resume-from-newer-position choice if remote progress is newer.

## Deliverables

- Local progress persistence independent of network state.
- Progress sync worker/use case.
- Conflict handling when opening a downloaded book.
- Correct usage of:
  - `GET /api/books/:id/progress`
  - `PUT /api/books/:id/progress`

## Recommended Behavior

- Save locally on a short interval while actively playing.
- Sync to server on a slower interval while actively playing.
- Force a final progress sync attempt on pause if online and authenticated.
- When app is backgrounded but playback continues, keep interval-based sync running through the playback service layer.
- When opening a book:
  - if local progress is newer, use local and queue sync
  - if remote progress is newer, offer a resume prompt
  - if equal or effectively same, use local without prompting

## Suggested Tasks

1. Define local progress entity including `updated_at`.
2. Implement local progress writes from player events.
3. Implement periodic sync scheduling tied to playback state.
4. Implement pause-triggered sync.
5. Implement open-book reconciliation flow.
6. Add background-safe sync path while playback continues.
7. Handle expired access token by refreshing before sync when possible.

## UX Decision Captured

- If remote progress is newer, show a resume-from-newer-position option rather than silently overriding local state.

## Definition Of Done

- Progress is not lost offline.
- Progress reaches the server in normal online use.
- Newer-position resume behavior is understandable and follows the server contract.