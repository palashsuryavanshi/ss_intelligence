# Database Architecture

## Overview

- **Engine**: SQLite via SQLCipher (encrypted at rest)
- **ORM**: Room 2.8.4
- **Schema version**: 7
- **Encryption**: AES-256, key from Android Keystore

## Entity Relationship

```
screenshots (1) ──→ (N) extracted_urls
                ──→ (N) extracted_dates
                ──→ (N) extracted_phones
                ──→ (N) extracted_prices
                ──→ (N) extracted_otps
                ──→ (N) extracted_receipts
                ──→ (N) ocr_blocks
                ──→ (1) screenshot_embeddings
                ──→ (1) screenshot_categories
                ──→ (1) screenshot_visuals
                ──→ (N) topics
                ──→ (N) sessions
                ──→ (N) events
                ──→ (N) screenshot_tags
                ──→ (N) suggestions
                ──→ (1) archive_state
                ──→ (N) local_reminders
                ──→ (N) expense_records
                ──→ (N) action_history
                ──→ (N) collection_members

graph_entities (1) ──→ (N) graph_relations
collections (1) ──→ (N) collection_members

assistant_conversations (1) ──→ (N) assistant_messages
                                    ──→ (N) assistant_evidence
memory_snapshots (1) ──→ (N) memory_snapshot_items

automation_rules (1) ──→ (N) automation_executions
```

## Key Indexes

| Table | Index | Purpose |
|-------|-------|---------|
| screenshots | media_store_id (unique) | Deduplication |
| screenshots | content_hash | Duplicate detection |
| screenshots | date_added, id | Keyset pagination |
| screenshots | status | Worker queries |
| screenshots_fts | rowid | Full-text search |
| extracted_urls | host | Domain filter |
| extracted_prices | currency, amount | Price filter |
| extracted_dates | epoch_day | Date filter |
| graph_relations | entity_id | Entity search |
| collection_members | collection_id, screenshot_id (unique) | Membership |

## Migrations

| Version | Changes |
|---------|---------|
| 1→2 | search_history table |
| 2→3 | screenshot_embeddings, screenshot_categories |
| 3→4 | screenshot_visuals, graph_entities, graph_relations, collections, collection_members |
| 4→5 | assistant_conversations, assistant_messages, assistant_evidence, memory_snapshots, memory_snapshot_items |
| 5→6 | topics, sessions, events, screenshot_tags, suggestions, archive_state |
| 6→7 | local_reminders, expense_records, action_history, automation_rules, automation_executions, extracted_receipts |

## Encryption

- **Algorithm**: AES-256-CBC (SQLCipher default)
- **Key**: 32-byte random passphrase, wrapped with AES-256-GCM
- **Key storage**: Android Keystore (TEE-backed)
- **Passphrase storage**: SharedPreferences (encrypted with Keystore key)

## Backup

- `allowBackup="false"` in manifest
- Database excluded from backup rules
- No cloud backup by design