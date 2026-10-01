# SS Intelligence

A privacy-first, **local-only** screenshot intelligence app for Android.

It discovers the screenshots already on your device, reads their text with on-device OCR,
extracts structured information (links, dates, phone numbers, prices, one-time codes),
detects exact duplicates, and makes all of it searchable — with the network switched off.

Search understands a sentence rather than a keyword list:

> Find the screenshot where I saw Pixel 9a for ₹39,999

is parsed into `Pixel 9a` + `INR 39,999`, used as two independent constraints, and the
screenshots satisfying both are ranked first. Prices, dates, domains, phone numbers and
one-time codes are all understood in plain words. Everything runs on-device; there is no
model, no account and no network permission.

Phase 3 adds meaning: a query like `travel booking` finds flight tickets and hotel
reservations even when neither word appears in the screenshot, through a built-in local
semantic index — still with no download, no network permission, and no account. Screenshots
are also categorized, summarized, grouped into smart collections, and linked to related
screenshots, all on-device.

Phase 4 adds sight and structure. Search understands palette and shape — `blue screenshots`,
`long screenshots`, or pick a picture and find the ones that look like it. A local knowledge
graph turns extracted text into entities you can browse: everything about a product, the
prices you actually saw with it, the websites it appeared on. Screenshots gain a timeline
with events and sequences, manual collections, side-by-side comparison, and a
near-duplicate view. The visual index is a perceptual hash and a set of geometric rules —
no model to download, no permission added, still no network.

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
- [Visual intelligence](#visual-intelligence)
- [Knowledge graph](#knowledge-graph)
- [Organization](#organization)
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
| No cloud AI | The semantic index is **built in**: hashed text embeddings plus a curated concept graph, no model file, no download, no inference server |
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
- Meaning-based search verified on-device: `Build meaning index` embedded 82 screenshots in
  ~5 seconds; a `travel booking` query with no travel content answered honestly with no
  results instead of the whole library; the detail page showed an extractive summary,
  categories and related screenshots; Settings reported `hashed-ngram v1` with per-row
  coverage and the privacy dashboard stated each guarantee with its mechanism
- Phase 4 verified on-device: `blue screenshots` returned only the screenshot whose extracted
  palette contains blue, labelled **“Blue tones”**; `long screenshots` returned only the tall
  stitched capture; pinning a screenshot as the search image ranked 25 lookalikes first on
  pixels alone — all DuckDuckGo settings pages labelled “Very similar”, with the pinned image
  itself correctly stepping aside; the Explore screen listed filed entities with reference
  counts; an entity page showed both screenshots mentioning `termux.pro` with its websites and
  categories; Settings reported a measured storage breakdown (328 KB screenshots + extracted,
  92 KB text index, 332 KB text vectors, 12 KB image data, 60 KB graph, models
  `built in (0 B)`) adding up inside a 1.3 MB database
- 365 unit tests and 90 instrumented tests pass; release build succeeds under R8 with no
  `INTERNET` permission

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

Phase 3 added three more device-only findings:

6. FTS4 silently rejects a prefix query combined with `OR` (`"a"* OR "b"*` matches nothing);
   the relaxed rung and the semantic prefilter silently returned nothing until the OR form
   dropped the prefix star.
7. A rebuild worker looped forever on textless screenshots: an empty string can never be
   embedded, so those rows stayed "stale" permanently while the progress counter climbed
   past 5,000 for 83 screenshots. Stale selection now excludes blank OCR, and the worker
   carries a circuit breaker.
8. An unfiltered `FILTERS_ONLY` rung answered text queries with the whole library disguised
   as results, masking the semantic fallback. A text query with no constraints left now
   tries meaning first, and answers no-results honestly if that fails too.

Phase 4 added six more, all found by running it on a real library rather than by reasoning
about the code:

9. Adding the same screenshot to a collection twice filed two membership rows, so counts and
   member lists double-counted it. A unique `(collection_id, screenshot_id)` index makes
   re-adding a no-op by construction.
10. Search-by-image ranked the query image itself first — it is, after all, maximally similar
    to itself. It now steps aside; every other row keeps its place.
11. **Every frequent OCR word became a PRODUCT entity.** The real library filed `post` on 10
    screenshots, `search` on 9, `data` on 7, `protection` and `trackers` on 6 each — 365
    product entities in total, which made Explore and the Home suggestion meaningless. A
    phrase is now only a product if it carries a model number or a product noun, which cut the
    same library to 12.
12. **A global `(?i)` flag leaked onto the order-code pattern**, so the uppercase code class
    also matched lowercase words: `order Protection` filed an ORDER named `Protection` on four
    screenshots, which the Timeline then presented as a fabricated “event”. The flag is now
    scoped to the keyword, and a code must contain a digit.
13. **The storage breakdown reported 0 B for the text index, the text vectors and the image
    data.** Table names were normalized with `substringBefore("_")`, which truncated every name
    at its first underscore and collapsed eight tables into two buckets. `dbstat` entries are now
    attributed to the table that owns them, including `sqlite_autoindex_*` and FTS shadow tables.
14. **A pinned search image with an empty text box returned nothing.** The pin row rendered
    correctly while the screen stayed on the idle prompt, because the “do we have input?” guard
    only looked at the text box. A visual-only search is now its own retrieval path, ranked
    directly by perceptual hash.
15. That result list then announced itself as **“Searching for everything”** and **“Text
    matches”** — both untrue. A blank query describes itself as “everything” because it has no
    constraints, and the mode indicator had no third state. Both now say what actually
    happened: “screenshots that look like the one you picked”, “Similar-looking screenshots”.

Two test defects were also fixed rather than left to pass by luck: the month-boundary test
seeded "3 days ago", which lands in the previous month on the 1st, 2nd or 3rd, and the
migration tests reused database files left on the device by an earlier run, so a second run
validated a schema the current migration code never produced.

---

## Tests

```bash
./gradlew testDebugUnitTest        # fast JVM tests, no device needed
./gradlew connectedDebugAndroidTest  # Room + full search engine, requires a device/emulator
```

**365 local unit tests** cover URL, price, phone, date and OTP extraction, content hashing,
screenshot heuristics, FTS query construction, the whole Phase 2 search layer (query parser
and each sub-parser, intent classification, ranker, snippets, currency rendering), the
Phase 3 semantic layer (deterministic embeddings, concept expansion, hybrid scoring,
rule classification, extractive summaries, entities, sensitive flags, smart groups), and the
Phase 4 layers (perceptual hashing, palette analysis, layout geometry, screenshot-type
classification, entity normalization, product-naming rules, graph building, comparison, the
visual query parser). The search, semantic, vision and graph layers are deliberately free of
Android dependencies so they are testable as plain JVM code.

**90 instrumented tests** cover the database (insert, update, delete, cascade behaviour, FTS
search, search ranking, filters, duplicate lookup, the pending queue, stale-work recovery,
incremental re-indexing, keyset pagination, index rebuild and OTP isolation from search),
both schema migrations — all three, from v1, v2 and v3 — the complete Phase 2 engine against a
real SQLite engine, and the
Phase 3 hybrid engine over a fixed six-screenshot benchmark library — keyword, phrase,
price, combined, date, URL, phone, code, duplicate, filter, sort, deletion, semantic
concept queries, hybrid ordering, exact-match dominance, duplicate collapsing, the
no-browse-masquerade rule, and Room-level semantic cascade behaviour — plus the Phase 4 layer
over a real database: visual similarity ranking, near-duplicate clustering, palette and
long-screenshot queries, search-by-image with and without text, visual-only search answering
no-results rather than browsing, entity pages with prices and websites, cascade integrity
(shared entities survive, orphans are swept), collection membership, timeline grouping, event
and sequence detection, comparison, the v3→v4 migration, and the analyzer over synthetic
pixels — plus a 10,000-row performance suite.

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

Room, version 4, with exported schemas in `app/schemas/`.

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
| `screenshot_embeddings` | `vector` (512 floats), `model`, `version` (Phase 3) |
| `screenshot_categories` | `category`, `confidence`, `source`, `classifier_version` (Phase 3) |
| `screenshot_visuals` | `dhash`, `colors`, `brightness`, `is_dark`, `text_coverage`, `shot_type`, `layout`, `model_version` (Phase 4) |
| `graph_entities` | `type`, `display_name`, `normalized_name` (Phase 4) |
| `graph_relations` | `screenshot_id`, `entity_id`, `kind`, `confidence` (Phase 4) |
| `collections` | `name`, `created_at` (Phase 4) |
| `collection_members` | `collection_id`, `screenshot_id`, `added_at` (Phase 4) |

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

`version = 4`, with three explicit migrations:

| Migration | Adds |
|---|---|
| `MIGRATION_1_2` | `search_history` |
| `MIGRATION_2_3` | `screenshot_embeddings`, `screenshot_categories` |
| `MIGRATION_3_4` | `screenshot_visuals`, `graph_entities`, `graph_relations`, `collections`, `collection_members` |

There is deliberately **no destructive fallback**: silently dropping a user's index because a
version changed is exactly the failure mode the explicit "Clear index" control exists to
avoid. A migration test validates each migrated schema against the exported JSON, so a
hand-written `CREATE TABLE` that drifts from the entity definition fails in CI rather than on
a user's device. The tests delete their database files first, so a second run validates the
current migration code rather than a leftover schema from an earlier run.

`collection_members` carries a unique index on `(collection_id, screenshot_id)`: adding the
same screenshot twice is a no-op, so counts and member lists can never double-count.

The Phase 4 tables are all derived data. Visuals recompute from pixels, the graph rebuilds
from the extraction tables, and collections are user data carried across the upgrade untouched
(empty by definition). The graph's foreign keys cascade, so deleting a screenshot removes its
relationships and the orphan sweep drops entities nobody references any more — while shared
entities survive.

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
>
> A second silent rejection in the same family was found in Phase 3: a **prefix query
> combined with `OR`** — `"travel"* OR "booking"*` — also matches zero rows with no error,
> while `"travel" OR "booking"` works. The OR form (relaxed-search rung, semantic prefilter)
> is therefore exact-token only; prefix recall still comes from the AND rungs. A regression
> test pins the emitted shape against a real database.

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
Visual analysis: dHash · palette · type · layout  (Phase 4, from a ~192px decode)
   ↓
Single transaction: screenshot + geometry + all extracted tables
   ↓
Semantic + visual + graph indexing, after the commit  (each guarded independently)
   ↓
COMPLETED
```

### Failure policy

A failure in any **one** extraction step does not fail the screenshot — the partial result is
still indexed and the degraded stage is logged. Only a failure to read or decode the image is
fatal, because without an image there is nothing to index.

Derived indexing follows the same rule, for the same reason: semantic, visual and graph
indexing run **after** the core transaction commits and can never roll back the lexical index.
A screenshot whose image cannot be analyzed still indexes and still searches; it simply has
no palette and no visual neighbours until the visual index is rebuilt.

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

Two more handle the derived Phase 3/4 indexes, both resumable and chunked:

- `SemanticIndexWorker` — text vectors, summaries and categories.
- `VisualIndexWorker` — image hashes, palettes, types, layouts, and graph filing.

Each backfill selects only rows whose stored `version` is behind the current one, so it is
incremental rather than a full rebuild, and it stops visibly if a pass makes no progress
instead of draining the battery. Background processing mode governs the catch-up: Automatic
runs soon, Charging Only waits for power and idle, and Manual means only an explicit tap in
Settings runs the builders. Browsing and search work identically in every mode — this only
governs expensive background work.

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
| `blue screenshots` | palette contains blue (Phase 4) |
| `dark screenshots` / `light screenshots` | brightness flag, not a palette color |
| `grey`, `violet`, `cyan`, `teal`, `maroon`, `beige` | nearest named color |
| `long screenshots` / `tall` / `stitched` / `full page` | aspect ratio ≥ 2.8 (Phase 4) |

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
- `dark mode` is not a brightness ask. "Dark" and "light" are only treated as appearance when
  they stand alone; in `dark mode` the phrase is left intact for the keyword extractor.

Parser order is itself meaningful: date → URL → code → phone → price → content type → visual →
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

One masking rule keeps the ladder honest: when a text query reaches `FILTERS_ONLY` with no
structured constraint left, running it would list the whole library disguised as results.
Instead the engine tries the semantic index, and if that has nothing either, the answer is
no results — never a browse pretending to be a search.

### Duplicate collapsing

Byte-identical screenshots collapse into one result (`3 identical screenshots`) instead of
filling the first page with the same image, and identical OCR text gets the same treatment.
The hidden members stay reachable from the duplicates screen, so nothing is buried.

### Result mode indicator

Every result list says which half of the engine answered: `Meaning-based results` when the
semantic index contributed, `Text matches` otherwise. Semantic hits carry a `Related to
"…"` reason naming the concept, never an embedding value or a similarity number. Phase 4 adds
two more reason kinds, worded the same honest way: `Blue tones` when a palette matched, and
the entity's own name (`Pixel 9a`) when the knowledge graph did.

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

### Multimodal search

Search can combine a sentence, a picture, or both. Picking a screenshot as the search image
pins it to the query, the box shows a thumbnail of what "like this one" refers to, and the
library is ranked by perceptual-hash proximity. The query image itself steps aside — it is
trivially similar to itself — while every other row keeps its place.

An image **alone** is a search on its own: with no words there is nothing for the retrieval
ladder to work from, so the library is ranked by Hamming distance directly and only rows
within the similarity threshold come back. "Nothing looks like this" is a real answer, and it
is reported as no results rather than as a browse.

The two combine when both are present, which is the interesting case: words and pixels narrow
independently, and the exact-match guarantee still holds. A screenshot matching the whole
sentence and every structured filter scores 60 before the soft signals are counted, while
semantic, visual and entity matches cap at 40 combined. No embedding and no hash, however
close, can outrank an exact match — because 40 is less than 60, always. The weights are
configurable; if they are ever re-tuned, the test asserting that floor fails rather than the
guarantee dying quietly.

The UI always says which signals answered. A list carrying only visual reasons reads
**"Similar-looking screenshots"**, not "Text matches" — nothing about the words was
considered, so claiming otherwise would be a lie. When several colors are named, the first
reaches SQL as a filter and the rest score in the ranker. The image picker uses screenshots
already on the device: no camera, no new permission.

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

`SemanticSearchProvider.Disabled` is the only neural-signal implementation, and the fusion
step that would consume it is kept — semantic search is an *addition* to the deterministic
engine, never a replacement for it. Phase 3's built-in semantic index (below) plugs in
through `SemanticRepository` instead. `ScreenshotProcessor`, `ImageSimilarityDetector` and
the extractor interfaces are similarly open for extension.

---

## Meaning-based search

Phase 3 answers queries by meaning as well as by words. `travel booking` finds flight
tickets and hotel reservations even when neither word appears in the screenshot; `phone
deal` surfaces listings through the concepts they belong to. The architecture is hybrid by
design — the deterministic engine from Phase 2 always runs first and always works alone:

```
User Query → Query Parser → Structured Search ─┐
                                               ├→ Candidate Fusion → Hybrid Ranker → Results
              Query concepts → Vector Search ───┘
```

### The local model question

There is no neural model in this phase, and that is a decision rather than a gap. A
downloaded embedding model would need either a multi-hundred-megabyte APK addition or a
runtime download — and a runtime download needs the `INTERNET` permission, which would
break the structural privacy guarantee this whole app is built on. The spec explicitly
prefers a deterministic implementation over forcing a model in, and the measured result
below shows the deterministic index answers the benchmark queries.

What ships instead, as the `EmbeddingProvider` implementation:

- **Hashed text embeddings** (`hashed-ngram v1`, 512 dimensions): word unigrams, word
  bigrams and character trigrams hashed with FNV-1a into a TF-weighted, L2-normalized
  vector. Tolerant to OCR misreads (`Pixcl` still shares most trigrams with `Pixel`),
  plurals and compounding. 2 KB per screenshot; vectors load only for the prefiltered
  candidate set, never for the whole library.
- **A curated concept graph** (`travel → flight, hotel, ticket, PNR…`): the semantic
  jump from `travel booking` to a boarding pass comes from an explicit, reviewable table —
  not from emergent model behaviour. Bounded (3 concepts, 12 terms per query) so
  expansion cannot flood results with false positives.
- **Cosine similarity** over normalized vectors, with a 0.25 floor below which
  "similar" is noise.

The `EmbeddingProvider` interface (model name, version, dimension, availability) means a
neural model can replace this provider later without touching storage, ranking or UI.
Every stored embedding carries its model identity, and mixing versions is refused loudly
rather than silently.

### Hybrid scoring

Five normalized signals combine by configurable weight (Phase 4 starting point 40 / 25 / 20 /
10 / 5 for lexical / semantic / metadata / visual / entity):

```
final = lexical × 0.40 + semantic × 0.25 + metadata × 0.20 + visual × 0.10 + entity × 0.05
```

The exact-match guarantee is arithmetic, not a special case: a full lexical + metadata
match scores 60 before the soft signals are counted, while semantic, visual and entity
matches cap at 40 combined. No embedding and no hash, however close, can outrank an exact
match. A test pins this invariant so a future re-tuning breaks loudly instead of silently.

### Categories, summaries, related

- **Automatic categories** (Shopping, Receipts, Travel, Flights, Hotels, Finance, … —
  20 total) from weighted evidence rules, never from a single keyword: `flight mode`
  mentions flight and must not become Travel. Multi-label, confidence-stamped with the
  classifier version, and user corrections are stored separately and never overwritten.
- **Extractive summaries** assembled from stated facts only — `Pixel 9a — ₹39,999 —
  Amazon`. No paraphrase, no inference, nothing to hallucinate; a test asserts the summary
  introduces no word the screenshot did not contain.
- **Related screenshots** on the detail page, ranked by embedding similarity plus shared
  entities, categories and hosts — never just neighbours in time.
- **Smart collections** on Home, grouped by category/host/phrase with labels from the
  members' own words (`Pixel / Shopping`), surfaced only with 3+ members.
- **Sensitive flags** (OTP, banking, payment, identity, password, private chat) kept
  internal and used to keep such screenshots out of suggestions and previews.

### Semantic index management

Embeddings and categories derive automatically as screenshots are indexed, and a `Build
meaning index` worker backfills the rest in resumable chunks with visible progress. The
worker prefers charging + idle for catch-up runs, skips textless screenshots (an empty
string embeds to the zero vector — and once looped a rebuild forever, see below), and
carries a circuit breaker so stalled progress stops visibly instead of draining the
battery silently. `Delete meaning index` removes embeddings and automatic categories while
keeping screenshots, OCR, metadata and user corrections.

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

**Known limitation, addressed in Phase 4:** hashing encoded bytes means two screenshots that
look identical but were saved with different encoders will not match. Perceptual hashing now
covers that case — see [Visual intelligence](#visual-intelligence) — and the two are reported
separately so "similar" is never called "duplicate".

---

## Visual intelligence

Phase 4 adds sight without adding a model, a permission, or a byte of downloaded weights.

### What the visual index actually is

| Aspect | Value |
|---|---|
| Name | `dhash` (perceptual hash) + `visual-v1` rules |
| Source | First principles, implemented in this repository |
| License | Same as the app |
| Size | 0 bytes — no download, nothing to install |
| Architecture | Any; pure Kotlin over an `IntArray` of pixels |
| Runtime | ~5 ms per screenshot at a 192px long edge |

Two deliberately separate concerns:

- `ImageEmbeddingProvider` produces a **64-bit dHash** — nine brightness transitions per
  row, eight rows. It answers "does this look like that one?", and nothing else. dHash
  compares *neighbouring* pixels, so a dark-mode variant of the same screen stays related
  rather than reading as a different image.
- `BitmapVisualAnalyzer` produces the **descriptive** signals: dominant colors, mean
  brightness, screenshot type, layout, and long-screenshot detection. It reads OCR box
  geometry that Phase 1 already stored, so layout is derived from text positions — chat
  bubbles alternate sides, tables align into columns, receipts are narrow stacks.

Everything is recomputed deterministically from pixels plus stored geometry. Nothing is a
model opinion, and there are no object-recognition claims anywhere: the app reports that a
screenshot is *mostly blue*, never that it *contains a phone*.

### Palette and brightness

Colors are named coarsely on purpose — eleven names with sRGB centers, weighted Euclidean
nearest match, a color counted only above an 8% share so speckle is not palette, and the top
three kept. The UI says exactly what that means: `blue screenshots` returns screenshots whose
extracted palette contains blue, and a palette hit is labelled `Blue tones`. `dark` and
`light` are brightness flags rather than colors, and `dark mode` is left alone as a phrase.

### Screenshot type

Accumulated evidence with weights, not a single keyword. OCR words, hosts, price presence,
OTP presence, layout and aspect each contribute, and a type needs 1.5 points before it can
win — so `account settings page` is App screen, not Banking. A long screenshot is recognized
by aspect alone (≥ 2.8:1; phone captures are about 2.2:1) regardless of its content, and
`LONG_SCREENSHOT` is its own type rather than a flag on another.

### Memory discipline

The analyzer decodes at a 192px long edge — a 1080×2400 screenshot becomes roughly 88×192,
about 17k pixels, in `RGB_565` — computes everything from that, and recycles immediately. A
palette, a hash and a brightness reading need no more. `OutOfMemoryError` and decode failures
return a neutral analysis rather than propagating.

### What the user sees

- **Visually similar** (from a detail page or search-by-image): ranked by Hamming distance,
  labelled `Near duplicate` / `Very similar` / `Similar`. Distances never appear as numbers.
- **Similar screenshots** on the Duplicates screen: clusters within a few bits, explicitly
  separated from exact duplicates and worded as "similar, not duplicates".
- **Visual section on the detail page**: type, palette, layout, appearance — as observed
  attributes, never as recognized objects.

---

## Knowledge graph

Relational tables, not a graph database. Entities and relationships are rows, and every
traversal is a join.

### Model

| Table | Holds |
|---|---|
| `graph_entities` | `type`, `display_name`, `normalized_name`, unique per `(type, normalized)` |
| `graph_relations` | `screenshot_id`, `entity_id`, `kind`, `confidence` |

Entity types: product, company, website, price, date, phone, order, booking, category,
location, event. Relationship kinds: `mentions`, `priced at`, `sold by`, `found on`,
`in category`, `dated`.

**`similar` and `duplicate` are deliberately not stored.** Similarity comes from embeddings
and duplication from content hashes, both recomputed live; persisting them would duplicate the
source of truth and go stale the moment an embedding changed.

### Grounding, and where it refuses to guess

Every entity is grounded in data extraction already produced. Products come from the
screenshot's own phrases, companies and websites from its hosts, prices, dates, phones and
order codes from the normalized extraction tables, categories from the classifier. The builder
invents nothing — it only files what extraction found.

Two rules keep it honest:

- **Companies come from hosts only.** The word "apple" in a recipe never files Apple Inc.;
  `www.apple.com` does. Registrable-part extraction keeps `smile.amazon.in` and
  `www.amazon.in` as the same company, including country-code domains like `bbc.co.uk`.
- **A phrase must look like a product to be filed as one.** Without this, every frequent OCR
  word became a product: a real library produced 365 of them, led by `post` (10 screenshots),
  `search` (9) and `data` (7), and Explore degenerated into a word frequency list. Two kinds
  of evidence are accepted, both grounded in the phrase itself — a **digit** (model numbers are
  the strongest product signal there is: `Pixel 9a`, `OnePlus 13`) or a **product noun**
  (`phone`, `laptop`, `earbuds` and a curated list of others). The same library then filed 12,
  which are products. A bare number is never enough; the word has to be there too.
- **Order and booking codes must contain a digit.** Real codes do (`7QK2LP`, `ORD998877`);
  English words do not. Without that rule a lowercase word following "order" became an
  identifier, and the Timeline offered a fabricated "event" for it.
- **Merging is conservative.** Normalization collapses case, spacing, punctuation and joined
  letter/digit boundaries, so `Pixel 9a`, `PIXEL 9A` and `Pixel9a` are one entity. A digit →
  letter split is deliberately *not* applied, because `9a` is a model suffix and splitting it
  would mean the entity could never match itself. A brand prefix stays part of the name:
  `Google Pixel 9a` does not silently merge with `Pixel 9a`.

Prices normalize to `currency:amount` and are never converted. Filing is find-or-create, one
screenshot's footprint is replaced atomically, and re-indexing can never duplicate an edge.

Entity pages list categories from the classifier but never show `OTHER` — that bucket means
"nothing matched", and presenting it as a category states nothing.

### Integrity

Deleting a screenshot cascades its relationships. An entity nobody references any more is
swept; an entity other screenshots still reference survives. Prices on an entity page are
listed in screenshot-date order and titled "Prices seen in your screenshots" — an observation
of what was on screen, never a claim about a current price.

### Entity pages and Explore

An entity page answers "everything about Pixel 9a": every screenshot mentioning it, the prices
seen with it, its websites, its categories. Explore lists only entity types actually detected,
each with its reference count, so the screen is empty rather than padded when there is nothing
to show.

---

## Organization

- **Timeline** — days the user actually took screenshots, newest first, each with that day's
  top categories. A day appears only when screenshots exist on it; dates are never fabricated.
- **Events** — screenshots sharing a booking or order identifier, within a 14-day span. The
  shared code is the evidence; time proximity alone never groups anything, because a recurring
  number is not an event.
- **Sequences** — adjacent same-day screenshots (≤ 30 min apart) with at least 50% term
  overlap. Both conditions are required: adjacency without overlap is just burst photography.
- **Collections** — manual, many-to-many, storing references and never images. Membership is
  unique per pair, so adding twice is a no-op. Alongside the Phase 3 smart collections, which
  each state their own criteria so "why is this here" always has an answer.
- **Compare** — two screenshots side by side with their detected differences: a moved price
  as `Price ₹39999 → ₹41999`, a changed website, and added/removed terms. A price *change* is
  only claimed when both sides have exactly one price in that currency; otherwise it is an
  addition or removal, not a move. Pixel diffing is out of scope on purpose — status-bar icons
  and rotating ads would be reported as change, which is noise dressed as information.
- **Contextual actions** on the detail page (`Find visually similar`, `More from this
  website`, `More about X`, `Compare`) are resolved against the index before being shown, so
  an action that would lead nowhere is never constructed.
- **One quiet suggestion** on Home: the most-referenced product with at least 5 screenshots,
  inside the app and never as a notification.

### Storage accounting

Settings shows a **measured** breakdown from SQLite page accounting (`dbstat`), not estimates:
screenshots and extracted data, text index, text vectors, image data, graph and categories,
search history, and the total. Models report `built in (0 B)` because there are none to store.
When page accounting is unavailable the section says so instead of guessing. Each derived
component can also be deleted independently — image embeddings, automatic categories, the
graph — and none of those actions touches screenshots, OCR text, extracted data, user
collections, or category corrections.

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
- **Hamming scans need no vector extension.** A 64-bit hash is 8 bytes; the whole library's
  hashes fit in memory trivially, and visual similarity is a linear scan in Kotlin with no
  index and no ANN structure. Near-duplicate clustering compares each row against group
  representatives rather than every row, and the scan is capped like the search window.
- **Multimodal context loads once per search, not once per row.** Candidate palettes, entity
  labels and the query image's hash are three batched reads, not N.
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
| 3 | Local semantic search, categories, summaries, related, groups | `SemanticRepository`, `EmbeddingProvider` | done |
| 4 | Visual search (image embeddings), collections, timeline, entity graph | `ImageEmbeddingProvider`, `GraphRepository` | done |
| 5 | Paging 3 over search results | `SearchRequest.limit` → `Pager` | not started |
| 6 | Optional neural embedding model replacing `HashedNgramEmbeddingProvider` | `EmbeddingProvider` | not started |

Settings shows unimplemented toggles disabled with an explicit "planned for a later release"
note, rather than shipping no-op controls.
