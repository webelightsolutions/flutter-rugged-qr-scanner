/**
 * Rugged QR Scanner Plugin for Flutter
 *
 * This plugin provides hardware QR/barcode scanning support for rugged Android devices.
 * It supports multiple manufacturers including CipherLab, Zebra, Honeywell, Datalogic,
 * Unitech, and generic barcode scanner intents.
 *
 * The plugin uses Android Broadcast Receivers to listen for scan data from hardware
 * scanners and forwards the data to Flutter via method channels.
 *
 * @author Rugged QR Scanner Team
 * @since 1.0.0
 */
package com.rugged_device_qr_scanner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.util.Log
// CipherLab SDK - optional, users must add barcodebase.jar to their app
// The SDK is accessed via reflection to allow the plugin to compile without it
// import com.cipherlab.barcodebase.ReaderDataStruct
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler

/**
 * Main plugin class that implements FlutterPlugin and MethodCallHandler.
 *
 * This class handles:
 * - Scanner detection and initialization
 * - Broadcast receiver setup for scan data
 * - Communication with Flutter via method channels
 * - Scanner enable/disable functionality
 */
class RuggedScannerPlugin : FlutterPlugin, MethodCallHandler {
    /** Method channel name for Flutter communication */
    private val CHANNEL = "com.rugged_device_qr_scanner"
    
    /** Method channel for communicating with Flutter */
    private var methodChannel: MethodChannel? = null
    
    /** Application context */
    private var context: Context? = null
    
    /** Broadcast receiver for hardware scanner intents */
    private var hardwareScannerReceiver: BroadcastReceiver? = null
    
    /** Debug receiver for logging scanner-related broadcasts */
    private var debugReceiver: BroadcastReceiver? = null
    
    /** Whether the hardware scanner is currently enabled */
    private var isHardwareScannerEnabled = false
    
    /**
     * Extracts scan data from a CipherLab ReaderDataStruct object using reflection.
     *
     * This method uses reflection to access the ReaderDataStruct methods, allowing
     * the plugin to work even if the CipherLab SDK (barcodebase.jar) is not included
     * in the plugin itself. Users must add the SDK to their app for this to work.
     *
     * @param readerDataStruct The ReaderDataStruct object containing scan data
     * @return The scanned code as a String, or null if extraction failed
     */
    private fun extractDataFromReaderDataStruct(readerDataStruct: Any): String? {
        return try {
            // Try GetCodeDataStr() method
            val getCodeDataStrMethod = readerDataStruct.javaClass.getMethod("GetCodeDataStr")
            val scanData = getCodeDataStrMethod.invoke(readerDataStruct) as? String
            if (scanData != null && scanData.isNotEmpty() && scanData.length >= 3) {
                return scanData
            }
            
            // Fallback: Try getCodeDataArray()
            try {
                val getCodeDataArrayMethod = readerDataStruct.javaClass.getMethod("getCodeDataArray")
                val codeDataArray = getCodeDataArrayMethod.invoke(readerDataStruct) as? ByteArray
                if (codeDataArray != null && codeDataArray.isNotEmpty()) {
                    return String(codeDataArray, Charsets.UTF_8)
                }
            } catch (e: Exception) {
                Log.d("RuggedScannerPlugin", "Error calling getCodeDataArray(): ${e.message}")
            }
            
            null
        } catch (e: Exception) {
            Log.d("RuggedScannerPlugin", "Error extracting data from ReaderDataStruct: ${e.message}")
            null
        }
    }

    /**
     * Called when the plugin is attached to the Flutter engine.
     *
     * This method:
     * - Initializes the method channel for Flutter communication
     * - Sets up the broadcast receiver for scanner intents
     * - Configures the manifest receiver to send scan data to Flutter
     *
     * @param binding The Flutter plugin binding containing engine and context
     */
    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext
        methodChannel = MethodChannel(binding.binaryMessenger, CHANNEL)
        methodChannel?.setMethodCallHandler(this)
        
        // Set method channel in manifest receiver so it can send scan data
        ScannerBroadcastReceiver.setMethodChannel(methodChannel)
        
