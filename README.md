# SerialSnap 2.0 — Solar Serial Collector

An Android field app for collecting SolarEdge optimizer/inverter and Enphase microinverter serial numbers. It reads both barcodes/QR codes and printed text, asks the installer to verify the result, and builds a persistent site inventory.

## Build and install

1. Open this folder in Android Studio.
2. Allow Gradle to sync and download dependencies.
3. Connect an Android phone with USB debugging enabled.
4. Press **Run**, or choose **Build > Build APK(s)**.
5. The debug APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

## Scan workflow

Enter the customer/site, choose the device type, and optionally enter its roof/array position. Tap **Scan Device**, fill the label in the camera frame, and use bright even light. Verify the values and tap **Save**. The camera reopens for the next unit. Duplicate values are skipped automatically.

Tap a saved device to edit it or hold it to delete it. **Export CSV** creates a spreadsheet-ready commissioning list containing serial number, device type, site, location, and capture time.

All OCR runs on the phone. The serial list is stored in the app's private preferences and remains after closing the app. Clearing app data or uninstalling removes it.
