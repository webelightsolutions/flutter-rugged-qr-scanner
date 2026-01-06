## 0.0.1

### Initial Release

First stable release of the Rugged QR Scanner package for Flutter, providing hardware barcode/QR code scanning support for rugged Android devices.

#### Features

* **Hardware Scanner Support** - Direct integration with built-in infrared/laser scanners on rugged Android devices
* **Multi-Manufacturer Support** - Compatible with scanners from:
  - CipherLab (RS36, RS31, RS35, RK95 series and other devices with Reader_Service)
* **Stream-Based API** - Real-time scan results via `Stream<ScanResult>` for instant UI updates
* **Automatic Scanner Detection** - Automatically detects available hardware scanners on device initialization
* **Enable/Disable Control** - Programmatic control to enable/disable scanner functionality
* **Background Scanning** - Receives scan results even when app is in background
* **Rich Scan Results** - Each scan result includes:
  - Scanned code/data
  - Timestamp
  - Barcode format (QR_CODE, CODE_128, EAN_13, etc.)
  - Source identifier
* **Availability Monitoring** - Stream-based availability tracking for hardware scanner status changes
* **CipherLab SDK Integration** - Support for CipherLab devices via `barcodebase.jar` SDK (reflection-based for flexibility)
* **Broadcast Receiver Support** - Native Android broadcast receiver implementation for reliable scan data capture
* **Comprehensive Error Handling** - Graceful fallbacks when scanners are unavailable
* **Debug Logging** - Extensive debug logging for troubleshooting scanner issues

#### Platform Support

* ✅ Android (primary platform)
* ❌ iOS (not supported - hardware scanners are Android-specific)
* ❌ Web, Windows, macOS, Linux (not supported)

#### Requirements

* CipherLab devices: Requires `barcodebase.jar` SDK to be added to the app
* Please add Proguard rules properly

#### API Highlights

* `RuggedScannerService` - Main service class for scanner control
* `ScanResult` - Model class for scan results
* `scanResults` - Stream of scan results
* `availabilityStream` - Stream of scanner availability changes
* `isAvailable` - Property to check if hardware scanner is available
* `isEnabled` - Property to check if scanner is currently enabled
* `enable()` - Method to enable the hardware scanner
* `disable()` - Method to disable the hardware scanner
* `dispose()` - Method to clean up resources

#### Documentation

* Complete README with setup instructions
* CipherLab SDK setup guide
* API reference documentation
* Complete example app
* Troubleshooting guide
