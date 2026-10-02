# Privacy Data Inventory

## Data Collected

| Data Type | Source | Purpose | Stored On-Device |
|-----------|--------|---------|-----------------|
| Screenshots | MediaStore discovery | Index & search | ❌ (URI only) |
| OCR text | ML Kit Text Recognition | Search, metadata | ✅ Room DB |
| Extracted entities (URLs, phones, dates, prices, emails, OTPs) | Regex/entity extraction | Search, actions | ✅ Room DB |
| Screenshot visual analysis | Pixel analysis (dhash, colors, brightness) | Visual search | ✅ Room DB |
| Local embeddings | Hashed n-gram embedding provider | Semantic search | ✅ Room DB |
| Knowledge graph relations | Entity co-occurrence analysis | Entity search | ✅ Room DB |
| Receipt extraction (merchant, amount, date) | Receipt OCR pattern parsing | Expense prefill | ✅ Room DB |
| Assistant conversations | User queries | Assistant history | ✅ Room DB (configurable) |
| Search history | User queries | Recent searches | ✅ Room DB (configurable) |
| Automation rules | User-created rules | Automation execution | ✅ Room DB |
| Expense records | User-confirmed receipts | Expense tracking | ✅ Room DB |
| User settings | User preferences | UI and app behavior | ✅ DataStore |
| App lock setting | User preference | Security gating | ✅ DataStore |

## Data Transmitted

**None.** The application has no `INTERNET` permission in the merged manifest.
All processing is strictly local. No screenshots, OCR text, embeddings, or
metadata are ever uploaded.

## Data Shared

Only through explicit user-initiated sharing actions:
- Share via system share sheet (with sensitive content warning)
- Export data (user initiated, local file only)

## Retention

| Category | Retention | Controls |
|----------|-----------|----------|
| Screenshots index | Until user deletes or media changes | Pause indexing, folder exclusion |
| OCR text | Until re-indexed or screenshot removed | Clear index |
| Semantic embeddings | Until rebuild or clear | Clear data |
| Assistant history | Until user clears or disables | Privacy settings toggle |
| Search history | Until user clears or disables | Privacy settings toggle |
| Automation rules | Until user deletes | Settings > Automation |
| Expense records | Until user deletes | Expenses screen |

## Deletion

- **Per-screenshot**: Deleting a screenshot cascades to all derived data via
  `ON DELETE CASCADE` foreign keys.
- **Complete**: Settings > Privacy > Delete All Data removes all application
  data including database, search history, semantic index, visual data,
  knowledge graph, assistant history, collections, automation rules, and caches.

## Permissions

| Permission | Purpose | When Requested |
|------------|---------|----------------|
| READ_MEDIA_IMAGES | Discover screenshots via MediaStore | Onboarding |
| READ_EXTERNAL_STORAGE | Media discovery on API 29-32 | Onboarding |
| WRITE_CALENDAR | Create calendar events from automation | First use |
| WAKE_LOCK | Keep processing alive during indexing | Internal |
| ACCESS_NETWORK_STATE | Check connectivity for WorkManager | Internal |
| RECEIVE_BOOT_COMPLETED | Schedule indexing after reboot | Install |
| FOREGROUND_SERVICE | Background indexing workers | Indexing active |
| POST_NOTIFICATIONS | Detection notifications (redacted) | First use |

## No Internet Permission

The merged AndroidManifest.xml explicitly removes `android.permission.INTERNET`
with `tools:node="remove"`. This is a structural guarantee: the app cannot
upload data even if a code path attempted it.