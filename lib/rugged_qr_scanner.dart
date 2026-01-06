/// Rugged QR Scanner - Flutter package for hardware QR/barcode scanning
/// on rugged Android devices.
///
/// This package provides support for hardware scanners from various manufacturers
/// including CipherLab, Zebra, Honeywell, Datalogic, Unitech, and generic scanners.
///
/// ## Getting Started
///
/// 1. Add the package to your `pubspec.yaml`:
/// ```yaml
/// dependencies:
///   rugged_qr_scanner: ^1.0.0
/// ```
///
/// 2. For CipherLab devices, add the SDK to your app:
///    - Place `barcodebase.jar` in `android/app/libs/`
///    - Add to `android/app/build.gradle.kts`:
///      ```kotlin
///      dependencies {
///          implementation(files("libs/barcodebase.jar"))
///      }
///      ```
///
/// 3. Use the service:
/// ```dart
/// import 'package:rugged_qr_scanner/rugged_qr_scanner.dart';
///
/// final scannerService = RuggedScannerService();
/// scannerService.scanResults.listen((result) {
///   print('Scanned: ${result.code}');
/// });
/// await scannerService.enable();
/// ```
///
/// See the [README](https://github.com/webelightsolutions/flutter-rugged-qr-scanner) for
/// detailed documentation and examples.
library rugged_qr_scan;

export 'src/models/scan_result.dart';
export 'src/services/rugged_scanner_service.dart';
