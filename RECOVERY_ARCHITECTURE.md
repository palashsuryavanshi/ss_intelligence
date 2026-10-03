# Recovery Architecture

## Failure Detection

| Failure | Detection | Recovery |
|---------|-----------|----------|
| Database corruption | SQLite integrity check | Rebuild from OCR or re-index |
| Worker death | WorkManager retry | Resume from checkpoint |
| OOM | Try-catch + graceful degradation | Reduce concurrency, retry |
| Disk full | IOException catch | Pause indexing, notify user |
| Permission revoked | SecurityException | Prompt for permission |
| Model unavailable | Load failure | Fall back to simpler model |
| Process kill | WorkManager reschedule | Resume from last state |

## Worker Recovery

```
Worker starts
  → Load checkpoint (last processed ID)
  → Process next batch
  → Save checkpoint
  → Repeat

Worker killed
  → WorkManager detects
  → Reschedules with backoff
  → Worker restarts from checkpoint
```

## Database Recovery

```
App launch
  → Open database (SQLCipher)
  → If corruption detected:
    → Log error
    → Attempt integrity_check
    → If repairable: VACUUM + REINDEX
    → If not: prompt user to Clear Index
  → Never silently delete user data
```

## Index Repair

`RepairManager` provides targeted repairs:

| Repair Type | What It Does | Data Preserved |
|-------------|--------------|----------------|
| OCR | Re-run OCR on all screenshots | Screenshots, metadata |
| Embeddings | Clear + rebuild semantic index | Screenshots, OCR |
| Visual | Clear + rebuild visual index | Screenshots, OCR |
| Knowledge Graph | Clear + rebuild graph | Screenshots, OCR |
| Derived Metadata | Re-run autonomous analysis | Screenshots, OCR |

## Data Deletion Recovery

- Deletion is immediate and irreversible
- No trash/bin for indexed data
- User confirmation required for destructive operations
- Cascade deletes all derived data via foreign keys

## Crash Resilience

- All workers use `runCatching` around expensive operations
- UI never crashes from background failures
- Errors are logged (without sensitive data) and surfaced to user
- App lock state is persisted across process death