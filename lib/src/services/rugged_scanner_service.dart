import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import '../models/scan_result.dart';

/// Service for handling QR code and barcode scanning on rugged Android devices.
///
/// This service provides a unified interface for hardware scanners from various
/// manufacturers including CipherLab, Zebra, Honeywell, Datalogic, and Unitech.
///
/// The service automatically detects available hardware scanners and provides
/// a stream-based API for receiving scan results.
///
/// Example:
/// ```dart
/// final scannerService = RuggedScannerService();
///
/// // Listen to scan results
/// scannerService.scanResults.listen((result) {
///   print('Scanned: ${result.code}');
/// });
///
/// // Enable scanner
/// await scannerService.enable();
///
/// // Disable when done
/// await scannerService.disable();
///
/// // Dispose resources
/// scannerService.dispose();
/// ```
class RuggedScannerService {
  /// Method channel for communication with native Android code.
  static const MethodChannel _channel = MethodChannel(
    'com.rugged.qr_scanner',
  );

  /// Controller for the scan results stream.
  StreamController<ScanResult>? _scanController;

  /// Stream of scan results.
  Stream<ScanResult>? _scanStream;

  /// Controller for the availability stream.
  StreamController<bool>? _availabilityController;

  /// Stream of hardware scanner availability changes.
  Stream<bool> get _availabilityStream => _availabilityController?.stream ?? const Stream<bool>.empty();

  /// Whether a hardware scanner is available on this device.
  bool _isHardwareScannerAvailable = false;

  /// Whether the hardware scanner is currently enabled.
  bool _isHardwareScannerEnabled = false;

  /// Creates a new [RuggedScannerService] instance.
  ///
  /// The service will automatically initialize and detect available hardware scanners.
  RuggedScannerService() {
    _scanController = StreamController<ScanResult>.broadcast();
    _scanStream = _scanController!.stream;
    _availabilityController = StreamController<bool>.broadcast();
    _initializeHardwareScanner();
  }

  /// Initializes the hardware scanner detection and setup.
  ///
  /// This method is called automatically during service construction.
  /// It checks if a hardware scanner is supported and sets up the listener
  /// if one is available.
  ///
  /// Supported manufacturers:
  /// - CipherLab (RS36, etc.)
  /// - Zebra (TC series, MC series)
  /// - Honeywell (CT series, CN series)
  /// - Datalogic
  /// - Unitech
  /// - Generic barcode scanner intents
  Future<void> _initializeHardwareScanner() async {
    debugPrint('🔧 [RuggedScannerService] Initializing hardware scanner...');
    try {
      debugPrint(
        '📡 [RuggedScannerService] Checking if hardware scanner is supported...',
      );
      final bool supported = await _isHardwareScannerSupported();
      debugPrint(
        '📡 [RuggedScannerService] Hardware scanner supported: $supported',
      );

      if (supported) {
        _isHardwareScannerAvailable = true;
        debugPrint('✅ [RuggedScannerService] Hardware scanner is available');
        await _setupHardwareScannerListener();
        _availabilityController?.add(true);
        debugPrint(
          '✅ [RuggedScannerService] Hardware scanner detected and initialized',
        );
      } else {
        _isHardwareScannerAvailable = false;
        _availabilityController?.add(false);
        debugPrint(
          '⚠️ [RuggedScannerService] Hardware scanner not supported on this device',
        );
      }
    } catch (e, stackTrace) {
      debugPrint('❌ [RuggedScannerService] Hardware scanner not available: $e');
      debugPrint('❌ [RuggedScannerService] Stack trace: $stackTrace');
      _isHardwareScannerAvailable = false;
      _availabilityController?.add(false);
    }
  }

  /// Checks if a hardware scanner is supported on this device.
  ///
  /// Returns `true` if a hardware scanner is detected, `false` otherwise.
  ///
  /// This method communicates with the native Android code to check for
  /// available scanner services.
  Future<bool> _isHardwareScannerSupported() async {
    try {
      final result = await _channel.invokeMethod<bool>(
        'isHardwareScannerSupported',
      );
      return result ?? false;
    } catch (e) {
      // If the method call fails, assume scanner is not supported
      return false;
    }
  }

