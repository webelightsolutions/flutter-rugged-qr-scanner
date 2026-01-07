import 'dart:async';

import 'package:flutter/material.dart';
import 'package:rugged_device_qr_scanner/rugged_device_qr_scanner.dart';

void main() {
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Rugged QR Scanner Example',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.deepPurple),
        useMaterial3: true,
      ),
      debugShowCheckedModeBanner: false,
      home: const ScannerExamplePage(),
    );
  }
}

class ScannerExamplePage extends StatefulWidget {
  const ScannerExamplePage({super.key});

  @override
  State<ScannerExamplePage> createState() => _ScannerExamplePageState();
}

class _ScannerExamplePageState extends State<ScannerExamplePage> {
  final RuggedScannerService _scannerService = RuggedScannerService();
  StreamSubscription<ScanResult>? _scanSubscription;
  final List<ScanResult> _scanHistory = [];
  bool _isEnabled = false;

  @override
  void initState() {
    super.initState();
    _setupScanner();
  }

  void _setupScanner() {
    _scanSubscription = _scannerService.scanResults.listen((result) {
      setState(() {
        _scanHistory.insert(0, result);
        if (_scanHistory.length > 50) {
          _scanHistory.removeLast();
        }
      });

      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Row(
            children: [
              const Icon(Icons.check_circle, color: Colors.white),
              const SizedBox(width: 12),
              Expanded(
                child: Text(
                  'Scanned: ${result.code}',
                  style: const TextStyle(fontSize: 15),
                ),
              ),
            ],
          ),
          backgroundColor: Colors.green.shade700,
          behavior: SnackBarBehavior.floating,
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(12),
          ),
          duration: const Duration(seconds: 2),
        ),
      );
    });

    _scannerService.availabilityStream.listen((isAvailable) {
      setState(() {});
    });
  }

  Future<void> _toggleScanner() async {
    if (_isEnabled) {
      await _scannerService.disable();
    } else {
      await _scannerService.enable();
    }
    setState(() {
      _isEnabled = _scannerService.isEnabled;
    });
  }

  @override
  void dispose() {
    _scanSubscription?.cancel();
    _scannerService.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final isAvailable = _scannerService.isAvailable;
    final isEnabled = _scannerService.isEnabled;

    return Scaffold(
      backgroundColor: Colors.grey.shade50,
      appBar: AppBar(
        elevation: 0,
        title: const Text(
          'QR Scanner',
          style: TextStyle(
            fontWeight: FontWeight.w600,
            fontSize: 20,
          ),
        ),
        centerTitle: true,
        backgroundColor: Colors.deepPurple,
        foregroundColor: Colors.white,
      ),
      body: Column(
        children: [
          // Status Section
          Container(
            width: double.infinity,
            decoration: BoxDecoration(
              color: Colors.white,
              boxShadow: [
                BoxShadow(
                  color: Colors.black.withValues(alpha: 0.05),
                  blurRadius: 10,
                  offset: const Offset(0, 4),
                ),
              ],
            ),
            padding: const EdgeInsets.all(24),
            child: Column(
              children: [
                // Scanner Icon
                Container(
                  padding: const EdgeInsets.all(20),
                  decoration: BoxDecoration(
                    color: isAvailable ? Colors.deepPurple.shade50 : Colors.grey.shade100,
                    borderRadius: BorderRadius.circular(20),
                  ),
                  child: Icon(
                    Icons.qr_code_scanner,
                    size: 48,
                    color: isAvailable ? Colors.deepPurple : Colors.grey.shade400,
                  ),
                ),
                const SizedBox(height: 20),

                // Status Indicators
                _StatusIndicator(
                  icon: isAvailable ? Icons.check_circle : Icons.error,
                  label: isAvailable ? 'Hardware Scanner Ready' : 'Hardware Not Available',
                  color: isAvailable ? Colors.green : Colors.red,
                ),
                const SizedBox(height: 12),
                _StatusIndicator(
                  icon: isEnabled ? Icons.power_settings_new : Icons.power_off,
                  label: isEnabled ? 'Scanner Active' : 'Scanner Inactive',
                  color: isEnabled ? Colors.blue : Colors.grey,
                ),
                const SizedBox(height: 24),

                // Toggle Button
                SizedBox(
                  width: double.infinity,
                  height: 56,
                  child: ElevatedButton(
                    onPressed: isAvailable ? _toggleScanner : null,
                    style: ElevatedButton.styleFrom(
                      backgroundColor: isEnabled ? Colors.red.shade400 : Colors.deepPurple,
                      foregroundColor: Colors.white,
                      disabledBackgroundColor: Colors.grey.shade300,
                      elevation: 0,
                      shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(16),
                      ),
                    ),
                    child: Row(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        Icon(
                          isEnabled ? Icons.stop : Icons.play_arrow,
                          size: 24,
                        ),
                        const SizedBox(width: 8),
                        Text(
                          isEnabled ? 'Stop Scanner' : 'Start Scanner',
                          style: const TextStyle(
                            fontSize: 16,
                            fontWeight: FontWeight.w600,
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ],
            ),
          ),

          // History Header
          Padding(
            padding: const EdgeInsets.fromLTRB(24, 24, 24, 12),
            child: Row(
              children: [
                const Icon(
                  Icons.history,
                  size: 24,
                  color: Colors.deepPurple,
                ),
                const SizedBox(width: 8),
                const Text(
                  'Scan History',
                  style: TextStyle(
                    fontSize: 20,
                    fontWeight: FontWeight.w600,
                    color: Colors.black87,
                  ),
                ),
                const Spacer(),
                if (_scanHistory.isNotEmpty)
                  Container(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 12,
                      vertical: 6,
                    ),
                    decoration: BoxDecoration(
                      color: Colors.deepPurple.shade50,
                      borderRadius: BorderRadius.circular(20),
                    ),
                    child: Text(
                      '${_scanHistory.length}',
                      style: TextStyle(
                        fontSize: 14,
                        fontWeight: FontWeight.w600,
                        color: Colors.deepPurple.shade700,
                      ),
                    ),
                  ),
              ],
            ),
          ),

          // History List
          Expanded(
            child: _scanHistory.isEmpty
                ? Center(
                    child: Column(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        Icon(
                          isAvailable ? Icons.qr_code_2 : Icons.scanner_outlined,
                          size: 80,
                          color: Colors.grey.shade300,
                        ),
                        const SizedBox(height: 16),
                        Text(
                          isAvailable ? 'No scans yet' : 'Scanner not available',
                          style: TextStyle(
                            fontSize: 18,
                            fontWeight: FontWeight.w500,
                            color: Colors.grey.shade600,
                          ),
                        ),
                        const SizedBox(height: 8),
                        Text(
                          isAvailable ? 'Press the trigger to start scanning' : 'Hardware scanner not found',
                          style: TextStyle(
                            fontSize: 14,
                            color: Colors.grey.shade500,
                          ),
                          textAlign: TextAlign.center,
                        ),
                      ],
                    ),
                  )
                : ListView.builder(
                    padding: const EdgeInsets.symmetric(horizontal: 16),
                    itemCount: _scanHistory.length,
                    itemBuilder: (context, index) {
                      final result = _scanHistory[index];
                      return _ScanResultCard(
                        result: result,
                        index: index,
                      );
                    },
                  ),
          ),
        ],
      ),
    );
  }
}

class _StatusIndicator extends StatelessWidget {
  final IconData icon;
  final String label;
  final Color color;

  const _StatusIndicator({
    required this.icon,
    required this.label,
    required this.color,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.1),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(
          color: color.withValues(alpha: 0.3),
          width: 1,
        ),
      ),
      child: Row(
        children: [
          Icon(icon, color: color, size: 20),
          const SizedBox(width: 12),
          Text(
            label,
            style: const TextStyle(
              fontSize: 15,
              fontWeight: FontWeight.w500,
              color: Colors.black87,
            ),
          ),
        ],
      ),
    );
  }
}

