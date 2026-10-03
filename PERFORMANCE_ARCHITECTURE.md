# Performance Architecture

## Design Principles

1. **Lazy initialization** — ServiceLocator uses `by lazy` for all heavy dependencies
2. **Incremental processing** — Only new/changed screenshots are indexed
3. **Bounded concurrency** — WorkManager constraints prevent resource exhaustion
4. **Pagination** — Keyset pagination for all large lists
5. **Background processing** — All expensive work runs on Dispatchers.IO

## Startup Performance

```
Application.onCreate()
  → WorkManager init (lightweight)
  → ServiceLocator.install() (no heavy work)

First Activity
  → Compose UI renders immediately
  → Repository/DB accessed lazily on first query
  → Models loaded only when needed
```

**Target**: Cold start < 500ms on mid-range devices, regardless of library size.

## Indexing Pipeline

```
MediaStore discovery
  → Content hash (fast, no OCR)
  → Skip if hash unchanged
  → OCR (ML Kit, ~200-500ms per image)
  → Entity extraction (regex, <10ms)
  → Embedding generation (hash-based, <5ms)
  → Database write (batched transaction)
```

**Throughput**: ~2-5 screenshots/second on mid-range devices.

## Search Performance

| Library Size | Keyword Search | Semantic Search | Visual Search |
|-------------|---------------|-----------------|---------------|
| 1,000 | <50ms | <100ms | <200ms |
| 10,000 | <100ms | <300ms | <500ms |
| 50,000 | <200ms | <800ms | <2s |

## Memory Management

- **Bitmaps**: Decoded at required resolution only; recycled after use
- **Embeddings**: Stored as FloatArray in DB; loaded per-query
- **Search results**: Bounded to 500 candidates; reranked before display
- **Thumbnails**: Coil memory cache (25% of available heap) + disk cache

## Battery Optimization

- WorkManager constraints: charging, network, battery-not-low
- Thermal throttling: reduce concurrency when thermal state is elevated
- Batch operations: minimize wake locks
- No polling: event-driven via MediaStore + periodic reconciliation

## Storage Management

| Component | Location | Size Control |
|-----------|----------|-------------|
| Database | App-private, encrypted | SQLCipher |
| Thumbnails | Disk cache | LRU, 100MB max |
| Embeddings | In database | Bounded by library size |
| Models | APK bundled | No download |
| Exports | User-selected | User-controlled |