# Screenshot Intelligence 1.0.0 — Release Notes

## What's New

Screenshot Intelligence v1.0.0 is the first public release. It transforms your
screenshot library into a searchable, organized, and actionable knowledge base —
all processed locally on your device.

## Core Features

- **Search your screenshots** — Full-text search over OCR text with filters for dates, prices, phones, URLs, and OTPs
- **Search by meaning** — Semantic search using local embeddings
- **Find visually similar screenshots** — Visual search using image features
- **Ask questions** — Natural language assistant with evidence citations
- **Organize** — Collections, tags, and smart suggestions
- **Take action** — Contextual actions: open URLs, call, message, calendar, reminders
- **Automate** — Local rules with typed triggers and safety confirmations

## Privacy & Security

- **No INTERNET permission** — Structural guarantee that data cannot be uploaded
- **SQLCipher encryption** — Database encrypted with Android Keystore-managed keys
- **App lock** — Biometric or device credential authentication
- **Sensitive content protection** — OTPs, financial data, and identity documents are classified and protected
- **Clipboard protection** — Sensitive copies auto-clear after 30 seconds
- **Notification redaction** — Sensitive content never appears in notifications
- **Complete data deletion** — One-tap removal of all application data

## Performance

- Handles 10,000+ screenshots with keyset pagination
- Incremental indexing — only new/changed files are processed
- Background processing respects battery and thermal constraints
- Lazy initialization for fast startup

## Technical

- Android 10+ (API 29)
- Target SDK 37 (Android 14)
- arm64-v8a, armeabi-v7a, x86_64
- 47 MB APK (debug), ~18 MB release AAB download

## Known Limitations

See [KNOWN_LIMITATIONS.md](KNOWN_LIMITATIONS.md) for the complete list.

## Feedback

Report bugs or suggest features via the in-app feedback channel or the project's
GitHub repository.