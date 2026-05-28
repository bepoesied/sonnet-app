# 03: Move Playback Timeline Math

## Goal

Extract pure audiobook progress calculations from Android playback control into shared code.

## Context

`PlaybackController` currently mixes Android Media3 work with platform-neutral timeline math. The math is valuable on iOS too because native playback still needs the same chapter seek, absolute-position, and progress mapping behavior.

Media3 and foreground playback remain Android-specific.

## Scope

- Move `seekTargetFor`, `positionFor`, `progressAt`, `chapterTitleAt`, `chapterOffsetAt`, `totalDurationMs`, and chapter projection logic to `shared/commonMain`.
- Introduce shared value types for seek targets and chapter progress.
- Keep `PlaybackController` in Android and make it call shared timeline functions.
- Add common unit tests covering multi-chapter seek behavior, boundary positions, unknown durations, and end-of-book behavior.

## Out Of Scope

- Moving `PlaybackController`.
- Moving `PlayerViewModel`.
- Moving `SonnetMediaSessionService`.
- Changing Media3 player setup.
- Changing progress sync behavior.

## Expected Result

- Media3 behavior remains Android-specific.
- Timeline rules are tested and shared.
- Android seeking and chapter display continue to behave the same.

## Verification

- Run `./gradlew :shared:allTests` or the available shared test task.
- Run `./gradlew :app:assembleDebug`.
- Manually smoke test opening and seeking a downloaded book on Android if feasible.