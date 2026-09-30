# SS Intelligence

A privacy-first, **local-only** screenshot intelligence app for Android.

It discovers the screenshots already on your device, reads their text with on-device OCR,
extracts structured information (links, dates, phone numbers, prices, one-time codes),
detects exact duplicates, and makes all of it searchable — with the network switched off.

Search understands a sentence rather than a keyword:

> Find the screenshot where I saw Pixel 9a for ₹39,999

is parsed into `Pixel 9a` + `INR 39,999`, used as two independent constraints, and the
screenshots satisfying both are ranked first. Prices, dates, domains, phone numbers and
one-time codes are all understood in plain words. Everything runs on-device; there is no
model, no account and no network permission.

Phase 3 would add optional local semantic search. It is deliberately out of scope; the
seam for it already exists. See [Roadmap](#roadmap).

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
`ACCESS_NETWORK_STATE` is worth a note: it can only *read* connectivity state, which
WorkManager needs for its constraints, and without `INTERNET` it grants no ability to open a
socket or send anything. Reading the state is not the same as using the network.

Extracted data is treated as sensitive local data throughout:

- OTP codes are **never** placed in notifications, share intents, logs, or list previews.
  They are masked in the UI until explicitly revealed.
- OCR text, phone numbers and URLs are never logged — not even in debug builds.
- **Search queries are treated as sensitive too.** A query is often the one string a person
  would least want retained, so the search layer logs nothing at all, code values never reach
  autocomplete or history, and history is off by default.
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

Everything below was run on an emulator (API 36 / SDK 37) rather than assumed:

- First launch → onboarding → permission → MediaStore discovery → 83 screenshots indexed
- **With wifi and mobile data disabled**: discovery, OCR and extraction all completed
  (`Active default network: none`)
- OCR text recognised; link, date and one-time-code extraction confirmed in the UI
- Detail screen showed the OTP masked (`••••••`) until the reveal control was activated
- A byte-identical copy of a screenshot was detected as an exact duplicate, grouped
  (2 copies, shared SHA-256 prefix) and **not** re-read — it reused the canonical OCR result
- Settings reported index size and offered Clear index behind a confirmation dialog
- Search understood a sentence on a real library: `prices under Rs 40000` was shown as
  **“Searching for: up to ₹40,000”** and returned the one screenshot with an INR price at or
  below that amount — no relaxation, no stray search terms
- A query with no exact match showed the relaxation notice rather than silently widening
- The debug inspector reported intent, terms, prices, candidate count, relaxation level and
  per-result scores for the same query
- 257 unit tests and 48 instrumented tests pass; release build succeeds under R8

Five bugs were found only by running this on a device, and all are now fixed and covered by
regression tests:

1. A currency *word* between the operator and the digits (`under Rs 40000`) hid the operator,
   so the query degraded to an exact-price search and "under" leaked into the search terms.
2. `LIKE '%domain%'` on the full URL matched `notamazon.in` when searching for `amazon.in`.
3. A currency *mismatch* scored as a *perfect* price match, so a `$1,299` screenshot ranked
   top for `₹1,299`.
4. An empty content-type selection serialized to `"|"` rather than `""`, which matched no
   filter and hid every row — every unfiltered search returned nothing.
5. `kotlinx-serialization` resolved to 1.7.3 while Room 2.8.4's migration code needs 1.8.1;
   the mismatch only surfaces as an `AbstractMethodError` at runtime, including during a real
   database migration on a user's device.

---

## Tests

```bash
./gradlew testDebugUnitTest        # fast JVM tests, no device needed
./gradlew connectedDebugAndroidTest  # Room + full search engine, requires a device/emulator
```

**257 local unit tests** cover URL, price, phone, date and OTP extraction, content hashing,
screenshot heuristics, FTS query construction, and the whole Phase 2 search layer: the
query parser and each of its sub-parsers, intent classification, the ranker, snippet
extraction and currency rendering. The search layer is deliberately free of Android
dependencies so it is testable as plain JVM code.

**48 instrumented tests** cover the database (insert, update, delete, cascade behaviour, FTS
search, search ranking, filters, duplicate lookup, the pending queue, stale-work recovery,
incremental re-indexing, keyset pagination, index rebuild and OTP isolation from search), the
v1→v2 schema migration, and the complete search engine against a real SQLite engine —
keyword, phrase, price, price-operator, combined, date, URL, phone, code, duplicate, filter,
sort and deletion cases, plus a 10,000-row performance suite.

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
| `search_history` | `query`, `created_at` (opt-in, Phase 2) |

All have an indexed `screenshot_id` foreign key with `ON DELETE CASCADE`, so deleting a
screenshot can never leave orphaned extracted rows. `extracted_prices` is additionally indexed
on `(currency, amount)` to make queries like `currency = 'INR' AND amount >= 30000` index-driven,
and `extracted_phones` on `normalized` and `extracted_urls` on `host`, which is what lets a
price, phone or domain search be a single indexed lookup rather than a scan.

`extracted_otps` is deliberately **excluded from the full-text index**, so an OTP cannot be
surfaced by typing the code into the search box. It is still *searchable* when the user types a
code deliberately with a label — the value is matched, then discarded before it reaches the UI.

`search_history` holds only the query text and a timestamp, never result ids, is capped at 25
rows on every write, and is empty unless the user opted in. It is not indexed for search and
is never synced anywhere.

### Schema version

`version = 2`, with an explicit `MIGRATION_1_2` adding `search_history`. There is deliberately
**no destructive fallback**: silently dropping a user's index because a version changed is
exactly the failure mode the explicit "Clear index" control exists to avoid. A migration test
validates the migrated schema against the exported `2.json`, so a hand-written `CREATE TABLE`
that drifts from the entity definition fails in CI rather than on a user's device.

> **Implementation note.** `kotlinx-serialization-json` is pinned explicitly. Room 2.8.4's
> migration code is compiled against 1.8.1 while Gradle's consistent resolution was choosing
> 1.7.3; the mismatch only surfaces as an `AbstractMethodError` at runtime — including during a
> real database migration on a user's device.

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

Search is a pipeline, not a query string:

```
User Query
    ↓  QueryParser
SearchQuery { text terms, phrases, prices, date range, domain, phone, code, content types }
    ↓  bounded SQL retrieval (FTS4 MATCH + EXISTS on the normalized tables)
candidate window (≤ 400 rows)
    ↓  SearchRanker
ranked results, each with the reasons it matched
```

**The raw sentence never reaches SQLite.** It is parsed once into `SearchQuery`, and the
database only ever sees derived parts: FTS terms, a price band, a date window, a host, a
normalized number. That is what makes queries indexable and results explainable.

### What the parser understands

| You type | Becomes |
|---|---|
| `Pixel 9a` | phrase `pixel 9a` (recall terms `pixel`, `9a`) |
| `for ₹39,999` / `Rs 40000` / `39999 rupees` | `INR 39,999` / `INR 40000` / `INR 39999` |
| `below` `under` `less than` `up to` `<` | price ≤ amount |
| `above` `over` `greater than` `>` | price ≥ amount |
| `around` `approximately` `about` | price within **±5%** |
| `between ₹10,000 and ₹20,000` | price range |
| `from September 2026` | 1–30 Sep 2026, local midnight boundaries |
| `yesterday`, `last week`, `last 7 days`, `last month` | local date range |
| `amazon.in`, `https://www.amazon.in/deals` | host `amazon.in` |
| `9876543210`, `+91 98765 43210` | `+919876543210` |
| `OTP 483921` | code `483921` (matched, never displayed) |
| `show duplicate screenshots` | content filter, not the word "duplicate" |

Several parsers are **deliberately reluctant**, because a wrong structured filter silently
removes the right answer:

- A bare number is not a price. `Order ID: 39999` stays a number; a price needs an operator,
  a currency marker, or an explicit word (`price`, `cost`, `for`, `at`).
- A bare number is not a date. `20260930` needs separators, a month name, or a 19xx/20xx
  shape **with a preposition** — so `iPhone 2026` is a model, not a year.
- A number is not a one-time code without a label. `987654` stays a number; `OTP 987654` is a
  code. Codes are 4–8 digits, so a 10-digit phone can never be mistaken for one.
- Currency is normalized but **never converted**. `$1,299` does not satisfy `₹1,299`.
- `May` is only a month with a day number or a year, because "may" is also a modal verb.

Parser order is itself meaningful: date → URL → code → phone → price → content type →
keywords. Each step claims its span of the query, so `₹39,999` becomes a price filter and
never also a search term, and `from September` never leaves "from" behind for the price parser
to misread.

### Ranking

Deterministic, model-free, and configurable in one value object (`RelevanceWeights`):

| Signal | Points |
|---|---|
| Exact phrase in OCR | 100 |
| Every term present | 60 |
| Most terms present (≥60%) | 40 |
| Price exact / inside an "around" band | 50 |
| Price near the band | 30 × closeness |
| Domain (host or subdomain) | 40 |
| Phone number (exact only) | 50 |
| One-time code (exact only) | 50 |
| Date window | 30 |
| Filename hit | 20 |
| Any OCR hit | 10 |
| Recency | ≤ 6 |

Dimensions add, so a screenshot matching both the phrase and the price outranks one matching
only the phrase. Phrase matching also checks a whitespace-stripped copy of the OCR text,
because OCR puts a line break wherever the image had one. Ties break by recency, then by row
id, so the order is total and never changes between runs.

**Scores are never shown to the user.** There is no probability model behind them, so a
"92% match" badge would be a fabrication. The UI shows the *reasons* instead — "Matched:
Pixel 9a, ₹39,999" — and the debug inspector shows the numbers.

### Relaxed search

A query is tried at full strictness first. Only when that returns nothing is one constraint
dropped at a time, and the rung that produced the answer is stated in plain language:

| Rung | Change | What the user sees |
|---|---|---|
| `EXACT` | — | (nothing) |
| `PRICE_APPROXIMATE` | exact price widens to ±5% | "No exact match. Showing prices close to what you asked for." |
| `TEXT_ONLY` | price dropped | "No exact match. Showing screenshots matching your words." |
| `ANY_TERM` | terms OR-ed | "Showing screenshots matching any of your words." |
| `FILTERS_ONLY` | text dropped | "Showing screenshots that match your filters only." |

Silently weakening a query would misrepresent what was asked for, so the notice is part of
the contract rather than decoration.

### Filters, sorting, history

Filter chips (`All`, `Prices`, `Dates`, `Links`, `Numbers`, `Codes`, `Duplicates`) and the
refine sheet (price range, currency, relative date, "has a code", "duplicates only") are
single parameterized queries — no SQL is built from user input. `Relevance`, `Newest` and
`Oldest` are all available; relevance is the default.

Search history is **off by default**. Nothing is written unless the user opts in, turning it
off deletes what was stored, and queries that look like they carry a one-time code are never
stored even when it is on. Only the query text and a timestamp are kept — never result ids,
so a history row cannot be used to reconstruct what was found.

### Autocomplete

Suggestions come only from data already on the device: recent searches, hosts seen in indexed
screenshots, and short phrases mined from OCR text by matching the typed prefix against the
local FTS index. The prefix never leaves the process and is never sent anywhere.

### Debugging

Debug builds have a **search inspector** behind a long press on the "Search" title: the raw
query, everything the parser understood, the candidate count, the relaxation level, and each
result's score with the reasons behind it. A release build has no click handler and no
registered route, so it is not merely hidden — it does not exist.

### Future-proofing

```kotlin
interface ScreenshotSearchEngine {
    suspend fun search(query: String): List<SearchResult>
    suspend fun search(request: SearchRequest): SearchResponse
    fun observe(request: SearchRequest): Flow<SearchResponse>
    fun suggestions(prefix: String): Flow<List<SearchSuggestion>>
}

interface SemanticSearchProvider {
    val isEnabled: Boolean
    suspend fun search(query: String, candidates: List<Long>): List<Long>
}
```

`SemanticSearchProvider.Disabled` is the only implementation, and the fusion step that would
consume it is already in place — semantic search is an *addition* to the deterministic engine,
never a replacement for it. `ScreenshotProcessor`, `ImageSimilarityDetector` and the extractor
interfaces are similarly open for extension.

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

- **Keyset pagination** (`WHERE date_added < ? OR (date_added = ? AND id = ?) ORDER BY ...`),
  so page N costs the same as page 1. `OFFSET` is not used for browsing.
- **A bounded candidate window**, not a full scan: SQL narrows the library to ≤ 400 rows and
  only those are scored. Ranking the whole library in memory is exactly what this avoids.
- **Exact-phrase hits are unioned into the candidate set**, so a precise but old match cannot
  be crowded out of the window by newer partial ones.
- **Covering indexes** on every field in a filter or sort. Date filters compare **epoch
  seconds** — the unit of `date_added` — so converting in Kotlin rather than multiplying the
  column in SQL is what keeps the `(date_added, id)` index usable.
- **Domain matching is host equality or a subdomain**, not a substring match on the URL.
- **LazyColumn** everywhere; the screenshot browser never materializes the whole collection.
- **Downscaled thumbnails only.** Coil owns the disk and memory cache.
- **Bounded decode concurrency** (2 at a time) with batched work units.
- **Transactions** for all batch writes; discovery reconciliation is atomic.
- **Streams are always closed** (`use`), including on the error paths.
- **Debounced search** (250 ms) so typing runs one query, not one per keystroke.
- OCR geometry is line-level only, in normalized coordinates, to keep the table small.

Measured on an API 37 emulator over a synthetic **10,000-row** library (the budget asserted in
`searchStaysResponsiveOverTenThousandScreenshots` is 2 s per query):

| Query | Time |
|---|---|
| `screenshots from last week` | 383 ms |
| `Pixel` | 800 ms |
| `Pixel 9a` (phrase) | 952 ms |
| `₹39,999` | 854 ms |
| `phones below ₹40,000` | 901 ms |
| `Pixel 9a for ₹39,999` | 1,157 ms |
| `screenshots from amazon.in` | 711 ms |

These are emulator numbers on a debug build, and they are dominated by the FTS `MATCH` plus
the `ORDER BY` over the matched set, not by ranking. The honest caveat: the candidate window
means a very old exact match can be missed in a library far larger than the window, which is
a deliberate trade for bounded memory and predictable latency. Paging (Paging 3) is the
intended next step for that.

---

## Accessibility

- Every screenshot row is a **single semantics node** with a spoken summary (filename, date,
  status, text excerpt, duplicate flag), so TalkBack reads a sentence rather than fragments.
- Images carry content descriptions; decorative images are explicitly `null`.
- Status is always spelled out in text ("Indexed", "Queued", "Reading text", "Not indexed"),
  never conveyed by colour alone.
- Progress bars expose a spoken progress description.
- The search field announces what it understands ("Prices, dates, links and phone numbers in a
  sentence are understood"), and the match chips are announced as one phrase — "Matched: Pixel
  9a, ₹39,999" — rather than as a row of disconnected labels.
- **Matched text in a snippet is emphasised with weight, not colour**, so it survives dark
  mode and is never the only signal.
- Results never show a fabricated percentage; reasons are shown instead.
- Search state is explicit (`Idle`, `Searching`, `Results`, `NoResults`, `Error`,
  `DatabaseUnavailable`) so the screen is never blank and never says "no results" while still
  searching.
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

A search query is treated as sensitive too: the search layer logs nothing at all, a failed
search shows a fixed message rather than the underlying database error (which can contain SQL
and file paths), and the only place a query is ever persisted is the opt-in history table.

---

## Roadmap

Deliberately **not** implemented, in any phase:

- Cloud OCR, Gemini/OpenAI/API integration, cloud database, cloud embeddings
- Automatic image descriptions, LLM-generated summaries, LLM query interpretation
- Vector database, cross-device sync, web dashboard, online accounts, online backup
- Automatic screenshot deletion

The deterministic search engine is the product. AI is an *optional* addition to it, never a
replacement, so the app stays completely functional with no model present.

| Phase | Feature | Where it plugs in | Status |
|---|---|---|---|
| 1 | Local indexing, OCR, extraction, duplicates, keyword search | — | done |
| 2 | Structured query search, ranking, relaxed fallback, suggestions | — | done |
| 3 | Local semantic search (optional, on-device embeddings) | `SemanticSearchProvider` + the fusion step in `LocalSearchEngine` | seam ready, `Disabled` by default |
| 4 | Automatic categorization | new extractor stage in `ScreenshotProcessor` | not started |
| 5 | Merge duplicates, collections, timeline | `duplicate/` package, new tables | not started |
| 6 | Paging 3 over search results | `SearchRequest.limit` → `Pager` | not started |

Settings shows unimplemented toggles disabled with an explicit "planned for a later release"
note, rather than shipping no-op controls.
