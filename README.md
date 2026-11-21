# Veil Save Scanner

Read-only Android app starter for discovering likely game-save files in shared storage. Shizuku access is explicitly requested from the device owner.

## Scope
- Show Shizuku service and permission status.
- Request Shizuku permission through its official API.
- List candidate .sav, .dat, .db, .json, .bin and .bak files under Android/data, Android/obb, Android/media and Download.
- Do not read file contents, modify, delete, upload, or execute scanned files.

## Build
Open this repository in Android Studio with JDK 17 and Android SDK Platform 36. Build the app module. Android 17 compatibility still requires validation against its final SDK/device image.
