# AGENTS.md

## Project overview

This repository contains an Android/Kotlin application for local pose validation. The app uses the device camera, MediaPipe Tasks Vision, body-landmark tracking, optional face-direction checks, gyroscope-based stabilization, penalties, audio cues, optional Intiface Central device feedback, and a timelapse recorder to verify that the user keeps a selected pose for a configured duration.

The core pose-validation flow is intentionally local/on-device. Do not introduce cloud inference, analytics, account systems, remote validation, or network-dependent validation unless the task explicitly asks for an online-only feature.

The project has a strict connectivity flavor split:

- `offline` must remain local/offline and must not depend on network-only libraries.
- `online` may contain integrations that require network/runtime connectivity, such as Intiface Central / Buttplug WebSocket support.
- Shared `main` code may define interfaces, state models, settings, and UI that can render unsupported/offline states, but it must not import online-only SDKs or require online-only runtime behavior.

## Repository shape

The project is a single Android application module:

- `settings.gradle.kts` includes only `:app`.
- `app/build.gradle.kts` defines the Android app module, build types, product flavors, dependencies, and packaging rules.
- Main package: `com.incident201.poseguard`.
- Main activity: `app/src/main/java/com/incident201/poseguard/MainActivity.kt`.
- Main UI: `app/src/main/java/com/incident201/poseguard/ui/CameraScreen.kt`.
- Settings UI: `app/src/main/java/com/incident201/poseguard/ui/SettingsScreen.kt`.
- Main state coordinator: `app/src/main/java/com/incident201/poseguard/viewmodel/GameViewModel.kt`.
- Pose, face, smoothing, identity, crop, and movement logic: `app/src/main/java/com/incident201/poseguard/tracker/`.
- Audio cue logic: `app/src/main/java/com/incident201/poseguard/audio/`.
- Intiface shared contracts and state: `app/src/main/java/com/incident201/poseguard/intiface/`.
- Intiface online implementation: `app/src/online/java/com/incident201/poseguard/intiface/`.
- Intiface offline stub/factory: `app/src/offline/java/com/incident201/poseguard/intiface/`.
- Timelapse recording: `app/src/main/java/com/incident201/poseguard/video/`.
- Shared formatting helpers: `app/src/main/java/com/incident201/poseguard/util/`.
- App resources and string resources: `app/src/main/res/`.
- MediaPipe model assets: `app/src/main/assets/`.
- Dependency versions: `gradle/libs.versions.toml`.
- Release automation: `.github/workflows/release.yml`.

The app uses Jetpack Compose, CameraX, MediaPipe Tasks Vision, Kotlin coroutines/flows, Android sensors, SharedPreferences, Android media encoding APIs, and flavor-scoped optional online dependencies.

## Connectivity flavors

The Android module defines a `connectivity` flavor dimension with two product flavors:

- `offline`
- `online`

Keep this split intact.

### Offline flavor rules

The `offline` flavor must remain free of network-only dependencies and runtime network integrations.

For `offline`:

- Do not add WebSocket, HTTP, cloud, analytics, account, remote inference, or device-network SDK dependencies to `implementation`.
- Do not add online-only dependencies to `offlineImplementation`.
- Do not place imports for online-only SDKs in `app/src/main`.
- Do not place imports for online-only SDKs in `app/src/offline`.
- Do not make offline code instantiate online clients, sockets, or network services.
- Do not make offline code require Intiface Central or any external server.
- Use stubs that return an unsupported state or a no-op behavior for online-only features.
- Keep offline behavior safe, deterministic, and local.

The offline flavor may compile shared UI that mentions an online-only feature only when that UI reads shared state and can render an unavailable/unsupported state without referencing online-only classes.

### Online flavor rules

The `online` flavor may include optional network-dependent integrations.

For `online`:

- Put concrete online-only implementations under `app/src/online/java/...`.
- Add online-only dependencies with `onlineImplementation(...)`.
- Keep WebSocket/client SDK imports inside `app/src/online`.
- Preserve shared interfaces in `app/src/main` so the rest of the app can work through common contracts.
- Keep error handling and lifecycle cleanup robust, especially for long-lived clients, sockets, callbacks, and coroutine jobs.

Current online-only integration:

- Intiface Central / Buttplug WebSocket support.
- The Buttplug dependency belongs to `onlineImplementation`.
- The concrete controller is `OnlineIntifaceController`.
- The offline flavor provides an `IntifaceController` stub that reports the feature as unsupported.

### Adding a new online-only feature

When adding another feature that requires network access, a server, an external device service, or a network SDK, follow this pattern:

1. Put shared data models, UI state, and interfaces in `app/src/main`.
2. Put the real implementation in `app/src/online`.
3. Put a compile-safe unsupported/no-op stub in `app/src/offline`.
4. Add the dependency only with `onlineImplementation`.
5. Keep common UI dependent only on shared interfaces/state.
6. Make offline UI render a clear unavailable/unsupported state.
7. Do not introduce online-only imports into `main` or `offline`.
8. Do not make offline flavor behavior depend on network availability.

If a feature cannot be represented safely in shared UI without online-only dependencies, keep the feature-specific UI behind shared state/contracts or provide separate flavor-specific entry points.

## Agent workflow rules

Make focused changes that match the requested behavior. Prefer editing the existing component that already owns the behavior instead of adding parallel logic elsewhere.

Do not add instructions asking the user or another agent to run local Gradle builds, local app launches, emulator checks, or unit tests. Build and test verification is handled by GitHub Actions. When finishing a task, summarize the files changed and the expected behavior change.

Do not commit real secrets, keystores, `.env` files, signing passwords, generated APKs, or recorded videos.

Do not rename the app package, Gradle module, MediaPipe model asset names, SharedPreferences keys, or flavor names unless the task explicitly requires a migration.

Do not move code across `main`, `online`, and `offline` source sets casually. Source-set placement is part of the architecture.

## High-level runtime pipeline

The main processing flow is coordinated by `GameViewModel`.

Camera frames are registered by timestamp through `registerCameraFrame()`. The ViewModel keeps a bounded cache of recent bitmaps, removes stale frames, and recycles dropped bitmaps. MediaPipe pose results later arrive with the same timestamp and are matched back to the original bitmap.

After a MediaPipe pose result arrives, the ViewModel processes it on `mediaPipeResultExecutor`. The effective pose pipeline is:

1. Reject or temporarily hold missing-pose frames.
2. Stabilize identity with `PoseIdentityStabilizer`.
3. Smooth landmark coordinates with `PoseSmoother`.
4. During the start countdown, collect occlusion calibration frames with `PoseOcclusionGuard`.
5. Build overlay state, including body crop and optional face detection crop.
6. When the session is in `HoldingPose`, apply occlusion guard projection for tracking.
7. Run `MovementTracker` against the reference pose.
8. Apply the face-direction rule if enabled.
9. Update gauge state, violation counters, penalties, session result, audio events, and optional Intiface feedback.

Keep this order in mind. Many bugs in this project come from changing a later step while forgetting that earlier steps may already have transformed, held, smoothed, or rejected the pose.

## Main UI and state files

### `MainActivity.kt`

`MainActivity` is only the app shell. It enables edge-to-edge mode, applies `MyApplicationTheme`, creates `GameViewModel`, and renders `CameraScreen`.

Do not put feature logic here unless the task is specifically about Activity setup.

### `ui/CameraScreen.kt`

`CameraScreen` is the main Compose screen. It owns UI composition around camera preview, permission handling, camera switching, pose overlay drawing, onboarding display, settings entry, audio announcements, debug UI, session controls, and timelapse UI.

This file bridges Android camera/bitmap work and Compose state. Keep heavy frame processing out of Composables and in the existing ViewModel/service pipeline.

Important details:

- It observes ViewModel state through `collectAsState()`.
- It displays onboarding before the main camera UI when onboarding is incomplete.
- It uses CameraX types such as `ProcessCameraProvider`, `Preview`, `ImageAnalysis`, `PreviewView`, and `CameraSelector`.
- It uses `PoseLandmarkerService` for pose inference.
- It passes frames and timestamps into `GameViewModel` so the ViewModel can match camera bitmaps with MediaPipe results.
- It uses `TimelapseRecorder` during sessions when recording is enabled.
- It draws pose skeleton connections using hardcoded MediaPipe landmark index pairs.
- It passes Intiface state and callbacks to settings UI through shared ViewModel state.

