# Rugged QR Scanner

A Flutter plugin for scanning QR codes and barcodes using the built-in hardware scanner on rugged Android devices. Provides seamless integration with industrial-grade barcode scanners from CipherLab

## Features

- 🔧 **Hardware Scanner Support** - Direct integration with built-in infrared/laser scanners
- 📱 **Multiple Manufacturers** - CipherLab, Zebra, Honeywell, Datalogic, Unitech
- 🎯 **Simple API** - Easy-to-use Flutter interface
- 📊 **Real-time Scanning** - Stream-based results for instant UI updates
- 🔄 **Background Support** - Receives scans even when app is in background
- ⚡ **Fast & Reliable** - Optimized for industrial environments

## Supported Devices

### CipherLab

RS36, RS31, RS35, RK95 series and other devices with Reader_Service

**SDK Required**: Yes - `barcodebase.jar`

## Installation

Add to your `pubspec.yaml`:

```yaml
dependencies:
  rugged_qr_scanner: ^1.0.0
```

## Quick Start

```dart
import 'package:rugged_qr_scanner/rugged_qr_scanner.dart';

class ScannerScreen extends StatefulWidget {
  @override
  _ScannerScreenState createState() => _ScannerScreenState();
}

class _ScannerScreenState extends State<ScannerScreen> {
  final _scannerService = RuggedScannerService();
  StreamSubscription<ScanResult>? _scanSubscription;
  
  @override
  void initState() {
    super.initState();
    _initScanner();
  }
  
  Future<void> _initScanner() async {
    // Check if device supports hardware scanner
    final isSupported = await _scannerService.isHardwareScannerSupported();
  
    if (isSupported) {
      // Enable the scanner
      await _scannerService.enableHardwareScanner();
  
      // Listen for scan results
      _scanSubscription = _scannerService.scanResults.listen((result) {
        print('Scanned: ${result.code}');
        print('Format: ${result.format}');
        print('Source: ${result.source}');
      });
    }
  }
  
  @override
  void dispose() {
    _scanSubscription?.cancel();
    _scannerService.dispose();
    super.dispose();
  }
}
```

## CipherLab SDK Setup (Required for CipherLab Devices)

CipherLab devices require the `barcodebase.jar` SDK. Without it, scans will not be received.

### 1. Obtain the SDK

**From your device:**

```bash
adb pull /system/priv-app/ReaderService_CipherLab/ReaderService_CipherLab.apk
# Extract barcodebase.jar from the APK
```

**Or** contact CipherLab support for the SDK package.

### 2. Add to your project

```bash
# Create libs directory
mkdir -p android/app/libs

# Copy SDK
cp barcodebase.jar android/app/libs/
```

### 3. Update build.gradle.kts

```kotlin
// android/app/build.gradle.kts
dependencies {
    implementation(files("libs/barcodebase.jar"))
}
```

### 4. Add ProGuard rules (Release builds)

Create `android/app/proguard-rules.pro`:

```proguard
# Keep all CipherLab SDK classes - they are accessed via reflection
-keep class com.cipherlab.** { *; }
-keepclassmembers class com.cipherlab.** { *; }

# Specifically keep ReaderDataStruct and its methods (accessed via reflection)
-keep class com.cipherlab.barcodebase.ReaderDataStruct { *; }
-keepclassmembers class com.cipherlab.barcodebase.ReaderDataStruct {
    public *;
}

# Keep all methods in ReaderDataStruct that are accessed via reflection
-keepclassmembers class com.cipherlab.barcodebase.ReaderDataStruct {
    public java.lang.String GetCodeDataStr();
    public byte[] getCodeDataArray();
    public java.lang.String GetCodeTypeStr();
}

# Keep the plugin classes
-keep class com.rugged.qr_scanner.** { *; }
-keepclassmembers class com.rugged.qr_scanner.** { *; }

# Keep BroadcastReceiver classes
-keep public class * extends android.content.BroadcastReceiver

# Keep all Serializable classes (ReaderDataStruct implements Serializable)
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep Parcelable implementations
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}

# Keep Flutter plugin classes
-keep class io.flutter.** { *; }
-keepclassmembers class io.flutter.** { *; }

# Keep Flutter deferred components classes (they reference optional Play Core)
-keep class io.flutter.embedding.engine.deferredcomponents.** { *; }
-keep class io.flutter.embedding.android.FlutterPlayStoreSplitApplication { *; }

# Don't warn about missing classes (SDK might not be present in all builds)
-dontwarn com.cipherlab.**

# Suppress warnings for optional Play Core dependencies (used by Flutter for deferred components)
# These are optional and not required for most apps
-dontwarn com.google.android.play.core.splitcompat.**
-dontwarn com.google.android.play.core.splitinstall.**
-dontwarn com.google.android.play.core.tasks.**
```

