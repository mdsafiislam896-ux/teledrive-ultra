# TeleDrive Ultra — Android native track

This branch keeps the existing PWA source and adds an Android/Kotlin foundation.

## CI build
The GitHub Actions workflow builds official TDLib JNI libraries from the upstream `tdlib/td` Android example, stages its generated Java bindings and ABI libraries, then builds a debug APK.

Required repository Actions secrets:
- `TELEGRAM_API_ID`
- `TELEGRAM_API_HASH`

Do not commit API credentials, Telegram OTPs, 2FA passwords, keystores, or signing passwords.

## Current implementation status
- Android Gradle/Kotlin project scaffold: added.
- Official TDLib Android JNI build pipeline: configured for arm64-v8a and armeabi-v7a.
- Per-account isolated TDLib client/database directories and Android Keystore-wrapped database keys: initial implementation.
- Phone/code/2FA and TDLib QR authorization UI: initial integration; requires compile/runtime verification.
- QR image rendering: ZXing.
- WorkManager worker entry point: added, but upload queue and backup scheduling are not yet implemented.
- Release signing: intentionally not configured until a keystore is generated and stored as GitHub Actions secrets.
- Device login, QR scan, file upload/download and multi-account lifecycle: not yet device-tested.

The CI run is the first acceptance gate. Fix any upstream TDLib or Kotlin API drift reported by the compiler before treating the APK as installable.
