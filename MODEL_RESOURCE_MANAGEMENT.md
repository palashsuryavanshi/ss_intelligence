# Model Resource Management

## Bundled Models

| Model | Version | Size | Purpose | License |
|-------|---------|------|---------|---------|
| ML Kit Text Recognition | 16.0.1 | ~12MB | OCR | Apache 2.0 |
| Hashed N-gram Embedding | 1.0 | 0 (code) | Semantic search | App license |

## Model Lifecycle

```
APK install
  → Models bundled in APK
  → No download required
  → No network access needed

First use
  → Model loaded lazily
  → Inference on device
  → Results stored locally
```

## Resource Constraints

| Resource | OCR | Embedding | Visual |
|----------|-----|-----------|--------|
| RAM | ~128MB | ~16MB | ~64MB |
| CPU | High | Low | Medium |
| GPU | Optional | None | None |
| Network | None | None | None |

## Model Integrity

- `ModelIntegrityManager` maintains a manifest of expected models
- SHA-256 hashes verified on startup (for downloadable models)
- Bundled models verified by APK signature
- No silent model replacement

## Future Model Downloads

If downloadable models are added in future versions:
- HTTPS only with certificate pinning
- SHA-256 verification after download
- Resumable downloads with retry
- Storage space check before download
- User consent before download
- Clear size disclosure before download