# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

This is an Android app built with Gradle. Use Android Studio or the Gradle wrapper:

```bash
# Debug build
./gradlew assembleDebug

# Install on connected device/emulator
./gradlew installDebug

# Lint
./gradlew lint
```

The compiled debug APK is output to `app/build/outputs/apk/debug/app-debug.apk`.

**There is no `gradlew` wrapper in the repo root** — Gradle commands must be run from Android Studio's terminal (which sets up the wrapper path) or with a globally-installed Gradle.

There are no automated tests in the repository.

---

## Android App Architecture

Single-activity app (`minSdk 26`, `targetSdk 34`, Kotlin 17, no Jetpack Compose, no ViewModel/LiveData, no Hilt). Two activities:

- **`MainActivity`** — dialing queue. Loads contacts from an Excel file, renders them in a `RecyclerView`, sequences outbound calls via `Intent.ACTION_CALL`. Polls the backoffice server every 3 s for remote dial commands.
- **`SettingsActivity`** — configures auto-call delay and the backoffice server URL.

### Data flow

1. User picks an `.xlsx`/`.xls` file (file picker) or browses a previously-granted folder URI.
2. Apache POI parses the sheet; rows become `Contact` objects (`id`, `name`, `phone`, `status`, `notes`, `calledAt`, `source`).
3. `ContactAdapter` renders the list; `currentIndex` in `MainActivity` tracks which contact is next.
4. Tapping the FAB triggers `dialCurrent()` → `Intent.ACTION_CALL` → system phone app.
5. On return, a disposition dialog captures the outcome; the result is written back to the contact and re-exported to `<originalName>_results.xlsx` in the granted folder via Apache POI.
6. If "Auto Call" is checked, `scheduleAutoCall()` runs a recursive `Handler.postDelayed` countdown (1-s ticks) and shows a cancellable `Snackbar`.

### Key persistence details

- `SharedPreferences` key `"dialer_state"` stores `contacts` (JSON via Gson), `currentIndex`, `lastFileUri`, `lastFileName`, `folderUri`, `autoCall`, `autoCallDelay`, `serverUrl`, and `deviceId` (stable UUID).
- Folder and file URI permissions are held with `takePersistableUriPermission` so they survive reboots.
- Results export uses the original file (`template.$ext` copied to `getExternalFilesDir(null)/`) as a base, appending Status / Notes / CalledAt columns. This is required because POI cannot stream `.xlsx` from a `ContentResolver` URI directly.
- Results overwrite `<name>_results.xlsx` in the same folder on every disposition save.

### Excel column detection

The loader recognises English headers (`Name`, `Phone`, `Number`) and Portuguese headers (`Nome`, `Telefone`, `Número`) — all case-insensitive. Numeric phone cells are cast via `toLong().toString()` to strip the decimal point.

### Contact source tracking

`Contact.source` is a non-null `String` (default `""`). Defined values:

| Value | Origin |
|---|---|
| `"app_excel"` | Loaded from device file picker |
| `"app_manual"` | Added via in-app Add Contact dialog |
| `"backoffice_excel"` | Excel uploaded through backoffice UI |
| `"backoffice_manual"` | Added via `POST /api/contacts/set` |
| `"remote_dial"` | Temporary contact created when backoffice dials a number not in the local list |

The `sanitize()` extension gives contacts persisted before `source` was added an empty string, preventing crashes on deserialization.

### Async patterns

All network calls (polling, result reporting) use bare `Thread { }` blocks posting back to the main thread via `Handler.post()`. There are no coroutines, no OkHttp/Retrofit — only `java.net.HttpURLConnection`. Errors are silently swallowed with `catch (_: Exception) {}`.

### Theming / dark mode

The theme is `Theme.MaterialComponents.DayNight.NoActionBar` — dark mode is fully system-driven, no programmatic toggle. Light and dark color palettes live in `values/colors.xml` and `values-night/colors.xml` respectively. The header uses `@drawable/header_gradient` (with a `drawable-night/` variant) on a plain `LinearLayout` — no Toolbar/AppBar.

