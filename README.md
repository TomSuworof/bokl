# `BOKL`

A lightweight, offline ebook reader for Android built with Kotlin and Jetpack Compose.

> **Disclaimer:** This project was vibe-coded (generated with the assistance of AI coding tools). It may contain bugs, unexpected behavior, or unfinished features. Do not expect production-level stability — use it at your own risk.

## Features

- Read **EPUB** and **TXT** books from any folder on your device
- No ads, no tracking, fully offline
- Persistent reading progress per book
- Adjustable reader settings
- Page-turn navigation with curl effect
- Material 3 design with dynamic color support (Android 12+)
- System dark/light theme support

## Requirements

- Android 8.0 (API 26) or higher
- Android Studio (for building)

## Building

```bash
./gradlew assembleDebug
```

The APK will be generated at `app/build/outputs/apk/debug/app-debug.apk`.

## Running tests

```bash
./gradlew test
```

## Tech stack

- **Kotlin** + **Jetpack Compose** (Material 3)
- **jsoup** for EPUB/XHTML content parsing
- AndroidX ViewModel, Lifecycle, and Activity Compose
- Gradle Kotlin DSL