When changing UI text, use string resources and the existing localization approach.

### `ui/SettingsScreen.kt`

`SettingsScreen` is the Compose settings UI for validation behavior, tuning, audio cues, timelapse options, debug controls, and optional Intiface Central controls.

Most settings are persisted and applied through `GameViewModel`, not stored directly in the Composable.

When adding a new setting:

- Add it to `GameSettings` if it affects validation, persisted configuration, or UI state.
- Load and save it in `GameViewModel`.
- Apply it to the relevant engine in `applySettingsToEngines()` or an existing update method.
- Add localized labels/descriptions in string resources.
- Keep online-only settings behind shared state so offline flavor can render unavailable/unsupported behavior without importing online-only classes.

### `ui/IntifaceUiText.kt`

`IntifaceUiText.kt` maps shared `IntifaceUiMessage` values to localized user-facing text.

Keep Intiface message localization here rather than hardcoding visible status/error strings in the controller or settings UI.

### `ui/OnboardingScreen.kt`

First-run onboarding and language setup. It is shown by `CameraScreen` based on `onboardingCompleted`.

## `GameViewModel.kt`

`GameViewModel` is the central state machine and processing coordinator. It owns:

- `GameState`: `Idle`, `WaitingForStabilization`, `StartingDelay`, `HoldingPose`, `Success`, `Failed`.
- Timer state, selected duration, start countdown, and final session summary.
- Settings persistence through SharedPreferences.
- Onboarding completion.
- Gyroscope-based device stabilization before starting the pose countdown.
- Pose frame cache and timestamp matching.
- MediaPipe result processing executor.
- Pose identity stabilization.
- Pose smoothing.
- Pose occlusion calibration and projection.
- Movement validation.
- Face visibility rule validation.
- Violation counters, penalties, defeat/success transitions.
- Audio event emission.
- Optional Intiface state coordination through the shared `IntifaceController` interface.
- Cleanup on stop, defeat, final-screen dismissal, and `onCleared()`.

Important internal rules:

- `processingGeneration` invalidates stale frames/results after resets, stops, defeats, and new sessions.
- `frameLock` protects cached bitmaps and latest analyzed frame.
- `processingLock` protects processing state and tracker engines.
- Missing raw pose frames can be held briefly using the last usable smoothed pose.
- Identity and smoothing are reset when pose continuity is broken.
- Occlusion calibration happens during the final seconds of `StartingDelay`.
- The reference pose for `MovementTracker` is built after occlusion calibration.
- Movement and face violations are handled only while `GameState.HoldingPose`.
- Intiface settings are persisted in the same settings store, but the concrete Intiface implementation is flavor-specific.

Be careful when changing this file. Prefer small changes that preserve the existing state transitions and lock boundaries.

## Intiface Central integration

Intiface support is a flavor-scoped optional feature.

Shared files:

- `app/src/main/java/com/incident201/poseguard/intiface/IntifaceController.kt`
- `app/src/main/java/com/incident201/poseguard/ui/IntifaceUiText.kt`
- Intiface-related state and settings in `GameViewModel.kt`
- Intiface settings UI in `SettingsScreen.kt`

Online-only files:

- `app/src/online/java/com/incident201/poseguard/intiface/OnlineIntifaceController.kt`
- `app/src/online/java/com/incident201/poseguard/intiface/IntifaceControllerFactory.kt`

Offline-only files:

- `app/src/offline/java/com/incident201/poseguard/intiface/IntifaceControllerFactory.kt`

Architecture rules:

- `IntifaceController` is the shared interface used by `GameViewModel`.
- `OnlineIntifaceController` is the only place that should import Buttplug/Intiface WebSocket client classes.
- The offline factory must return a compile-safe unsupported controller.
- Shared UI must rely on `IntifaceUiState` and `IntifaceUiMessage`, not online SDK classes.
- The Buttplug dependency must remain `onlineImplementation`.
- Do not add Buttplug, WebSocket, or Intiface SDK dependencies to `implementation` or `offlineImplementation`.

Current behavior expectations:

