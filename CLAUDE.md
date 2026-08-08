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

Note: there is no `gradlew` wrapper script in the repo root — builds must be run from Android Studio.

## Architecture

Single-activity Android app (`minSdk 26`, `targetSdk 34`, Kotlin) with no navigation library — just two activities:

- **`MainActivity`** — the main dialing queue. Loads contacts from an Excel file, displays them in a `RecyclerView`, and sequences outbound calls via `Intent.ACTION_CALL`. State (contacts list + `currentIndex`) is persisted to `SharedPreferences` as JSON via Gson so the queue survives process death. Also polls the backoffice server every 3 seconds for remote dial commands.
- **`SettingsActivity`** — configures the auto-call delay, and the backoffice server URL.

### Data flow

1. User picks an `.xlsx`/`.xls` file (file picker) or browses a previously-granted folder URI.
2. Apache POI parses the sheet; rows become `Contact` objects (fields: `id`, `name`, `phone`, `status`, `notes`, `calledAt`).
3. `ContactAdapter` renders the list; `currentIndex` tracks which contact is next.
4. Tapping the FAB triggers `dialCurrent()` → `Intent.ACTION_CALL` → system phone app.
5. On return, a disposition dialog captures the call outcome; result is written back to the contact and the list is re-exported to `<originalName>_results.xlsx` in the granted folder via Apache POI (`XSSFWorkbook`).
6. If "Auto Call" is checked, `scheduleAutoCall()` uses a `Handler` countdown and shows a cancellable `Snackbar` before dialing the next contact.

### Key persistence details

- `SharedPreferences` key `"dialer_state"` stores `contacts` (JSON), `currentIndex`, `lastFileUri`, `lastFileName`, `folderUri`, `autoCall` (bool), `autoCallDelay` (int seconds), `serverUrl` (string), and `deviceId` (UUID).
- Folder and file URI permissions are persisted with `takePersistableUriPermission` so they survive reboots.
- Results export overwrites `<name>_results.xlsx` in the same folder on every disposition save.

### Excel column detection

The loader scans the header row for a column named `"Name"` (case-insensitive) and one containing `"Phone"` or `"Number"`. Numeric phone cells are cast via `toLong().toString()` to strip the decimal.

---

## Backoffice

A Node.js/Express web server with a browser UI for managing and remotely triggering calls.

**Live URL:** `https://autodialer-os6o.onrender.com`

Deployed on Render (free tier, Docker runtime). Auto-deploys from the `main` branch. The `Dockerfile` is at the repo root; it copies `backoffice/server/` as the app and `backoffice/index.html` into `public/`.

### Running locally

```bash
cd backoffice/server
npm install
node index.js        # server at http://localhost:3000
```

The backoffice UI is served as a static file from the same Express process. Set the Android app's Server URL (in Settings) to the machine's LAN IP, e.g. `http://192.168.1.247:3000`. Windows Firewall must allow inbound TCP on port 3000.

### Backoffice API

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/status` | Health check |
| `POST` | `/api/contacts/upload` | Upload an Excel file; returns parsed contact list |
| `GET` | `/api/contacts` | Return current session contact list |
| `PATCH` | `/api/contacts/:id` | Update a contact's status/notes |
| `POST` | `/api/dial` | Queue a dial command `{ contactId, name, phone }` |
| `GET` | `/api/dial/next?deviceId=<uuid>` | Android app polls this; returns and clears the pending command |
| `POST` | `/api/dial/result` | Android app reports call outcome |

### Polling architecture

The Android app polls `GET /api/dial/next?deviceId=<uuid>` every 3 seconds while `MainActivity` is in the foreground (`onResume` / `onPause`). Each device generates a stable UUID on first run (stored in `SharedPreferences` as `deviceId`). When the backoffice queues a command via `POST /api/dial`, the next poll picks it up, matches the phone number against the local contact list, and triggers `dialCurrent()`.

**Note:** server state (contact list, pending dial) is in-memory and resets on restart. The free Render tier spins down after 15 min of inactivity, but the app's polling keeps it alive while any agent's phone has the app open.

### Future: multi-agent login

The `deviceId` UUID is already sent with every poll request, laying the groundwork for routing commands to a specific phone. When multiple agents use the app, a login system should:
1. Associate `deviceId` with a user identity on the server
2. Have `POST /api/dial` accept a `targetDeviceId` so the backoffice can address a specific phone
3. `GET /api/dial/next` only returns commands addressed to the polling device's `deviceId`
