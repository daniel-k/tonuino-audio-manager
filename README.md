# Tonuino Audio Manager

Android app for managing Tonuino-ready USB drives directly on your phone. It enforces the folder and track layout Tonuino expects, helps import or convert audio to numbered MP3 files, and lets you reorder or clean up a drive without plugging it into a computer.

## What it does
- Detects removable USB storage and requests access via the Storage Access Framework.
- Browses the drive with Tonuino rules: root folders must be numbered `01`-`99`, and tracks inside a folder must be `001.mp3`-`255.mp3` (others can be revealed with the “Show ignored items” toggle).
- Displays ID3 metadata and embedded artwork for MP3s and summarizes albums when looking at a folder.
- Creates numbered folders at the root, adds audio files to a folder, and auto-numbers new tracks sequentially.
- Converts incoming audio to mono MP3 (MediaCodec + LAME); optionally transcodes MP3 sources too and preserves basic metadata/cover art.
- Drag-and-drop reorders MP3s inside a folder and renames them to a new sequential order; also supports deleting files or folders.
- Reload action to refresh caches after unplugging or replacing the drive.

## Build and run
Prerequisites: Android Studio (or the Android Gradle Plugin 8.13.x toolchain), JDK 11+, and the Android SDK with API 36; minSdk is 33.

From the repo root:
1. Ensure `local.properties` points to your Android SDK (`sdk.dir=...`).
2. Build: `./gradlew assembleDebug`
3. Install on a device with USB host/OTG support: `adb install -r app/build/outputs/apk/debug/app-debug.apk`

## Using the app
- Connect a USB drive, launch the app, and grant access when prompted.
- Navigate into a numbered folder to add files. Use the add action to pick audio; files are numbered automatically, and non-MP3 sources are transcoded by default.
- Toggle “Always transcode MP3” in the overflow menu if you want transcode MP3s when added.
- Use “Reorder files” to drag tracks, then Apply to rename them to the new 001, 002, … order.
- Long-press an item to delete it; album art and metadata are shown for confirmation.

## Project layout
- `app/src/main/java/com/example/tonuinoaudiomanager/MainActivity.kt`: USB browsing, import, reorder, and delete workflows.
- `MediaCodecMp3Converter.kt`: Decodes audio and writes MP3 with LAME and ID3 metadata.
- `UsbFileAdapter.kt`: RecyclerView adapter for folder/file rows with metadata and drag handles.

## Notes
- Known bugs and ideas are tracked in `TODO.md`.
- No license file is included; ask the maintainers before redistributing.
