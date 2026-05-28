# 15: Add iOS Consumption Scaffolding

## Goal

Make shared behavior practically consumable from a future SwiftUI app.

## Context

By this point, shared should own persistence, settings, models, API behavior, sync rules, library use cases, and selected ViewModels. The iOS app still does not need to be built in this slice, but the generated framework should expose usable APIs.

SwiftUI does not automatically manage AndroidX ViewModel lifecycle or collect Kotlin `Flow`/`StateFlow`, so some interop choices are needed before iOS UI work starts.

## Scope

- Export needed dependencies from the iOS framework, especially ViewModel.
- Add `iosMain` ViewModel resolver helper if shared ViewModels will be constructed from Swift.
- Choose SKIE or KMP-NativeCoroutines for observing `Flow`/`StateFlow` from SwiftUI.
- Confirm iOS database and DataStore builders are available from shared code.
- Do not build iOS UI in this slice unless that is the next product goal.

## Out Of Scope

- Building SwiftUI screens.
- Implementing iOS audio playback.
- Implementing iOS Keychain session store unless needed for a smoke test app.
- Implementing iOS browser auth unless the iOS app is starting immediately.

## Expected Result

- Shared framework API is shaped for iOS use.
- Android behavior remains unchanged.
- Future iOS work can start from well-defined shared entry points.

## Verification

- Build the shared iOS framework targets.
- Confirm Swift-visible APIs have acceptable names and exported ViewModel types.
- Confirm there is a documented choice for SwiftUI Flow observation.