- Default Intiface WebSocket URL is `ws://127.0.0.1:12345`.
- The legacy default `ws://10.0.2.2:12345/buttplug` may be migrated by ViewModel preference loading logic.
- User-provided URLs such as `ws://127.0.0.1:12345/buttplug` should remain allowed.
- Manual device search should create a fresh connection and should not reuse a stale client after an error.
- Server-error cleanup must invalidate stale callbacks and avoid racing a new connection against old-client disconnect.
- Offline flavor must show the feature as unavailable/online-only, not attempt to connect.

When editing this integration, preserve the controller lifecycle, generation checks, callback invalidation, and mutex boundaries.

## Audio package

Audio cue logic lives under `app/src/main/java/com/incident201/poseguard/audio/`.

Important files include:

- `AudioCue.kt`
- `AudioCuePlayer.kt`
- `PcmSignalPlayer.kt`

Audio cues are local app behavior and are separate from Intiface feedback. Keep audio cue settings, phrase templates, PCM patterns, and playback lifecycle coordinated through `GameViewModel`.

Do not mix Android audio playback logic with Intiface device feedback. They are separate output channels.

## Tracker package map

### `MovementTracker.kt`

This file contains the core rule that decides whether the user drifted too far, moved too much, or disappeared.

It defines the pose data model:

- `PosePoint` / `Point3D`
- `PoseLandmarks`
- `MovementMetrics`
- `TrackingResult`
- `MovementTracker.Violation`

`PoseLandmarks.fromAllLandmarks()` maps MediaPipe indices into the named body points used by the tracker:

- shoulders: 11, 12
- elbows: 13, 14
- hips: 23, 24
- knees: 25, 26

The tracker uses `TRAINING_POSE_LANDMARK_INDICES` for validation:

- shoulders 11, 12
- elbows 13, 14
- wrists 15, 16
- hips 23, 24
- knees 25, 26
- ankles 27, 28

`startTracking()` stores the reference pose, computes a 2D body center, computes a fixed reference body scale, normalizes reference landmarks, and initializes previous-frame state.

`trackFrame()` compares the current pose to the reference pose and previous pose. It computes:

- pose drift: robust normalized shape distance from the reference pose
- global drift: body center displacement relative to reference scale
- pose motion: robust normalized landmark movement since previous frame
- global motion: body center movement since previous frame

Drift and motion use deadbands. Drift must stay above the threshold long enough to pass a grace window before it becomes a violation. Motion can trigger either by sustained excess motion or by an immediate spike.

`wristDriftWeight` downweights wrist drift compared with the rest of the body. This matters because wrists are noisy and often less relevant than the torso/limbs for pose stability.

Change this file when the requested behavior is about movement sensitivity, drift/motion scoring, tracked landmark weights, disappearance logic, or violation classification.

### `PoseLandmarkerService.kt`

This is the MediaPipe Pose Landmarker wrapper.

It creates a `PoseLandmarker` with:

- model asset: `pose_landmarker_heavy.task`
- running mode: `LIVE_STREAM`
- one pose: `setNumPoses(1)`

`detectLiveStreamFrame()` converts a `Bitmap` to a MediaPipe image and calls `detectAsync()` with the timestamp.

The result listener calls `processResult()`, which:

- returns an empty `PoseLandmarks` when no pose is detected
- returns an empty `PoseLandmarks` when fewer than 33 landmarks are present
- maps all landmarks into `Point3D`
- preserves `x`, `y`, `z`, `visibility`, and `presence`
- fills named shoulder/elbow/hip/knee fields
- delivers the result with image size and timestamp

The class has explicit lifecycle locking and `close()` handling. Preserve this when changing MediaPipe setup or error handling.

Change this file when the requested behavior is about MediaPipe pose model options, confidence thresholds, landmark extraction, or pose inference lifecycle.

### `FaceDetectorService.kt`

This is the MediaPipe Face Detector wrapper used on a cropped face candidate bitmap, not on the full camera frame.

The detector is recreated when confidence changes.

`detectOnCrop()`:

- ensures the input bitmap is `ARGB_8888`
- runs MediaPipe face detection
- returns `FaceNotVisible` when there are no detections
- selects the detection with the highest score
- returns `FaceVisible` with bounding box, keypoints, and score
- returns `Error` with a compact error message if detection fails
- recycles only the temporary copy it created

