# PoseGuard — Offline Cornertime App for Android with Pose Tracking

**PoseGuard** is an Android application for monitoring whether a person can hold a static pose for a configured amount of time.

The app is useful for posture training, stillness enforcement, cornertime, and consensual D/s or power exchange scenarios where the subject must keep a strict static pose for an extended period.

PoseGuard uses on-device computer vision models to track body position and detect movement during a session. It can also optionally check face direction, apply timer penalties, provide voice feedback, record a timelapse, and integrate with external device-control services.

## Offline vs Online and Privacy

PoseGuard is now available in two versions:

- **Offline**
- **Online**

PoseGuard is designed around local, on-device processing.

Both the **offline** and **online** versions perform all core app processing on the Android device, including pose detection, face detection, movement analysis, timer logic, penalties, voice feedback, and timelapse recording.

**Neither version uploads images, video, logs, telemetry, tracking data, session data, or any other private data to external servers.**

The only difference between the two versions is extended connectivity features.


### Offline version

The **offline** version does not request Android `INTERNET` permission.

This means the app is physically prevented by Android from accessing the network.

Choose this version if it is important to you that PoseGuard cannot connect to external services at all.

### Online version

The **online** version requests Android `INTERNET` permission.

This allows optional integrations with user-configured external services, such as `buttplug.io` / Intiface Central and compatible external devices or toys.

**The online version does not use this permission to upload images, video, logs, telemetry, tracking data, session data, or any other private data.**

Choose this version only if you need network-based integrations.

## Which version should I install?

Install **offline** if you want PoseGuard to have no network access at all.

Install **online** if you need integrations with external services, Intiface Central, Buttplug-compatible services, or compatible network-connected devices/toys.

## Features

- Tracks body pose using MediaPipe Pose Landmarker
- Optionally tracks face visibility and face direction using MediaPipe Face Detector
- Detects both:
  - overall pose drift compared to the initial position
  - short, sudden movements
- Uses a configurable timer for pose-holding sessions
- Provides voice instructions and violation feedback using the system Text-to-Speech engine
- Can optionally apply penalties by automatically adding extra time to the timer after violations
- Can optionally monitor whether the subject turns toward the camera or away from it
- Allows sensitivity adjustment for tracking and violation detection thresholds
- Can record a short timelapse video of the session
- Supports optional external device feedback in the online version

## How it works

At the beginning of a session, the app captures the initial pose as a reference.

During the session, it continuously compares the current body position against that initial reference.

The app monitors two main types of movement:

1. **Pose drift** — gradual or overall deviation from the starting pose.
2. **Sudden movement** — short, sharp movements that may indicate a break in stillness.

If the detected movement exceeds the configured sensitivity threshold, the app can issue a spoken warning or apply an optional timer penalty.

When face tracking is enabled, the app can also monitor whether the subject turns toward the camera or turns away from it, depending on the selected mode.

## System requirements

- Android 11 or newer
- A device powerful enough to run local computer vision models in real time

Recommended:

- 8 GB RAM or more (~1.5 free RAM needed during the session)
- Powerful CPU (ARM64, 8 cores)
- OpenGL ES 3.1+ support for GPU acceleration
- Good lighting
- A stable device/camera position


Performance depends on the device, camera resolution, lighting conditions, and selected tracking options.

## Usage recommendations

For best tracking accuracy:

- Make sure the subject is fully visible in the camera frame.
- Use good lighting so the body silhouette and face are clearly visible.
- Avoid loose or bulky clothing that hides the body shape.
- Prefer tight-fitting or minimal clothing for accurate pose tracking.
- Place the device steadily and avoid camera shake.
- Keep the background as simple as possible when practical.

Poor lighting, partial body visibility, camera movement, or clothing that hides the silhouette may reduce tracking accuracy and cause false violations.

## Languages

The application supports:

- English
- Russian

## Status

This project is currently under development.
