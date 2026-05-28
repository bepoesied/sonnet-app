# Sonnet Android Implementation Plan

This directory breaks the Android audiobook app into small work files we can execute one at a time.

Product decisions captured here:

- Android only for the first release.
- UI built entirely with Jetpack Compose and Material 3.
- One configured server and one signed-in account at a time.
- Main library shows both online and downloaded books, with downloaded state indicated inline.
- Tapping a book should open playback for that book.
- Only downloaded books are playable.
- Downloads are full-book only in v1.
- If a presigned download URL fails, surface an error for now.
- Cover images should be cached by stable identity where practical, not by full presigned URL.
- Progress should sync on pause, while background playback continues, and at playback intervals.
- If remote progress is newer, offer a resume-from-newer-position choice.
- Mark complete and mark incomplete are in scope.
- Sleep timer should match the Expo app behavior and advance by elapsed played time, not wall clock time.
- Android Auto / headset integrations are nice-to-have if they come easily after the core media session work.
- Logout should clear downloaded books but retain the chosen server URL.
- Debug builds may allow `http`; production should require `https`.
- No analytics or crash reporting in v1.

Suggested execution order:

1. `01-foundation.md`
2. `02-authentication.md`
3. `03-library-and-images.md`
4. `04-downloads-and-offline-storage.md`
5. `05-player-and-background-audio.md`
6. `06-progress-sync-and-reconciliation.md`
7. `07-completion-states-and-session-management.md`
8. `08-polish-and-hardening.md`

Cross-reference sources:

- Existing reference app: `~/repos/sonnet/sonnet-app`
- API contract: `~/repos/sonnet/MOBILE.md`

Notes about the current Android project:

- The app is currently a starter Compose project.
- `minSdk` is set to 36 in `app/build.gradle.kts`; that will need review very early because it is far above a normal production Android floor.
- No app architecture, persistence, networking, auth, or media stack has been added yet.