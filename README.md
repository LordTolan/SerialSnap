# SerialSnap

An Android app that repeatedly photographs equipment labels, reads likely serial numbers with on-device ML Kit OCR, asks the user to verify the result, and appends unique values to a persistent list.

## Build and install

1. Open this folder in Android Studio.
2. Allow Gradle to sync and download dependencies.
3. Connect an Android phone with USB debugging enabled.
4. Press **Run**, or choose **Build > Build APK(s)**.
5. The debug APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

## Scan workflow

Tap **Take Picture & Scan**, fill the label in the camera frame, and use bright even light. Verify the detected values (one per line), then tap **Add**. The camera reopens immediately for the next label. Duplicate values are skipped automatically.

All OCR runs on the phone. The serial list is stored in the app's private preferences and remains after closing the app. Clearing app data or uninstalling removes it.
