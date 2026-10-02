# Security Architecture

## Overview

Screenshot Intelligence is a **local-only** Android application. There is no
backend, no cloud AI, and no network communication by default. The security
model is built on:

1. **Structural privacy**: No `INTERNET` permission in the merged manifest.
2. **Encryption at rest**: Sensitive derived data is local; database encryption
   via SQLCipher is evaluated for a future release.
3. **App lock**: Optional BiometricPrompt + device credential fallback.
4. **Sensitive content detection**: Deterministic pattern matching on OCR text.
5. **Secure defaults**: Analytics OFF, search history OFF by default, sensitive
   previews protected, notifications redacted.

## Architecture Layers

```
UI (Compose Screens)
  ↓
Privacy & Security Managers (PrivacyManager, AppLockManager, etc.)
  ↓
Application Services (Assistant, Automation, Search)
  ↓
Repository Layer (ScreenshotRepository, ActionRepository, etc.)
  ↓
Room Database (local SQLite, encrypted at rest via AOSP file-based encryption)
```

## Key Components

| Component | Package | Purpose |
|-----------|---------|---------|
| `SensitiveContentDetector` | security | Classifies OCR text by sensitivity level |
| `PrivacyManager` | security | Centralized privacy settings |
| `AppLockManager` | security | BiometricPrompt / device credential auth |
| `ClipboardSecurityManager` | security | Clipboard with IS_SENSITIVE + auto-clear |
| `DataDeletionManager` | security | Complete and per-screenshot deletion |
| `AppLog` (util) | util | Privacy-aware logging (debug-only, no OCR text) |

## Sensitive Data Handling

- OCR text is stored in Room but is the primary input for classification.
- Sensitive thumbnails are blurred or replaced with placeholders (planned).
- OTPs and authentication codes are never logged, notified, or shared without
  explicit user confirmation.
- Assistant conversations are persisted only if the user enables history.
- Search queries are not persisted unless the user enables search history.

## Backup Policy

| Data | Backup | Rationale |
|------|--------|-----------|
| Room database | Excluded | Contains sensitive OCR text |
| User preferences | Safe to backup | Non-sensitive UI settings |
| Encryption keys | Never backed up | Stored in Android Keystore |
| Screenshots | Not backed up | MediaStore references only |

Backup rules are defined in `res/xml/backup_rules.xml` and `allowBackup="false"`
is set on the application tag.

## File Security

- Screenshots are accessed via `content://` URIs (MediaStore); no copies are
  made by default.
- Temporary files for OCR/model processing are deleted after use.
- Exported files are user-initiated, local, and may contain sensitive data
  (warned to user).