# Jarvis Caller

A fast, minimal, voice-driven Android calling assistant built in Kotlin.

## Features
- Launches into a pure black minimal UI
- Speaks: "Who do you want to call?"
- Listens with Android SpeechRecognizer
- Loads contacts via ContactsContract
- Ranks matches using fuzzy Levenshtein similarity + call frequency + recency
- Calls immediately on high-confidence match
- Confirms with voice on medium-confidence match
- Shows tap-to-call list on low-confidence match

## Project Structure
```
JarvisCaller/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── README.md
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/jarviscaller/
        │   └── MainActivity.kt
        └── res/
            ├── layout/activity_main.xml
            └── values/
                ├── strings.xml
                └── themes.xml
```

## Build Instructions
1. Open this folder in **Android Studio** (Hedgehog or newer)
2. Let Gradle sync complete
3. Build APK: `Build > Build APK` or run `./gradlew assembleDebug`
4. APK output: `app/build/outputs/apk/debug/app-debug.apk`

## Install
1. Transfer APK to your Android phone
2. Enable "Install unknown apps" for your file manager
3. Tap the APK to install

## Permissions Required
- RECORD_AUDIO - microphone for voice input
- READ_CONTACTS - contact lookup
- CALL_PHONE - place calls
- READ_CALL_LOG - improve ranking by frequency/recency (optional)

## Tech Stack
- Language: Kotlin
- Min SDK: 26 (Android 8.0)
- Target SDK: 35
- Architecture: Single Activity, ViewBinding
- APIs: SpeechRecognizer, TextToSpeech, ContactsContract, CallLog

## License
MIT
