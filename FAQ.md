# Screenshot Intelligence — FAQ

## General

### What is Screenshot Intelligence?
A local Android app that makes your screenshot library searchable, organized, and actionable. All processing happens on your device.

### Does it upload my screenshots?
No. The app has no INTERNET permission. Your screenshots never leave your device.

### Does it require an account?
No. No account creation, no sign-in, no cloud sync.

### What Android versions are supported?
Android 10 (API 29) and above.

### How much storage does it use?
The app itself is ~18 MB. Your indexed data (OCR text, embeddings) is stored in an encrypted local database. Actual usage depends on your screenshot library size.

## Indexing

### How do I add screenshots?
The app automatically discovers screenshots via MediaStore. You can also trigger manual scans from Settings.

### Why is indexing slow?
Indexing involves OCR, entity extraction, and embedding generation. Large libraries take time. You can pause/resume indexing and configure performance settings.

### Can I exclude folders?
Yes. Settings > Indexing > Excluded Folders.

### What happens if I delete a screenshot from my gallery?
The app detects the change during the next scan and removes it from the index.

## Search

### What can I search for?
Text (OCR), prices, dates, phone numbers, URLs, emails, OTPs, and visual similarity.

### Why didn't my search find a screenshot?
- The screenshot may not be indexed yet
- OCR may not have recognized the text (poor quality, handwriting)
- The search term may not match exactly

### What is semantic search?
Search by meaning rather than exact text. Uses local embeddings.

### What is visual search?
Find screenshots that look similar based on image features (colors, layout).

## Privacy

### How do I enable app lock?
Settings > Privacy & Security > App Lock. Uses your device's biometric or screen lock.

### How do I delete all my data?
Settings > Privacy & Security > Delete All Data. This is irreversible.

### Can I export my data?
Yes. Settings > Data > Export. You choose what to export.

### Are notifications safe?
Yes. Notifications never contain sensitive content. They show generic descriptions like "Sensitive content detected."

## Assistant

### What can I ask the assistant?
Questions about your screenshot library: "What was the Pixel 9a price?", "Find receipts from last week", "Show me OTP screenshots".

### Does the assistant remember conversations?
Only if you enable assistant history in Settings > Privacy. History is stored locally and encrypted.

### Why couldn't the assistant answer my question?
The assistant can only answer based on indexed screenshot content. If the relevant screenshot isn't indexed or OCR failed, it cannot answer.

## Actions

### What actions can I take?
Open URLs, call numbers, send messages, add to calendar, create reminders, save expenses, share content.

### Are actions safe?
Yes. Sensitive actions require confirmation. Sharing warns about detected sensitive content.

## Troubleshooting

See [TROUBLESHOOTING.md](TROUBLESHOOTING.md) for common issues.