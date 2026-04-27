# SoundForge ClearWave — Complete App Handoff

**Infinite Signal Labs** | Missoula, MT  
**Stack:** Android (Kotlin/Compose) + Python Cloud Worker + Firebase + Gemini AI

---

## What This Is

An AI-powered audio cleanup app for Android. Users record or import audio, optionally let Gemini AI recommend DSP settings, then process it — either on-device instantly or via a Cloud Run worker for heavier workloads. Output is broadcast-ready M4A.

**Primary use case:** Podcasters, streamers, and content creators who want clean audio without understanding DSP.

---

## Repository Layout

```
soundforge-clearwave/
├── android/                    # Android app (Kotlin + Jetpack Compose)
│   └── app/src/main/java/com/isl/soundforge/
│       ├── MainActivity.kt
│       ├── MainApplication.kt
│       ├── ai/
│       │   └── GeminiAudioAnalyzer.kt    # Gemini 1.5 Flash integration
│       ├── audio/
│       │   ├── AudioFileManager.kt        # URI → File, metadata extraction
│       │   ├── AudioProcessor.kt          # On-device DSP pipeline
│       │   └── AudioProcessingService.kt  # Foreground service
│       ├── firebase/
│       │   ├── AuthManager.kt             # Email + Google Sign-In
│       │   ├── ProcessingQueueManager.kt  # Firestore job queue
│       │   └── StorageManager.kt          # Firebase Storage I/O
│       └── ui/
│           ├── EngineViewModel.kt         # Single source of truth
│           └── screens/
│               ├── AuthScreen.kt
│               ├── HomeScreen.kt
│               ├── ProcessScreen.kt
│               ├── LibraryScreen.kt
│               └── SettingsScreen.kt
├── backend/                    # Python Cloud Run worker
│   ├── audio_processor.py      # DSP pipeline (mirrors Android exactly)
│   ├── firebase_client.py      # Firestore + Storage wrapper
│   ├── main.py                 # Worker entry point
│   ├── requirements.txt
│   ├── Dockerfile
│   ├── .dockerignore
│   └── deploy.sh
└── tests/                      # JVM-only DSP unit tests (no Android deps)
    └── src/
        ├── main/kotlin/.../DspMath.kt
        └── test/kotlin/.../DspMathTest.kt
```

---

## Architecture

```
User
 │
 ▼
Android App (Kotlin/Compose)
 │         │
 │         ├─► On-device path:  AudioProcessor.kt → local M4A file
 │         │
 │         └─► Cloud path:
 │               1. Upload raw audio → Firebase Storage  users/{uid}/raw/
 │               2. Write job doc  → Firestore           users/{uid}/jobs/{jobId}
 │               3. Poll job via Flow until status == done|error
 │               4. Download result ← Firebase Storage  users/{uid}/processed/
 │
 ▼
Cloud Run Worker (Python)
 │
 ├─ Firestore collection-group listener: jobs where status == "queued"
 ├─ Transactional claim (prevents duplicate processing across instances)
 ├─ DSP pipeline: noisereduce → HPF → BS.1770 normalize → AAC encode
 └─ Update job doc with status + metrics
```

**Both the Android `AudioProcessor.kt` and `backend/audio_processor.py` implement the same DSP chain** so on-device and cloud results are perceptually consistent:

1. Noise reduction (spectral subtraction)
2. Room correction (80 Hz single-pole IIR HPF)
3. Loudness normalization (BS.1770-4 / pyloudnorm on backend; RMS-based on Android)
4. Clip guard (hard-limit at ±1.0 / 0 dBFS)

---

## Firebase Setup (Required Before First Run)

**This is the only manual step — do this first.**

### 1. Create Firebase Project

1. Go to https://console.firebase.google.com
2. Create project: `soundforge-clearwave`
3. Enable Google Analytics (optional but free)

### 2. Add Android App

1. Register package name: `com.isl.soundforge`
2. Download `google-services.json`
3. Place it at: `android/app/google-services.json`
4. The template is at `android/app/google-services.json.template`

