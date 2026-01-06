# SDK Setup for Example App

## CipherLab SDK Setup

The example app includes the CipherLab SDK (`barcodebase.jar`) for testing purposes.

### Location
- SDK file: `example/android/app/libs/barcodebase.jar`
- Build config: `example/android/app/build.gradle.kts`

### For Your Own App

When using this package in your own app, you need to:

1. **Obtain the SDK**: Get `barcodebase.jar` from:
   - Your CipherLab device (extract from `ReaderService_CipherLab.apk`)
   - CipherLab developer resources
   - Your device manufacturer

2. **Add to your app**:
   ```bash
   # Create libs directory
   mkdir -p android/app/libs
   
   # Copy SDK
   cp barcodebase.jar android/app/libs/
   ```

3. **Update build.gradle.kts**:
   ```kotlin
   dependencies {
       implementation(files("libs/barcodebase.jar"))
   }
   ```

### Why is this needed?

The CipherLab scanner sends data as a `Serializable` object (`com.cipherlab.barcodebase.ReaderDataStruct`). Android cannot deserialize this object without the class definition, which is provided by `barcodebase.jar`.

The package uses reflection to access the SDK methods, so it compiles without the SDK, but **will not work** on CipherLab devices without it.

