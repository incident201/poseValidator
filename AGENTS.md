# AGENTS.md

## Project overview

This repository contains an Android/Kotlin application for local pose validation. The app uses the device camera, MediaPipe Tasks Vision, body-landmark tracking, optional face-direction checks, gyroscope-based stabilization, penalties, voice prompts, and a timelapse recorder to verify that the user keeps a selected pose for a configured duration.

The app is intentionally local/on-device. Do not introduce cloud API calls, remote inference, analytics, account systems, or network-dependent validation unless the task explicitly asks for that.

## Repository shape

The project is a single Android application module:

- `settings.gradle.kts` includes only `:app`.
- `app/build.gradle.kts` defines the Android app module.
- Main package: `com.incident201.poseguard`.
- Main activity: `app/src/main/java/com/incident201/poseguard/MainActivity.kt`.
- Main UI: `app/src/main/java/com/incident201/poseguard/ui/CameraScreen.kt`.
- Main state coordinator: `app/src/main/java/com/incident201/poseguard/viewmodel/GameViewModel.kt`.
- Pose, face, smoothing, identity, and movement logic: `app/src/main/java/com/incident201/poseguard/tracker/`.
- Timelapse recording: `app/src/main/java/com/incident201/poseguard/video/`.
- Shared formatting helpers: `app/src/main/java/com/incident201/poseguard/util/`.

The app uses Jetpack Compose, CameraX, MediaPipe Tasks Vision, Kotlin coroutines/flows, Android sensors, SharedPreferences, and Android media encoding APIs.

## Agent workflow rules

Make focused changes that match the requested behavior. Prefer editing the existing component that already owns the behavior instead of adding parallel logic elsewhere.

Do not add instructions asking the user or another agent to run local Gradle builds, local app launches, emulator checks, or unit tests. Build and test verification is handled by GitHub Actions. When finishing a task, summarize the files changed and the expected behavior change.

Do not commit real secrets, keystores, `.env` files, signing passwords, generated APKs, or recorded videos.

Do not rename the app package, Gradle module, MediaPipe model asset names, or SharedPreferences keys unless the task explicitly requires a migration.

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
8. Apply face-direction rule if enabled.
9. Update gauge state, violation counters, penalties, session result, and voice events.

Keep this order in mind. Many bugs in this project come from changing a later step while forgetting that earlier steps may already have transformed, held, smoothed, or rejected the pose.

## Main UI and state files

### `MainActivity.kt`

`MainActivity` is only the app shell. It enables edge-to-edge mode, applies `MyApplicationTheme`, creates `GameViewModel`, and renders `CameraScreen`.

Do not put feature logic here unless the task is specifically about Activity setup.

### `ui/CameraScreen.kt`

`CameraScreen` is the main Compose screen. It owns UI composition around camera preview, permission handling, camera switching, pose overlay drawing, onboarding display, settings entry, voice announcements, debug UI, session controls, and timelapse UI.

This file bridges Android camera/bitmap work and Compose state. Keep heavy frame processing out of Composables and in the existing ViewModel/service pipeline.

Important details:

- It observes ViewModel state through `collectAsState()`.
- It displays onboarding before the main camera UI when onboarding is incomplete.
- It uses CameraX types such as `ProcessCameraProvider`, `Preview`, `ImageAnalysis`, `PreviewView`, and `CameraSelector`.
- It uses `PoseLandmarkerService` for pose inference.
- It passes frames and timestamps into `GameViewModel` so the ViewModel can match camera bitmaps with MediaPipe results.
- It uses `TimelapseRecorder` during sessions when recording is enabled.
- It draws pose skeleton connections using hardcoded MediaPipe landmark index pairs.

When changing UI text, use string resources and the existing localization approach.

### `ui/SettingsScreen.kt`

Settings UI for validation behavior and tuning. Most settings are persisted and applied through `GameViewModel`, not stored directly in the Composable.

When adding a new setting:

- Add it to `GameSettings` if it affects validation or UI state.
- Load and save it in `GameViewModel`.
- Apply it to the relevant engine in `applySettingsToEngines()` or an existing update method.
- Add localized labels/descriptions in string resources.

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
- Voice event emission.
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

Be careful when changing this file. Prefer small changes that preserve the existing state transitions and lock boundaries.

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
- pose detection confidence: `0.70`
- pose presence confidence: `0.70`
- tracking confidence: `0.75`

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

It creates a `FaceDetector` with:

