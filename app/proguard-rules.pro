# Preserve source file and line numbers for readable diagnostic stack traces
-keepattributes SourceFile,LineNumberTable

# Persisted enum names serialized to SharedPreferences and DataStore
-keep enum com.usbbootable.creator.ui.theme.ThemeMode { *; }
-keep enum com.usbbootable.creator.core.storage.WriteBufferOption { *; }
-keep enum com.usbbootable.creator.core.storage.ProgressBarStyle { *; }
-keep enum com.usbbootable.creator.core.writer.WritePhase { *; }
