# LiteRec

**A lightweight Android screen, system-audio and microphone recorder.**
Record exactly what you choose: just the screen, just the internal audio, just the mic, or any combination. No ads, no accounts, no analytics, and no internet permission.

<p align="center">
  <img width="24%" height="24%" alt="74de6962573907553eac7cf331d1a44a" src="https://github.com/user-attachments/assets/d55213c8-babd-4ec5-91b7-5b7fe167e484" />
  <img width="24%" height="24%" alt="bb9044d80d9f10c19d59d9d2a3d11d9d" src="https://github.com/user-attachments/assets/c51cd65f-cb60-4ae6-bd61-7e58faa638a5" />
  <img width="24%" height="24%" alt="e407dc4d77acedad0f466846c42d0537" src="https://github.com/user-attachments/assets/9220b686-c4cd-4497-8e2b-a35306013166" />
  <img width="24%" height="24%" alt="cdb62a6f5053b9eca79b5f0e2052eaa3" src="https://github.com/user-attachments/assets/89b78a05-da81-4931-9e2f-8c5c4314f455" />
</p>



## Features

**Record only what you pick**

| Screen | System audio | Mic | Result |
|:---:|:---:|:---:|---|
| ✓ | | | Silent `.mp4` |
| | ✓ | | `.m4a` audio file |
| | | ✓ | `.m4a` audio file |
| ✓ | ✓ | | `.mp4` with system audio |
| ✓ | | ✓ | `.mp4` with mic audio |
| | ✓ | ✓ | `.m4a`, both mixed |
| ✓ | ✓ | ✓ | `.mp4`, both mixed |

- **Pause and resume** from the app or the notification
- **Quick Settings tile** and notification controls (pause, resume, stop)
- **Start delay**: off, a preset, or any custom value up to 300 seconds
- **Stop when the screen locks** (optional)
- **Library** with thumbnails, duration, size, sorting, multi-select share and delete, and a tappable folder path
- **Built-in player** for videos and audio-only recordings
- **Editor: trim and split** with an audio waveform (see below)
- **Custom save folder** (internal storage, SD card, or other storage providers) or the default `Movies/LiteRec` and `Music/LiteRec`
- **Adjustable interface size** (Normal to Huge)

**Editor**

Open any recording, tap **Trim**, and cut out the parts you don't want, such as the moments where you tapped start and stop in the notification.

- Audio waveform on a zoomable, scrollable timeline, so you can spot silences and spikes
- **Split here** at the playhead, then **Remove** or **Restore** any piece; **Remove before** / **Remove after** for quick start and end trims
- Fine-tune the playhead by 0.1 s or 1 s, with exact-frame preview
- Preview plays only what you're keeping
- **Lossless save**: kept pieces are copied without re-encoding, so quality is identical and saving takes seconds
- Saves a new `_trimmed` file next to the original, with an option to delete the original
- Works on video and audio-only recordings

Because video can only be cut cleanly at a keyframe (the recorder writes one every second), each kept video piece starts at the next keyframe after your cut, up to about 1 s later. The timeline shows that sliver darkened before you save. Ends are exact, and audio-only files cut almost exactly.

**Quality settings**

- Resolution: native screen size down to 480p (4K appears when your screen and encoder support it)
- Frame rate: 30, 60, 90 or 120 fps, where the hardware supports it
- Video bitrate: automatic or fixed, with an estimated MB per minute shown
- Codec: H.264 or H.265
- Orientation: auto, portrait or landscape
- Audio: sample rate, bitrate, and separate volume for system audio and mic
- File name pattern with `{date}`, `{time}` and `{type}`

## Why it's light

- No dependencies at all: no AndroidX, Material or Compose, only platform widgets
- No `INTERNET` permission, so the app can't send anything off your device
- Hardware video encoding straight from the screen to the encoder, with no per-frame CPU copies
- Each source is its own pipeline and only runs when its checkbox is on
- R8 minification and resource shrinking in release builds

## How it works

```
screen        -> VirtualDisplay -> MediaCodec (surface) ------------\
system audio  -> AudioRecord (playback capture) -\                   >-> MediaMuxer -> .mp4 / .m4a
microphone    -> AudioRecord (mic) ---------------+-> mixer -> AAC --/
```

- Encoder capabilities are detected at runtime. If your chosen resolution, frame rate or codec isn't supported, the app steps down to the next supported setting and tells you.
- Video timestamps share one monotonic clock, and audio timestamps are counted from encoded frames, so pausing never creates gaps or backwards jumps.
- Files are written straight into MediaStore (or your chosen folder) with no storage permission. If a folder provider can't seek, the app records locally and copies the file when you stop.

| File | Role |
|---|---|
| `Engine.kt` | Encoders, audio capture and mixing, muxer, pause and resume |
| `RecorderService.kt` | Foreground service, countdown, notifications, lifecycle |
| `StartFlow.kt` | Permission and screen-capture consent sequence |
| `Caps.kt` | Encoder capability detection and fallback chain |
| `Output.kt`, `Folders.kt` | File creation and folder handling |
| `EditorActivity.kt`, `Timeline.kt`, `Trimmer.kt` | Editor UI, waveform view, lossless cutting |
| `Library*.kt`, `PlayerActivity.kt`, `SettingsActivity.kt`, `MainActivity.kt` | UI |

## Requirements

- Android 10 (API 29) or newer. System audio capture needs Android 10+.
- To build: Android Studio (Ladybug or newer) and JDK 17+.

## Release
Download from [Releases](../../releases)

## Build

1. Clone the repo and open it in Android Studio. Let Gradle sync.
2. Run on a device, or build a release APK with **Build > Generate App Bundles or APKs**.
3. Command line: run `gradle wrapper` once, then `./gradlew assembleRelease`.

The release build is signed with the debug key so it installs directly. Use your own keystore to publish.

## Permissions

| Permission | Why |
|---|---|
| `RECORD_AUDIO` | Microphone and system audio capture |
| `FOREGROUND_SERVICE` (+ media projection, microphone types) | Keeps recording while the app is closed |
| `POST_NOTIFICATIONS` | Recording notification with pause and stop controls |

## Known limitations

- **Android asks for screen-capture permission every time.** On Android 14 and newer this is mandatory and an app can't turn it off. LiteRec offers a **Full screen only** option to shorten the prompt to one tap. Settings also shows an optional ADB command that other recording apps document for skipping the prompt; it may not work on every phone or Android version.
- **Some apps block audio capture.** Their sound is recorded as silence. DRM and secure content may appear black.
- **Mic and system audio together** can echo if the mic hears the speaker. Use headphones.
- **A force-killed recording may be unplayable**, because MP4 files are finalized when recording stops.
- **Trim start points snap to keyframes** (up to about 1 s later) because the editor doesn't re-encode.
- **Opening a folder from the library** depends on your phone's file manager. LiteRec tries several routes and falls back to the system file picker or copying the path.

## Contributing

Issues and pull requests are welcome. Before opening a PR, please test the source combinations you touched (screen only, system audio only, mic only, and mixes), including pause and resume, and trimming with splits in the middle of a clip.

## License

MIT