### 3. Enable Services

| Service | Settings |
|---|---|
| Authentication | Enable Email/Password + Google |
| Firestore | Create in production mode, region: `us-central1` |
| Storage | Default rules (restrict to auth users) |

### 4. Get Web Client ID

Firebase Console → Project Settings → Your apps → Web client (OAuth 2.0)  
Copy the client ID into `android/app/src/main/java/com/isl/soundforge/firebase/AuthManager.kt`:

```kotlin
const val WEB_CLIENT_ID = "YOUR_WEB_CLIENT_ID_HERE"  // line 70
```

### 5. Firestore Security Rules

```
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    match /users/{uid}/{document=**} {
      allow read, write: if request.auth != null && request.auth.uid == uid;
    }
  }
}
```

### 6. Storage Security Rules

```
rules_version = '2';
service firebase.storage {
  match /b/{bucket}/o {
    match /users/{uid}/{allPaths=**} {
      allow read, write: if request.auth != null && request.auth.uid == uid;
    }
  }
}
```

---

## Building the Android App

### Prerequisites

- Android Studio Hedgehog (2023.1.1) or later
- Android SDK 34
- Java 17
- A Firebase project (see above)

### Local Setup

```bash
# 1. Copy templates
cp android/local.properties.template android/local.properties
cp android/app/google-services.json.template android/app/google-services.json

# 2. Edit local.properties
sdk.dir=/path/to/your/Android/Sdk
GEMINI_API_KEY=your_gemini_key_here   # optional — app works without it

# 3. Fill in real values in google-services.json
# (download from Firebase Console)

# 4. Set WEB_CLIENT_ID in AuthManager.kt

# 5. Build
cd android
./gradlew assembleDebug
```

### Secrets Policy

| Secret | Location | How it gets into the app |
|---|---|---|
| `google-services.json` | `android/app/` (gitignored) | Parsed by Google Services plugin at build time |
| `GEMINI_API_KEY` | `local.properties` (gitignored) | `BuildConfig.GEMINI_API_KEY` via `buildConfigField` |
| Web Client ID | Hardcoded in `AuthManager.kt` | Not secret — it's a public OAuth client ID |
| Runtime Gemini key | Settings screen UI | Stored in `AppState.geminiApiKey`, never persisted |

**Never commit `local.properties` or `google-services.json`.**

---

## Android App — Screen by Screen

### AuthScreen
- Email/password sign-in and account creation
- Google Sign-In via `ActivityResultContracts.StartActivityForResult`
- Navigates to HomeScreen on success

### HomeScreen
- File picker (system audio chooser) or record button
- Displays waveform thumbnail, duration, file size
- "Enhance" button kicks off the full pipeline

### ProcessScreen
- Shows current `ProcessingState` (Uploading → Analyzing → Processing → Downloading → Done)
- Displays Gemini's analysis recommendation and explanation when available
- Toggle: on-device vs cloud processing
- Individual DSP toggles (noise reduction, room correction, normalization)
- Target LUFS slider (–23 to –9)
- Download/share button when Done

### LibraryScreen
- Lists all completed cloud jobs (`status == "done"` in Firestore)
- Swipe-to-delete (removes Firestore doc + Storage file)
- Tap to re-open in ProcessScreen

### SettingsScreen
- Runtime Gemini API key field
- Cloud vs on-device processing toggle
- Sign-out

---

## Android App — Key Components

### EngineViewModel (`ui/EngineViewModel.kt`)
Single ViewModel for the entire app. `AppState` is the only source of truth.  
All async work runs in `viewModelScope`; errors surface via `AppState.errorMessage`.

### AudioProcessor (`audio/AudioProcessor.kt`)
On-device DSP in Kotlin:
- Decodes via `MediaExtractor` + `MediaCodec` → `FloatArray` PCM
- Applies `applySpectralNoiseSuppression`, `applyRoomCorrection`, `normalizeLoudness`
- Encodes back to M4A via `MediaCodec` + `MediaMuxer`
- Reports progress via callback (0.0–1.0)