Change this file when the task concerns face detector model setup, face confidence, detection result mapping, or face detection error behavior.

### `PoseFrameCropper.kt`

This object calculates a body crop rectangle from the full set of pose landmarks.

`calculateCropRect()`:

- ignores non-finite landmark coordinates
- clamps normalized landmark positions to `0..1`
- converts normalized landmarks to bitmap pixels
- requires enough valid points
- computes the min/max landmark bounding box
- rejects degenerate boxes
- adds horizontal and vertical padding
- enforces a minimum padding in pixels
- clamps the final rectangle to bitmap bounds
- returns `null` when no safe crop can be produced

`cropAroundPose()` creates a bitmap crop when possible and returns the original bitmap when no crop rectangle can be calculated.

Change this file when body crop geometry, padding, fallback behavior, or overlay crop bounds need to change.

### `FaceCandidateCropper.kt`

This object estimates the square crop that should be sent to `FaceDetectorService`.

It uses face landmarks when they are available and falls back to shoulders when face landmarks are not usable.

After choosing a candidate square, it fits the square first into the body crop bounds and then into the full bitmap bounds. It rejects crops that are too small.

Change this file when face detection misses because the detector input crop is wrong, too small, too high/low, or clipped incorrectly.

### `PoseSmoother.kt`

This class smooths landmark coordinates using a One Euro Filter per landmark and per axis.

It tracks filters by landmark index. Each landmark has separate filters for `x`, `y`, and `z`. `visibility` and `presence` are passed through unchanged.

Behavior:

- empty landmark lists reset the smoother
- the first pose initializes filters
- long gaps reset the smoother
- delta time is clamped
- changing config resets all filters

Change this file when the task concerns landmark jitter, smoothing aggressiveness, lag, or filter reset behavior.

### `PoseOcclusionGuard.kt`

This class freezes unreliable limb landmarks during calibration and projects stable replacement positions during tracking.

It guards these indices:

- elbows: 13, 14
- wrists: 15, 16
- knees: 25, 26
- ankles: 27, 28

Calibration collects frames during the end of `StartingDelay`, computes local coordinates relative to body center and body scale, and identifies landmarks that should be frozen because they are effectively invisible or unstable.

Frozen landmarks are stored in local body coordinates, not raw screen coordinates. During tracking, the guard projects each frozen landmark back into the current body coordinate system using current body center and scale.

Change this file when the task concerns occluded elbows/wrists/knees/ankles, false movement caused by invisible limbs, frozen overlay points, reacquisition, or handoff behavior.

### `PoseIdentityStabilizer.kt`

This class protects the pipeline from left/right swaps, sudden identity flips, and implausible pose jumps.

It requires 33 landmarks for a normal pose. If the pose is missing or too short, it resets and returns a rejected result.

For each valid frame it compares:

- the raw pose as-is (`Direct`)
- a version with left/right MediaPipe landmark pairs swapped (`Swapped`)

It chooses `Direct` or `Swapped` only when one score wins by the configured margin. If the scores are too close, it keeps the previous transform and marks the result as ambiguous.

Short outlier bursts are frozen by returning the previous stable pose. After too many consecutive outliers, the stabilizer accepts the candidate to recover from real large movement or viewpoint changes.

Change this file when the task concerns left/right limb swapping, sudden person identity jumps, implausible skeleton scale changes, or debug text like `identity: Direct`, `Swapped`, `ambiguous`, `outlier`, or `rejected`.

## Video package

### `TimelapseRecorder.kt`

This class records a timelapse MP4 during a session.

It uses:

- a single-thread frame executor
- `MediaCodec` AVC encoder
- input `Surface`
- `MediaMuxer`
- temporary output file in cache
- frame copy/recycle discipline
- a generation counter to ignore stale frames after start/stop/discard/release

Each encoded frame is drawn into the video size and gets overlay badges such as elapsed timer text and violation count text.

The recorder has explicit `start()`, `startTimer()`, `offerFrame()`, `stop()`, `discard()`, and `release()` paths. Preserve generation checks and cleanup behavior when editing this file.

Change this file when the task concerns timelapse output, overlay text/badges, frame sampling, encoder settings, temporary file handling, or recording lifecycle.

## Assets and model names

