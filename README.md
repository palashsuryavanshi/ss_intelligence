# SS Intelligence

A privacy-first, **local-only** screenshot intelligence app for Android.

It discovers the screenshots already on your device, reads their text with on-device OCR,
extracts structured information (links, dates, phone numbers, prices, one-time codes),
detects exact duplicates, and makes all of it searchable — with the network switched off.

Phase 1 establishes the indexing foundation. Natural-language and semantic search are
deliberately out of scope; see [Roadmap](#roadmap).

---

## Table of contents

- [Privacy model](#privacy-model)
- [Requirements](#requirements)
- [Build and run](#build-and-run)
- [Tests](#tests)
- [Architecture](#architecture)
- [Permissions](#permissions)
- [OCR implementation](#ocr-implementation)
- [Database schema](#database-schema)
- [Indexing pipeline](#indexing-pipeline)
- [Search and ranking](#search-and-ranking)
- [Duplicate detection](#duplicate-detection)
- [Performance notes](#performance-notes)
- [Accessibility](#accessibility)
- [Logging](#logging)
- [Roadmap](#roadmap)

---

## Privacy model

The strongest guarantee in this app is structural, not a policy:

**The manifest does not declare `android.permission.INTERNET`.**

The app cannot upload screenshots or OCR text even if a future bug tried to, because the
platform will not grant it a network socket. There is no analytics SDK, no advertising SDK,
and no crash reporter.

| Property | How it is achieved |
|---|---|
| No network access | `INTERNET` permission absent from the manifest |
| No account | No auth code, no login screen, no third-party identity SDK |
| No cloud OCR | ML Kit model is **bundled** in the APK (`com.google.mlkit:text-recognition`), not downloaded via Play Services |
| OCR stays local | `InputImage.fromFilePath` is handed the MediaStore URI directly |
| Index stays local | Room database in app-private storage; excluded from backup |
| Originals untouched | The app only reads. It never writes, moves or deletes a screenshot |
| No analytics | No analytics, ads, or crash-reporting dependencies |

### Keeping `INTERNET` out

ML Kit's transitive dependencies declare `android.permission.INTERNET` for an optional
telemetry upload path. Two things keep it out of the shipped app:

1. `AndroidManifest.xml` removes it from the merged manifest with `tools:node="remove"`.
2. The `transport-backend-cct` artifact is **not** excluded, because ML Kit hard-links
   against `CCTDestination` and removing it crashes OCR at runtime with
   `ClassNotFoundException`.

The library stays on the classpath; the permission does not. Verify after any dependency
change:

```bash
./gradlew :app:assembleDebug
grep 'uses-permission android:name' \
  app/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml
```

The app's remaining permissions are `READ_MEDIA_IMAGES` plus WorkManager's
`WAKE_LOCK`, `ACCESS_NETWORK_STATE`, `RECEIVE_BOOT_COMPLETED` and `FOREGROUND_SERVICE`.

Extracted data is treated as sensitive local data throughout:

- OTP codes are **never** placed in notifications, share intents, logs, or list previews.
  They are masked in the UI until explicitly revealed.
- OCR text, phone numbers and URLs are never logged — not even in debug builds.
- `AppLog` is a no-op in release builds, and R4 strips `android.util.Log` entirely.

---

## Requirements

| | |
|---|---|
| Min SDK | 29 (Android 10) |
| Target / compile SDK | 37 |
| JDK | 17+ (Android Studio's bundled JBR works) |
| Gradle | 9.6.0 |
| AGP | 9.4.0 |

### Why minSdk 29

Screenshots are the primary target, and MediaStore exposes the stable columns this app
depends on (`RELATIVE_PATH`, `WIDTH`, `HEIGHT`) from Android 10 / API 29 onward. This also
lets the app ignore the pre-scoped-storage era entirely.

---

## Build and run

```bash
# from the project root
./gradlew assembleDebug        # APK -> app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug         # install onto a connected device/emulator
./gradlew assembleRelease      # minified + resource-shrunk release APK
```

The Gradle wrapper is committed, so `./gradlew` works without installing Gradle. It will
download Gradle 9.6.0 on first use.

`gradle.properties` deliberately does **not** set `org.gradle.java.home`: that would pin every
contributor and CI runner to one machine's JDK path. Set `JAVA_HOME` instead, or add your own
`org.gradle.java.home` to `~/.gradle/gradle.properties`.

Then launch **SS Intelligence**, tap **Get started**, and grant image access. The app scans
MediaStore and indexes in the background; you can keep using it while indexing runs.

### Toolchain notes

AGP 9 compiles Kotlin through its **built-in Kotlin support**. The root `build.gradle.kts`
declares the Kotlin Android plugin with `apply false` purely to pin the resolved Kotlin
version, which keeps KSP and the Compose compiler plugin in lockstep. Applying it in a module
is both unnecessary and an error under AGP 9.

Room is pinned to `androidx.room` 2.8.4 rather than the `androidx.room3` 3.x line. Both are
supported, but 2.8.4 is the well-established Android-native API surface and the code is
written against APIs that are unambiguous to verify; reliability matters more here than
adopting the newest major.

## Verified behaviour

Everything below was run on an emulator (API 36) rather than assumed:

- First launch → onboarding → permission → MediaStore discovery → 4 screenshots indexed
- **With wifi and mobile data disabled**: discovery, OCR and extraction all completed
  (`Active default network: none`)
- OCR text recognised; link, date and one-time-code extraction confirmed in the UI
- Detail screen showed the OTP masked (`••••••`) until the reveal control was activated
- A byte-identical copy of a screenshot was detected as an exact duplicate, grouped
  (2 copies, shared SHA-256 prefix) and **not** re-read — it reused the canonical OCR result
- Settings reported index size and offered Clear index behind a confirmation dialog
- 99 unit tests and 24 instrumented tests pass; release build succeeds under R8

---

## Tests

```bash
./gradlew testDebugUnitTest        # fast JVM tests, no device needed
./gradlew connectedDebugAndroidTest  # Room database tests, requires a device/emulator
```

**99 local unit tests** cover URL, price, phone, date and OTP extraction, content hashing,
screenshot heuristics and FTS query construction. Extraction logic is deliberately free of
Android dependencies so it is testable as plain JVM code.

**24 instrumented tests** cover the database: insert, update, delete, cascade behaviour, FTS
search, search ranking, filters, duplicate lookup, the pending queue, stale-work recovery,
incremental re-indexing, keyset pagination, index rebuild and OTP isolation from search.

One test is worth calling out because it guards a real bug found during development:
`searchMatchesMultipleTermsAsAnd` asserts both that multi-term search works *and* that the
explicit `AND` keyword does not. Room's `@Fts4` produces an FTS4 table, whose parser does not
accept FTS5-style `AND`; it silently matched nothing. The query builder uses the implicit
whitespace AND instead, and the test prevents that regression from coming back silently.

---

## Architecture

Clean, layered, with a strict dependency direction:

```
UI (Compose)
  ↓  ViewModel + StateFlow
Use cases
  ↓
Domain models + repository interfaces
  ↓
Repository implementations
  ↓
Local data sources (Room, MediaStore, DataStore)
```

Two things sit beside the layers, because they are cross-cutting capabilities rather than
steps in a flow:

- **`ml/`** — OCR and metadata extraction
- **`duplicate/`** — similarity detection

```
app/src/main/java/com/ssintelligence/app/
├── MainActivity.kt              single activity host
├── SsIntelligenceApp.kt         application + WorkManager config
├── ServiceLocator.kt            manual DI container
├── data/
│   ├── database/                entities, DAO, database
│   ├── media/                   MediaStore discovery + heuristics
│   └── repository/              Room- and DataStore-backed implementations
├── domain/
│   ├── model/                   framework-free models
│   ├── repository/              interfaces
│   ├── search/                  search engine interface
│   └── usecase/                 application operations
├── ml/
│   ├── ocr/                     ML Kit recognizer
│   └── extract/                 URL, phone, price, date, OTP extraction
├── duplicate/hashing/           similarity detector interface + SHA-256 impl
├── indexing/                    pipeline, workers, scheduler
├── search/                      FTS search engine + query builder
├── ui/                          home, browse, search, detail, duplicates, settings
└── util/                        logging
```

### Why a manual service locator

The graph is small and entirely local. A DI framework would add annotation processing and
build time without improving maintainability. `ServiceLocator` is lazy, so a cold start never
opens the database unless something needs it.

---

## Permissions

One permission, requested after a plain-language explanation:

| API level | Permission |
|---|---|
| 33+ (Android 13+) | `READ_MEDIA_IMAGES` |
| 29–32 (Android 10–12) | `READ_EXTERNAL_STORAGE` |

Not requested: contacts, location, microphone, camera, phone, SMS, storage-write, or internet.

Permission denial is a supported state, not an error: the app stays usable, explains the
situation, and offers a route to system settings.

---

## OCR implementation

ML Kit Text Recognition v2 with the **bundled** Latin-script model
(`com.google.mlkit:text-recognition`). The bundled model is the key privacy choice: it adds
roughly 4 MB per ABI but needs no download, so OCR works with the network disabled.

Pipeline:

```
MediaStore URI
   ↓  InputImage.fromFilePath   (ML Kit decodes and downscales internally)
ML Kit text recognition
   ↓
Text + blocks/lines + bounding boxes
   ↓
Normalized 0..1000 boxes, line-level only
```

Notes:

- **No intermediate image files.** The URI is passed straight to ML Kit, so no processed
  copies are written to disk.
- **No full-resolution bitmaps.** `InputImage.fromFilePath` lets ML Kit manage memory. The only
  `BitmapFactory` call reads *dimensions only* (`inJustDecodeBounds = true`) as a cheap
  pre-flight validity check.
- **Structure is preserved.** Line-level bounding boxes are stored in normalized coordinates
  so the schema is resolution independent and the table stays small. Block-level rows are
  discarded because they duplicate line text at roughly double the storage.
- **Confidence** is stored as `NULL` where the Latin model does not report it, rather than
  inventing a value.
- **Orientation, portrait, landscape, long and dark-mode captures** are handled by ML Kit.
- **OCR failure never fails indexing.** An unreadable image marks that row `FAILED` with a
  readable reason; the rest of the library continues.
- **`OutOfMemoryError` is caught** and reported as a per-row failure instead of crashing.

---

## Database schema

Room, version 1, with exported schemas in `app/schemas/`.

### `screenshots`

| Column | Notes |
|---|---|
| `id` | primary key |
| `media_store_id` | **unique** — the stable identity used for incremental indexing |
| `uri` | `content://` URI; the only reference to image data |
| `filename`, `relative_path` | display and discovery |
| `date_added`, `date_modified` | epoch seconds, matching MediaStore |
| `file_size`, `width`, `height`, `mime_type` | MediaStore metadata |
| `ocr_text` | recognised text |
| `content_hash` | SHA-256 of image bytes |
| `duplicate_of_id` | canonical row when this is an exact duplicate |
| `status` | `PENDING` / `PROCESSING` / `COMPLETED` / `FAILED` |
| `processing_error` | short, user-readable failure reason |
| `created_at`, `updated_at` | epoch millis |

Indexes: `media_store_id` (unique), `content_hash`, `(date_added, id)`, `date_modified`,
`status`, `duplicate_of_id`.

### Extracted information (normalized tables)

Extracted data lives in separate tables, not one JSON blob, so filtering and future joins
stay cheap and indexable.

| Table | Key columns |
|---|---|
| `extracted_urls` | `url`, `host` |
| `extracted_dates` | `raw_text`, `epoch_day`, `has_year` |
| `extracted_phones` | `raw_text`, `normalized`, `country` |
| `extracted_prices` | `raw_text`, `currency`, `amount` |
| `extracted_otps` | `code` |
| `ocr_blocks` | `level`, `text`, box coords, `confidence` |

All have an indexed `screenshot_id` foreign key with `ON DELETE CASCADE`, so deleting a
screenshot can never leave orphaned extracted rows. `extracted_prices` is additionally indexed
on `(currency, amount)` to make queries like `currency = 'INR' AND amount >= 30000` index-driven.

`extracted_otps` is deliberately **excluded from the full-text index**, so an OTP cannot be
surfaced by typing the code into the search box.

### Full-text search

`screenshots_fts` is an FTS4 **external-content** table over `filename` and `ocr_text`:

```kotlin
@Fts4(contentEntity = ScreenshotEntity::class)
@Entity(tableName = "screenshots_fts")
```

External content means SQLite stores only the inverted index, not a second copy of the OCR
text, and Room keeps it in sync automatically. The database does not double in size because
of search.

> **Implementation note.** `@Fts4` yields an FTS4 table, whose query parser does **not**
> accept the FTS5-style explicit `AND` keyword — `"pixel"* AND "9a"*` matches nothing at all,
> with no error. `FtsQueryBuilder` therefore combines terms with the *implicit* AND
> (plain whitespace), which the engine ANDs itself. An instrumented test asserts both halves of
> this so it cannot regress unnoticed.

---

## Indexing pipeline

```
MediaStore scan (metadata only)
   ↓
Reconcile with index          new → PENDING · changed → re-queued · gone → removed
   ↓
Batch of pending screenshots
   ↓
Validate URI
   ↓
SHA-256 content hash
   ↓
Exact duplicate? → reuse the canonical OCR result, skip OCR
   ↓
ML Kit OCR
   ↓
Extract URLs · Dates · Phones · Prices · OTPs   (each guarded independently)
   ↓
Single transaction: screenshot + geometry + all extracted tables
   ↓
COMPLETED
```

### Failure policy

A failure in any **one** extraction step does not fail the screenshot — the partial result is
still indexed and the degraded stage is logged. Only a failure to read or decode the image is
fatal, because without an image there is nothing to index.

### Incremental indexing

Nothing is re-OCR'd unless something changed:

| Situation | Behaviour |
|---|---|
| New media | Inserted as `PENDING`, processed |
| `date_modified` or `file_size` changed | Metadata refreshed, re-queued as `PENDING` |
| Unchanged and already `COMPLETED` | Untouched |
| Deleted from device | Row removed; extracted rows cascade away |

Reconciling by `media_store_id` means the app never assumes a filesystem path is stable —
paths change after moves, edits and cloud sync, but the `content://` URI does not.

### Background execution

Two `CoroutineWorker`s run as a single unique WorkManager chain:

- `DiscoveryWorker` — scans MediaStore and reconciles the index.
- `IndexingWorker` — processes queued screenshots.

Properties that matter:

- **Survives process death.** Rows left in `PROCESSING` by a killed process are re-queued on
  the next run.
- **Bounded resources.** 12 screenshots per batch, at most 2 decoded concurrently. This avoids
  RAM spikes and CPU/battery drain.
- **Cancellable.** Stop from Home or Settings; committed work stays indexed.
- **Offline by design.** `NetworkType.NOT_REQUIRED`.
- **Battery guard.** `requiresBatteryNotLow` and `requiresStorageNotLow`.
- **One bad screenshot never aborts a batch** — each is wrapped independently.

UI progress is derived from **database counters**, not worker progress data, so progress stays
correct after the app is killed and resumed.

---

## Search and ranking

Phase 1 search is full-text over OCR content plus the filename, using SQLite FTS4 `MATCH`
rather than `LIKE '%query%'` scans.

Query handling is case-insensitive and whitespace-tolerant. Terms are quoted and
prefix-matched, AND-ed, de-duplicated and capped at 10 terms so a pasted paragraph cannot
produce a pathological query.

### Ranking

Deterministic and index-friendly:

1. Exact filename match
2. Filename prefix match
3. Any other hit
4. Ties broken by recency

No ML model is involved, and the architecture leaves room for learned ranking later.

### Filters

`All`, `Links`, `Prices`, `Dates`, `Phones`, `Codes`, `Duplicates` — each a parameterized
`EXISTS` subquery against the relevant indexed table. No string interpolation into SQL.

### Future-proofing

`ScreenshotSearchEngine` is the seam for later phases:

```kotlin
interface ScreenshotSearchEngine {
    fun search(query: String, filter: SearchFilter, limit: Int): Flow<List<Screenshot>>
}
```

A Phase 2 structured-query implementation or a Phase 4 semantic implementation replaces this
interface only; no caller changes. `ScreenshotProcessor`, `ImageSimilarityDetector` and the
extractor interfaces are similarly open for extension.

---

## Duplicate detection

`ImageSimilarityDetector` is the abstraction; Phase 1 ships `ContentHashDetector`:

```kotlin
interface ImageSimilarityDetector {
    suspend fun fingerprint(uri: Uri): String
}
```

Implemented as **SHA-256 over the encoded image bytes**. Screenshots sharing a hash are
byte-identical, are grouped in the UI, and — importantly — only the earliest copy is
OCR'd; later copies reuse its extracted information.

The hashing rule itself lives in `ContentHasher`, free of Android types, so it is testable on
a plain JVM.

**Known limitation, by design:** hashing encoded bytes means two screenshots that look
identical but were saved with different encoders will not match. That is precisely the
near-duplicate case a `PerceptualHashDetector` (dHash/pHash) would catch in a later phase —
which is why the interface exists rather than a concrete hash call at the call site.

---

## Performance notes

Designed against 1,000 / 10,000 / 50,000+ screenshots:

- **Keyset pagination** (`WHERE date_added < ? OR (date_added = ? AND id < ?) ORDER BY ...`),
  so page N costs the same as page 1. `OFFSET` is not used for browsing.
- **Covering indexes** on every field in a filter or sort.
- **LazyColumn** everywhere; the screenshot browser never materializes the whole collection.
- **Downscaled thumbnails only.** Coil owns the disk and memory cache.
- **Bounded decode concurrency** (2 at a time) with batched work units.
- **Transactions** for all batch writes; discovery reconciliation is atomic.
- **Streams are always closed** (`use`), including on the error paths.
- **Debounced search** (250 ms) so typing runs one query, not one per keystroke.
- OCR geometry is line-level only, in normalized coordinates, to keep the table small.

---

## Accessibility

- Every screenshot row is a **single semantics node** with a spoken summary (filename, date,
  status, text excerpt, duplicate flag), so TalkBack reads a sentence rather than fragments.
- Images carry content descriptions; decorative images are explicitly `null`.
- Status is always spelled out in text ("Indexed", "Queued", "Reading text", "Not indexed"),
  never conveyed by colour alone.
- Progress bars expose a spoken progress description.
- Touch targets meet Material minimums; layouts tolerate dynamic font sizes.
- Full dark and light themes, plus Material 3 dynamic color on Android 12+.
- No colour-only meaning, no fixed text heights that clip at large font scales.

---

## Logging

Structured tags: `ScreenshotIndexer`, `OCRProcessor`, `MetadataExtractor`,
`ScreenshotRepository`, `DuplicateDetector`.

```
Good:  OCR completed screenshotId=123 characters=482 urls=2 prices=1 otps=0
Bad:   OCR RESULT: Your OTP is 839291
```

Logs carry **identifiers and counts only**. `AppLog` no-ops in release builds, and R4 removes
`android.util.Log` methods entirely, so sensitive values cannot leak through logs.

---

## Roadmap

Phase 1 deliberately stops here. Not implemented:

- Cloud OCR, Gemini/OpenAI/API integration, cloud database
- Natural-language and semantic search, vector database, image embeddings
- Automatic categorization, LLM query interpretation
- Cross-device sync, web dashboard, online accounts
- Automatic screenshot deletion

Planned, and the seams already exist for them:

| Phase | Feature | Where it plugs in |
|---|---|---|
| 2 | Structured query search (`"Pixel 9a for ₹39,999"`) | `ScreenshotSearchEngine` |
| 3 | Automatic categorization | new extractor stage in `ScreenshotProcessor` |
| 4 | Local semantic search | `ScreenshotSearchEngine`, new table |
| 5 | Merge duplicates, collections, timeline | `duplicate/` package, new tables |

Settings shows unimplemented toggles disabled with an explicit "planned for a later release"
note, rather than shipping no-op controls.