- model asset: `blaze_face_short_range.tflite`
- delegate: CPU
- running mode: `IMAGE`
- configurable minimum detection confidence, clamped to `0.5..0.95`

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
- requires at least 6 valid points
- computes the min/max landmark bounding box
- rejects degenerate boxes
- adds horizontal and vertical padding
- enforces a minimum padding in pixels
- clamps the final rectangle to bitmap bounds
- returns `null` when no safe crop can be produced

Default padding:

- horizontal padding factor: `0.30`
- vertical padding factor: `0.15`
- minimum padding: `32px`

`cropAroundPose()` creates a bitmap crop when possible and returns the original bitmap when no crop rectangle can be calculated.

Change this file when body crop geometry, padding, fallback behavior, or overlay crop bounds need to change.

### `FaceCandidateCropper.kt`

This object estimates the square crop that should be sent to `FaceDetectorService`.

`calculateFaceCandidateRect()` needs:

- full bitmap width/height
- current `PoseLandmarks`
- body crop rectangle from `PoseFrameCropper`

It first tries to use face landmarks `0..10`. If at least two valid face landmarks are available, it builds a crop around their bbox. Crop size is based on:

- face landmark width times `3.2`
- face landmark height times `4.0`
- shoulder width times `1.15`
- minimum size `160px`

If face landmarks are not usable, it falls back to shoulders 11 and 12. In that case, it places the crop above the shoulder midpoint and uses shoulder width times `1.45`, with a minimum size of `180px`.

After choosing a candidate square, it fits the square first into the body crop bounds and then into the full bitmap bounds. It rejects crops smaller than `96x96`.

Change this file when face detection misses because the detector input crop is wrong, too small, too high/low, or clipped incorrectly.

### `PoseSmoother.kt`

This class smooths landmark coordinates using a One Euro Filter per landmark and per axis.

It tracks filters by landmark index. Each landmark has separate filters for `x`, `y`, and `z`. `visibility` and `presence` are passed through unchanged.

Default config:

- `minCutoff = 0.35`
- `beta = 0.025`
- `derivativeCutoff = 1.0`

Config ranges:

- cutoff: `0.01..5.0`
- beta: `0.0..1.0`

Behavior:

- empty landmark lists reset the smoother
- the first pose initializes filters
- gaps longer than `1200ms` reset the smoother
- delta time is clamped to `1/120s..0.25s`
- changing config resets all filters

Change this file when the task concerns landmark jitter, smoothing aggressiveness, lag, or filter reset behavior.

### `PoseOcclusionGuard.kt`

This class freezes unreliable limb landmarks during calibration and projects stable replacement positions during tracking.

It guards these indices:

- elbows: 13, 14
- wrists: 15, 16
- knees: 25, 26
- ankles: 27, 28

Calibration:

- calibration window is `2000ms`
- frames are collected during the end of `StartingDelay`
- `finishCalibration()` computes local coordinates relative to body center and body scale
- for each guarded landmark it calculates median visibility, p10 visibility, median local position, median z, and p90 jitter
- a landmark is frozen when it is effectively invisible or unstable with low visibility

Frozen landmarks are stored in local body coordinates, not raw screen coordinates. During tracking, the guard projects each frozen landmark back into the current body coordinate system using current body center and scale.

Reacquisition:

- raw tracking can resume when visibility is high enough and the landmark is stable for several frames
- if the raw point is close to the frozen point, it switches directly back to raw tracking
- if the raw point is visible but far from the frozen point, it blends from frozen to raw over a short handoff
- if visibility drops again, it falls back to frozen projection

Change this file when the task concerns occluded elbows/wrists/knees/ankles, false movement caused by invisible limbs, frozen overlay points, reacquisition, or handoff behavior.

### `PoseIdentityStabilizer.kt`

This class protects the pipeline from left/right swaps, sudden identity flips, and implausible pose jumps.

It requires 33 landmarks for a normal pose. If the pose is missing or too short, it resets and returns a rejected result.

For each valid frame it compares:

- the raw pose as-is (`Direct`)
- a version with left/right MediaPipe landmark pairs swapped (`Swapped`)

The score is a weighted normalized distance to the previous stable pose. Core landmarks have higher weights:

- shoulders 11, 12: weight 3.0
- hips 23, 24: weight 3.0
- elbows 13, 14: weight 1.2
- knees 25, 26: weight 1.2

It normalizes points around the body center and scale derived from shoulder width, hip width, and torso length.