### AudioProcessingService (`audio/AudioProcessingService.kt`)
Foreground service for background recording. Shows a persistent notification while the microphone is active so Android doesn't kill the process.

### GeminiAudioAnalyzer (`ai/GeminiAudioAnalyzer.kt`)
Calls `gemini-1.5-flash` via REST (text-only — no audio bytes sent to the API).  
Sends measured characteristics (duration, peak dB, RMS, noise floor, dynamic range) and asks for JSON-structured DSP recommendations.  
Gracefully returns `null` if no API key is configured — the app still processes audio, just without AI recommendations.

### Firebase Managers
- **AuthManager**: email/password + Google Sign-In, wraps Firebase Auth suspend functions
- **StorageManager**: `uploadRaw()` and `downloadProcessed()` with progress callbacks; paths are `users/{uid}/raw/` and `users/{uid}/processed/`
- **ProcessingQueueManager**: enqueues jobs, watches a specific job doc as a `Flow<Job?>`, lists completed jobs for the library

---

## Python Cloud Backend

### Local Development

```bash
cd backend
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt

# Set credentials
export GOOGLE_APPLICATION_CREDENTIALS=/path/to/serviceAccount.json
export GOOGLE_CLOUD_PROJECT=your-project-id
export FIREBASE_STORAGE_BUCKET=your-project-id.appspot.com

python main.py
```

### Running Tests

```bash
cd backend
pytest tests/ -v
```

29 tests, all passing. Tests use WAV I/O only (no ffmpeg required) so they run in CI without the Docker environment.

### Test Coverage

| Class | Tests |
|---|---|
| `TestMeasurePeakDb` | silence→-120, full scale→0, half scale→-6.02, picks absolute max, near-zero |
| `TestEstimateNoiseFloorDb` | silence, shorter-than-frame, quiet-vs-loud separation, uniform signal |
| `TestApplyRoomCorrection` | DC attenuated, 1 kHz passes through, shape preserved, float32 output, 40 Hz attenuated |
| `TestNormalizeLoudness` | silence unchanged, scales to target LUFS, attenuates loud signal, clips output, short-clip RMS fallback |
| `TestApplyNoiseReduction` | shape/dtype preserved, silence stays zero, reduces noise energy, short-clip stationary mode, threshold clamping |
| `TestProcessFile` | round-trip WAV produces output, metrics in valid range, all-options-disabled, progress callback |

### Deploying to Cloud Run

```bash
# Prerequisites: gcloud auth login, set project
cd backend
./deploy.sh YOUR_PROJECT_ID YOUR_BUCKET_NAME
```

Deploys as `soundforge-worker` in `us-central1`. Configuration:
- Memory: 2 GiB, CPU: 2 vCPU
- Concurrency: 2 jobs per instance
- Min instances: 0 (scales to zero when idle)
- Max instances: 10
- Timeout: 3600s (long-running audio jobs)

The worker needs a service account with these IAM roles:
- `roles/datastore.user` (Firestore)
- `roles/storage.objectAdmin` (Cloud Storage)

### Key Design Decisions

**Transactional job claiming** (`firebase_client.py:_claim_job`): Uses a Firestore transaction to atomically flip `queued → processing`. If two worker instances both see the same job, only one succeeds — the other sees a non-queued status and skips it.

**Silence guard in noise reduction**: `noisereduce` divides by a noise power spectrum; on all-zero input it produces NaN. The guard at `audio_processor.py:141` detects peak < 1e-6 and returns the input unchanged.

**WAV intermediate for M4A encoding**: `soundfile` can't write M4A natively. The `_encode` function writes a WAV temp file, then pydub/ffmpeg converts it. WAV/FLAC/OGG are written directly.

**BS.1770 fallback for short clips**: `pyloudnorm` needs ≥0.4 s to measure integrated loudness. For shorter clips, `normalize_loudness` falls back to RMS-based gain (same algorithm as Android).

---

## JVM DSP Tests

Standalone Kotlin test module at `tests/` — no Android SDK, no emulator needed.