**Hard-coded colors that do NOT adapt to dark mode:** status text in `ContactAdapter.onBindViewHolder` (`Color.parseColor()` literals) and source chip backgrounds in `showContactProfileDialog`. Phone number text (`#78909C`) in `item_contact.xml` is also hard-coded. Everything else uses theme color resources.

### APK packaging note

`app/build.gradle` excludes several `META-INF/` files (`DEPENDENCIES`, `LICENSE*`, `NOTICE*`) from the APK — this is required to avoid duplicate-file build errors from Apache POI's transitive dependencies.

### File sharing

A `FileProvider` (authority `${packageName}.fileprovider`) exposes `getExternalFilesDir(null)` so the results XLSX can be shared via `Intent.ACTION_SEND`.

---

## Backoffice

A Node.js/Express web server with a single-page browser UI for managing contacts and remotely triggering calls.

**Live URL:** `https://autodialer-os6o.onrender.com`

Deployed on Render (free tier, Docker). Auto-deploys from `main`. **Render uses `backoffice/server/Dockerfile`** (with `dockerContext: backoffice/server` in `render.yaml`) — not the root `Dockerfile`. The root Dockerfile correctly copies `backoffice/index.html` into `public/`; the server Dockerfile does not, so in production the UI is served from the server's fallback path.

### Running locally

```bash
cd backoffice/server
npm install
node index.js        # server at http://localhost:3000
```

Set the Android app's Server URL (Settings) to the machine's LAN IP, e.g. `http://192.168.1.247:3000`. Windows Firewall must allow inbound TCP on port 3000.

### Server state

All state lives in `backoffice/server/state.js` — a plain CommonJS export mutated directly by route handlers. Three fields:

- `sessionContacts` — array of contact objects for the current session
- `pendingDial` — `{ contactId, name, phone, queuedAt }` or `null`
- `pendingContactsSync` — `{ contacts, setAt }` or `null`

State is **in-memory and resets on server restart**. The Render free tier spins down after 15 min of inactivity, but the Android app's polling keeps it alive.

### API routes

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/status` | Health check |
| `POST` | `/api/contacts/upload` | Upload an Excel file (via `multer` + `xlsx` npm package); returns parsed contact list |
| `POST` | `/api/contacts/set` | Set session contacts directly from a JSON body (source: `backoffice_manual`) |
| `GET` | `/api/contacts` | Return current session contact list |
| `PATCH` | `/api/contacts/:id` | Update a contact's status/notes |
| `POST` | `/api/dial` | Queue a dial command `{ contactId, name, phone }` |
| `GET` | `/api/dial/next?deviceId=<uuid>` | Android polls this; returns and clears `pendingDial` |
| `POST` | `/api/dial/result` | Android reports call outcome |
| `POST` | `/api/device/contacts` | Android pushes its loaded contact list (keyed by `deviceId`) to `deviceContacts` |
| `GET` | `/api/device/contacts` | Returns all `{ deviceId, contacts, syncedAt }` entries |
| `POST` | `/api/device/push` | Backoffice pushes contacts to all devices via `pendingContactsSync` |

### Polling architecture

The Android app polls `GET /api/dial/next?deviceId=<uuid>` every 3 s while `MainActivity` is in the foreground (`onResume` / `onPause`). The server also delivers `pendingContactsSync` on this same endpoint response, so a single poll handles both dial commands and contact-list pushes.

### Future: multi-agent login

The `deviceId` UUID is already sent with every poll request, laying the groundwork for routing commands to specific phones. When multiple agents use the app:
1. Associate `deviceId` with a user identity on the server.
2. Have `POST /api/dial` accept a `targetDeviceId`.
3. `GET /api/dial/next` only returns commands addressed to the polling device.

### Localization

The app is bilingual. Default strings (`values/strings.xml`) are Portuguese; English strings are in `values-en/strings.xml`. Exported Excel column headers and status labels are also localized — export files reflect the device language.
