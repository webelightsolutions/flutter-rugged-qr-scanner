/**
 * Broadcast receiver for hardware scanner intents.
 *
 * This receiver is registered in the AndroidManifest.xml to catch scanner broadcasts
 * even when the app is not in the foreground. It handles scan data from various
 * manufacturers and forwards it to Flutter via the method channel.
 *
 * The receiver uses goAsync() to allow background execution, which is required
 * for Android 8.0+ (API level 26+).
 *
 * Supported scanner intents:
 * - CipherLab: com.cipherlab.barcodebaseapi.GET_DATA
 * - Reader_Service: sw.reader.* actions
 * - Generic barcode scanner intents
 *
 * @author Rugged QR Scanner Team
 * @since 1.0.0
 */
package com.rugged.qr_scanner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
// CipherLab SDK - optional, users must add barcodebase.jar to their app
// The SDK is accessed via reflection to allow the plugin to compile without it
// import com.cipherlab.barcodebase.ReaderDataStruct
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

/**
 * Broadcast receiver that listens for scanner intents and forwards scan data to Flutter.
 *
 * This receiver is registered in the AndroidManifest.xml with high priority to ensure
 * it receives scanner broadcasts. It extracts scan data from various intent formats
 * and sends it to Flutter via the method channel.
 */
class ScannerBroadcastReceiver : BroadcastReceiver() {
    companion object {
        /** Method channel for sending scan data to Flutter */
        private var methodChannel: MethodChannel? = null
        
        /**
         * Sets the method channel for sending scan data to Flutter.
         *
         * This method is called by RuggedScannerPlugin during initialization
         * to provide the receiver with a way to communicate with Flutter.
         *
         * @param channel The method channel to use for Flutter communication
         */
        fun setMethodChannel(channel: MethodChannel?) {
            methodChannel = channel
        }
    }
    
