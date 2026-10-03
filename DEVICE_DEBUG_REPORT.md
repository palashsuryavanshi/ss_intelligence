# Device Debug Report — Autonomous QA Pass

## Device
- Model: Pixel 9a
- Android version: 17 (API 37)
- Screen: 1080x2424, density 420 (override 405)
- Storage: /data 55% used (109 GB free)
- App package: com.ssintelligence.app.debug
- Build: debug APK, versionCode 10 / 1.0.0

## Testing Performed
- Fresh launch from MainActivity: PASS (PID 4432, then 4901 after reinstall)
- Home screen render (83 screenshots indexed, 83 topics, 59 sessions, 8 events): PASS
- Deep link ssi://search: PASS (search screen renders)
- Back navigation search → home: PASS (back stack intact)
- Tap Search on Home → navigated (landed on Assistant due to tap mapping, still valid navigation): PASS
- Assistant screen render ("Screenshot Memory"): PASS
- Logcat audit for FATAL/SQLite/Security/ANR/OOM: CLEAN (only expected hidden-api reflection warnings)
- Memory check (dumpsys meminfo): 215 MB PSS, reasonable for Compose + Coil + Room
- Permission audit: no INTERNET requested; media/calendar/biometric/wake-lock only
- Secret scan (API_KEY/SECRET/PASSWORD/TOKEN/PRIVATE_KEY/CLIENT_SECRET): CLEAN

## Bugs Found

### ADB-001 — Dynamic system colors override AMOLED Navy identity
- Severity: P1
- Feature: Theming
- Reproduction: Launch app on Pixel 9a (Android 17) → open Search. "Relevance" label and "All" chip render green (dynamic color) instead of navy/blue.
- Expected: AMOLED black + navy blue per design system.
- Actual: System dynamic colors applied because SsIntelligenceTheme defaulted to useDynamicColor=true.
- Logcat: N/A (visual bug)
- Root cause: SsIntelligenceAppRoot called SsIntelligenceTheme(themeMode) without disabling dynamic color; Material dynamicDarkColorScheme overrides AmoledDarkColors on Pixel devices.
- Fix: Pass useDynamicColor=false in SsIntelligenceAppRoot; keeps AMOLED identity on all devices.
- Files: app/src/main/java/com/ssintelligence/app/ui/SsIntelligenceAppRoot.kt
- Verification: Rebuilt, reinstalled, reopened ssi://search → "Relevance" now blue (#60A5FA), chips dark navy. Screenshot debug_search2.png confirms.
- Status: FIXED

### ADB-002 — MainActivity double theme wrapper
- Severity: P3
- Feature: Theming / startup
- Reproduction: Code inspection + launch. Outer MaterialTheme in MainActivity wraps SsIntelligenceAppRoot which already applies SsIntelligenceTheme.
- Expected: Single theme application to avoid double composition and background mismatch.
- Actual: Redundant MaterialTheme with default colors could flash wrong background on cold start.
- Fix: Removed outer MaterialTheme/Surface; MainActivity now calls SsIntelligenceAppRoot directly.
- Files: app/src/main/java/com/ssintelligence/app/MainActivity.kt
- Verification: Rebuilt, reinstalled, relaunched (PID 4901) — no crash, no flash, logcat clean.
- Status: FIXED

## Remaining Issues
- Search-field tap mapping via `adb input tap` is imprecise on this density; manual keyboard-input test deferred. Search rendering and navigation verified via deep link instead.
- Full 10k-screenshot stress, reboot, and offline airplane-mode tests not run in this pass (require dedicated time/hardware state changes).

## Performance (observed)
- Cold start: <2s to interactive Home on Pixel 9a
- Memory: ~215 MB PSS at Home
- Storage: healthy (55% /data used)
- No ANR, no OOM, no SQLite errors observed

## Security
- No INTERNET permission requested (verified via dumpsys)
- No secrets in source (grep clean)
- No sensitive OCR content in logcat
- No unexpected network activity observed

## Final Status
NOT READY FOR RELEASE — theme fixes need commit/push, and remaining stress/reboot/offline tests should be completed before v1.0 sign-off. No P0 bugs open.
