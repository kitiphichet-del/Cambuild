# Cambuild / LumaCam
Android camera app written in Kotlin with CameraX, ML Kit on-device face detection, and offline automatic photo enhancement.

## What the app does
- Live front/rear camera and JPEG capture
- Import an image from the gallery
- Auto / Natural / Night / Portrait processing
- Portrait uses real on-device ML Kit face detection to locate faces and adjust face exposure without changing facial features
- Automatic exposure, tone, color, and detail enhancement without network upload
- Compare original and enhanced image and save BOTH as JPEG in Pictures/LumaCam

This is an **MVP** and is **not** an OPPO Find X9 Pro clone. Auto/Natural/Night are algorithmic enhancement, **not generative or neural AI**. Face detection uses on-device machine learning. Actual quality depends on camera sensor and conditions.

## Get APK from GitHub Actions
1. Open [Actions](../../actions/workflows/android-apk.yml)
2. Open **Build Android APK** and choose **Run workflow** (or view an automatic push build).
3. After success, open the run → Artifacts → download **Cambuild-LumaCam-debug-APK**.
4. Extract the ZIP and install `app-debug.apk` on Android 10+.

## Build locally
Open this repository in Android Studio (JDK 17, Android SDK 35).
If using terminal without a Gradle wrapper: install Gradle 8.11.1 and run `gradle assembleDebug`.

Photos are processed on-device. No ad SDK, cloud API, account, or image upload. For Android 10+ no storage permission is required to save photos in a media collection.

Important: GitHub Action build may require updates as Android/Gradle dependencies change. See Actions logs for compilation status.