class _ScanResultCard extends StatelessWidget {
  final ScanResult result;
  final int index;

  const _ScanResultCard({
    required this.result,
    required this.index,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      margin: const EdgeInsets.only(bottom: 12),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(16),
        boxShadow: [
          BoxShadow(
            color: Colors.black.withValues(alpha: 0.04),
            blurRadius: 8,
            offset: const Offset(0, 2),
          ),
        ],
      ),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // Icon Container
            Container(
              padding: const EdgeInsets.all(10),
              decoration: BoxDecoration(
                color: Colors.deepPurple.shade50,
                borderRadius: BorderRadius.circular(12),
              ),
              child: const Icon(
                Icons.qr_code_2,
                color: Colors.deepPurple,
                size: 24,
              ),
            ),
            const SizedBox(width: 16),

            // Content
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    result.code,
                    style: const TextStyle(
                      fontSize: 16,
                      fontWeight: FontWeight.w600,
                      color: Colors.black87,
                    ),
                  ),
                  const SizedBox(height: 8),
                  _InfoRow(
                    icon: Icons.category_outlined,
                    text: result.format ?? 'Unknown',
                  ),
                  const SizedBox(height: 4),
                  _InfoRow(
                    icon: Icons.access_time,
                    text: result.timestamp.toString().substring(11, 19),
                  ),
                  const SizedBox(height: 4),
                  _InfoRow(
                    icon: Icons.devices,
                    text: result.source ?? 'Unknown',
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _InfoRow extends StatelessWidget {
  final IconData icon;
  final String text;

  const _InfoRow({
    required this.icon,
    required this.text,
  });

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Icon(
          icon,
          size: 14,
          color: Colors.grey.shade600,
        ),
        const SizedBox(width: 6),
        Text(
          text,
          style: TextStyle(
            fontSize: 13,
            color: Colors.grey.shade600,
          ),
        ),
      ],
    );
  }
}
