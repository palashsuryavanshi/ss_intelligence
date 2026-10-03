# Screenshot Intelligence v1.0 — Feature Set

## Product Identity

| Field | Value |
|-------|-------|
| Package | com.ssintelligence.app |
| Version | 1.0.0 |
| Min SDK | 29 (Android 10) |
| Target SDK | 37 (Android 14) |
| Architectures | arm64-v8a, armeabi-v7a, x86_64 |

## Core Features (STABLE)

### Screenshot Ingestion
- MediaStore discovery of screenshots
- Incremental indexing (only new/changed files)
- Pause/resume indexing
- Battery-aware background processing
- Long screenshot support (tiled decoding)

### Local OCR
- ML Kit Text Recognition (bundled, offline)
- OCR text search via FTS4
- Multi-language support (Latin script)

### Metadata Extraction
- URLs, dates, phone numbers, prices, emails, OTPs
- Receipt extraction (merchant, amount, date, category)
- Indian phone number detection

### Search
- Full-text search (FTS4)
- Structured search (date, price, phone, domain, OTP filters)
- Semantic search (local hashed n-gram embeddings)
- Visual search (dhash, color analysis)
- Hybrid search with staged retrieval
- Search history (privacy-controlled)

### Screenshot Classification
- Automatic category detection
- Sensitive content detection (OTP, financial, identity)
- Sensitivity levels: PUBLIC → HIGHLY_SENSITIVE

### Knowledge Graph
- Entity extraction and relationship mapping
- Cross-screenshot entity search
- Graph-based navigation

### Assistant
- Natural language queries about screenshot library
- Evidence-first answers with source citations
- Local processing only
- Conversation history (privacy-controlled)

### Actions
- Contextual actions from detected content
- Open URL, call, message, map, calendar, reminder
- Share with sensitive content warning
- Copy with clipboard protection

### Organization
- Collections (manual and smart)
- Tags and suggestions
- Timeline view
- Duplicate detection

### Automation
- Local rules with typed triggers/conditions/actions
- Event-based triggers (new screenshot, entity detected, etc.)
- Time-based triggers
- Safety confirmations for consequential actions

### Privacy & Security
- No INTERNET permission (structural privacy)
- SQLCipher database encryption
- Android Keystore key management
- App lock (BiometricPrompt + device credential)
- Sensitive content protection
- Clipboard auto-clear
- Notification redaction
- Secure window (FLAG_SECURE)
- Complete data deletion
- Per-screenshot deletion with cascade

### Performance
- Keyset pagination for large lists
- Lazy initialization
- Background processing with WorkManager
- Bounded memory usage
- Incremental indexing

## Beta Features

| Feature | Status | Notes |
|---------|--------|-------|
| Visual search | Beta | Quality depends on image characteristics |
| Semantic search | Beta | Built-in provider, no neural model |
| Receipt extraction | Beta | Pattern-based, may miss variations |

## Internal (Not User-Facing)

| Feature | Purpose |
|---------|---------|
| Debug diagnostics | Development only |
| Search debug screen | Development only |
| Model integrity audit | Security monitoring |

## Explicitly NOT in v1.0

- Cloud processing or sync
- Account creation
- Social features
- Widget configuration (widget is fixed)
- Custom model download
- Redaction UI (automatic redaction only)
- Time-based reminder scheduling (reminders are local only)
- Multi-device sync
- Web interface