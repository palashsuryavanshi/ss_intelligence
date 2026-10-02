# Threat Model

## Assets

| Asset | Sensitivity | Protection |
|-------|-------------|------------|
| Screenshot OCR text | High | Room DB, local processing |
| OTPs / auth codes | Critical | Never logged, never notified, reveal-gated |
| Financial data (receipts, prices) | High | Local storage, sensitive classification |
| Personal identifiers (emails, phones) | Medium | Regex-extracted, local matching |
| Assistant conversations | High | Optional history, local only |
| Automation rules | Medium | Local storage, no external access |
| Expense records | Medium | Local storage, confirmation-required |
| Encryption keys | N/A | Android Keystore (planned) |

## Threats

### T1: Lost / Stolen Device
**Risk**: High  
**Current Protection**: Android device lock screen is primary defense. App lock
(BiometricPrompt) is available as an additional layer (configurable).  
**Remaining Limitation**: Without app lock enabled, an unlocked device exposes
all data. Sensitive thumbnails in app switcher are protected with FLAG_SECURE
when enabled.  
**Mitigation**: Enable app lock in Settings > Privacy.

### T2: Malicious App
**Risk**: Medium  
**Current Protection**: No `INTERNET` permission; no `QUERY_ALL_PACKAGES`;
no accessibility services used.  
**Remaining Limitation**: A malicious app with root access could read the
database if the device is rooted.  
**Mitigation**: Standard Android sandboxing protects non-rooted devices.

### T3: Malicious Imported File
**Risk**: Medium  
**Current Protection**: Import validation (schema check, size limit, path
traversal rejection) is planned.  
**Remaining Limitation**: May not cover all zip bomb variants.  
**Mitigation**: Size limits and archive inspection are enforced.

### T4: Database Extraction
**Risk**: High  
**Current Protection**: Database is in app-private storage. SQLCipher encryption
is evaluated for a future release.  
**Remaining Limitation**: On rooted devices, the database can be extracted.
Full encryption via SQLCipher with Android Keystore is the planned mitigation.  
**Mitigation**: App lock + device credential + no INTERNET permission.

### T5: Backup Exposure
**Risk**: Medium  
**Current Protection**: `allowBackup="false"` and database is excluded from
backup rules.  
**Remaining Limitation**: None for database; preferences are small and safe.  
**Mitigation**: Backup rules are explicitly configured.

### T6: Accidental Sharing
**Risk**: High  
**Current Protection**: Sensitive content detection warns before sharing.
Sharing is always user-initiated, specific to selected screenshots.  
**Remaining Limitation**: Redaction UI is planned but not yet implemented.  
**Mitigation**: Warning dialog + review option before share.

### T7: Notification Leakage
**Risk**: High  
**Current Protection**: Notifications use redacted descriptions
("Sensitive content detected") instead of actual OTP values or amounts.  
**Remaining Limitation**: Basic redaction only; does not redact
screenshot-classification-specific terms.  
**Mitigation**: Default privacy-first notification text.

### T8: Clipboard Leakage
**Risk**: Medium  
**Current Protection**: Sensitive copies are marked with `IS_SENSITIVE`
(Android 13+) and auto-cleared after 30 seconds.  
**Remaining Limitation**: Older devices don't support IS_SENSITIVE.  
**Mitigation**: Auto-clear timeout works on all API levels.

### T9: Log Leakage
**Risk**: Medium  
**Current Protection**: `AppLog` is a no-op in release builds; never logs OCR
text, OTPs, or personal data.  
**Remaining Limitation**: Third-party libraries may log sensitive data via
their own tags.  
**Mitigation**: Review transitive library logging in release builds.

### T10: Model Compromise
**Risk**: Low  
**Current Protection**: Local models are bundled or validated on download.
Hash verification is planned.  
**Remaining Limitation**: No runtime model integrity check yet.  
**Mitigation**: Model manifest with SHA-256 hashes is planned.

### T11: Malicious Deep Link
**Risk**: Low  
**Current Protection**: Deep links are validated against expected URI patterns
(`ssi://`), never parsed as arbitrary URLs. No external component can launch
exported activities without a valid deeplink.  
**Remaining Limitation**: None identified for current implementation.  
**Mitigation**: Scheme and path validation.

### T12: Automation Abuse
**Risk**: Low  
**Current Protection**: Automations cannot send messages, make calls, or share
without explicit user confirmation per execution. No shell/root/accessibility
APIs are used.  
**Remaining Limitation**: Calendar events and expense saves still execute
without per-run confirmation.  
**Mitigation**: Automation master toggle + per-rule enable/disable.

## Summary

| Threat | Risk | Protection Level |
|--------|------|-----------------|
| Lost/stolen device | High | Medium (app lock optional) |
| Malicious app | Medium | High (sandboxing + no INTERNET) |
| Malicious import | Medium | Low (validation planned) |
| Database extraction | High | Medium (rooted risk) |
| Backup exposure | Medium | High (allowBackup=false) |
| Accidental sharing | High | Medium (warning + redaction planned) |
| Notification leakage | High | High (redacted by default) |
| Clipboard leakage | Medium | Medium (IS_SENSITIVE + auto-clear) |
| Log leakage | Medium | High (AppLog no-op in release) |
| Model compromise | Low | Low (hash verification planned) |
| Malicious deep link | Low | High (validated URIs) |
| Automation abuse | Low | Medium (confirmations + no shell) |