# APK validation

The pull-request workflow runs Gradle `assembleDebug` and uploads `app-debug.apk` as a downloadable ZIP artifact if compilation succeeds. The artifact is a debug-signed APK suitable for manual installation and testing, not a production Play Store release.
