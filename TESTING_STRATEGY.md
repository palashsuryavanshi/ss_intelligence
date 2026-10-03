# Testing Strategy

## Test Levels

### Unit Tests (JVM)
- **Framework**: JUnit 4 + kotlinx-coroutines-test
- **Coverage**: Parsers, classifiers, ranking, repository logic, security
- **Execution**: `./gradlew test`
- **Count**: 454+ tests

### Integration Tests (Instrumented)
- **Framework**: AndroidX Test + Room Testing
- **Coverage**: Database migrations, SQLCipher encryption, workers
- **Execution**: `./gradlew connectedAndroidTest`
- **Count**: 98+ tests

### UI Tests (Manual)
- **Coverage**: Navigation, search, viewer, settings, onboarding
- **Execution**: Manual testing on device
- **Tools**: Android Studio Layout Inspector, Profiler

## Test Categories

### Functional
- Screenshot ingestion → OCR → Search → Action
- Collections CRUD
- Automation rules CRUD
- Assistant Q&A
- Data export/import

### Performance
- Cold/warm startup
- Search latency (1K, 10K, 50K screenshots)
- Memory usage during indexing
- Battery consumption
- Large list scrolling

### Security
- SQLCipher encryption/decryption
- App lock authentication
- Clipboard protection
- Sensitive content detection
- Deep link validation
- Backup exclusion

### Reliability
- Process death during indexing
- Database corruption recovery
- Worker interruption
- Low storage handling
- Permission revocation
- Migration from previous versions

### Accessibility
- TalkBack navigation
- Large font scaling
- High contrast
- Keyboard navigation
- Touch target sizes

## Regression Suite

Every release must pass:
1. All unit tests
2. All instrumented tests
3. Clean install → onboarding → index → search → delete
4. Upgrade from previous version
5. Offline functionality test
6. Large library stress test (10K+ screenshots)

## Test Data

- Synthetic screenshots (no real user data)
- Various image sizes and formats
- Multiple languages (Latin script)
- Edge cases: very long screenshots, corrupted files, low-quality images

## Continuous Integration

- Unit tests run on every PR
- Instrumented tests run on release candidates
- Lint and static analysis on every build
- Dependency vulnerability scanning