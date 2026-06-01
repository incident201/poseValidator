# poseValidator

**poseValidator** is an Android application for monitoring whether a person can hold a static pose for a configured amount of time.

The app uses on-device computer vision models to track the user's body position and detect movement during a session. It is designed for scenarios where the subject must remain still, maintain a specific posture, or avoid turning toward or away from the camera. 

## Features

- Tracks body pose using **MediaPipe Pose Landmarker**
- Optionally tracks the face using **MediaPipe Face Detector**
- Detects both:
  - overall pose drift compared to the initial position
  - short, sudden movements
- Uses a configurable timer for pose-holding sessions
- Provides voice instructions and violation feedback using the system **Text-to-Speech** engine
- Can optionally apply penalties by automatically adding extra time to the timer after violations
- Can optionally monitor face direction and detect when the subject turns toward the camera or away from it
- Allows sensitivity adjustment for tracking and violation detection thresholds
- Can record a short timelapse video of the session
- Supports **English** and **Russian**
- Works fully offline on the device

## Privacy

poseValidator runs entirely on the local Android device.

The app does **not** upload, send, or share video, tracking data, telemetry, or session data with any external server. Pose and face detection are performed locally using on-device models.

## How It Works

At the beginning of a session, the app captures the initial pose as a reference. During the session, it continuously compares the current body position against that initial reference.

The app monitors two main types of movement:

1. **Pose drift** — gradual or overall deviation from the starting pose.
2. **Sudden movement** — short, sharp movements that may indicate a break in stillness.

If the detected movement exceeds the configured sensitivity threshold, the app can issue a spoken warning or apply an optional timer penalty.

When face tracking is enabled, the app can also monitor whether the subject turns toward the camera or turns away from it, depending on the selected mode.

## System Requirements

- Android 11 or newer
- A device powerful enough to run local computer vision models in real time
- Recommended:
  - 8 GB RAM or more
  - A reasonably powerful CPU/GPU

Performance depends on the device, camera resolution, lighting conditions, and selected tracking options.

## Usage Recommendations

For best tracking accuracy:

- Make sure the subject is fully visible in the camera frame.
- Use good lighting so the body silhouette and face are clearly visible.
- Avoid loose or bulky clothing that hides the body shape.
- Prefer tight-fitting or minimal clothing for accurate pose tracking
- Place the device steadily and avoid camera shake.
- Keep the background as simple as possible when practical.

Poor lighting, partial body visibility, camera movement, or clothing that hides the silhouette may reduce tracking accuracy and cause false violations.

## Languages

The application supports:

- English
- Russian

## Status

This project is currently under development.