        setupHardwareScannerReceiver()
    }

    /**
     * Called when the plugin is detached from the Flutter engine.
     *
     * This method:
     * - Unregisters broadcast receivers
     * - Cleans up resources
     * - Nullifies references to prevent memory leaks
     *
     * @param binding The Flutter plugin binding (not used, but required by interface)
     */
    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        methodChannel?.setMethodCallHandler(null)
        methodChannel = null
        
        hardwareScannerReceiver?.let {
            try {
                context?.unregisterReceiver(it)
            } catch (e: Exception) {
                Log.e("RuggedScannerPlugin", "Error unregistering receiver: ${e.message}")
            }
        }
        hardwareScannerReceiver = null
        
        debugReceiver?.let {
            try {
                context?.unregisterReceiver(it)
            } catch (e: Exception) {
                Log.e("RuggedScannerPlugin", "Error unregistering debug receiver: ${e.message}")
            }
        }
        debugReceiver = null
        
        context = null
    }

    /**
     * Handles method calls from Flutter.
     *
     * Supported methods:
     * - `isHardwareScannerSupported`: Checks if a hardware scanner is available
     * - `enableHardwareScanner`: Enables the hardware scanner
     * - `disableHardwareScanner`: Disables the hardware scanner
     *
     * @param call The method call from Flutter
     * @param result The result callback to send response back to Flutter
     */
    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "isHardwareScannerSupported" -> {
                result.success(isHardwareScannerSupported())
            }
            "enableHardwareScanner" -> {
                val enabled = enableHardwareScanner()
                result.success(enabled)
            }
            "disableHardwareScanner" -> {
                val disabled = disableHardwareScanner()
                result.success(disabled)
            }
            else -> {
                result.notImplemented()
            }
        }
    }

    /**
     * Checks if a hardware scanner is supported on this device.
     *
     * This method checks for various scanner manufacturers and their SDKs:
     * - Zebra DataWedge (com.symbol.datawedge)
     * - Honeywell SDK (com.honeywell.decode)
     * - Datalogic SDK (com.datalogic.decode)
     * - Unitech SDK (com.unitech.scanner)
     * - Generic barcode scanner intents
     * - Device-specific scanner packages
     *
     * For CipherLab RS36 and similar devices, the method may return true
     * even if the specific SDK is not detected, as these devices typically
     * have built-in scanners that work via broadcast intents.
     *
     * @return true if a hardware scanner is detected, false otherwise
     */
    private fun isHardwareScannerSupported(): Boolean {
        // Check device model for known rugged devices
        val deviceModel = Build.MODEL
        val deviceManufacturer = Build.MANUFACTURER
        Log.d("RuggedScannerPlugin", "Device: $deviceManufacturer $deviceModel")
        
        // RS36 and other rugged devices often have built-in scanners
        if (deviceModel.contains("RS36", ignoreCase = true) || 
            deviceModel.contains("RS", ignoreCase = true) ||
            deviceManufacturer.contains("rugged", ignoreCase = true)) {
            Log.d("RuggedScannerPlugin", "Rugged device detected: $deviceModel")
            // Continue checking for specific SDKs, but also return true if generic intents are found
        }
        
        return try {
            // Check for Zebra DataWedge
            context?.packageManager?.getPackageInfo("com.symbol.datawedge", 0)
            Log.d("RuggedScannerPlugin", "Zebra DataWedge detected")
            true
        } catch (e: Exception) {
            try {
                // Check for Honeywell SDK
                context?.packageManager?.getPackageInfo("com.honeywell.decode", 0)
                Log.d("RuggedScannerPlugin", "Honeywell SDK detected")
                true
            } catch (e2: Exception) {
                try {
                    // Check for Datalogic SDK
                    context?.packageManager?.getPackageInfo("com.datalogic.decode", 0)
                    Log.d("RuggedScannerPlugin", "Datalogic SDK detected")
                    true
                } catch (e3: Exception) {
                    try {
                        // Check for Unitech SDK
                        context?.packageManager?.getPackageInfo("com.unitech.scanner", 0)
                        Log.d("RuggedScannerPlugin", "Unitech SDK detected")
                        true
                    } catch (e4: Exception) {
                        // Check for RS36 and other device-specific scanner packages
                        val deviceSpecificPackages = listOf(
                            "com.rs36.scanner",
                            "com.rugged.scanner",
                            "com.android.scanner",
                            "com.barcode.scanner",
                            "com.scanner.barcode"
                        )
                        
                        val hasDevicePackage = deviceSpecificPackages.any { packageName ->
                            try {
                                context?.packageManager?.getPackageInfo(packageName, 0)
                                Log.d("RuggedScannerPlugin", "Device-specific scanner package detected: $packageName")
                                true
                            } catch (e: Exception) {
                                false
                            }
                        }
                        
                        if (hasDevicePackage) {
                            return true
                        }
                        
                        // Check for generic barcode scanner intents (common for infrared scanners)
                        val genericIntents = listOf(
                            "com.honeywell.decode.intent.action.ACTION_DECODE",
                            "com.datalogic.decode.intent.action.DECODE",
                            "com.symbol.datawedge.api.RESULT_ACTION",
                            "com.unitech.scanner.action.DECODE",
                            "com.zebra.scanner.ACTION",
                            "com.motorolasolutions.scanner.ACTION",
                            "com.rs36.scanner.ACTION",
                            "com.rugged.scanner.ACTION",
                            "android.intent.action.VIEW",
                            "com.android.scanner.ACTION",
                            "com.barcode.scanner.ACTION"
                        )
                        
                        val hasGenericSupport = genericIntents.any { action ->
                            try {
                                val intent = Intent(action)
                                val receivers = context?.packageManager?.queryBroadcastReceivers(intent, 0)
                                if (receivers != null && receivers.isNotEmpty()) {
                                    Log.d("RuggedScannerPlugin", "Found receiver for intent: $action")
                                    true
                                } else {
                                    false
                                }
                            } catch (e: Exception) {
                                false
                            }
                        }
                        
                        if (hasGenericSupport) {
                            Log.d("RuggedScannerPlugin", "Generic barcode scanner intent detected (likely infrared scanner)")
                        } else {
                            // For RS36 and known rugged devices, assume scanner is available
                            // even if we can't detect the specific SDK
                            if (deviceModel.contains("RS36", ignoreCase = true) || 
                                deviceModel.contains("RS", ignoreCase = true)) {
                                Log.d("RuggedScannerPlugin", "RS36 device detected - assuming scanner support")
                                return true
                            }
                        }
                        
                        hasGenericSupport
                    }
                }
            }
        }
    }

    /**
     * Enable hardware scanner (including infrared scanners)
     */
    private fun enableHardwareScanner(): Boolean {
        Log.d("MainActivity", "=== ENABLING HARDWARE SCANNER ===")
        if (!isHardwareScannerSupported()) {
            Log.w("RuggedScannerPlugin", "Hardware scanner not supported on this device")
            return false
        }

        try {
            // Try Zebra DataWedge first
            if (isDataWedgeAvailable()) {
                Log.d("RuggedScannerPlugin", "Attempting to enable Zebra DataWedge...")
                enableDataWedge()
                isHardwareScannerEnabled = true
                Log.d("RuggedScannerPlugin", "Zebra DataWedge enabled successfully")
                return true
            }
            
            // Try Honeywell SDK
            if (isHoneywellAvailable()) {
                Log.d("RuggedScannerPlugin", "Attempting to enable Honeywell scanner...")
                enableHoneywellScanner()
                isHardwareScannerEnabled = true
                Log.d("RuggedScannerPlugin", "Honeywell scanner enabled successfully")
                return true
            }
            
            // Try Datalogic SDK
            if (isDatalogicAvailable()) {
                Log.d("RuggedScannerPlugin", "Attempting to enable Datalogic scanner...")
                enableDatalogicScanner()
                isHardwareScannerEnabled = true
                Log.d("RuggedScannerPlugin", "Datalogic scanner enabled successfully")
                return true
            }
            
            // Try Unitech SDK
            if (isUnitechAvailable()) {
                Log.d("RuggedScannerPlugin", "Attempting to enable Unitech scanner...")
                enableUnitechScanner()
                isHardwareScannerEnabled = true
                Log.d("RuggedScannerPlugin", "Unitech scanner enabled successfully")
                return true
            }
            
            // Try generic barcode scanner (for infrared scanners)
            Log.d("RuggedScannerPlugin", "Attempting to enable generic/infrared scanner...")
            if (enableGenericScanner()) {
                isHardwareScannerEnabled = true
                Log.d("RuggedScannerPlugin", "Generic/infrared scanner enabled successfully")
                return true
            } else {
                Log.w("RuggedScannerPlugin", "Generic scanner enable returned false")
            }
        } catch (e: Exception) {
            Log.e("RuggedScannerPlugin", "Error enabling hardware scanner: ${e.message}", e)
        }

        Log.w("MainActivity", "Failed to enable any hardware scanner")
        return false
    }

    /**
     * Disable hardware scanner
     */
    private fun disableHardwareScanner(): Boolean {
        if (!isHardwareScannerEnabled) {
            return true
        }

        try {
            when {
                isDataWedgeAvailable() -> disableDataWedge()
                isHoneywellAvailable() -> disableHoneywellScanner()
                isDatalogicAvailable() -> disableDatalogicScanner()
                isUnitechAvailable() -> disableUnitechScanner()
                else -> disableGenericScanner()
            }
            isHardwareScannerEnabled = false
            Log.d("RuggedScannerPlugin", "Hardware scanner disabled")
            return true
        } catch (e: Exception) {
            Log.e("RuggedScannerPlugin", "Error disabling hardware scanner: ${e.message}")
            return false
        }
    }

    /**
     * Check if Zebra DataWedge is available
     */
    private fun isDataWedgeAvailable(): Boolean {
        return try {
            context?.packageManager?.getPackageInfo("com.symbol.datawedge", 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Enable Zebra DataWedge
     */
    private fun enableDataWedge() {
        val intent = Intent("com.symbol.datawedge.api.ACTION")
        intent.putExtra("com.symbol.datawedge.api.SOFT_SCAN_TRIGGER", "START_SCANNING")
        context?.sendBroadcast(intent)
    }

    /**
     * Disable Zebra DataWedge
     */
    private fun disableDataWedge() {
        val intent = Intent("com.symbol.datawedge.api.ACTION")
        intent.putExtra("com.symbol.datawedge.api.SOFT_SCAN_TRIGGER", "STOP_SCANNING")
        context?.sendBroadcast(intent)
    }

    /**
     * Check if Honeywell SDK is available
     */
    private fun isHoneywellAvailable(): Boolean {
        return try {
            context?.packageManager?.getPackageInfo("com.honeywell.decode", 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Enable Honeywell scanner
     */
    private fun enableHoneywellScanner() {
        val intent = Intent("com.honeywell.decode.intent.action.ACTION_DECODE")
        intent.putExtra("com.honeywell.decode.intent.extra.DECODE_ACTION", "START_DECODE")
        context?.sendBroadcast(intent)
    }

    /**
     * Disable Honeywell scanner
     */
    private fun disableHoneywellScanner() {
        val intent = Intent("com.honeywell.decode.intent.action.ACTION_DECODE")
        intent.putExtra("com.honeywell.decode.intent.extra.DECODE_ACTION", "STOP_DECODE")
        context?.sendBroadcast(intent)
    }

    /**
     * Check if Datalogic SDK is available
     */
    private fun isDatalogicAvailable(): Boolean {
        return try {
            context?.packageManager?.getPackageInfo("com.datalogic.decode", 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Enable Datalogic scanner
     */
    private fun enableDatalogicScanner() {
        val intent = Intent("com.datalogic.decode.intent.action.DECODE")
        intent.putExtra("com.datalogic.decode.intent.extra.DECODE_ACTION", "START_DECODE")
        context?.sendBroadcast(intent)
    }

    /**
     * Disable Datalogic scanner
     */
    private fun disableDatalogicScanner() {
        val intent = Intent("com.datalogic.decode.intent.action.DECODE")
        intent.putExtra("com.datalogic.decode.intent.extra.DECODE_ACTION", "STOP_DECODE")
        context?.sendBroadcast(intent)
    }

    /**
     * Check if Unitech SDK is available
     */
    private fun isUnitechAvailable(): Boolean {
        return try {
            context?.packageManager?.getPackageInfo("com.unitech.scanner", 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Enable Unitech scanner
     */
    private fun enableUnitechScanner() {
        val intent = Intent("com.unitech.scanner.action.DECODE")
        intent.putExtra("com.unitech.scanner.extra.DECODE_ACTION", "START_DECODE")
        context?.sendBroadcast(intent)
    }

    /**
     * Disable Unitech scanner
     */
    private fun disableUnitechScanner() {
        val intent = Intent("com.unitech.scanner.action.DECODE")
        intent.putExtra("com.unitech.scanner.extra.DECODE_ACTION", "STOP_DECODE")
        context?.sendBroadcast(intent)
    }

    /**
     * Enable generic barcode scanner (for infrared scanners and other generic implementations)
     * For CipherLab devices, the scanner might work automatically without enable commands
     */
    private fun enableGenericScanner(): Boolean {
        Log.d("MainActivity", "Attempting to enable scanner for RS36/CipherLab device")
        
        // For CipherLab RS36, try multiple approaches
        if (Build.MODEL.contains("RS36", ignoreCase = true)) {
            Log.d("RuggedScannerPlugin", "RS36 detected - trying CipherLab-specific enable methods")
            
            // Approach 1: Try to start scanner service
            try {
                val serviceIntent = Intent()
                serviceIntent.setClassName("com.cipherlab.scanner", "com.cipherlab.scanner.ScannerService")
                serviceIntent.action = "com.cipherlab.scanner.START"
                context?.startService(serviceIntent)
                Log.d("RuggedScannerPlugin", "✓ Attempted to start CipherLab scanner service")
            } catch (e: Exception) {
                Log.d("RuggedScannerPlugin", "✗ Could not start CipherLab service: ${e.message}")
            }
            
            // Approach 2: Try different enable broadcasts
            val cipherLabActions = listOf(
                "com.cipherlab.scanner.ACTION",
                "com.cipherlab.decode.ACTION",
                "com.cipherlab.barcode.ACTION",
                "com.cipherlab.scanner.ENABLE",
                "com.cipherlab.decode.ENABLE",
                "com.cipherlab.scanner.START_SCAN",
                "com.cipherlab.decode.START_SCAN"
            )
            
            for (action in cipherLabActions) {
                try {
                    val intent = Intent(action)
                    intent.putExtra("ENABLE", true)
                    intent.putExtra("START", true)
                    context?.sendBroadcast(intent)
                    Log.d("RuggedScannerPlugin", "✓ Sent enable broadcast: $action")
                } catch (e: Exception) {
                    Log.d("RuggedScannerPlugin", "✗ Failed to send enable for $action: ${e.message}")
                }
            }
            
            // Approach 3: Maybe scanner works automatically - just return true
            Log.d("RuggedScannerPlugin", "Note: CipherLab scanner may work automatically when trigger is pressed")
            return true
        }
        
        // For other devices, try generic approaches
        val genericActions = listOf(
            "com.honeywell.decode.intent.action.ACTION_DECODE",
            "com.datalogic.decode.intent.action.DECODE",
            "com.unitech.scanner.action.DECODE"
        )
        
        var enabled = false
        for (action in genericActions) {
            try {
                val intent = Intent(action)
                intent.putExtra("DECODE_ACTION", "START_DECODE")
                context?.sendBroadcast(intent)
                Log.d("RuggedScannerPlugin", "Sent enable broadcast for: $action")
                enabled = true
            } catch (e: Exception) {
                Log.d("RuggedScannerPlugin", "Failed to send enable for $action: ${e.message}")
                continue
            }
        }
        
        return enabled
    }

    /**
     * Disable generic barcode scanner
     */
    private fun disableGenericScanner() {
        val genericActions = listOf(
            "com.honeywell.decode.intent.action.ACTION_DECODE",
            "com.datalogic.decode.intent.action.DECODE",
            "com.unitech.scanner.action.DECODE"
        )
        
        for (action in genericActions) {
            try {
                val intent = Intent(action)
                intent.putExtra("DECODE_ACTION", "STOP_DECODE")
                context?.sendBroadcast(intent)
            } catch (e: Exception) {
                // Continue trying other actions
                continue
            }
        }
    }

    /**
     * Setup broadcast receiver for hardware scanner results
     */
    private fun setupHardwareScannerReceiver() {
        hardwareScannerReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                Log.d("RuggedScannerPlugin", "=== SCANNER BROADCAST RECEIVED ===")
                if (intent == null) {
                    Log.e("RuggedScannerPlugin", "Intent is null!")
                    return
                }
                
                val action = intent.action
                Log.d("RuggedScannerPlugin", "Intent Action: $action")
                Log.d("RuggedScannerPlugin", "Intent Package: ${intent.`package`}")
                Log.d("RuggedScannerPlugin", "Intent Component: ${intent.component}")
                Log.d("RuggedScannerPlugin", "Intent Data URI: ${intent.data}")
                
                    // Special handling for com.cipherlab.barcodebaseapi.GET_DATA
                    // The data is in a Serializable object we can't deserialize, so we need to extract it differently
                    if (action == "com.cipherlab.barcodebaseapi.GET_DATA") {
                        Log.d("RuggedScannerPlugin", "Handling barcodebaseapi.GET_DATA - trying multiple extraction methods")
                        
                        // Debug: Log Intent details
                        Log.d("RuggedScannerPlugin", "Intent toString: ${intent.toString()}")
                        Log.d("RuggedScannerPlugin", "Intent data URI: ${intent.data}")
                        Log.d("RuggedScannerPlugin", "Intent dataString: ${intent.dataString}")
                        Log.d("RuggedScannerPlugin", "Intent type: ${intent.type}")
                        Log.d("RuggedScannerPlugin", "Intent categories: ${intent.categories}")
                        
                        // Check if data is in the Intent's data URI
                        if (intent.data != null) {
                            val uriData = intent.data.toString()
                            Log.d("RuggedScannerPlugin", "Intent data URI string: $uriData")
                            // Extract data from URI if it looks like scan data
                            if (uriData.length > 20 && !uriData.contains("://") && !uriData.contains("START_DECODE")) {
                                Log.d("RuggedScannerPlugin", "✅ Found potential data in Intent URI: $uriData")
                                if (methodChannel != null) {
                                    val arguments = HashMap<String, Any>()
                                    arguments["code"] = uriData
                                    try {
                                        methodChannel?.invokeMethod("onHardwareScan", arguments)
                                        Log.d("RuggedScannerPlugin", "✅ Successfully sent scan data to Flutter")
                                    } catch (e: Exception) {
                                        Log.e("RuggedScannerPlugin", "Error sending data to Flutter: ${e.message}", e)
                                    }
                                }
                                return
                            }
                        }
                        
                        // Method 0: Try to write Intent to Parcel and read data before deserialization
                        try {
                            Log.d("RuggedScannerPlugin", "Method 0: Attempting to write Intent to Parcel and read raw data")
                            val parcel = android.os.Parcel.obtain()
                            try {
                                intent.writeToParcel(parcel, 0)
                                parcel.setDataPosition(0)
                                
                                // Read Intent structure from Parcel
                                // Intent format: ComponentName, action, data URI, type, flags, package, categories, extras
                                parcel.readString() // ComponentName (can be null)
                                val parcelAction = parcel.readString()
                                // Read Parcelable with proper type handling for different API levels
                                val parcelData = try {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        parcel.readParcelable(android.net.Uri::class.java.classLoader, android.net.Uri::class.java)
                                    } else {
                                        @Suppress("DEPRECATION")
                                        parcel.readParcelable<android.net.Uri>(android.net.Uri::class.java.classLoader)
                                    }
                                } catch (e: Exception) {
                                    null
                                }
                                val parcelType = parcel.readString()
                                parcel.readInt() // flags
                                parcel.readString() // package
                                // Skip categories ArrayList - read size and skip elements
                                val categoriesSize = parcel.readInt()
                                repeat(categoriesSize) {
                                    parcel.readString() // skip each category string
                                }
                                
                                Log.d("RuggedScannerPlugin", "Parcel Intent action: $parcelAction, data: $parcelData, type: $parcelType")
                                
                                // Read Bundle from Parcel
                                val bundleFromParcel = parcel.readBundle()
                                if (bundleFromParcel != null) {
                                    Log.d("RuggedScannerPlugin", "✅ Got Bundle from Parcel, size: ${bundleFromParcel.size()}")
                                    
                                    // Try to get the Serializable key from the Bundle's map before it deserializes
                                    try {
                                        val baseBundleClass = Class.forName("android.os.BaseBundle")
                                        val mMapField = baseBundleClass.getDeclaredField("mMap")
                                        mMapField.isAccessible = true
                                        val map = mMapField.get(bundleFromParcel) as? Map<String, Any?>
                                        
                                        if (map != null && map.isNotEmpty()) {
                                            Log.d("RuggedScannerPlugin", "✅ Bundle map has ${map.size} entries")
                                            for ((key, value) in map) {
                                                Log.d("RuggedScannerPlugin", "Bundle map entry: key='$key', value type=${value?.javaClass?.name}")
                                                
                                                if (key == "com.cipherlab.barcodebase.ReaderDataStruct" && value != null) {
                                                    Log.d("RuggedScannerPlugin", "✅ Found ReaderDataStruct in Bundle map: ${value.javaClass.name}")
                                                    
                                                    // Extract data using reflection
                                                    val valueClass = value.javaClass
                                                    val dataFieldNames = listOf("data", "barcode", "scanData", "scan_data", 
                                                        "barcodeData", "barcode_data", "result", "value", "decodeResult", 
                                                        "decode_result", "content", "text", "string", "mData", "mBarcode",
                                                        "rawData", "raw_data", "decodedData", "decoded_data")
                                                    
                                                    for (dataFieldName in dataFieldNames) {
                                                        try {
                                                            val dataField = valueClass.getDeclaredField(dataFieldName)
                                                            dataField.isAccessible = true
                                                            val dataValue = dataField.get(value)
                                                            if (dataValue is String && dataValue.isNotEmpty() && dataValue.length >= 3) {
                                                                Log.d("RuggedScannerPlugin", "✅ Extracted data from ReaderDataStruct.$dataFieldName: $dataValue")
                                                                if (methodChannel != null) {
                                                                    val arguments = HashMap<String, Any>()
                                                                    arguments["code"] = dataValue
                                                                    try {
                                                                        methodChannel?.invokeMethod("onHardwareScan", arguments)
                                                                        Log.d("RuggedScannerPlugin", "✅ Successfully sent scan data to Flutter")
                                                                    } catch (e: Exception) {
                                                                        Log.e("RuggedScannerPlugin", "Error sending data to Flutter: ${e.message}", e)
                                                                    }
                                                                }
                                                                return
                                                            }
                                                        } catch (e: Exception) {
                                                            continue
                                                        }
                                                    }
                                                    
                                                    // Try all declared String fields
                                                    try {
                                                        val allFields = valueClass.declaredFields
                                                        for (field in allFields) {
                                                            if (field.type == String::class.java) {
                                                                field.isAccessible = true
                                                                val fieldValue = field.get(value) as? String
                                                                if (fieldValue != null && fieldValue.isNotEmpty() && fieldValue.length >= 3) {
                                                                    Log.d("RuggedScannerPlugin", "✅ Extracted data from ReaderDataStruct.${field.name}: $fieldValue")
                                                                    if (methodChannel != null) {
                                                                        val arguments = HashMap<String, Any>()
                                                                        arguments["code"] = fieldValue
                                                                        try {
                                                                            methodChannel?.invokeMethod("onHardwareScan", arguments)
                                                                            Log.d("RuggedScannerPlugin", "✅ Successfully sent scan data to Flutter")
                                                                        } catch (e: Exception) {
                                                                            Log.e("RuggedScannerPlugin", "Error sending data to Flutter: ${e.message}", e)
                                                                        }
                                                                    }
                                                                    return
                                                                }
                                                            }
                                                        }
                                                    } catch (e: Exception) {
                                                        Log.d("RuggedScannerPlugin", "Error iterating fields: ${e.message}")
                                                    }
                                                } else if (value is String && value.isNotEmpty() && value.length >= 3) {
                                                    val isCommand = value == "START_DECODE" || value == "STOP_DECODE" ||
                                                        value == "ENABLE" || value == "DISABLE" ||
                                                        value == "START" || value == "STOP" ||
                                                        key.contains("ACTION", ignoreCase = true) ||
                                                        key.contains("COMMAND", ignoreCase = true)
                                                    
                                                    if (!isCommand) {
                                                        Log.d("RuggedScannerPlugin", "✅ Found data in Bundle map key '$key': $value")
                                                        if (methodChannel != null) {
                                                            val arguments = HashMap<String, Any>()
                                                            arguments["code"] = value
                                                            try {
                                                                methodChannel?.invokeMethod("onHardwareScan", arguments)
                                                                Log.d("RuggedScannerPlugin", "✅ Successfully sent scan data to Flutter")
                                                            } catch (e: Exception) {
                                                                Log.e("RuggedScannerPlugin", "Error sending data to Flutter: ${e.message}", e)
                                                            }
                                                        }
                                                        return
                                                    }
                                                }
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Log.d("RuggedScannerPlugin", "Error accessing Bundle map from Parcel: ${e.message}")
                                    }
                                }
                            } finally {
                                parcel.recycle()
                            }
                        } catch (e: Exception) {
                            Log.d("RuggedScannerPlugin", "Error writing Intent to Parcel: ${e.message}", e)
                        }
                        
                        // Method 0.5: Try to clone the Intent and access extras from clone
                        // This might help if the original Intent's Bundle is parcelled
                        try {
                            Log.d("RuggedScannerPlugin", "Method 0.5: Attempting to clone Intent and access extras")
                            val clonedIntent = intent.clone() as Intent
                            val clonedExtras = clonedIntent.extras
                            if (clonedExtras != null && clonedExtras.size() > 0) {
                                Log.d("RuggedScannerPlugin", "✅ Cloned Intent has ${clonedExtras.size()} extras")
                                val keys = clonedExtras.keySet()
                                for (key in keys) {
                                    Log.d("RuggedScannerPlugin", "Cloned Intent key: $key")
                                    if (key == "com.cipherlab.barcodebase.ReaderDataStruct") {
                                        // Try to get it as Serializable from clone
                                        try {
                                            val serializable = clonedIntent.getSerializableExtra(key)
                                            if (serializable != null) {
                                                Log.d("RuggedScannerPlugin", "✅ Got Serializable from cloned Intent: ${serializable.javaClass.name}")
                                                // Extract using reflection (same as below)
                                                val valueClass = serializable.javaClass
                                                val dataFieldNames = listOf("data", "barcode", "scanData", "scan_data", 
                                                    "barcodeData", "barcode_data", "result", "value", "decodeResult", 
                                                    "decode_result", "content", "text", "string", "mData", "mBarcode",
                                                    "rawData", "raw_data", "decodedData", "decoded_data")
                                                
                                                for (dataFieldName in dataFieldNames) {
                                                    try {
                                                        val dataField = valueClass.getDeclaredField(dataFieldName)
                                                        dataField.isAccessible = true
                                                        val dataValue = dataField.get(serializable)
                                                        if (dataValue is String && dataValue.isNotEmpty() && dataValue.length >= 3) {
                                                            Log.d("RuggedScannerPlugin", "✅ Extracted data from ReaderDataStruct.$dataFieldName: $dataValue")
                                                            if (methodChannel != null) {
                                                                val arguments = HashMap<String, Any>()
                                                                arguments["code"] = dataValue
                                                                try {
                                                                    methodChannel?.invokeMethod("onHardwareScan", arguments)
                                                                    Log.d("RuggedScannerPlugin", "✅ Successfully sent scan data to Flutter")
                                                                } catch (e: Exception) {
                                                                    Log.e("RuggedScannerPlugin", "Error sending data to Flutter: ${e.message}", e)
                                                                }
                                                            }
                                                            return
                                                        }
                                                    } catch (e: Exception) {
                                                        continue
                                                    }
                                                }
                                                
                                                // Try all declared String fields
                                                try {
                                                    val allFields = valueClass.declaredFields
                                                    for (field in allFields) {
                                                        if (field.type == String::class.java) {
                                                            field.isAccessible = true
                                                            val fieldValue = field.get(serializable) as? String
                                                            if (fieldValue != null && fieldValue.isNotEmpty() && fieldValue.length >= 3) {
                                                                Log.d("RuggedScannerPlugin", "✅ Extracted data from ReaderDataStruct.${field.name}: $fieldValue")
                                                                if (methodChannel != null) {
                                                                    val arguments = HashMap<String, Any>()
                                                                    arguments["code"] = fieldValue
                                                                    try {
                                                                        methodChannel?.invokeMethod("onHardwareScan", arguments)
                                                                        Log.d("RuggedScannerPlugin", "✅ Successfully sent scan data to Flutter")
                                                                    } catch (e: Exception) {
                                                                        Log.e("RuggedScannerPlugin", "Error sending data to Flutter: ${e.message}", e)
                                                                    }
                                                                }
                                                                return
                                                            }
                                                        }
                                                    }
                                                } catch (e: Exception) {
                                                    Log.d("RuggedScannerPlugin", "Error iterating fields: ${e.message}")
                                                }
                                            }
                                        } catch (e: ClassNotFoundException) {
                                            Log.d("RuggedScannerPlugin", "ClassNotFoundException on cloned Intent too")
                                        } catch (e: Exception) {
                                            Log.d("RuggedScannerPlugin", "Error getting Serializable from clone: ${e.message}")
                                        }
                                    } else {
                                        // Try to get as String
                                        try {
                                            val value = clonedExtras.getString(key)
                                            if (value != null && value.isNotEmpty() && value.length >= 3) {
                                                val isCommand = value == "START_DECODE" || value == "STOP_DECODE" ||
                                                    value == "ENABLE" || value == "DISABLE" ||
                                                    value == "START" || value == "STOP" ||
                                                    key.contains("ACTION", ignoreCase = true) ||
                                                    key.contains("COMMAND", ignoreCase = true)
                                                
                                                if (!isCommand) {
                                                    Log.d("RuggedScannerPlugin", "✅ Found data in cloned Intent key '$key': $value")
                                                    if (methodChannel != null) {
                                                        val arguments = HashMap<String, Any>()
                                                        arguments["code"] = value
                                                        try {
                                                            methodChannel?.invokeMethod("onHardwareScan", arguments)
                                                            Log.d("RuggedScannerPlugin", "✅ Successfully sent scan data to Flutter")
                                                        } catch (e: Exception) {
                                                            Log.e("RuggedScannerPlugin", "Error sending data to Flutter: ${e.message}", e)
                                                        }
                                                    }
                                                    return
                                                }
                                            }
                                        } catch (e: Exception) {
                                            // Skip
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.d("RuggedScannerPlugin", "Error cloning Intent: ${e.message}")
                        }
                        
                        // Method 0.5: Try to get the ReaderDataStruct using SDK (now that SDK is included)
                        // The key is "BarcodeData" not "com.cipherlab.barcodebase.ReaderDataStruct"
                        try {
                            Log.d("RuggedScannerPlugin", "Method 0.5: Attempting to get ReaderDataStruct using SDK")
                            val serializableKey = "BarcodeData"
                            val readerDataStruct = intent.getSerializableExtra(serializableKey)
                            if (readerDataStruct != null && readerDataStruct.javaClass.name == "com.cipherlab.barcodebase.ReaderDataStruct") {
                                Log.d("RuggedScannerPlugin", "✅ Got ReaderDataStruct object using SDK: ${readerDataStruct.javaClass.name}")
                                
                                // Use reflection to extract data
                                val scanData = extractDataFromReaderDataStruct(readerDataStruct)
                                if (scanData != null && scanData.isNotEmpty() && scanData.length >= 3) {
                                    Log.d("RuggedScannerPlugin", "✅ Extracted scan data from ReaderDataStruct: $scanData")
                                    
                                    // Try to get code type using reflection
                                    var codeType: String? = null
                                    try {
                                        val getCodeTypeStrMethod = readerDataStruct.javaClass.getMethod("GetCodeTypeStr")
                                        codeType = getCodeTypeStrMethod.invoke(readerDataStruct) as? String
                                        Log.d("RuggedScannerPlugin", "Code type: $codeType")
                                    } catch (e: Exception) {
                                        Log.d("RuggedScannerPlugin", "Could not get code type: ${e.message}")
                                    }
                                    
                                    // Send to Flutter
                                    if (methodChannel != null) {
                                        val arguments = HashMap<String, Any>()
                                        arguments["code"] = scanData
                                        if (codeType != null && codeType.isNotEmpty()) {
                                            arguments["format"] = codeType
                                        }
                                        try {
                                            methodChannel?.invokeMethod("onHardwareScan", arguments)
                                            Log.d("RuggedScannerPlugin", "✅ Successfully sent scan data to Flutter")
                                        } catch (e: Exception) {
                                            Log.e("RuggedScannerPlugin", "Error sending data to Flutter: ${e.message}", e)
                                        }
                                    }
                                    return
                                }
                            }
                        } catch (e: ClassNotFoundException) {
                            Log.d("RuggedScannerPlugin", "ClassNotFoundException - SDK may not be loaded properly: ${e.message}")
                        } catch (e: Exception) {
                            Log.d("RuggedScannerPlugin", "Error getting ReaderDataStruct: ${e.message}", e)
                        }
                    
                    // Method 1: Try to get string extras directly (without hasExtra() which triggers Serializable deserialization)
                    val possibleKeys = listOf(
                        "data", "barcode", "scan_data", "SCAN_DATA", "barcode_data", "BARCODE_DATA",
                        "decode_data", "DECODE_DATA", "result", "RESULT", "value", "VALUE",
                        "raw_data", "RAW_DATA", "barcode_string", "BARCODE_STRING",
                        "com.cipherlab.barcodebaseapi.DATA", "com.cipherlab.barcodebaseapi.data",
                        "reader_data", "READER_DATA", "scan_result", "SCAN_RESULT",
                        "com.cipherlab.barcodebase.ReaderDataStruct" // Try the Serializable key name directly
                    )
                    
                    for (key in possibleKeys) {
                        try {
                            // Try getStringExtra directly - this might work even if hasExtra() crashes
                            val value = intent.getStringExtra(key)
                            if (value != null && value.isNotEmpty() && value.length >= 3) {
                                Log.d("RuggedScannerPlugin", "✅ Found data from barcodebaseapi.GET_DATA in key '$key': $value")
                                
                                // Send to Flutter directly
                                if (methodChannel != null) {
                                    val arguments = HashMap<String, Any>()
                                    arguments["code"] = value
                                    try {
                                        methodChannel?.invokeMethod("onHardwareScan", arguments)
                                        Log.d("RuggedScannerPlugin", "✅ Successfully sent scan data to Flutter")
                                    } catch (e: Exception) {
                                        Log.e("RuggedScannerPlugin", "Error sending data to Flutter: ${e.message}", e)
                                    }
                                }
                                return
                            }
                        } catch (e: Exception) {
                            // Skip this key if accessing it causes issues
                            continue
                        }
                        // Also try getCharSequenceExtra
                        try {
                            val charSeq = intent.getCharSequenceExtra(key)
                            if (charSeq != null && charSeq.isNotEmpty() && charSeq.length >= 3) {
                                val value = charSeq.toString()
                                Log.d("RuggedScannerPlugin", "✅ Found data from barcodebaseapi.GET_DATA (CharSequence) in key '$key': $value")
                                
                                // Send to Flutter directly
                                if (methodChannel != null) {
                                    val arguments = HashMap<String, Any>()
                                    arguments["code"] = value
                                    try {
                                        methodChannel?.invokeMethod("onHardwareScan", arguments)
                                        Log.d("RuggedScannerPlugin", "✅ Successfully sent scan data to Flutter")
                                    } catch (e: Exception) {
                                        Log.e("RuggedScannerPlugin", "Error sending data to Flutter: ${e.message}", e)
                                    }
                                }
                                return
                            }
                        } catch (e: Exception) {
                            // Skip this key if accessing it causes issues
                            continue
                        }
                    }
                    
                    // Method 2: Try to access Bundle's internal map directly using reflection
                    // This avoids triggering deserialization of Serializable objects
                    try {
                        Log.d("RuggedScannerPlugin", "Method 2: Attempting to access Bundle map via reflection")
                        val parcelField = intent.javaClass.getDeclaredField("mExtras")
                        parcelField.isAccessible = true
                        val bundle = parcelField.get(intent) as? android.os.Bundle
                        if (bundle != null) {
                            Log.d("RuggedScannerPlugin", "Got Bundle object: ${bundle.javaClass.name}")
                            
                            // Try to get all declared fields from Bundle and its superclasses
                            var map: Map<String, Any?>? = null
                            var currentClass: Class<*>? = bundle.javaClass
                            
                            while (currentClass != null && map == null) {
                                try {
                                    Log.d("RuggedScannerPlugin", "Checking class: ${currentClass.name}")
                                    val fields = currentClass.declaredFields
                                    for (field in fields) {
                                        try {
                                            field.isAccessible = true
                                            val fieldValue = field.get(bundle)
                                            Log.d("RuggedScannerPlugin", "Found field: ${field.name}, type: ${field.type.name}, value type: ${fieldValue?.javaClass?.name}")
                                            
                                            if (fieldValue is Map<*, *>) {
                                                map = fieldValue as? Map<String, Any?>
                                                if (map != null) {
                                                    Log.d("RuggedScannerPlugin", "✅ Found Bundle map using field '${field.name}' in class ${currentClass.name}, size: ${map.size}")
                                                    break
                                                }
                                            }
                                        } catch (e: Exception) {
                                            // Skip fields we can't access
                                            continue
                                        }
                                    }
                                    if (map != null) break
                                    currentClass = currentClass.superclass
                                } catch (e: Exception) {
                                    Log.d("RuggedScannerPlugin", "Error checking class ${currentClass?.name}: ${e.message}")
                                    currentClass = currentClass?.superclass
                                }
                            }
                            
                            // If map is null or empty, try to force unparcelling first
                            // This handles parcelled Bundles where the map might be empty
                            if (map == null || map.isEmpty()) {
                                try {
                                    Log.d("RuggedScannerPlugin", "Map is ${if (map == null) "null" else "empty"}, attempting to force unparcelling")
                                    
                                    // Try to force unparcelling by accessing bundle.size()
                                    try {
                                        val bundleSize = bundle.size()
                                        Log.d("RuggedScannerPlugin", "Bundle size: $bundleSize")
                                        
                                        // After accessing size(), the map should be populated
                                        // Try to get the map again
                                        val baseBundleClass = Class.forName("android.os.BaseBundle")
                                        val mMapField = baseBundleClass.getDeclaredField("mMap")
                                        mMapField.isAccessible = true
                                        val unparcelledMap = mMapField.get(bundle) as? Map<String, Any?>
                                        if (unparcelledMap != null && unparcelledMap.isNotEmpty()) {
                                            Log.d("RuggedScannerPlugin", "✅ Bundle unparcelled! Map now has ${unparcelledMap.size} entries")
                                            map = unparcelledMap
                                        }
                                    } catch (e: Exception) {
                                        Log.d("RuggedScannerPlugin", "Error forcing unparcelling via size(): ${e.message}")
                                    }
                                    
                                    // If still empty, try using Bundle's keySet() method
                                    // This should also force unparcelling
                                    val keys = bundle.keySet()
                                    Log.d("RuggedScannerPlugin", "Bundle has ${keys.size} keys: $keys")
                                    
                                    // If keySet() returned keys but map is still empty, try to get the map again
                                    if (keys.isNotEmpty() && (map == null || map.isEmpty())) {
                                        try {
                                            val baseBundleClass = Class.forName("android.os.BaseBundle")
                                            val mMapField = baseBundleClass.getDeclaredField("mMap")
                                            mMapField.isAccessible = true
                                            val unparcelledMap = mMapField.get(bundle) as? Map<String, Any?>
                                            if (unparcelledMap != null && unparcelledMap.isNotEmpty()) {
                                                Log.d("RuggedScannerPlugin", "✅ Bundle unparcelled after keySet()! Map now has ${unparcelledMap.size} entries")
                                                map = unparcelledMap
                                            }
                                        } catch (e: Exception) {
                                            Log.d("RuggedScannerPlugin", "Error getting map after keySet(): ${e.message}")
                                        }
                                    }
                                    
                                    for (key in keys) {
                                        Log.d("RuggedScannerPlugin", "Processing key: $key")
                                        
                                        // Skip the Serializable key
                                        if (key == "com.cipherlab.barcodebase.ReaderDataStruct") {
                                            Log.d("RuggedScannerPlugin", "Found Serializable key: $key, attempting to extract data from it")
                                            
                                            // Try to get the Serializable object and extract data from it
                                            try {
                                                // Use getSerializableExtra but catch ClassNotFoundException
                                                val serializable = bundle.getSerializable(key)
                                                if (serializable != null) {
                                                    Log.d("RuggedScannerPlugin", "Got Serializable object: ${serializable.javaClass.name}")
                                                    
                                                    // Try to extract data using reflection
                                                    val valueClass = serializable.javaClass
                                                    val dataFieldNames = listOf("data", "barcode", "scanData", "scan_data", 
                                                        "barcodeData", "barcode_data", "result", "value", "decodeResult", 
                                                        "decode_result", "content", "text", "string", "mData", "mBarcode",
                                                        "rawData", "raw_data", "decodedData", "decoded_data")
                                                    
                                                    for (dataFieldName in dataFieldNames) {
                                                        try {
                                                            val dataField = valueClass.getDeclaredField(dataFieldName)
                                                            dataField.isAccessible = true
                                                            val dataValue = dataField.get(serializable)
                                                            Log.d("RuggedScannerPlugin", "Field '$dataFieldName' value: $dataValue (type: ${dataValue?.javaClass?.name})")
                                                            if (dataValue is String && dataValue.isNotEmpty() && dataValue.length >= 3) {
                                                                Log.d("RuggedScannerPlugin", "✅ Extracted data from ReaderDataStruct.$dataFieldName: $dataValue")
                                                                
                                                                // Send to Flutter directly
                                                                if (methodChannel != null) {
                                                                    val arguments = HashMap<String, Any>()
                                                                    arguments["code"] = dataValue
                                                                    try {
                                                                        methodChannel?.invokeMethod("onHardwareScan", arguments)
                                                                        Log.d("RuggedScannerPlugin", "✅ Successfully sent scan data to Flutter")
                                                                    } catch (e: Exception) {
                                                                        Log.e("RuggedScannerPlugin", "Error sending data to Flutter: ${e.message}", e)
                                                                    }
                                                                }
                                                                return
                                                            }
                                                        } catch (e: NoSuchFieldException) {
                                                            continue
                                                        } catch (e: Exception) {
                                                            Log.d("RuggedScannerPlugin", "Error accessing field $dataFieldName: ${e.message}")
                                                        }
                                                    }
                                                    
                                                    // Try all declared String fields
                                                    try {
                                                        val allFields = valueClass.declaredFields
                                                        for (field in allFields) {
                                                            if (field.type == String::class.java) {
                                                                field.isAccessible = true
                                                                val fieldValue = field.get(serializable) as? String
                                                                if (fieldValue != null && fieldValue.isNotEmpty() && fieldValue.length >= 3) {
                                                                    Log.d("RuggedScannerPlugin", "✅ Extracted data from ReaderDataStruct.${field.name}: $fieldValue")
                                                                    
                                                                    if (methodChannel != null) {
                                                                        val arguments = HashMap<String, Any>()
                                                                        arguments["code"] = fieldValue
                                                                        try {
                                                                            methodChannel?.invokeMethod("onHardwareScan", arguments)
                                                                            Log.d("RuggedScannerPlugin", "✅ Successfully sent scan data to Flutter")
                                                                        } catch (e: Exception) {
                                                                            Log.e("RuggedScannerPlugin", "Error sending data to Flutter: ${e.message}", e)
                                                                        }
                                                                    }
                                                                    return
                                                                }
                                                            }
                                                        }
                                                    } catch (e: Exception) {
                                                        Log.d("RuggedScannerPlugin", "Error iterating fields: ${e.message}")
                                                    }
                                                }
                                            } catch (e: ClassNotFoundException) {
                                                Log.d("RuggedScannerPlugin", "ClassNotFoundException when getting Serializable - trying alternative approach")
                                                // The Serializable class is not available, but we can still try to access it via reflection
                                                // by getting it from the map if it was already unparcelled
                                            } catch (e: Exception) {
                                                Log.d("RuggedScannerPlugin", "Error getting Serializable: ${e.message}")
                                            }
                                            continue
                                        }
                                        
                                        // Try to get as String
                                        try {
                                            val value = bundle.getString(key)
                                            if (value != null && value.isNotEmpty() && value.length >= 3) {
                                                val isCommand = value == "START_DECODE" || value == "STOP_DECODE" ||
                                                    value == "ENABLE" || value == "DISABLE" ||
                                                    value == "START" || value == "STOP" ||
                                                    key.contains("ACTION", ignoreCase = true) ||
                                                    key.contains("COMMAND", ignoreCase = true)
                                                
                                                if (!isCommand) {
                                                    Log.d("RuggedScannerPlugin", "✅ Found data from Bundle key '$key': $value")
                                                    
                                                    // Send to Flutter directly
                                                    if (methodChannel != null) {
                                                        val arguments = HashMap<String, Any>()
                                                        arguments["code"] = value
                                                        try {
                                                            methodChannel?.invokeMethod("onHardwareScan", arguments)
                                                            Log.d("RuggedScannerPlugin", "✅ Successfully sent scan data to Flutter")
                                                        } catch (e: Exception) {
                                                            Log.e("RuggedScannerPlugin", "Error sending data to Flutter: ${e.message}", e)
                                                        }
                                                    }
                                                    return
                                                }
                                            }
                                        } catch (e: Exception) {
                                            // This key might be the Serializable, skip it
                                            Log.d("RuggedScannerPlugin", "Could not get key '$key' as String: ${e.message}")
                                            continue
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.d("RuggedScannerPlugin", "Error using Bundle.keySet(): ${e.message}", e)
                                }
                            }
                            
                            if (map != null && map.isNotEmpty()) {
                                Log.d("RuggedScannerPlugin", "Iterating through Bundle map (${map.size} entries)")
                                // Iterate through the map to find string values
                                for ((key, value) in map) {
                                    Log.d("RuggedScannerPlugin", "Map entry: key='$key', value type=${value?.javaClass?.name}")
                                    
                                    // Check for BarcodeData key (the actual key used by Reader_Service)
                                    if (key == "BarcodeData" || key == "com.cipherlab.barcodebase.ReaderDataStruct") {
                                        Log.d("RuggedScannerPlugin", "Found ReaderDataStruct key: $key, attempting to extract data using SDK")
                                        
                                        // Try to cast to ReaderDataStruct and use SDK methods
                                        try {
                                            if (value != null && value.javaClass.name == "com.cipherlab.barcodebase.ReaderDataStruct") {
                                                Log.d("RuggedScannerPlugin", "✅ Got ReaderDataStruct from Bundle map using SDK: ${value.javaClass.name}")
                                                
                                                // Use reflection to extract data
                                                val scanData = extractDataFromReaderDataStruct(value)
                                                if (scanData != null && scanData.isNotEmpty() && scanData.length >= 3) {
                                                    Log.d("RuggedScannerPlugin", "✅ Extracted scan data from ReaderDataStruct: $scanData")
                                                    
                                                    // Try to get code type using reflection
                                                    var codeType: String? = null
                                                    try {
                                                        val getCodeTypeStrMethod = value.javaClass.getMethod("GetCodeTypeStr")
                                                        codeType = getCodeTypeStrMethod.invoke(value) as? String
                                                        Log.d("RuggedScannerPlugin", "Code type: $codeType")
                                                    } catch (e: Exception) {
                                                        Log.d("RuggedScannerPlugin", "Could not get code type: ${e.message}")
                                                    }
                                                    
                                                    if (methodChannel != null) {
                                                        val arguments = HashMap<String, Any>()
                                                        arguments["code"] = scanData
                                                        if (codeType != null && codeType.isNotEmpty()) {
                                                            arguments["format"] = codeType
                                                        }
                                                        try {
                                                            methodChannel?.invokeMethod("onHardwareScan", arguments)
                                                            Log.d("RuggedScannerPlugin", "✅ Successfully sent scan data to Flutter")
                                                        } catch (e: Exception) {
                                                            Log.e("RuggedScannerPlugin", "Error sending data to Flutter: ${e.message}", e)
                                                        }
                                                    }
                                                    return
                                                }
                                            }
                                        } catch (e: ClassCastException) {
                                            Log.d("RuggedScannerPlugin", "Value is not ReaderDataStruct: ${value?.javaClass?.name}")
                                        } catch (e: Exception) {
                                            Log.d("RuggedScannerPlugin", "Error extracting data from ReaderDataStruct: ${e.message}")
                                        }
                                        continue
                                    }
                                    
                                    // Check if this is a string value we can use
                                    if (value is String && value.isNotEmpty() && value.length >= 3) {
                                        // Filter out command strings
                                        val isCommand = value == "START_DECODE" || value == "STOP_DECODE" ||
                                            value == "ENABLE" || value == "DISABLE" ||
                                            value == "START" || value == "STOP" ||
                                            key.contains("ACTION", ignoreCase = true) ||
                                            key.contains("COMMAND", ignoreCase = true)
                                        
                                        if (!isCommand) {
                                            Log.d("RuggedScannerPlugin", "✅ Found data from Bundle map key '$key': $value")
                                            
                                            // Send to Flutter directly
                                            if (methodChannel != null) {
                                                val arguments = HashMap<String, Any>()
                                                arguments["code"] = value
                                                try {
                                                    methodChannel?.invokeMethod("onHardwareScan", arguments)
                                                    Log.d("RuggedScannerPlugin", "✅ Successfully sent scan data to Flutter")
                                                } catch (e: Exception) {
                                                    Log.e("RuggedScannerPlugin", "Error sending data to Flutter: ${e.message}", e)
                                                }
                                            }
                                            return
                                        }
                                    }
                                }
                                Log.d("RuggedScannerPlugin", "Finished iterating Bundle map, no data found")
                            } else {
                                Log.d("RuggedScannerPlugin", "Could not access Bundle map - map is null")
                            }
                        } else {
                            Log.d("RuggedScannerPlugin", "Bundle is null")
                        }
                    } catch (e: Exception) {
                        Log.d("RuggedScannerPlugin", "Could not access Bundle map directly: ${e.message}", e)
                    }
                    
                            // Method 4: Final attempt - try to use Intent's internal methods
                            // This is a last resort before giving up
                            try {
                                Log.d("RuggedScannerPlugin", "Method 4: Attempting final extraction methods")
                                
                                // Try to get all possible string extras without checking hasExtra first
                                val allPossibleKeys = listOf(
                                    "data", "barcode", "scan_data", "SCAN_DATA", "barcode_data", "BARCODE_DATA",
                                    "decode_data", "DECODE_DATA", "result", "RESULT", "value", "VALUE",
                                    "raw_data", "RAW_DATA", "barcode_string", "BARCODE_STRING",
                                    "com.cipherlab.barcodebaseapi.DATA", "com.cipherlab.barcodebaseapi.data",
                                    "reader_data", "READER_DATA", "scan_result", "SCAN_RESULT",
                                    "content", "CONTENT", "text", "TEXT", "string", "STRING"
                                )
                                
                                for (key in allPossibleKeys) {
                                    try {
                                        val value = intent.getStringExtra(key)
                                        if (value != null && value.isNotEmpty() && value.length >= 3) {
                                            val isCommand = value == "START_DECODE" || value == "STOP_DECODE" ||
                                                value == "ENABLE" || value == "DISABLE" ||
                                                value == "START" || value == "STOP"
                                            
                                            if (!isCommand) {
                                                Log.d("RuggedScannerPlugin", "✅ Found data in key '$key': $value")
                                                if (methodChannel != null) {
                                                    val arguments = HashMap<String, Any>()
                                                    arguments["code"] = value
                                                    try {
                                                        methodChannel?.invokeMethod("onHardwareScan", arguments)
                                                        Log.d("RuggedScannerPlugin", "✅ Successfully sent scan data to Flutter")
                                                    } catch (e: Exception) {
                                                        Log.e("RuggedScannerPlugin", "Error sending data to Flutter: ${e.message}", e)
                                                    }
                                                }
                                                return
                                            }
                                        }
                                    } catch (e: Exception) {
                                        // Skip
                                    }
                                }
                            } catch (e: Exception) {
                                Log.d("RuggedScannerPlugin", "Error in final extraction attempt: ${e.message}")
                            }
                            
                            // Method 5: Log detailed information about what we received for debugging
                            Log.w("RuggedScannerPlugin", "⚠️ Could not extract data from barcodebaseapi.GET_DATA using any method")
                            Log.w("RuggedScannerPlugin", "⚠️ Reader_Service is sending data but it's in a Serializable object (com.cipherlab.barcodebase.ReaderDataStruct) we can't deserialize")
                            Log.w("RuggedScannerPlugin", "⚠️ The Bundle appears empty because Android can't deserialize the Serializable without the class definition")
                            Log.w("RuggedScannerPlugin", "⚠️ SOLUTION: You need to include the CipherLab SDK (barcodebase.jar or similar) in your project")
                            Log.w("RuggedScannerPlugin", "⚠️ Add the CipherLab SDK to: android/app/libs/ and update build.gradle")
                            Log.w("RuggedScannerPlugin", "⚠️ Alternative: Contact CipherLab to request Reader_Service send data as String extra instead of Serializable")
                            // Return early to avoid trying to access extras which will crash
                            return
                } else {
                    // Safely log extras without accessing keySet (which can crash with Serializable objects)
                    try {
                        val extras = intent.extras
                        if (extras != null) {
                            Log.d("RuggedScannerPlugin", "Intent has extras (count: ${extras.size()})")
                        } else {
                            Log.d("RuggedScannerPlugin", "Intent has no extras")
                        }
                    } catch (e: Exception) {
                        Log.w("RuggedScannerPlugin", "Could not access intent extras (may contain Serializable objects): ${e.message}")
                    }
                }
                
                // Extract scan data - wrap entire extraction in try-catch to handle Serializable objects
                val code: String? = try {
                    // First, try to ignore enable/disable command broadcasts (wrap each check in try-catch)
                    try {
                        if (intent.hasExtra("DECODE_ACTION")) {
                            val decodeAction = intent.getStringExtra("DECODE_ACTION")
                            if (decodeAction == "START_DECODE" || decodeAction == "STOP_DECODE") {
                                Log.d("RuggedScannerPlugin", "Ignoring enable/disable command broadcast: $decodeAction")
                                return
                            }
                        }
                    } catch (e: Exception) {
                        // Ignore if we can't access this extra
                    }
                    try {
                        if (intent.hasExtra("ACTION")) {
                            val actionExtra = intent.getStringExtra("ACTION")
                            if (actionExtra == "START_DECODE" || actionExtra == "STOP_DECODE") {
                                Log.d("RuggedScannerPlugin", "Ignoring enable/disable command broadcast: $actionExtra")
                                return
                            }
                        }
                    } catch (e: Exception) {
                        // Ignore if we can't access this extra
                    }
                    try {
                        if (intent.hasExtra("ENABLE") && intent.getBooleanExtra("ENABLE", false)) {
                            Log.d("RuggedScannerPlugin", "Ignoring enable command broadcast")
                            return
                        }
                    } catch (e: Exception) {
                        // Ignore if we can't access this extra
                    }
                    try {
                        if (intent.hasExtra("START") && intent.getBooleanExtra("START", false)) {
                            Log.d("RuggedScannerPlugin", "Ignoring start command broadcast")
                            return
                        }
                    } catch (e: Exception) {
                        // Ignore if we can't access this extra
                    }
                    
                    // Extract scan data
                    when {
                    // Zebra DataWedge
                    intent.hasExtra("com.symbol.datawedge.data_string") -> {
                        val data = intent.getStringExtra("com.symbol.datawedge.data_string")
                        Log.d("RuggedScannerPlugin", "Found Zebra DataWedge data: $data")
                        data
                    }
                    // Honeywell SDK
                    intent.hasExtra("com.honeywell.decode.intent.extra.DECODE_DATA") -> {
                        val data = intent.getStringExtra("com.honeywell.decode.intent.extra.DECODE_DATA")
                        Log.d("RuggedScannerPlugin", "Found Honeywell data: $data")
                        data
                    }
                    // Datalogic SDK
                    intent.hasExtra("com.datalogic.decode.intent.extra.DECODE_DATA") -> {
                        val data = intent.getStringExtra("com.datalogic.decode.intent.extra.DECODE_DATA")
                        Log.d("RuggedScannerPlugin", "Found Datalogic data: $data")
                        data
                    }
                    // Unitech SDK
                    intent.hasExtra("com.unitech.scanner.extra.DECODE_DATA") -> {
                        val data = intent.getStringExtra("com.unitech.scanner.extra.DECODE_DATA")
                        Log.d("RuggedScannerPlugin", "Found Unitech data: $data")
                        data
                    }
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
                                    Log.d("RuggedScannerPlugin", "✅ Found data from barcodebaseapi.GET_DATA in key '$key': $foundData")
                                    break
                                }
                            }
                        }
                        if (foundData == null) {
                            // Last resort: try any string extra
                            intent.extras?.let { extras ->
                                for (key in extras.keySet()) {
                                    val value = extras.get(key)
                                    if (value is String && value.isNotEmpty() && value.length >= 3) {
                                        val keyStr = key.toString()
                                        val valueStr = value.toString()
                                        val isCommand = valueStr == "START_DECODE" || 
                                            valueStr == "STOP_DECODE" ||
                                            valueStr == "ENABLE" ||
                                            valueStr == "DISABLE" ||
                                            keyStr.contains("ACTION", ignoreCase = true) ||
                                            keyStr.contains("COMMAND", ignoreCase = true)
                                        
                                        if (!isCommand) {
                                            foundData = valueStr
                                            Log.d("RuggedScannerPlugin", "✅ Found data from barcodebaseapi.GET_DATA in key '$keyStr': $foundData")
                                            break
                                        }
                                    }
                                }
                            }
                        }
                        foundData
                    }
                    intent.hasExtra("com.cipherlab.barcodebaseapi.DATA") -> {
                        val data = intent.getStringExtra("com.cipherlab.barcodebaseapi.DATA")
                        Log.d("RuggedScannerPlugin", "Found com.cipherlab.barcodebaseapi.DATA: $data")
                        data
                    }
                    // CipherLab specific extras
                    intent.hasExtra("com.cipherlab.decode.DATA") -> {
                        val data = intent.getStringExtra("com.cipherlab.decode.DATA")
                        Log.d("RuggedScannerPlugin", "Found CipherLab decode data: $data")
                        data
                    }
                    intent.hasExtra("com.cipherlab.scanner.DATA") -> {
                        val data = intent.getStringExtra("com.cipherlab.scanner.DATA")
                        Log.d("RuggedScannerPlugin", "Found CipherLab scanner data: $data")
                        data
                    }
                    intent.hasExtra("com.cipherlab.barcode.DATA") -> {
                        val data = intent.getStringExtra("com.cipherlab.barcode.DATA")
                        Log.d("RuggedScannerPlugin", "Found CipherLab barcode data: $data")
                        data
                    }
                    intent.hasExtra("com.cipherlab.decode.SCAN_DATA") -> {
                        val data = intent.getStringExtra("com.cipherlab.decode.SCAN_DATA")
                        Log.d("RuggedScannerPlugin", "Found CipherLab scan data: $data")
                        data
                    }
                    intent.hasExtra("com.cipherlab.scanner.SCAN_DATA") -> {
                        val data = intent.getStringExtra("com.cipherlab.scanner.SCAN_DATA")
                        Log.d("RuggedScannerPlugin", "Found CipherLab scanner scan data: $data")
                        data
                    }
                    intent.hasExtra("com.cipherlab.decode.RESULT") -> {
                        val data = intent.getStringExtra("com.cipherlab.decode.RESULT")
                        Log.d("RuggedScannerPlugin", "Found CipherLab decode result: $data")
                        data
                    }
                    intent.hasExtra("com.cipherlab.scanner.RESULT") -> {
                        val data = intent.getStringExtra("com.cipherlab.scanner.RESULT")
                        Log.d("RuggedScannerPlugin", "Found CipherLab scanner result: $data")
                        data
                    }
                    // Reader_Service (sw.reader.*) specific extras
                    intent.hasExtra("sw.reader.scan.result") -> {
                        val data = intent.getStringExtra("sw.reader.scan.result")
                        Log.d("RuggedScannerPlugin", "Found sw.reader.scan.result: $data")
                        data
                    }
                    intent.hasExtra("sw.reader.scan.data") -> {
                        val data = intent.getStringExtra("sw.reader.scan.data")
                        Log.d("RuggedScannerPlugin", "Found sw.reader.scan.data: $data")
                        data
                    }
                    intent.hasExtra("sw.reader.decode.result") -> {
                        val data = intent.getStringExtra("sw.reader.decode.result")
                        Log.d("RuggedScannerPlugin", "Found sw.reader.decode.result: $data")
                        data
                    }
                    intent.hasExtra("sw.reader.decode.data") -> {
                        val data = intent.getStringExtra("sw.reader.decode.data")
                        Log.d("RuggedScannerPlugin", "Found sw.reader.decode.data: $data")
                        data
                    }
                    intent.hasExtra("sw.reader.barcode.result") -> {
                        val data = intent.getStringExtra("sw.reader.barcode.result")
                        Log.d("RuggedScannerPlugin", "Found sw.reader.barcode.result: $data")
                        data
                    }
                    intent.hasExtra("sw.reader.barcode.data") -> {
                        val data = intent.getStringExtra("sw.reader.barcode.data")
                        Log.d("RuggedScannerPlugin", "Found sw.reader.barcode.data: $data")
                        data
                    }
                    intent.hasExtra("sw.reader.DATA") -> {
                        val data = intent.getStringExtra("sw.reader.DATA")
                        Log.d("RuggedScannerPlugin", "Found sw.reader.DATA: $data")
                        data
                    }
                    intent.hasExtra("sw.reader.RESULT") -> {
                        val data = intent.getStringExtra("sw.reader.RESULT")
                        Log.d("RuggedScannerPlugin", "Found sw.reader.RESULT: $data")
                        data
                    }
                    intent.hasExtra("sw.reader.data") -> {
                        val data = intent.getStringExtra("sw.reader.data")
                        Log.d("RuggedScannerPlugin", "Found sw.reader.data: $data")
                        data
                    }
                    intent.hasExtra("sw.reader.result") -> {
                        val data = intent.getStringExtra("sw.reader.result")
                        Log.d("RuggedScannerPlugin", "Found sw.reader.result: $data")
                        data
                    }
                    intent.hasExtra("barcode") -> {
                        val data = intent.getStringExtra("barcode")
                        Log.d("RuggedScannerPlugin", "Found barcode: $data")
                        data
                    }
                    intent.hasExtra("BARCODE") -> {
                        val data = intent.getStringExtra("BARCODE")
                        Log.d("RuggedScannerPlugin", "Found BARCODE: $data")
                        data
                    }
                    intent.hasExtra("Barcode") -> {
                        val data = intent.getStringExtra("Barcode")
                        Log.d("RuggedScannerPlugin", "Found Barcode: $data")
                        data
                    }
                    intent.hasExtra("SCAN_DATA") -> {
                        val data = intent.getStringExtra("SCAN_DATA")
                        Log.d("RuggedScannerPlugin", "Found SCAN_DATA: $data")
                        data
                    }
                    intent.hasExtra("scan_data") -> {
                        val data = intent.getStringExtra("scan_data")
                        Log.d("RuggedScannerPlugin", "Found scan_data: $data")
                        data
                    }
                    // Generic barcode scanner (common for infrared scanners)
                    intent.hasExtra("barcode_string") -> {
                        val data = intent.getStringExtra("barcode_string")
                        Log.d("RuggedScannerPlugin", "Found barcode_string: $data")
                        data
                    }
                    intent.hasExtra("data") -> {
                        val data = intent.getStringExtra("data")
                        Log.d("RuggedScannerPlugin", "Found data: $data")
                        data
                    }
                    intent.hasExtra("DATA") -> {
                        val data = intent.getStringExtra("DATA")
                        Log.d("RuggedScannerPlugin", "Found DATA: $data")
                        data
                    }
                    intent.hasExtra("Data") -> {
                        val data = intent.getStringExtra("Data")
                        Log.d("RuggedScannerPlugin", "Found Data: $data")
                        data
                    }
                    intent.hasExtra("result") -> {
                        val data = intent.getStringExtra("result")
                        Log.d("RuggedScannerPlugin", "Found result: $data")
                        data
                    }
                    intent.hasExtra("RESULT") -> {
                        val data = intent.getStringExtra("RESULT")
                        Log.d("RuggedScannerPlugin", "Found RESULT: $data")
                        data
                    }
                    intent.hasExtra("Result") -> {
                        val data = intent.getStringExtra("Result")
                        Log.d("RuggedScannerPlugin", "Found Result: $data")
                        data
                    }
                    intent.hasExtra("value") -> {
                        val data = intent.getStringExtra("value")
                        Log.d("RuggedScannerPlugin", "Found value: $data")
                        data
                    }
                    intent.hasExtra("VALUE") -> {
                        val data = intent.getStringExtra("VALUE")
                        Log.d("RuggedScannerPlugin", "Found VALUE: $data")
                        data
                    }
                    intent.hasExtra("content") -> {
                        val data = intent.getStringExtra("content")
                        Log.d("RuggedScannerPlugin", "Found content: $data")
                        data
                    }
                    intent.hasExtra("CONTENT") -> {
                        val data = intent.getStringExtra("CONTENT")
                        Log.d("RuggedScannerPlugin", "Found CONTENT: $data")
                        data
                    }
                    intent.hasExtra("text") -> {
                        val data = intent.getStringExtra("text")
                        Log.d("RuggedScannerPlugin", "Found text: $data")
                        data
                    }
                    intent.hasExtra("TEXT") -> {
                        val data = intent.getStringExtra("TEXT")
                        Log.d("RuggedScannerPlugin", "Found TEXT: $data")
                        data
                    }
                    intent.hasExtra("string") -> {
                        val data = intent.getStringExtra("string")
                        Log.d("RuggedScannerPlugin", "Found string: $data")
                        data
                    }
                    intent.hasExtra("STRING") -> {
                        val data = intent.getStringExtra("STRING")
                        Log.d("RuggedScannerPlugin", "Found STRING: $data")
                        data
                    }
                    intent.hasExtra("message") -> {
                        val data = intent.getStringExtra("message")
                        Log.d("RuggedScannerPlugin", "Found message: $data")
                        data
                    }
                    intent.hasExtra("MESSAGE") -> {
                        val data = intent.getStringExtra("MESSAGE")
                        Log.d("RuggedScannerPlugin", "Found MESSAGE: $data")
                        data
                    }
                    intent.hasExtra("decode_data") -> {
                        val data = intent.getStringExtra("decode_data")
                        Log.d("RuggedScannerPlugin", "Found decode_data: $data")
                        data
                    }
                    intent.hasExtra("scannerdata") -> {
                        val data = intent.getStringExtra("scannerdata")
                        Log.d("RuggedScannerPlugin", "Found scannerdata: $data")
                        data
                    }
                    else -> {
                        // Try to get data from URI if it's a VIEW intent
                        val uriData = intent.data?.toString()
                        if (uriData != null && !uriData.contains("START_DECODE") && !uriData.contains("STOP_DECODE")) {
                            Log.d("RuggedScannerPlugin", "Found data in URI: $uriData")
                            uriData
                        } else {
                            Log.w("RuggedScannerPlugin", "No recognized barcode data field found in intent")
                            // Try to extract any string extra as last resort (but skip command strings)
                            // Avoid accessing keySet() directly to prevent Serializable crashes
                            var foundData: String? = null
                            try {
                                val extras = intent.extras
                                if (extras != null) {
                                    val candidates = mutableListOf<Pair<String, String>>()
                                    // Try known keys first to avoid accessing keySet()
                                    val knownKeys = listOf("data", "barcode", "scan_data", "SCAN_DATA", 
                                        "barcode_data", "BARCODE_DATA", "decode_data", "DECODE_DATA",
                                        "result", "RESULT", "value", "VALUE", "raw_data", "RAW_DATA")
                                    
                                    for (key in knownKeys) {
                                        try {
                                            if (extras.containsKey(key)) {
                                                val value = extras.get(key)
                                                if (value is String && value.isNotEmpty() && value.length >= 3) {
                                                    val valueStr = value.toString()
                                                    val isCommand = valueStr == "START_DECODE" || 
                                                        valueStr == "STOP_DECODE" ||
                                                        valueStr == "ENABLE" ||
                                                        valueStr == "DISABLE" ||
                                                        valueStr == "START" ||
                                                        valueStr == "STOP"
                                                    
                                                    if (!isCommand) {
                                                        candidates.add(Pair(key, valueStr))
                                                        Log.d("RuggedScannerPlugin", "📋 Candidate scan data: key=$key, value=$valueStr (length=${valueStr.length})")
                                                    }
                                                }
                                            }
                                        } catch (e: Exception) {
                                            // Skip this key if it causes issues
                                        }
                                    }
                                    
                                    // Sort by length (longer = more likely to be scan data)
                                    candidates.sortByDescending { it.second.length }
                                    foundData = candidates.firstOrNull()?.second
                                    if (foundData != null) {
                                        Log.d("RuggedScannerPlugin", "✅ Selected scan data from ${candidates.size} candidates: ${candidates.firstOrNull()?.first} = $foundData")
                                    } else {
                                        Log.d("RuggedScannerPlugin", "❌ No valid scan data found in known keys")
                                    }
                                }
                            } catch (e: Exception) {
                                Log.w("RuggedScannerPlugin", "Error accessing extras (may contain Serializable objects): ${e.message}")
                            }
                            foundData
                        }
                    }
                }
                } catch (e: Exception) {
                    // If we can't access extras due to Serializable, try to get data directly using reflection
                    Log.w("RuggedScannerPlugin", "Error accessing intent extras (may contain Serializable objects): ${e.message}")
                    if (action == "com.cipherlab.barcodebaseapi.GET_DATA") {
                        try {
                            val readerData = intent.getSerializableExtra("reader_data")
                            if (readerData != null) {
                                Log.d("RuggedScannerPlugin", "Found reader_data Serializable object: ${readerData.javaClass.name}")
                                // Try to extract data using reflection
                                try {
                                    val dataField = readerData.javaClass.getDeclaredField("data")
                                    dataField.isAccessible = true
                                    val dataValue = dataField.get(readerData)
                                    if (dataValue is String && dataValue.isNotEmpty()) {
                                        val extractedCode = dataValue
                                        Log.d("RuggedScannerPlugin", "✅ Extracted data from ReaderDataStruct via reflection: $extractedCode")
                                        
                                        // Send to Flutter directly
                                        if (methodChannel != null) {
                                            val arguments = HashMap<String, Any>()
                                            arguments["code"] = extractedCode
                                            try {
                                                methodChannel?.invokeMethod("onHardwareScan", arguments)
                                                Log.d("RuggedScannerPlugin", "✅ Successfully sent scan data to Flutter")
                                            } catch (e: Exception) {
                                                Log.e("RuggedScannerPlugin", "Error sending data to Flutter: ${e.message}", e)
                                            }
                                        }
                                        return
                                    }
                                } catch (e: Exception) {
                                    Log.d("RuggedScannerPlugin", "Could not extract data from ReaderDataStruct via reflection: ${e.message}")
                                }
                            }
                        } catch (e: Exception) {
                            Log.d("RuggedScannerPlugin", "Could not access reader_data: ${e.message}")
                        }
                    }
                    // If we can't extract data, return early
                    return
                }

                Log.d("RuggedScannerPlugin", "Extracted code: $code")
                
                if (code.isNullOrEmpty()) {
                    Log.w("RuggedScannerPlugin", "Code is null or empty, cannot process scan")
                    return
                }
                
                if (methodChannel == null) {
                    Log.e("RuggedScannerPlugin", "MethodChannel is null! Cannot send data to Flutter")
                    return
                }
                
                Log.d("RuggedScannerPlugin", "MethodChannel is available, preparing to send data to Flutter")
                
                    val format = when {
                        intent.hasExtra("com.symbol.datawedge.label_type") -> {
                            intent.getStringExtra("com.symbol.datawedge.label_type")
                        }
                        intent.hasExtra("com.honeywell.decode.intent.extra.DECODE_TYPE") -> {
                            intent.getStringExtra("com.honeywell.decode.intent.extra.DECODE_TYPE")
                        }
                        intent.hasExtra("com.datalogic.decode.intent.extra.DECODE_TYPE") -> {
                            intent.getStringExtra("com.datalogic.decode.intent.extra.DECODE_TYPE")
                        }
                        intent.hasExtra("com.unitech.scanner.extra.DECODE_TYPE") -> {
                            intent.getStringExtra("com.unitech.scanner.extra.DECODE_TYPE")
                        }
                        intent.hasExtra("barcode_type") -> {
                            intent.getStringExtra("barcode_type")
                        }
                        intent.hasExtra("format") -> {
                            intent.getStringExtra("format")
                        }
                        else -> null
                    }

                    val arguments = HashMap<String, Any>()
                    arguments["code"] = code
                    if (format != null) {
                        arguments["format"] = format
                    }

                    Log.d("RuggedScannerPlugin", "Sending to Flutter - code: $code, format: $format")
                    try {
                    methodChannel?.invokeMethod("onHardwareScan", arguments)
                        Log.d("RuggedScannerPlugin", "Successfully sent scan data to Flutter")
                    } catch (e: Exception) {
                        Log.e("RuggedScannerPlugin", "Error sending data to Flutter: ${e.message}", e)
                }
            }
        }

        // Register for multiple scanner intents (including infrared scanners)
        val filter = IntentFilter().apply {
            // Zebra DataWedge
            addAction("com.symbol.datawedge.api.RESULT_ACTION")
            // Honeywell SDK
            addAction("com.honeywell.decode.intent.action.ACTION_DECODE")
            addAction("com.honeywell.decode.intent.action.ACTION_DECODE_RESULT")
            addAction("com.honeywell.decode.intent.action.ACTION_DECODE_DATA")
            addAction("com.honeywell.decode.intent.action.ACTION_DECODE_FAILURE")
            // Datalogic SDK
            addAction("com.datalogic.decode.intent.action.DECODE")
            addAction("com.datalogic.decode.intent.action.DECODE_RESULT")
            addAction("com.datalogic.decode.intent.action.DECODE_DATA")
            // Unitech SDK
            addAction("com.unitech.scanner.action.DECODE")
            addAction("com.unitech.scanner.action.DECODE_RESULT")
            addAction("com.unitech.scanner.action.DECODE_DATA")
            // CipherLab specific intents - comprehensive list
            addAction("com.cipherlab.scanner.ACTION")
            addAction("com.cipherlab.decode.ACTION")
            addAction("com.cipherlab.barcode.ACTION")
            // CipherLab barcodebaseapi (Reader_Service uses this for scan results)
            addAction("com.cipherlab.barcodebaseapi.GET_DATA")
            addAction("com.cipherlab.barcodebaseapi.DATA")
            addAction("com.cipherlab.barcodebaseapi.RESULT")
            addAction("com.cipherlab.scanner.RESULT")
            addAction("com.cipherlab.decode.RESULT")
            addAction("com.cipherlab.barcode.RESULT")
            addAction("com.cipherlab.scanner.DATA")
            addAction("com.cipherlab.decode.DATA")
            addAction("com.cipherlab.barcode.DATA")
            addAction("com.cipherlab.scanner.SCAN")
            addAction("com.cipherlab.decode.SCAN")
            addAction("com.cipherlab.barcode.SCAN")
            addAction("com.cipherlab.scanner.RECEIVED")
            addAction("com.cipherlab.decode.RECEIVED")
            addAction("com.cipherlab.barcode.RECEIVED")
            addAction("com.cipherlab.scanner.SCAN_RESULT")
            addAction("com.cipherlab.decode.SCAN_RESULT")
            addAction("com.cipherlab.barcode.SCAN_RESULT")
            addAction("com.cipherlab.scanner.DECODE_RESULT")
            addAction("com.cipherlab.decode.DECODE_RESULT")
            addAction("com.cipherlab.barcode.DECODE_RESULT")
            // RS36/CipherLab device scanner service intents (Reader_Service)
            addAction("sw.reader.scan.result")
            addAction("sw.reader.scan.data")
            addAction("sw.reader.decode.result")
            addAction("sw.reader.decode.data")
            addAction("sw.reader.barcode.result")
            addAction("sw.reader.barcode.data")
            addAction("sw.reader.scan")
            addAction("sw.reader.decode")
            addAction("sw.reader.barcode")
            addAction("sw.reader.result")
            addAction("sw.reader.data")
            addAction("sw.reader.scan_result")
            addAction("sw.reader.decode_result")
            addAction("sw.reader.barcode_result")
            addAction("sw.reader.scan_data")
            addAction("sw.reader.decode_data")
            addAction("sw.reader.barcode_data")
            addAction("com.cipherlab.reader.SCAN_RESULT")
            addAction("com.cipherlab.reader.DECODE_RESULT")
            addAction("com.cipherlab.reader.DATA")
            // Generic scanner result actions
            addAction("android.intent.action.VIEW")
            addAction("com.android.scanner.RESULT")
            addAction("com.android.scanner.DATA")
            // Generic barcode scanner intents (for infrared scanners)
            addAction("com.zebra.scanner.ACTION")
            addAction("com.motorolasolutions.scanner.ACTION")
            // Reader_Service specific (for RS36/CipherLab scanner service)
            addAction("sw.reader.ACTION")
            addAction("sw.reader.RESULT")
            addAction("sw.reader.DATA")
            addAction("com.cipherlab.reader.ACTION")
            addAction("com.cipherlab.reader.RESULT")
            addAction("com.cipherlab.reader.DATA")
            addAction("com.cipherlab.reader.SCAN")
            addAction("com.cipherlab.reader.SCAN_RESULT")
            addAction("com.cipherlab.reader.SCAN_DATA")
            // Very generic catch-all patterns
            addAction("com.android.intent.action.SCAN_RESULT")
            addAction("com.android.intent.action.SCAN_DATA")
            addAction("com.android.intent.action.BARCODE_RESULT")
            addAction("com.android.intent.action.BARCODE_DATA")
            addCategory(Intent.CATEGORY_DEFAULT)
        }

        context?.registerReceiver(hardwareScannerReceiver, filter)
        Log.d("MainActivity", "BroadcastReceiver registered for scanner intents")
        Log.d("MainActivity", "Registered ${filter.countActions()} scanner intent actions")
        
        // Register debug receiver to catch ALL broadcasts (for debugging scan results)
        // This will help us see what the device actually sends when scanning
        debugReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent == null) return
                val action = intent.action ?: return
                
                // Log ALL broadcasts that might be scanner-related (very broad filter)
                // Also log sw.reader.* and Reader_Service related broadcasts
                // Also log any broadcast that has string extras (potential scan data)
                val hasStringExtras = intent.extras?.let { extras ->
                    extras.keySet().any { key ->
                        val value = extras.get(key)
                        value is String && value.toString().length > 5 && 
                        !value.toString().contains("START", ignoreCase = true) &&
                        !value.toString().contains("STOP", ignoreCase = true) &&
                        !value.toString().contains("ENABLE", ignoreCase = true) &&
                        !value.toString().contains("DISABLE", ignoreCase = true)
                    }
                } ?: false
                
                val shouldLog = action.contains("decode", ignoreCase = true) ||
                    action.contains("scanner", ignoreCase = true) ||
                    action.contains("barcode", ignoreCase = true) ||
                    action.contains("cipherlab", ignoreCase = true) ||
                    action.contains("datawedge", ignoreCase = true) ||
                    action.contains("scan", ignoreCase = true) ||
                    action.contains("reader", ignoreCase = true) ||
                    action.contains("sw.reader", ignoreCase = true) ||
                    action.startsWith("sw.") ||
                    hasStringExtras ||
                    (intent.extras != null && intent.extras!!.size() > 0 && 
                     !action.startsWith("android.intent.action.") && 
                     !action.startsWith("com.android.") &&
                     action.contains(".", false))
                
                if (shouldLog) {
                    Log.d("RuggedScannerPlugin", "🔍 DEBUG: ===== BROADCAST RECEIVED =====")
                    Log.d("RuggedScannerPlugin", "🔍 DEBUG: Action: $action")
                    Log.d("RuggedScannerPlugin", "🔍 DEBUG: Package: ${intent.`package`}")
                    intent.extras?.let { extras ->
                        if (extras.size() > 0) {
                            Log.d("RuggedScannerPlugin", "🔍 DEBUG: Intent has ${extras.size()} extras:")
                            for (key in extras.keySet()) {
                                val value = extras.get(key)
                                val valueStr = value?.toString() ?: "null"
                                val keyStr = key.toString()
                                
                                // Check if this looks like scan data (not a command)
                                val looksLikeScanData = valueStr.length > 5 && 
                                    valueStr != "START_DECODE" && 
                                    valueStr != "STOP_DECODE" &&
                                    valueStr != "ENABLE" &&
                                    valueStr != "DISABLE" &&
                                    !keyStr.contains("ACTION", ignoreCase = true) &&
                                    !keyStr.contains("COMMAND", ignoreCase = true) &&
                                    !keyStr.contains("ENABLE", ignoreCase = true) &&
                                    !keyStr.contains("DISABLE", ignoreCase = true) &&
                                    !valueStr.contains("START", ignoreCase = true) &&
                                    !valueStr.contains("STOP", ignoreCase = true)
                                
                                if (looksLikeScanData) {
                                    Log.d("RuggedScannerPlugin", "🔍 DEBUG: ⚠️⚠️⚠️ POTENTIAL SCAN DATA ⚠️⚠️⚠️")
                                    Log.d("RuggedScannerPlugin", "🔍 DEBUG:   Extra[$keyStr] = $valueStr (${value?.javaClass?.simpleName})")
                                    Log.d("RuggedScannerPlugin", "🔍 DEBUG: ⚠️⚠️⚠️ END POTENTIAL SCAN DATA ⚠️⚠️⚠️")
                                } else {
                                    Log.d("RuggedScannerPlugin", "🔍 DEBUG:   Extra[$keyStr] = $valueStr (${value?.javaClass?.simpleName})")
                                }
                            }
                        } else {
                            Log.d("RuggedScannerPlugin", "🔍 DEBUG: Intent has no extras")
                        }
                    }
                    intent.data?.let { data ->
                        Log.d("RuggedScannerPlugin", "🔍 DEBUG: Intent data URI: $data")
                    }
                    Log.d("RuggedScannerPlugin", "🔍 DEBUG: ===== END BROADCAST =====")
                }
            }
        }
        
        // Try to register for ALL broadcasts using a very broad filter
        // We'll use a priority filter to catch broadcasts before other receivers
        val debugFilter = IntentFilter()
        debugFilter.priority = 1000  // High priority to catch broadcasts early
        debugFilter.addAction(Intent.ACTION_VIEW)
        debugFilter.addCategory(Intent.CATEGORY_DEFAULT)
        // Add all possible CipherLab actions
        val cipherLabActions = listOf(
            "com.cipherlab.scanner.*",
            "com.cipherlab.decode.*",
            "com.cipherlab.barcode.*",
            "com.cipherlab.*"
        )
        // Note: We can't use wildcards, so we'll register for specific common ones
        debugFilter.addAction("com.cipherlab.scanner.ACTION")
        debugFilter.addAction("com.cipherlab.decode.ACTION")
        debugFilter.addAction("com.cipherlab.barcode.ACTION")
        debugFilter.addAction("com.cipherlab.scanner.RESULT")
        debugFilter.addAction("com.cipherlab.decode.RESULT")
        debugFilter.addAction("com.cipherlab.barcode.RESULT")
        debugFilter.addAction("com.cipherlab.scanner.DATA")
        debugFilter.addAction("com.cipherlab.decode.DATA")
        debugFilter.addAction("com.cipherlab.barcode.DATA")
        // Add sw.reader.* actions for Reader_Service
        debugFilter.addAction("sw.reader.scan.result")
        debugFilter.addAction("sw.reader.scan.data")
        debugFilter.addAction("sw.reader.decode.result")
        debugFilter.addAction("sw.reader.decode.data")
        debugFilter.addAction("sw.reader.barcode.result")
        debugFilter.addAction("sw.reader.barcode.data")
        debugFilter.addAction("sw.reader.ACTION")
        debugFilter.addAction("sw.reader.RESULT")
        debugFilter.addAction("sw.reader.DATA")
        debugFilter.addAction("sw.reader.enable2d.complete")
        debugFilter.addAction("sw.reader.enable2d")
        debugFilter.addAction("sw.reader.complete")
        debugFilter.addAction("sw.reader.scan")
        debugFilter.addAction("sw.reader.decode")
        debugFilter.addAction("sw.reader.barcode")
        
        try {
            context?.registerReceiver(debugReceiver, debugFilter)
            Log.d("RuggedScannerPlugin", "Debug receiver registered (will log scanner-related broadcasts)")
        } catch (e: Exception) {
            Log.w("RuggedScannerPlugin", "Could not register debug receiver: ${e.message}")
        }
    }

}
