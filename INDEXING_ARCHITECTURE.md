# Indexing Architecture

## Pipeline Stages

```
1. Discovery
   MediaStore query → new/changed/deleted files

2. Validation
   File exists, readable, supported format, size < 100MB

3. Deduplication
   Content hash → skip if unchanged since last index

4. OCR
   ML Kit Text Recognition → OCR text + bounding boxes

5. Entity Extraction
   Regex patterns → URLs, phones, dates, prices, emails, OTPs, receipts

6. Classification
   Sensitivity detection → PUBLIC to HIGHLY_SENSITIVE

7. Embedding
   Hashed n-gram provider → 128-dim vector

8. Visual Analysis
   dhash, colors, brightness → visual features

9. Knowledge Graph
   Entity co-occurrence → graph relations

10. Persistence
    Single transaction → all derived data written atomically
```

## Incremental Indexing

- **Content hash**: SHA-256 of file bytes; compared against stored hash
- **Version tracking**: Each derived artifact has a version field
- **Selective rebuild**: Only outdated artifacts are regenerated
- **Skip unchanged**: If hash and versions match, skip entirely

## Worker Architecture

| Worker | Trigger | Constraint |
|--------|---------|------------|
| IndexingWorker | App launch, manual scan | Battery not low |
| SemanticIndexWorker | After OCR complete | Charging (optional) |
| VisualIndexWorker | After OCR complete | Charging (optional) |
| AutonomousAnalysisWorker | After semantic complete | Idle |

## Failure Handling

- **Retry policy**: 3 attempts with exponential backoff
- **Permanent failures**: Corrupt images marked FAILED, not retried
- **Worker death**: WorkManager reschedules; processing resumes from checkpoint
- **Database locked**: Retry with backoff; never crash

## Idempotency

- All database writes use `OnConflictStrategy.REPLACE`
- Entity extraction is deterministic (same input → same output)
- Embedding generation is deterministic
- Deletion cascades via foreign keys