/// Model class representing a QR code or barcode scan result.
///
/// This class encapsulates all information about a single scan operation,
/// including the scanned data, timestamp, format, and source.
///
/// Example:
/// ```dart
/// final result = ScanResult(
///   code: 'ABC123',
///   timestamp: DateTime.now(),
///   format: 'QR_CODE',
///   source: 'hardware_scanner',
/// );
/// ```
class ScanResult {
  /// The scanned code/data as a string.
  ///
  /// This is the actual content that was scanned from the QR code or barcode.
  final String code;

  /// The timestamp when the scan occurred.
  ///
  /// This is automatically set to the current time when the scan result is created.
  final DateTime timestamp;

  /// The barcode format (e.g., 'QR_CODE', 'CODE_128', 'EAN_13').
  ///
  /// May be null if the format is not available or not detected.
  final String? format;

  /// The source of the scan.
  ///
  /// Typically one of:
  /// - 'hardware_scanner': Scanned using the device's built-in hardware scanner
  /// - 'camera': Scanned using the device's camera (if supported)
  final String? source;

  /// Creates a new [ScanResult] instance.
  ///
  /// [code] and [timestamp] are required.
  /// [format] and [source] are optional.
  ScanResult({
    required this.code,
    required this.timestamp,
    this.format,
    this.source,
  });

  @override
  String toString() {
    return 'ScanResult(code: $code, timestamp: $timestamp, format: $format, source: $source)';
  }
}