The code expects these assets by exact filename:

- `pose_landmarker_heavy.task`
- `blaze_face_short_range.tflite`

The Android build config keeps `.tflite` and `.task` resources uncompressed. Do not rename or relocate these files without updating the service code and packaging assumptions.

## Settings and persistence

Runtime settings are represented by `GameSettings` in `GameViewModel.kt` and persisted through SharedPreferences named `game_settings`.

Important setting groups:

- language
- face check mode
- movement sensitivity
- penalty behavior
- timelapse recording
- occlusion guard thresholds
- wrist drift weight
- pose smoother config
- debug mode
- audio cue settings
- Intiface connection/settings
- onboarding completion

When adding settings, keep default values, load logic, update methods, persistence keys, and engine application in sync.

Be careful with old preference keys. Some values already have migration/normalization logic. If changing a default that may already be persisted, add an explicit migration instead of silently overwriting user choices.

## Localization

The app supports Russian and English via `AppLanguage`.

`GameViewModel.tr()` selects resources using the current language. Compose UI also uses helper localization functions.

When adding user-facing text:

- add string resources
- keep Russian and English behavior aligned
- avoid hardcoded visible UI strings
- keep hardcoded strings only for debug-only messages when appropriate

For Intiface messages, use `IntifaceUiMessage` and `localizedIntifaceMessage()` instead of building visible text in controller code.

## State machine notes

Session start flow:

1. User starts a session from idle/success/failed state.
2. ViewModel resets trackers and counters.
3. Device must remain still according to gyroscope magnitude.
4. After stabilization, the app enters `StartingDelay`.
5. During countdown, pose frames continue to be processed and occlusion calibration frames are collected near the end.
6. At countdown finish, ViewModel gets a fresh analyzed frame.
7. If no fresh pose/body is available, the session fails.
8. Occlusion calibration is finalized.
9. Reference pose is created and passed to `MovementTracker`.
10. State becomes `HoldingPose`.
11. Timer loop starts.
12. Movement and face rules can now create penalties or defeat.
13. Optional audio and Intiface feedback may be emitted according to settings.
14. Timer reaching zero creates success.

Defeat, stop, final-screen dismissal, and ViewModel clearing all reset or invalidate processing state. Preserve this behavior when editing session logic.

## Face rule notes

Face checking is optional and controlled by `FaceCheckMode`:

- `Disabled`
- `FaceToCamera`
- `FaceAwayFromCamera`

Face failures are counted across consecutive frames. A rule violation is triggered only after the threshold is reached. `FaceDetectionStatus.NotProcessed` and `Error` do not directly count as face rule failures.

Face detection depends on body crop and face candidate crop. If face behavior is wrong, inspect `PoseFrameCropper`, `FaceCandidateCropper`, `FaceDetectorService`, and `GameViewModel.buildOverlayState()` together.

## Movement violation notes

Movement validation can produce:

- no violation
- drift limit exceeded
- motion limit exceeded
- person disappeared

`GameViewModel` maps those to rule violations and applies penalties or defeat based on current settings.

Penalty behavior is rate-limited by `minimumPenaltyIntervalSeconds`. A violation can be ignored for penalty purposes if it happens too soon after the previous penalty.

The person-disappeared rule does not increment the public drift/motion/face counters, but it can still defeat the session through the general violation flow.

## Concurrency and lifecycle rules

This project processes camera frames, MediaPipe callbacks, UI state, audio playback, optional Intiface device callbacks, and video encoding concurrently. Preserve existing concurrency boundaries.

Important patterns:

- `GameViewModel` uses `frameLock` for cached bitmaps and latest analyzed frame.
- `GameViewModel` uses `processingLock` for tracker state and processing generation.
- `processingGeneration` invalidates stale frame results.
- `mediaPipeResultExecutor` serializes MediaPipe result handling.
- `PoseLandmarkerService` uses lifecycle locking.
- `FaceDetectorService` synchronizes detector recreation and detection.
- `TimelapseRecorder` uses a single-thread executor, a lock, a stop mutex, and recording generations.
- `OnlineIntifaceController` uses mutexes/generation checks to avoid stale client callbacks and reconnect races.

Avoid introducing shared mutable state that is not protected by existing locks, mutexes, generations, or confined executors.

