# 05 Player And Background Audio

## Goal

Build the offline audiobook player with chapter playback, seeking, sleep timer, and background playback using Media3.

## Scope

- Play only downloaded audio files.
- Chapter-based playback.
- 15-second seek back/forward.
- Scrubbable timeline.
- Chapter picker.
- Sleep timer matching the Expo app behavior.
- Lock screen and notification controls.
- Background playback.

## Required V1 Controls

- play / pause
- seek backward 15s
- seek forward 15s
- chapter list and chapter jump
- sleep timer presets
- chapter-end sleep
- lock screen transport controls

## Sleep Timer Behavior

- Should behave like the Expo app.
- Timer should advance by elapsed played time, not wall clock time.
- If playback is paused, the timer should stop advancing.
- Seeking forward while playing should count as consumed listening time only if we intentionally preserve the Expo semantics; otherwise keep the implementation tied to actual playback progression and document any difference.

## Recommended Technical Shape

- Use ExoPlayer via Media3.
- Use a `MediaSessionService` for background playback and notification.
- Keep player state in a dedicated playback repository / controller and expose it to Compose with Flow.
- Keep chapter metadata and current book state separate from raw player state.

## Deliverables

- Player screen that opens directly for a selected book.
- Media session with notification controls.
- Resume from saved local position.
- Chapter switching.
- Sleep timer UI and behavior.

## Suggested Tasks

1. Add Media3 dependencies.
2. Build playback controller abstraction around ExoPlayer.
3. Implement player screen Compose UI.
4. Implement chapter queueing and seek controls.
5. Implement media session service and notification.
6. Wire headset and lock screen controls.
7. Implement sleep timer based on elapsed playback.
8. Verify background playback and app resume behavior.

## Nice To Have If Low Cost

- Bluetooth headset media button support.
- Wired headset media button support.
- Android Auto compatibility if the media session foundation makes it straightforward.

## Definition Of Done

- A downloaded book can be opened, played, controlled from the notification/lock screen, and resumed reliably after app backgrounding.