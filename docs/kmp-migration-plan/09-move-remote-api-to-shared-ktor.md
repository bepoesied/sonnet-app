# 09: Move Remote API To Shared Ktor

## Goal

Share API contract, DTO parsing, and authenticated request behavior across Android and iOS.

## Context

Current API clients use OkHttp and `org.json`, which are not the desired common API layer. Ktor plus kotlinx.serialization should own endpoint paths, DTOs, parsing, error modeling, and timestamp conversion in shared code.

Platform code should only provide the Ktor engine.

## Scope

- Add Ktor and kotlinx.serialization dependencies to `:shared`.
- Replace shared API DTOs with `@Serializable` types where useful.
- Implement shared auth remote data source with Ktor.
- Implement shared books remote data source with Ktor.
- Keep platform Ktor engines platform-specific.
- Android can use the OkHttp engine or another supported Android engine.
- Remove Android-only `org.json` parsing from the moved API layer.

## Out Of Scope

- Changing API endpoints.
- Changing auth token lifecycle behavior.
- Changing server contract documented in `MOBILE.md`.
- Moving browser OIDC handoff.

## Expected Result

- API behavior is shared across Android and iOS.
- Android observes the same network behavior as before.
- Future iOS code can use the same API clients with an iOS Ktor engine.

## Verification

- Run shared API unit tests with a Ktor mock engine if added.
- Run `./gradlew :app:assembleDebug`.
- Test login bootstrap, library refresh, completion update, progress update, and logout against a real or test server.