```bash
cd tests
./gradlew test
```

23 tests, all passing. Tests the extracted pure-math functions in `DspMath.kt`:
`rmsOf`, `estimateNoiseFloor`, `measurePeakDb`, `estimateNoiseFloorDb`, `normalizeLoudness`, `applyRoomCorrection`, `applySpectralNoiseSuppression`

---

## What's Complete

| Layer | Status |
|---|---|
| Android UI (5 screens) | Complete |
| EngineViewModel + AppState | Complete |
| On-device DSP (AudioProcessor.kt) | Complete |
| Firebase Auth (email + Google) | Complete |
| Firebase Storage manager | Complete |
| Firestore queue manager | Complete |
| Gemini AI integration | Complete |
| Foreground recording service | Complete |
| Android resources (manifest, icons, themes, strings) | Complete |
| Build config (BOM pins, BuildConfig secrets) | Complete |
| JVM DSP unit tests (23/23 passing) | Complete |
| Python DSP pipeline | Complete |
| Python Firebase client | Complete |
| Python Cloud Run worker | Complete |
| Python test suite (29/29 passing) | Complete |
| Dockerfile + .dockerignore | Complete |
| Cloud Run deploy script | Complete |

## What's Not Done

| Item | Notes |
|---|---|
| Firebase project creation | Manual step — see setup section above |
| `google-services.json` | Download from Firebase Console after creating project |
| `AuthManager.WEB_CLIENT_ID` | Paste in after creating Firebase project |
| Gemini API key | Optional — get from https://aistudio.google.com |
| Play Store listing | App ID: `com.isl.soundforge` |
| Push notifications | Not implemented — users poll or keep the app open |
| Waveform visualization | `AudioItem.waveformData` is populated but no waveform renderer is wired in ProcessScreen |
| Billing / subscription gate | No paywall implemented — all features are free |
| Analytics events | Firebase Analytics SDK included but no custom events logged |

---

## End-to-End Flow (What Happens When You Hit Enhance)

1. **File selected** → `onFilePicked(uri)` → `AudioFileManager.readMeta()` extracts name/duration/size
2. **Enhance tapped** → `EngineViewModel.enhance()`
3. **Gemini analysis** (if API key configured): sends measured characteristics as text prompt → receives JSON DSP recommendations → updates `processingOptions` automatically
4. **Cloud path:**
   - Copies file to app cache (`fileManager.copyToAppStorage`)
   - Uploads to `users/{uid}/raw/{filename}` with progress
   - Creates Firestore doc at `users/{uid}/jobs/{jobId}` with status `"queued"`
   - Worker picks it up within ~1s (Firestore listener), claims atomically, runs DSP, uploads to `users/{uid}/processed/enhanced_{name}.m4a`, marks `"done"`
   - Android's `watchJob` Flow emits the terminal state
   - Downloads processed file to `cacheDir/processed/`
5. **On-device path:** `AudioProcessor.processFile()` runs synchronously in a coroutine, writes output to `filesDir/processed/`
6. **Done state** → `ProcessingState.Done(outputItem)` → ProcessScreen shows download/share button

---

## Dependency Versions (Pinned)

### Android
- Compose BOM: `2023.10.01`
- Firebase BOM: `32.6.0`
- Navigation Compose: `2.7.5`
- OkHttp: `4.12.0`
- Kotlin coroutines: `1.7.3`
- Coil: `2.5.0`
- Gson: `2.10.1`
- compileSdk/targetSdk: 34, minSdk: 26 (Android 8.0+)

### Python
- noisereduce ≥3.0.2
- pyloudnorm ≥0.1.1
- scipy ≥1.11.4
- soundfile ≥0.12.1
- pydub ≥0.25.1
- firebase-admin ≥6.5.0
- Python 3.11 (Docker base image)

---

## Support Contact

Bones — CEO, Infinite Signal Labs  
Missoula, MT  
Repository: `billybones031-ui/soundforge-clearwave`  
Branch: `claude/build-android-app-Dj7qv`