  /// Sets up the listener for hardware scanner events.
  ///
  /// This method registers a method call handler that receives scan results
  /// from the native Android code and forwards them to the [scanResults] stream.
  Future<void> _setupHardwareScannerListener() async {
    try {
      debugPrint(
        '🔧 [RuggedScannerService] Setting up hardware scanner listener...',
      );
      _channel.setMethodCallHandler((call) async {
        debugPrint(
          '📡 [RuggedScannerService] Method call received: ${call.method}',
        );
        debugPrint('📡 [RuggedScannerService] Arguments: ${call.arguments}');

        if (call.method == 'onHardwareScan') {
          try {
            final String code = call.arguments['code'] as String;
            final String? format = call.arguments['format'] as String?;

            debugPrint(
              '✅ [RuggedScannerService] Scan received - Code: $code, Format: $format',
            );

            if (_scanController == null) {
              debugPrint('❌ [RuggedScannerService] Scan controller is null!');
              return;
            }

            final scanResult = ScanResult(
              code: code,
              timestamp: DateTime.now(),
              format: format,
              source: 'hardware_scanner',
            );

            debugPrint(
              '📤 [RuggedScannerService] Adding scan result to stream: $scanResult',
            );
            _scanController?.add(scanResult);
            debugPrint(
              '✅ [RuggedScannerService] Scan result added to stream successfully',
            );
          } catch (e, stackTrace) {
            debugPrint('❌ [RuggedScannerService] Error processing scan: $e');
            debugPrint('❌ [RuggedScannerService] Stack trace: $stackTrace');
          }
        } else {
          debugPrint('⚠️ [RuggedScannerService] Unknown method: ${call.method}');
        }
      });
      debugPrint(
        '✅ [RuggedScannerService] Hardware scanner listener setup complete',
      );
    } catch (e, stackTrace) {
      debugPrint(
        '❌ [RuggedScannerService] Error setting up hardware scanner listener: $e',
      );
      debugPrint('❌ [RuggedScannerService] Stack trace: $stackTrace');
    }
  }

  /// Enables the hardware scanner.
  ///
  /// Returns `true` if the scanner was successfully enabled, `false` otherwise.
  ///
  /// This method activates the hardware scanner on the device. Once enabled,
  /// scan results will be sent to the [scanResults] stream when the trigger
  /// button is pressed or a barcode is detected.
  ///
  /// Returns `false` if:
  /// - No hardware scanner is available
  /// - The scanner failed to enable
  Future<bool> enable() async {
    debugPrint('🔧 [RuggedScannerService] Enabling hardware scanner...');
    if (!_isHardwareScannerAvailable) {
      debugPrint('❌ [RuggedScannerService] Hardware scanner not available');
      return false;
    }

    try {
      debugPrint(
        '📡 [RuggedScannerService] Calling native enableHardwareScanner...',
      );
      final result = await _channel.invokeMethod<bool>('enableHardwareScanner');
      final enabled = result ?? false;
      debugPrint('📡 [RuggedScannerService] Native response: $enabled');
      _isHardwareScannerEnabled = enabled;
      // Notify listeners of state change
      _availabilityController?.add(true);
      debugPrint('✅ [RuggedScannerService] Hardware scanner enabled: $enabled');
      return enabled;
    } catch (e, stackTrace) {
      debugPrint('❌ [RuggedScannerService] Error enabling hardware scanner: $e');
      debugPrint('❌ [RuggedScannerService] Stack trace: $stackTrace');
      return false;
    }
  }

  /// Disables the hardware scanner.
  ///
  /// Returns `true` if the scanner was successfully disabled, `false` otherwise.
  ///
  /// This method deactivates the hardware scanner. After disabling, the scanner
  /// will no longer send scan results until it is enabled again.
  ///
  /// Returns `false` if:
  /// - No hardware scanner is available
  /// - The scanner failed to disable
  Future<bool> disable() async {
    if (!_isHardwareScannerAvailable) {
      return false;
    }

    try {
      final result = await _channel.invokeMethod<bool>('disableHardwareScanner');
      final disabled = result ?? false;
      _isHardwareScannerEnabled = !disabled;
      // Notify listeners of state change
      _availabilityController?.add(true);
      return disabled;
    } catch (e) {
      debugPrint('Error disabling hardware scanner: $e');
      return false;
    }
  }

  /// Stream of scan results.
  ///
  /// Listen to this stream to receive scan results from the hardware scanner.
  /// Each scan result contains the scanned code, timestamp, format, and source.
  ///
  /// Example:
  /// ```dart
  /// scannerService.scanResults.listen((result) {
  ///   print('Scanned: ${result.code}');
  /// });
  /// ```
  Stream<ScanResult> get scanResults => _scanStream ?? const Stream.empty();

  /// Stream of hardware scanner availability changes.
  ///
  /// This stream emits `true` when a hardware scanner becomes available,
  /// and `false` when it becomes unavailable.
  ///
  /// Example:
  /// ```dart
  /// scannerService.availabilityStream.listen((isAvailable) {
  ///   print('Scanner available: $isAvailable');
  /// });
  /// ```
  Stream<bool> get availabilityStream => _availabilityStream;

  /// Whether a hardware scanner is available on this device.
  ///
  /// This property is `true` if a hardware scanner was detected during
  /// initialization, `false` otherwise.
  bool get isAvailable => _isHardwareScannerAvailable;

  /// Whether the hardware scanner is currently enabled.
  ///
  /// This property is `true` if the scanner is enabled and ready to scan,
  /// `false` otherwise.
  bool get isEnabled => _isHardwareScannerEnabled;

  /// Disposes of all resources used by this service.
  ///
  /// Call this method when you're done using the service to free up resources.
  /// After calling [dispose], the service should not be used anymore.
  ///
  /// This method:
  /// - Closes all stream controllers
  /// - Clears all references
  /// - Stops listening to native events
  void dispose() {
    _scanController?.close();
    _scanController = null;
    _scanStream = null;
    _availabilityController?.close();
    _availabilityController = null;
  }
}