    /**
     * Called when a broadcast intent is received.
     *
     * This method:
     * - Extracts scan data from the intent
     * - Handles various scanner formats (CipherLab, Reader_Service, generic)
     * - Sends scan data to Flutter via method channel
     * - Uses goAsync() for background execution (required for Android 8.0+)
     *
     * @param context The context in which the receiver is running
     * @param intent The broadcast intent containing scan data
     */
    override fun onReceive(context: Context?, intent: Intent?) {
        // Use goAsync() to allow background execution (required for Android 8.0+)
        // This prevents ANR (Application Not Responding) errors
        val pendingResult = goAsync()
        
        if (intent == null) {
            Log.w("ScannerBroadcastReceiver", "Received null intent")
            pendingResult.finish()
            return
        }
        
        val action = intent.action ?: run {
            Log.w("ScannerBroadcastReceiver", "Received intent with null action")
            pendingResult.finish()
            return
        }
        
        Log.d("ScannerBroadcastReceiver", "=== MANIFEST RECEIVER: BROADCAST RECEIVED ===")
        Log.d("ScannerBroadcastReceiver", "✅ Receiver executing with goAsync()")
        Log.d("ScannerBroadcastReceiver", "Intent Action: $action")
        Log.d("ScannerBroadcastReceiver", "Intent Package: ${intent.`package`}")
        Log.d("ScannerBroadcastReceiver", "Intent Component: ${intent.component}")
        Log.d("ScannerBroadcastReceiver", "Intent Data URI: ${intent.data}")
        Log.d("ScannerBroadcastReceiver", "Intent Flags: ${intent.flags}")
        
        // Safely log extras without accessing keySet (which can crash with Serializable objects)
        try {
            val extras = intent.extras
            if (extras != null) {
                Log.d("ScannerBroadcastReceiver", "Intent has extras (count: ${extras.size()})")
                // Try to log known keys without accessing keySet()
                val knownKeys = listOf("data", "barcode", "scan_data", "SCAN_DATA", "reader_data",
                    "com.cipherlab.barcodebaseapi.DATA", "result", "RESULT")
                for (key in knownKeys) {
                    try {
                        if (extras.containsKey(key)) {
                            val value = extras.get(key)
                            Log.d("ScannerBroadcastReceiver", "  $key = $value (${value?.javaClass?.simpleName})")
                        }
                    } catch (e: Exception) {
                        // Skip if this key causes issues
                    }
                }
            } else {
                Log.d("ScannerBroadcastReceiver", "Intent has no extras")
            }
        } catch (e: Exception) {
            Log.w("ScannerBroadcastReceiver", "Could not access intent extras (may contain Serializable objects): ${e.message}")
        }
        
        // Ignore enable/disable command broadcasts
        if (intent.hasExtra("DECODE_ACTION")) {
            val decodeAction = intent.getStringExtra("DECODE_ACTION")
            if (decodeAction == "START_DECODE" || decodeAction == "STOP_DECODE") {
                Log.d("ScannerBroadcastReceiver", "Ignoring enable/disable command broadcast: $decodeAction")
                pendingResult.finish()
                return
            }
        }
        if (intent.hasExtra("ACTION")) {
            val actionExtra = intent.getStringExtra("ACTION")
            if (actionExtra == "START_DECODE" || actionExtra == "STOP_DECODE") {
                Log.d("ScannerBroadcastReceiver", "Ignoring enable/disable command broadcast: $actionExtra")
                pendingResult.finish()
                return
            }
        }
        if (intent.hasExtra("ENABLE") && intent.getBooleanExtra("ENABLE", false)) {
            Log.d("ScannerBroadcastReceiver", "Ignoring enable command broadcast")
            pendingResult.finish()
            return
        }
        if (intent.hasExtra("START") && intent.getBooleanExtra("START", false)) {
            Log.d("ScannerBroadcastReceiver", "Ignoring start command broadcast")
            pendingResult.finish()
            return
        }
        
        // Extract scan data using the same logic as MainActivity
        val code = when {
            // CipherLab barcodebaseapi.GET_DATA (Reader_Service uses this)
            // Try multiple possible extra keys
            action == "com.cipherlab.barcodebaseapi.GET_DATA" -> {
                var foundData: String? = null
                val possibleKeys = listOf(
                    "data", "barcode", "scan_data", "SCAN_DATA", "barcode_data", "BARCODE_DATA",
                    "decode_data", "DECODE_DATA", "result", "RESULT", "value", "VALUE",
                    "com.cipherlab.barcodebaseapi.DATA", "com.cipherlab.barcodebaseapi.data"
                )
                for (key in possibleKeys) {
                    if (intent.hasExtra(key)) {
                        val value = intent.getStringExtra(key)
                        if (value != null && value.isNotEmpty() && value.length >= 3) {
                            foundData = value
                            Log.d("ScannerBroadcastReceiver", "✅ Found data from barcodebaseapi.GET_DATA in key '$key': $foundData")
                            break
                        }
                    }
                }
                // Try to get data from ReaderDataStruct using SDK (now that SDK is included)
                // The key is "BarcodeData" not "com.cipherlab.barcodebase.ReaderDataStruct"
                if (foundData == null) {
                    try {
                        // Try "BarcodeData" first (the actual key used by Reader_Service)
                        var readerDataStruct = intent.getSerializableExtra("BarcodeData")
                        if (readerDataStruct == null) {
                            // Fallback to the class name as key
                            readerDataStruct = intent.getSerializableExtra("com.cipherlab.barcodebase.ReaderDataStruct")
                        }
                        
                        if (readerDataStruct != null && readerDataStruct.javaClass.name == "com.cipherlab.barcodebase.ReaderDataStruct") {
                            Log.d("ScannerBroadcastReceiver", "✅ Found ReaderDataStruct using SDK: ${readerDataStruct.javaClass.name}")
                            
                            // Use reflection to extract data
                            try {
                                // Try GetCodeDataStr() method
                                val getCodeDataStrMethod = readerDataStruct.javaClass.getMethod("GetCodeDataStr")
                                val scanData = getCodeDataStrMethod.invoke(readerDataStruct) as? String
                                if (scanData != null && scanData.isNotEmpty() && scanData.length >= 3) {
                                    foundData = scanData
                                    Log.d("ScannerBroadcastReceiver", "✅ Extracted scan data using GetCodeDataStr(): $foundData")
                                }
                            } catch (e: Exception) {
                                Log.d("ScannerBroadcastReceiver", "Error calling GetCodeDataStr(): ${e.message}")
                            }
                            
                            // Fallback: Try getCodeDataArray()
                            if (foundData == null) {
                                try {
                                    val getCodeDataArrayMethod = readerDataStruct.javaClass.getMethod("getCodeDataArray")
                                    val codeDataArray = getCodeDataArrayMethod.invoke(readerDataStruct) as? ByteArray
                                    if (codeDataArray != null && codeDataArray.isNotEmpty()) {
                                        val scanData = String(codeDataArray, Charsets.UTF_8)
                                        if (scanData.isNotEmpty() && scanData.length >= 3) {
                                            foundData = scanData
                                            Log.d("ScannerBroadcastReceiver", "✅ Extracted scan data using getCodeDataArray(): $foundData")
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.d("ScannerBroadcastReceiver", "Error calling getCodeDataArray(): ${e.message}")
                                }
                            }
                        }
                    } catch (e: ClassNotFoundException) {
                        Log.d("ScannerBroadcastReceiver", "ClassNotFoundException - SDK may not be loaded: ${e.message}")
                    } catch (e: Exception) {
                        Log.d("ScannerBroadcastReceiver", "Error getting ReaderDataStruct: ${e.message}")
                    }
                }
                foundData
            }
            intent.hasExtra("com.cipherlab.barcodebaseapi.DATA") -> {
                val data = intent.getStringExtra("com.cipherlab.barcodebaseapi.DATA")
                Log.d("ScannerBroadcastReceiver", "Found com.cipherlab.barcodebaseapi.DATA: $data")
                data
            }
            // CipherLab specific extras
            intent.hasExtra("com.cipherlab.decode.DATA") -> {
                intent.getStringExtra("com.cipherlab.decode.DATA")
            }
            intent.hasExtra("com.cipherlab.scanner.DATA") -> {
                intent.getStringExtra("com.cipherlab.scanner.DATA")
            }
            intent.hasExtra("com.cipherlab.barcode.DATA") -> {
                intent.getStringExtra("com.cipherlab.barcode.DATA")
            }
            // Reader_Service (sw.reader.*) specific extras
            intent.hasExtra("sw.reader.scan.result") -> {
                intent.getStringExtra("sw.reader.scan.result")
            }
            intent.hasExtra("sw.reader.scan.data") -> {
                intent.getStringExtra("sw.reader.scan.data")
            }
            intent.hasExtra("sw.reader.decode.result") -> {
                intent.getStringExtra("sw.reader.decode.result")
            }
            intent.hasExtra("sw.reader.decode.data") -> {
                intent.getStringExtra("sw.reader.decode.data")
            }
            intent.hasExtra("sw.reader.barcode.result") -> {
                intent.getStringExtra("sw.reader.barcode.result")
            }
            intent.hasExtra("sw.reader.barcode.data") -> {
                intent.getStringExtra("sw.reader.barcode.data")
            }
            intent.hasExtra("sw.reader.DATA") -> {
                intent.getStringExtra("sw.reader.DATA")
            }
            intent.hasExtra("sw.reader.RESULT") -> {
                intent.getStringExtra("sw.reader.RESULT")
            }
            intent.hasExtra("sw.reader.data") -> {
                intent.getStringExtra("sw.reader.data")
            }
            intent.hasExtra("sw.reader.result") -> {
                intent.getStringExtra("sw.reader.result")
            }
            // Generic extras
            intent.hasExtra("barcode") -> {
                intent.getStringExtra("barcode")
            }
            intent.hasExtra("BARCODE") -> {
                intent.getStringExtra("BARCODE")
            }
            intent.hasExtra("SCAN_DATA") -> {
                intent.getStringExtra("SCAN_DATA")
            }
            intent.hasExtra("scan_data") -> {
                intent.getStringExtra("scan_data")
            }
            intent.hasExtra("data") -> {
                intent.getStringExtra("data")
            }
            intent.hasExtra("DATA") -> {
                intent.getStringExtra("DATA")
            }
            intent.hasExtra("result") -> {
                intent.getStringExtra("result")
            }
            intent.hasExtra("RESULT") -> {
                intent.getStringExtra("RESULT")
            }
            // Try URI data
            intent.data != null -> {
                val uriData = intent.data?.toString()
                if (uriData != null && !uriData.contains("START_DECODE") && !uriData.contains("STOP_DECODE")) {
                    uriData
                } else null
            }
            // Last resort: try any string extra
            else -> {
                var foundData: String? = null
                intent.extras?.let { extras ->
                    val candidates = mutableListOf<Pair<String, String>>()
                    for (key in extras.keySet()) {
                        val value = extras.get(key)
                        if (value is String && value.isNotEmpty() && value.length >= 3) {
                            val keyStr = key.toString()
                            val valueStr = value.toString()
                            val isCommand = valueStr == "START_DECODE" || 
                                valueStr == "STOP_DECODE" ||
                                valueStr == "ENABLE" ||
                                valueStr == "DISABLE" ||
                                valueStr == "START" ||
                                valueStr == "STOP" ||
                                keyStr.contains("ACTION", ignoreCase = true) ||
                                keyStr.contains("COMMAND", ignoreCase = true) ||
                                keyStr.contains("ENABLE", ignoreCase = true) ||
                                keyStr.contains("DISABLE", ignoreCase = true)
                            
                            if (!isCommand) {
                                candidates.add(Pair(keyStr, valueStr))
                                Log.d("ScannerBroadcastReceiver", "📋 Candidate scan data: key=$keyStr, value=$valueStr")
                            }
                        }
                    }
                    candidates.sortByDescending { it.second.length }
                    foundData = candidates.firstOrNull()?.second
                }
                foundData
            }
        }
        
        Log.d("ScannerBroadcastReceiver", "Extracted code: $code")
        
        if (code.isNullOrEmpty()) {
            Log.w("ScannerBroadcastReceiver", "Code is null or empty, cannot process scan")
            // Try to log known keys for debugging (avoid keySet() to prevent Serializable crashes)
            try {
                val extras = intent.extras
                if (extras != null) {
                    Log.d("ScannerBroadcastReceiver", "Checking known keys for scan data:")
                    val knownKeys = listOf("data", "barcode", "scan_data", "SCAN_DATA", "reader_data",
                        "com.cipherlab.barcodebaseapi.DATA", "result", "RESULT", "value", "VALUE")
                    for (key in knownKeys) {
                        try {
                            if (extras.containsKey(key)) {
                                val value = extras.get(key)
                                Log.d("ScannerBroadcastReceiver", "  $key = $value (${value?.javaClass?.simpleName})")
                            }
                        } catch (e: Exception) {
                            // Skip if this key causes issues
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("ScannerBroadcastReceiver", "Could not access extras for debugging: ${e.message}")
            }
            pendingResult.finish()
            return
        }
        
        if (methodChannel == null) {
            Log.e("ScannerBroadcastReceiver", "MethodChannel is null! Cannot send data to Flutter")
            pendingResult.finish()
            return
        }
        
        val arguments = HashMap<String, Any>()
        arguments["code"] = code
        
        // Try to extract format
        val format = when {
            intent.hasExtra("barcode_type") -> intent.getStringExtra("barcode_type")
            intent.hasExtra("format") -> intent.getStringExtra("format")
            intent.hasExtra("FORMAT") -> intent.getStringExtra("FORMAT")
            else -> null
        }
        if (format != null) {
            arguments["format"] = format
        }
        
        Log.d("ScannerBroadcastReceiver", "Sending to Flutter - code: $code, format: $format")
        try {
            methodChannel?.invokeMethod("onHardwareScan", arguments)
            Log.d("ScannerBroadcastReceiver", "✅ Successfully sent scan data to Flutter")
        } catch (e: Exception) {
            Log.e("ScannerBroadcastReceiver", "❌ Error sending data to Flutter: ${e.message}", e)
        } finally {
            // Finish the async operation
            pendingResult.finish()
        }
    }
}

