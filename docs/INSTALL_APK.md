# Installing the CalmDuty Android Companion (APK & Source)

CalmDuty's Android companion (`100% Kotlin + Jetpack Compose + Material 3`) is distributed directly as an open-source **GitHub Release APK** (no Play Store account required) and can also be compiled locally from source in under a minute.

---

## Option 1 — Direct APK Sideload (Fastest — 60 Seconds)

1. Open the [**Latest Release**](https://github.com/Timtech4u/edgeops-android/releases/latest) page on your Android phone (Android 10+ / Pixel 8+ recommended for hardware Gemini Nano).
2. Download **`calmduty-v0.1.0-android.apk`** (and optionally verify its checksum against `SHA256SUMS.txt`).
3. Tap the downloaded APK on your phone → allow **Install unknown apps** for your browser/Files app → tap **Install**.
4. Open **CalmDuty**, sign in with Google, and grant **Notifications** permission when prompted so urgent (`90%+`) fires and `×2+` alert spikes notify you immediately.

### Installing via ADB (Desktop → Phone)
```bash
adb install -r calmduty-v0.1.0-android.apk
```

---

## Option 2 — Build From Source Against Your Own Self-Hosted Backend

If you are self-hosting CalmDuty on your own Google Cloud Run or Docker server:

### Prerequisites
- JDK 21 + Android SDK 36 (Android Studio or CLI tools)
- Your own Firebase project with **Google Sign-In**, **Cloud Firestore**, **Firebase App Check**, and **Firebase AI Logic** enabled (`npx firebase-tools init ailogic`).

### Steps
1. Clone the repository:
   ```bash
   git clone https://github.com/Timtech4u/edgeops-android.git
   cd edgeops-android
   ```
2. Download your Firebase `google-services.json` and place it at `app/google-services.json` *(gitignored)*.
3. Point the Android app to your self-hosted CalmDuty API URL by editing `DEFAULT_BASE_URL` in:
   `app/src/main/java/com/example/data/CloudRunInvestigatorClient.kt`
4. Register your debug SHA-1 fingerprint in **Firebase Console → Project Settings → Your Apps** and register your debug App Check UUID under **Firebase Console → App Check → Manage debug tokens**.
5. Run unit tests & build the APK:
   ```bash
   gradle testDebugUnitTest assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

---

## Testing Offline Gemini Nano Mode (Airplane Mode)
1. Sync your Slack channels while online so alerts are cached cleanly on-device.
2. Turn on **Airplane Mode** on your Pixel phone (an amber **Works Offline** badge appears in the top bar).
3. Open any alert → tap **Draft customer reply** in Triage Chat — Firebase AI Logic (`InferenceMode.PREFER_ON_DEVICE`) drafts the reply locally on-device at `$0` cost with zero network traffic.