Enable in `android/app/build.gradle.kts`:

```kotlin
android {
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}
```

## Complete Example

```dart
import 'package:flutter/material.dart';
import 'package:rugged_qr_scanner/rugged_qr_scanner.dart';
import 'dart:async';

void main() => runApp(MyApp());

class MyApp extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Rugged Scanner Demo',
      theme: ThemeData(primarySwatch: Colors.blue),
      home: ScannerScreen(),
    );
  }
}

class ScannerScreen extends StatefulWidget {
  @override
  _ScannerScreenState createState() => _ScannerScreenState();
}

class _ScannerScreenState extends State<ScannerScreen> {
  final _scannerService = RuggedScannerService();
  StreamSubscription<ScanResult>? _scanSubscription;
  
  bool _isSupported = false;
  bool _isEnabled = false;
  List<ScanResult> _scanHistory = [];
  
  @override
  void initState() {
    super.initState();
    _checkSupport();
  }
  
  Future<void> _checkSupport() async {
    final isSupported = await _scannerService.isHardwareScannerSupported();
    setState(() => _isSupported = isSupported);
  
    if (isSupported) {
      _setupScanListener();
      await _toggleScanner(); // Auto-enable
    }
  }
  
  void _setupScanListener() {
    _scanSubscription = _scannerService.scanResults.listen((result) {
      setState(() => _scanHistory.insert(0, result));
    });
  }
  
  Future<void> _toggleScanner() async {
    if (_isEnabled) {
      await _scannerService.disableHardwareScanner();
    } else {
      await _scannerService.enableHardwareScanner();
    }
    setState(() => _isEnabled = !_isEnabled);
  }
  
  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text('Hardware Scanner'),
        actions: [
          if (_isSupported)
            IconButton(
              icon: Icon(_isEnabled ? Icons.scanner : Icons.scanner_outlined),
              onPressed: _toggleScanner,
              tooltip: _isEnabled ? 'Disable Scanner' : 'Enable Scanner',
            ),
        ],
      ),
      body: Column(
        children: [
          // Status Card
          Card(
            margin: EdgeInsets.all(16),
            child: Padding(
              padding: EdgeInsets.all(16),
              child: Column(
                children: [
                  Row(
                    children: [
                      Icon(
                        _isSupported ? Icons.check_circle : Icons.cancel,
                        color: _isSupported ? Colors.green : Colors.red,
                      ),
                      SizedBox(width: 8),
                      Text(
                        _isSupported 
                            ? 'Hardware Scanner Available'
                            : 'No Hardware Scanner Detected',
                        style: TextStyle(fontWeight: FontWeight.bold),
                      ),
                    ],
                  ),
                  if (_isSupported) ...[
                    SizedBox(height: 8),
                    Row(
                      children: [
                        Icon(
                          _isEnabled ? Icons.power : Icons.power_off,
                          color: _isEnabled ? Colors.green : Colors.grey,
                          size: 20,
                        ),
                        SizedBox(width: 8),
                        Text(
                          _isEnabled ? 'Scanner Enabled' : 'Scanner Disabled',
                        ),
                      ],
                    ),
                  ],
                ],
              ),
            ),
          ),
      
          // Scan History
          Padding(
            padding: EdgeInsets.symmetric(horizontal: 16),
            child: Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Text(
                  'Scan History (${_scanHistory.length})',
                  style: Theme.of(context).textTheme.titleMedium,
                ),
                if (_scanHistory.isNotEmpty)
                  TextButton.icon(
                    onPressed: () => setState(() => _scanHistory.clear()),
                    icon: Icon(Icons.clear_all),
                    label: Text('Clear'),
                  ),
              ],
            ),
          ),
      
          Expanded(
            child: _scanHistory.isEmpty
                ? Center(
                    child: Column(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        Icon(Icons.qr_code_scanner, size: 64, color: Colors.grey),
                        SizedBox(height: 16),
                        Text(
                          _isEnabled
                              ? 'Press trigger button to scan'
                              : 'Enable scanner to start',
                          style: TextStyle(color: Colors.grey),
                        ),
                      ],
                    ),
                  )
                : ListView.builder(
                    itemCount: _scanHistory.length,
                    itemBuilder: (context, index) {
                      final scan = _scanHistory[index];
                      return Card(
                        margin: EdgeInsets.symmetric(horizontal: 16, vertical: 4),
                        child: ListTile(
                          leading: CircleAvatar(
                            child: Icon(Icons.qr_code),
                          ),
                          title: Text(
                            scan.code,
                            style: TextStyle(fontFamily: 'monospace'),
                          ),
                          subtitle: Text(
                            '${scan.format ?? 'Unknown'} • ${_formatTime(scan.timestamp)}',
                          ),
                          trailing: IconButton(
                            icon: Icon(Icons.copy),
                            onPressed: () {
                              // Copy to clipboard
                              ScaffoldMessenger.of(context).showSnackBar(
                                SnackBar(content: Text('Copied to clipboard')),
                              );
                            },
                          ),
                        ),
                      );
                    },
                  ),
          ),
        ],
      ),
    );
  }
  
  String _formatTime(DateTime time) {
    return '${time.hour.toString().padLeft(2, '0')}:'
           '${time.minute.toString().padLeft(2, '0')}:'
           '${time.second.toString().padLeft(2, '0')}';
  }
  
  @override
  void dispose() {
    _scanSubscription?.cancel();
    _scannerService.dispose();
    super.dispose();
  }
}
```

