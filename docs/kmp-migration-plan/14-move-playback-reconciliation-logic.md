# 14: Move Playback Reconciliation Logic

## Goal

Share remote/local progress reconciliation beyond basic timeline math.

## Context

Android `PlaybackController` currently decides how local progress and remote progress should be reconciled. The same decisions should apply on iOS with a native player.

The shared layer should return platform-neutral decisions. Android then applies those decisions through Media3 seeks, local Room writes, prompts, or sync triggers.

## Scope

- Move remote/local progress decision logic from `PlaybackController` to shared.
- Return platform-neutral reconciliation decisions such as use local, use remote, ask user, or reset completed.
- Keep Media3 controller in Android.
- Keep media item construction in Android.
- Keep service connection and actual seeking in Android.

## Out Of Scope

- Moving Media3 playback.
- Moving sleep timer implementation unless pure pieces naturally separate.
- Changing prompt threshold unless tests reveal a bug.
- Changing progress sync scheduling.

## Expected Result

- Android playback control is thinner.
- iOS can later reuse the same resume/progress decisions with a native player.
- Resume prompts and reconciliation behave the same on Android.

## Verification

- Add common tests for newer remote progress, newer local progress, completed remote state, missing remote timestamps, prompt threshold, and pending local sync.
- Run shared tests.
- Run `./gradlew :app:assembleDebug`.
- Test resume prompt and progress reconciliation on Android.