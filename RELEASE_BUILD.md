# Release Build

## Build Configuration

```kotlin
android {
    namespace = "com.ssintelligence.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.ssintelligence.app"
        minSdk = 29
        targetSdk = 37
        versionCode = 10
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}
```

## Release Process

```bash
# 1. Clean checkout
git clone https://github.com/palashsuryavanshi/ss_intelligence.git
cd ss_intelligence

# 2. Run tests
./gradlew test
./gradlew connectedAndroidTest

# 3. Build release AAB
./gradlew bundleRelease

# 4. Verify artifact
aapt2 dump permissions app/build/outputs/bundle/release/app-release.aab
# Verify: no INTERNET permission

# 5. Sign (CI/local with secrets)
jarsigner -keystore release.keystore app-release.aab alias

# 6. Archive
mkdir -p release
cp app/build/outputs/bundle/release/app-release.aab release/
sha256sum release/app-release.aab > release/checksums.txt
```

## Signing

- **Keystore**: Stored outside source control (CI secrets or local `~/.android/`)
- **Key alias**: `ssintelligence`
- **Backup**: Keystore backed up to secure location
- **Rotation**: Documented process for key rotation

## Verification Checklist

Before publishing:
- [ ] `aapt2 dump permissions` shows no INTERNET
- [ ] `aapt2 dump badging` shows correct version
- [ ] APK installs on clean device
- [ ] Onboarding completes
- [ ] Indexing works
- [ ] Search works
- [ ] App lock works
- [ ] Delete all data works
- [ ] Offline functionality verified
- [ ] No debug logs in logcat
- [ ] No hardcoded secrets in source

## Artifact Archive

```
release/
├── app-release.aab
├── checksums.txt
├── RELEASE_NOTES.md
├── RELEASE_CHECKLIST.md
├── PRIVACY_POLICY.md
├── THIRD_PARTY_LICENSES.md
├── BUILD_INFO.md
└── TEST_REPORT.md
```

## Version History

| Version | Date | Changes |
|---------|------|---------|
| 1.0.0 | 2026-10-03 | Initial public release |