# 07 Completion States And Session Management

## Goal

Implement explicit complete/incomplete state handling and finish the account/session lifecycle details around downloads and offline use.

## Scope

- Mark book complete.
- Mark book incomplete.
- Reflect completion state in library and local metadata.
- Keep downloaded books playable offline even if tokens expire.
- Block new sync/download work when auth is expired and refresh cannot recover.

## Deliverables

- UI action to mark complete / incomplete.
- Correct use of:
  - `PUT /api/books/:id/complete`
  - `PUT /api/books/:id/incomplete`
- Local completion state persistence.
- Session-expiry handling that preserves offline playback.

## Required Behavior

- Completion endpoints should be used for explicit complete/incomplete toggles.
- Ordinary progress writes should set incomplete semantics.
- If the user is offline and tokens expire, downloaded books must remain playable.
- If refresh token is expired, require login again before new downloads or server sync can continue.

## Suggested Tasks

1. Add complete/incomplete UI affordance in library and/or player.
2. Update local metadata when completion changes.
3. Sync completion state to server with optimistic but recoverable UI.
4. Gate download and sync work on valid auth state.
5. Preserve offline playback with stale auth.

## Definition Of Done

- Completion state behaves consistently across local metadata, library UI, and server state.
- Expired auth blocks network operations without breaking offline listening.