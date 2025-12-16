# Tonuino Audio Manager

Android app for managing Tonuino-ready USB drives directly on your phone. It enforces the folder and track layout Tonuino expects, helps import or convert audio to numbered MP3 files, and lets you reorder or clean up a drive without plugging it into a computer.

## What it does
- Detects removable USB storage and requests access via the Storage Access Framework.
- Browses the drive with Tonuino rules: root folders must be numbered `01`-`99`, and tracks inside a folder must be `001.mp3`-`255.mp3` (others can be revealed with the “Show ignored items” toggle).
- Displays ID3 metadata and embedded artwork for MP3s and summarizes albums when looking at a folder.
- Creates numbered folders at the root (auto-picks the next free number), adds audio files to a folder, and auto-numbers new tracks sequentially.
- Converts incoming audio to mono MP3 (MediaCodec + LAME); optionally transcodes MP3 sources too and preserves basic metadata/cover art.
- Drag-and-drop reorders MP3s inside a folder and renames them to a new sequential order; also supports auto-reordering by track metadata and deleting files or folders.
- Writes Tonuino-compatible NFC tags for numbered folders and reads Tonuino tags to show the mode/folder and preview the referenced tracks.
- Reload action to refresh caches after unplugging or replacing the drive.

## Build and run
Prerequisites: Android Studio (or the Android Gradle Plugin 8.13.x toolchain), JDK 11+, and the Android SDK with API 36; minSdk is 33.

From the repo root:
1. Ensure `local.properties` points to your Android SDK (`sdk.dir=...`).
2. Build: `./gradlew assembleDebug`
3. Install on a device with USB host/OTG support: `adb install -r app/build/outputs/apk/debug/app-debug.apk`

## Using the app
- Connect a USB drive, launch the app, and grant access when prompted.
- At the root, tap Add folder; the app chooses the next available `01`-`99` name and jumps into the new folder.
- Navigate into a numbered folder to add files. Use the add action to pick audio; files are numbered automatically up to 255, and non-MP3 sources are transcoded by default (toggle “Always transcode MP3” in the overflow menu).
- Use “Reorder files” to drag tracks, or tap “Auto reorder” to sort by track metadata; Apply renames them to the new 001, 002, … order.
- Long-press an item for actions (delete, or for numbered root folders, “Write NFC tag”).
- To write a tag, long-press a numbered root folder, choose “Write NFC tag,” then hold a Tonuino-compatible tag to the phone; the tag is programmed in Audiobook (multiple) mode for that folder and shows the folder’s tracks.
- To read a Tonuino tag, just tap it while the app is open; the app automatically opens the reader view, shows the mode and folder number, and lists the folder’s tracks from your connected drive.

## Project layout
- `app/src/main/java/com/example/tonuinoaudiomanager/MainActivity.kt`: USB browsing, import, reorder, and delete workflows.
- `ReadNfcActivity.kt` / `WriteNfcActivity.kt`: Reading Tonuino NFC tags and writing tags for numbered folders.
- `nfc/`: Lower-level NFC helpers (tag parsing/writing, intent dispatch).
- `MediaCodecMp3Converter.kt`: Decodes audio and writes MP3 with LAME and ID3 metadata.
- `UsbFileAdapter.kt`: RecyclerView adapter for folder/file rows with metadata and drag handles.

## Notes
- Known bugs and ideas are tracked in `TODO.md`.
- Licensed under the BSD 3-Clause license (`LICENSE`); third-party notices are in `THIRD_PARTY_NOTICES`.
