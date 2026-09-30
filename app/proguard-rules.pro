# SS Intelligence — release shrinking rules.

# Keep Room generated implementations (referenced by name at runtime).
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Database class *

# ML Kit Text Recognition is accessed through its public API surface.
-keep class com.google.mlkit.vision.text.** { *; }

# Strip Logcat logging from release builds so OCR-adjacent metadata can never
# leak through logs. AppLog is already a no-op in release; this is belt-and-braces.
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
}
