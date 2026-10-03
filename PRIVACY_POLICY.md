# Screenshot Intelligence — Privacy Policy

## Overview

Screenshot Intelligence is a local-first Android application. Your screenshots
are processed on your device and are not uploaded to any server.

## Data We Access

| Data | Purpose | Stored |
|------|---------|--------|
| Screenshot images | OCR, search, visual analysis | Not stored (MediaStore URI only) |
| OCR text | Full-text search, entity extraction | Encrypted local database |
| Extracted entities (URLs, phones, dates, prices, emails, OTPs) | Search, actions, assistant | Encrypted local database |
| Visual features (dhash, colors) | Visual search | Encrypted local database |
| Semantic embeddings | Semantic search | Encrypted local database |
| Assistant conversations | Conversation history | Encrypted local database (optional) |
| Search queries | Recent searches | Encrypted local database (optional) |
| Collections, tags, automation rules | Organization | Encrypted local database |
| Settings | App behavior | Encrypted local storage |

## Data We Never Access

- Your contacts
- Your location
- Your microphone
- Your camera
- Other apps' data
- Network requests (no INTERNET permission)

## Network Behavior

Screenshot Intelligence has **no INTERNET permission**. It cannot upload
screenshots, OCR text, embeddings, or any other data to external servers.
This is enforced at the Android manifest level.

Some actions naturally involve other applications:
- Opening a URL in a browser
- Sharing content via the system share sheet
- Placing a phone call
- Sending a message

These actions are always explicit and user-initiated.

## AI Models

All AI processing uses on-device models:
- **OCR**: Google ML Kit Text Recognition (bundled with app)
- **Embeddings**: Built-in hashed n-gram provider (no download)
- **Visual analysis**: Local image processing (no model download)

No model requires network access. No model data leaves the device.

## Data Storage

All application data is stored in an encrypted SQLite database using SQLCipher.
Encryption keys are managed by Android Keystore and never leave the device.

## Data Sharing

Data is shared only when you explicitly choose to:
- Share a screenshot or text via the system share sheet
- Export data to a local file
- Perform an action that opens another application

## Analytics and Crash Reporting

**Analytics**: Disabled by default. No screenshot content is ever tracked.
**Crash reporting**: Disabled by default. No crash data is collected.

## Data Deletion

You can delete your data at any time:
- **Per-screenshot**: Delete a screenshot removes all derived data
- **Complete**: Settings > Privacy > Delete All Data removes everything

Deletion is immediate and irreversible.

## Permissions

| Permission | Purpose |
|------------|---------|
| READ_MEDIA_IMAGES | Discover screenshots |
| WRITE_CALENDAR | Create calendar events from automation |
| USE_BIOMETRIC | App lock |
| WAKE_LOCK | Background indexing |
| FOREGROUND_SERVICE | Indexing notifications |
| POST_NOTIFICATIONS | Indexing status (redacted) |

## Children's Privacy

This app is not directed at children under 13. It does not knowingly collect
personal information from children.

## Changes to This Policy

We will notify you of material changes through the app or via the app store
listing. The "last updated" date will be revised accordingly.

## Contact

For privacy questions, use the in-app feedback channel or the project's
GitHub repository.

---

*Last updated: 2026-10-03*