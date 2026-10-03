# Screenshot Intelligence — Troubleshooting

## Indexing Issues

### Screenshots not appearing
1. Check that the app has READ_MEDIA_IMAGES permission
2. Check that the screenshot folder is not excluded (Settings > Indexing > Excluded Folders)
3. Trigger a manual scan (Settings > Indexing > Scan Now)
4. Check if the screenshot is in a supported format (PNG, JPG, WEBP)

### Indexing stuck or slow
- Large libraries take time. Check progress in Settings > Indexing.
- Pause and resume indexing if needed.
- Switch to "Battery Saver" mode in Settings > Indexing > Performance.
- Ensure the device is charging for faster indexing.

### "Failed" screenshots
- The image may be corrupted or inaccessible
- The file may have been deleted from storage
- Long screenshots may exceed memory limits — try again with more free RAM

## Search Issues

### No results found
- Try broader search terms
- Check that the screenshot is indexed (not PENDING or FAILED)
- Disable filters that may be too restrictive
- Try semantic search instead of exact text

### Search is slow
- Large libraries may take longer for complex queries
- Disable semantic search in Settings > Search if not needed
- Clear search cache in Settings > Privacy > Clear Cache

## App Lock Issues

### Biometric not working
- Ensure your device has biometrics enrolled in Android settings
- Try device credential fallback (PIN/pattern/password)
- Restart the app

### Forgot unlock method
- Use your device's screen lock (PIN/pattern/password)
- If all methods fail, you may need to clear app data (this deletes all indexed data)

## Storage Issues

### Running out of storage
- Clear thumbnail cache: Settings > Privacy > Clear Cache
- Delete old screenshots from your gallery
- Export and delete old data: Settings > Data > Export, then Delete

### Database corruption
- The app will detect and report corruption
- Use Settings > Privacy > Repair to rebuild derived indexes
- As a last resort, Clear Index and re-index

## Assistant Issues

### Assistant not responding
- Ensure the app is not in battery saver mode
- Check that the assistant has permission to access screenshots
- Try a simpler question

### Incorrect answers
- OCR may have misread text — check the original screenshot
- The assistant only knows what's in your indexed screenshots
- Semantic search may not understand context — try more specific terms

## Performance Issues

### App is slow
- Close other apps to free RAM
- Reduce indexing performance in Settings > Indexing > Performance
- Clear cache in Settings > Privacy > Clear Cache

### High battery usage
- Reduce background indexing frequency
- Disable visual search if not needed
- Enable battery saver mode

## Data Issues

### Export fails
- Ensure sufficient storage space
- Try exporting to a different location
- Export smaller batches

### Import fails
- Verify the export file is not corrupted
- Ensure the file was exported from a compatible version
- Check that the file format is supported

## Still Need Help?

See [SUPPORT.md](SUPPORT.md) for contact options.