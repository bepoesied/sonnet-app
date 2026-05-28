# 08 Polish And Hardening

## Goal

Stabilize the app after the core feature set is working.

## Scope

- Better error handling and messaging.
- Retry affordances where appropriate.
- Performance and battery review.
- Test coverage for core flows.
- Manual validation across network and lifecycle edge cases.

## Deliverables

- Cleaner user-visible errors for auth, downloads, and sync.
- Verified background playback behavior.
- Verified logout cleanup behavior.
- A manual QA checklist for release readiness.

## High Value Test Areas

- Login with valid server and OIDC config.
- Login failure paths.
- Token refresh after app restart.
- Full-book download success.
- Full-book download failure and retry.
- Offline playback with no network.
- Progress saving during playback.
- Pause-triggered progress sync.
- Background playback interval sync.
- Resume prompt when remote progress is newer.
- Complete/incomplete toggles.
- Logout clears downloads and session while retaining server URL.
- Debug `http` vs release `https` enforcement.

## Manual QA Matrix

- online from fresh install
- offline after successful download
- network drop during playback
- network drop during download
- app background / foreground
- process death and cold restart
- expired or revoked refresh token

## Definition Of Done

- The app is stable enough that remaining work is mostly UX refinement rather than architecture repair.