## API Reference

### RuggedScannerService

Main service class for scanner control.

#### Methods

**`Future<bool> isHardwareScannerSupported()`**

Checks if device has a hardware scanner.

**`Future<bool> enableHardwareScanner()`**

Enables the hardware scanner.

**`Future<bool> disableHardwareScanner()`**

Disables the hardware scanner.

**`Stream<ScanResult> get scanResults`**

Broadcast stream that emits scan results.

**`void dispose()`**

Cleans up resources.

### ScanResult

Model class for scan results.

#### Properties

- `String code` - The scanned data
- `DateTime timestamp` - When scan occurred
- `String? format` - Barcode format (e.g., "QR_CODE", "CODE_128")
- `String source` - Always "hardware_scanner"

## Troubleshooting

### Scans not appearing (CipherLab)

1. Verify SDK is added to `android/app/libs/barcodebase.jar`
2. Check `build.gradle.kts` includes: `implementation(files("libs/barcodebase.jar"))`
3. Clean and rebuild: `flutter clean && flutter pub get && flutter build apk`

### Release build crashes

Add ProGuard rules (see [CipherLab SDK Setup](#cipherlab-sdk-setup-required-for-cipherlab-devices))

### Scanner enabled but nothing happens

1. Verify you're listening to the stream:

   ```dart
   _scannerService.scanResults.listen((result) {
     print('Scan: ${result.code}');
   });
   ```
2. Check device logs:

   ```bash
   adb logcat | grep -E "(RuggedScanner|flutter)"
   ```

### Device not detected

Check if your device model is supported. The plugin detects:

- CipherLab: Models containing "RS30", "RS31", "RS35", "RS36", "RS31", "RS35", "RK25", "RK95", "CP30", "CP50"

## Platform Support

| Platform | Supported |
| -------- | --------- |
| Android  | ✅ Yes    |
| iOS      | ❌ No     |
| Web      | ❌ No     |
| Windows  | ❌ No     |
| macOS    | ❌ No     |
| Linux    | ❌ No     |

**Why Android only?** Hardware barcode scanners are exclusive to rugged Android devices used in industrial/enterprise environments.

## Additional Information

- **Package**: [rugged_qr_scanner](https://pub.dev/packages/rugged_qr_scanner)
- **Repository**: [GitHub](https://github.com/yourusername/rugged_qr_scanner)
- **Issue Tracker**: [GitHub Issues](https://github.com/yourusername/rugged_qr_scanner/issues)
- **License**: MIT

## Contributing

Contributions welcome! Please see [CONTRIBUTING.md](https://github.com/yourusername/rugged_qr_scanner/blob/main/CONTRIBUTING.md) for guidelines.

## License

MIT License - see [LICENSE](https://github.com/yourusername/rugged_qr_scanner/blob/main/LICENSE) file.

---

**Note**: This plugin is designed for enterprise/industrial applications with rugged Android devices. For consumer apps, consider camera-based QR scanning packages like `mobile_scanner` or `qr_code_scanner`.
