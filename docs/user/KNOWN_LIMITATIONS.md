# Screenshot Intelligence v1.0 — Known Limitations

## OCR Accuracy
- OCR quality depends on image resolution, lighting, and text clarity
- Handwriting recognition is not supported
- Some non-Latin scripts may not be recognized
- Long screenshots are processed in tiles; text at tile boundaries may be missed

## Search Limitations
- Semantic search uses a built-in hashed n-gram provider, not a neural model
- Visual search quality depends on image characteristics
- Search does not understand context beyond OCR text and extracted entities
- No cross-language search

## Assistant Limitations
- Answers are based only on indexed screenshot content
- No real-time web access
- No understanding of images beyond OCR text and visual features
- Complex reasoning is limited
- No memory of previous conversations unless history is enabled

## Automation Limitations
- Rules are evaluated only when the app is running or during background work
- Time-based triggers depend on WorkManager scheduling
- No location-based triggers
- No integration with external services

## Platform Limitations
- Background indexing may be delayed by Android battery restrictions
- Some actions require compatible third-party apps
- No tablet-specific layouts (responsive design only)
- No foldable-specific optimizations

## Data Limitations
- Import supports JSON export format only
- No automatic cloud backup (by design)
- No multi-device sync
- Database corruption recovery may require re-indexing

## Privacy Limitations
- App lock relies on Android BiometricPrompt; not all devices support biometrics
- Clipboard auto-clear requires Android 13+ for IS_SENSITIVE flag
- Secure window (FLAG_SECURE) prevents screenshots but not all screen recording
- Rooted devices may bypass app-level security controls