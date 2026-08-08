# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

This is an Android app built with Gradle. Use Android Studio or the Gradle wrapper:

```bash
# Debug build
./gradlew assembleDebug

# Install on connected device/emulator
./gradlew installDebug

# Run tests
./gradlew test

# Lint
./gradlew lint
```

The compiled debug APK is output to `app/build/outputs/apk/debug/app-debug.apk`.

## Architecture

Single-activity Android app (`minSdk 26`, `targetSdk 34`, Kotlin) with no navigation library — just two activities:

- **`MainActivity`** — the main dialing queue. Loads contacts from an Excel file, displays them in a `RecyclerView`, and sequences outbound calls via `Intent.ACTION_CALL`. State (contacts list + `currentIndex`) is persisted to `SharedPreferences` as JSON via Gson so the queue survives process death.
- **`SettingsActivity`** — configures the auto-call delay and any other user preferences.

### Data flow

1. User picks an `.xlsx`/`.xls` file (file picker) or browses a previously-granted folder URI.
2. Apache POI parses the sheet; rows become `Contact` objects (fields: `id`, `name`, `phone`, `status`, `notes`, `calledAt`).
3. `ContactAdapter` renders the list; `currentIndex` tracks which contact is next.
4. Tapping the FAB triggers `dialCurrent()` → `Intent.ACTION_CALL` → system phone app.
5. On return, a disposition dialog captures the call outcome; result is written back to the contact and the list is re-exported to `<originalName>_results.xlsx` in the granted folder via Apache POI (`XSSFWorkbook`).
6. If "Auto Call" is checked, `scheduleAutoCall()` uses a `Handler` countdown and shows a cancellable `Snackbar` before dialing the next contact.

### Key persistence details

- `SharedPreferences` key `"dialer_state"` stores `contacts` (JSON), `currentIndex`, `lastFileUri`, `lastFileName`, `folderUri`, `autoCall` (bool), and `autoCallDelay` (int seconds).
- Folder and file URI permissions are persisted with `takePersistableUriPermission` so they survive reboots.
- Results export overwrites `<name>_results.xlsx` in the same folder on every disposition save.

### Excel column detection

The loader scans the header row for a column named `"Name"` (case-insensitive) and one containing `"Phone"` or `"Number"`. Numeric phone cells are cast via `toLong().toString()` to strip the decimal.