It chooses `Direct` or `Swapped` only when one score wins by `SWITCH_MARGIN`. If the scores are too close, it keeps the previous transform and marks the result as ambiguous.

It rejects or freezes outliers based on:

- score threshold
- hard score threshold
- core scale ratio
- shoulder/hip width ratio
- torso length ratio
- non-finite core landmarks
- invalid initial identity core

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

Frames are sampled every `100ms`, encoded at `30fps`, and presentation timestamps are compressed by speed factor `10`.

Each encoded frame is drawn into the video size and gets two overlay badges:

- elapsed timer text
- violation count text

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
- onboarding completion

When adding settings, keep default values, load logic, update methods, persistence keys, and engine application in sync.

Be careful with old preference keys. Some sensitivity values already have migration/normalization logic.

## Localization

The app supports Russian and English via `AppLanguage`.

`GameViewModel.tr()` selects resources using the current language. Compose UI also uses helper localization functions.

When adding user-facing text:

- add string resources
- keep Russian and English behavior aligned
- avoid hardcoded visible UI strings
- keep hardcoded strings only for debug-only messages when appropriate

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
13. Timer reaching zero creates success.

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

This project processes camera frames, MediaPipe callbacks, UI state, and video encoding concurrently. Preserve existing concurrency boundaries.

Important patterns:

- `GameViewModel` uses `frameLock` for cached bitmaps and latest analyzed frame.
- `GameViewModel` uses `processingLock` for tracker state and processing generation.
- `processingGeneration` invalidates stale frame results.
- `mediaPipeResultExecutor` serializes MediaPipe result handling.
- `PoseLandmarkerService` uses `lifecycleLock`.
- `FaceDetectorService` synchronizes detector recreation and detection.
- `TimelapseRecorder` uses a single-thread executor, a lock, a stop mutex, and recording generations.

Avoid introducing shared mutable state that is not protected by the existing locks or confined to the existing executors.

## Bitmap ownership rules

Several paths create temporary bitmaps. Be careful with ownership.

- `GameViewModel` recycles cached camera bitmaps after matching or dropping.
- `FaceDetectorService` recycles only the ARGB copy it creates internally.
- `GameViewModel.buildOverlayState()` recycles the face crop bitmap after detection.
- `TimelapseRecorder` copies offered frames, recycles owned copies, and recycles overlay bitmaps.
- `PoseFrameCropper.cropAroundPose()` can return the original bitmap if no crop was possible.

Before recycling a bitmap, confirm that the current code owns that instance.

## Where to make common changes

Use this map before editing:

- Main UI layout, overlays, buttons, camera-facing UI: `ui/CameraScreen.kt`
- Settings UI: `ui/SettingsScreen.kt`
- First-run flow: `ui/OnboardingScreen.kt`
- App state, timers, penalties, preferences, gyroscope stabilization, processing pipeline: `viewmodel/GameViewModel.kt`
- Drift/motion scoring and movement violations: `tracker/MovementTracker.kt`
- Pose inference setup and landmark extraction: `tracker/PoseLandmarkerService.kt`
- Face detector setup and result mapping: `tracker/FaceDetectorService.kt`
- Body crop rectangle: `tracker/PoseFrameCropper.kt`
- Face detector input crop: `tracker/FaceCandidateCropper.kt`
- Landmark jitter smoothing: `tracker/PoseSmoother.kt`
- Occluded limb freezing/reacquisition: `tracker/PoseOcclusionGuard.kt`
- Left/right identity swaps and outlier rejection: `tracker/PoseIdentityStabilizer.kt`
- Timelapse recording and video overlays: `video/TimelapseRecorder.kt`
- Duration formatting: `util/DurationFormat.kt`
- Dependency versions: `gradle/libs.versions.toml`
- Android module config: `app/build.gradle.kts`
- Release automation: `.github/workflows/release.yml`

## Tests

Tests live under:

- `app/src/test/java/com/incident201/poseguard/`
- `app/src/test/java/com/incident201/poseguard/tracker/`
- `app/src/androidTest/java/com/incident201/poseguard/`

When changing deterministic tracker logic, update or add JVM tests near the affected tracker class. When changing UI screenshots, update the relevant Roborazzi test or golden files if the repository uses them.

Do not ask the user to run tests locally. Build and test execution is handled by GitHub Actions.

## Final response expectations for coding agents

When completing a task in this repository, report:

- which behavior changed
- which files were touched
- any relevant state-machine, tracker, or asset implications
- whether GitHub Actions should cover the verification

Do not include local Gradle build commands as required next steps.
