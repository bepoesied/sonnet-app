# 10: Move Progress Sync Logic

## Goal

Share deterministic progress sync behavior while keeping scheduling platform-specific.

## Context

`ProgressSyncer` decides what pending progress should be synced and how successful syncs are marked. That behavior is reusable on iOS.

Scheduling is platform-specific and battery-sensitive. Android should continue deciding when sync runs through playback events, connectivity callbacks, and future WorkManager safety nets.

## Scope

- Move `ProgressSyncer` to shared.
- Make it depend on shared Room DAO/models.
- Make it depend on shared remote interfaces.
- Keep Android `SyncCoordinator` in `:app`.
- Keep network callback, playback interval triggers, and future WorkManager scheduling in `:app`.

## Out Of Scope

- Moving Android connectivity callbacks.
- Adding WorkManager in this slice.
- Changing sync cadence.
- Changing playback controller behavior beyond imports/dependencies.

## Expected Result

- The rules for syncing pending progress are shared.
- Android still decides when sync runs.
- Failed syncs still leave progress pending for later retry.

## Verification

- Add common tests for pending sync success, failure, completion, incompletion, and single-flight behavior where practical.
- Run `./gradlew :shared:allTests` or available shared tests.
- Run `./gradlew :app:assembleDebug`.
- Test playback progress sync from Android.