For Intiface/client integrations, do not reuse a client after server errors unless the current controller logic explicitly proves it is still valid. Prefer generation checks and fresh reconnect for manual device search.

## Bitmap ownership rules

Several paths create temporary bitmaps. Be careful with ownership.

- `GameViewModel` recycles cached camera bitmaps after matching or dropping.
- `FaceDetectorService` recycles only the ARGB copy it creates internally.
- `GameViewModel.buildOverlayState()` recycles the face crop bitmap after detection.
- `TimelapseRecorder` copies offered frames, recycles owned copies, and recycles overlay bitmaps.
- `PoseFrameCropper.cropAroundPose()` can return the original bitmap if no crop was possible.

Before recycling a bitmap, confirm that the current code owns that instance.

## Dependency rules

General dependency rules:

- Keep shared local dependencies in `implementation`.
- Keep online-only dependencies in `onlineImplementation`.
- Do not add network-only dependencies to `implementation`.
- Do not add network-only dependencies to `offlineImplementation`.
- Update dependency versions in `gradle/libs.versions.toml`.
- Keep optional future dependencies commented only when there is a clear project reason.

For Intiface/Buttplug:

- The Buttplug WebSocket client dependency belongs only in `onlineImplementation`.
- The shared `main` source set must not import Buttplug classes.
- The offline source set must not import Buttplug classes.

## Where to make common changes

Use this map before editing:

- Main UI layout, overlays, buttons, camera-facing UI: `ui/CameraScreen.kt`
- Settings UI: `ui/SettingsScreen.kt`
- First-run flow: `ui/OnboardingScreen.kt`
- Intiface localized UI text: `ui/IntifaceUiText.kt`
- App state, timers, penalties, preferences, gyroscope stabilization, processing pipeline: `viewmodel/GameViewModel.kt`
- Shared Intiface contracts/state: `intiface/IntifaceController.kt`
- Online Intiface implementation: `app/src/online/java/com/incident201/poseguard/intiface/OnlineIntifaceController.kt`
- Offline Intiface stub/factory: `app/src/offline/java/com/incident201/poseguard/intiface/IntifaceControllerFactory.kt`
- Drift/motion scoring and movement violations: `tracker/MovementTracker.kt`
- Pose inference setup and landmark extraction: `tracker/PoseLandmarkerService.kt`
- Face detector setup and result mapping: `tracker/FaceDetectorService.kt`
- Body crop rectangle: `tracker/PoseFrameCropper.kt`
- Face detector input crop: `tracker/FaceCandidateCropper.kt`
- Landmark jitter smoothing: `tracker/PoseSmoother.kt`
- Occluded limb freezing/reacquisition: `tracker/PoseOcclusionGuard.kt`
- Left/right identity swaps and outlier rejection: `tracker/PoseIdentityStabilizer.kt`
- Audio cue definitions/playback: `audio/`
- Timelapse recording and video overlays: `video/TimelapseRecorder.kt`
- Duration formatting: `util/DurationFormat.kt`
- Dependency versions: `gradle/libs.versions.toml`
- Android module config, flavors, and dependencies: `app/build.gradle.kts`
- Release automation: `.github/workflows/release.yml`

## Tests

Tests live under:

- `app/src/test/java/com/incident201/poseguard/`
- `app/src/test/java/com/incident201/poseguard/tracker/`
- `app/src/androidTest/java/com/incident201/poseguard/`

When changing deterministic tracker logic, update or add JVM tests near the affected tracker class. When changing UI screenshots, update the relevant Roborazzi test or golden files if the repository uses them.

When changing flavor-specific code, keep both source sets compile-safe:

- shared interface changes require both online and offline implementations to be updated
- online-only dependency changes must stay scoped to online
- offline stubs must remain dependency-free and compile-safe

Do not ask the user to run tests locally. Build and test execution is handled by GitHub Actions.

## Final response expectations for coding agents

When completing a task in this repository, report:

- which behavior changed
- which files were touched
- whether the change affects `main`, `online`, `offline`, or all flavors
- any relevant state-machine, tracker, lifecycle, dependency, or asset implications
- whether GitHub Actions should cover the verification

Do not include local Gradle build commands as required next steps.
