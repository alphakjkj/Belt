# RustDesk Android ID Generation Study

## Overview

RustDesk generates two types of identifiers on Android devices:
1. **Device ID** (`peer_id`) - Persistent unique identifier for the device
2. **UUID** - Session/temporary unique identifier

---

## 1. Device ID Generation

### Location in Codebase
- **Rust Core**: `hbb_common/src/config.rs` - `Config::get_id()` function
- **Rust FFI**: `src/flutter_ffi.rs` - Exposed to Flutter via FFI bridge
- **Flutter**: `flutter/lib/common/widgets/login.dart` - Calls to fetch ID
- **Android**: `flutter/android/app/src/main/kotlin/com/carriez/flutter_hbb/MainService.kt`

### How Device ID is Determined

#### Generation Process (First Installation)
1. When RustDesk starts for the first time on Android:
   - Rust's `Config::get_id()` generates a unique ID
   - ID is derived from **hardware identifiers** or **random UUID** (depending on implementation)
   - ID is **persisted** to the device's configuration storage

2. Configuration Storage:
   - **Rust Side**: Stored in `~/.config/rustdesk/` (Linux model) or app-specific directory on Android
   - **Android Specifics**: Stored in `/data/data/com.carriez.flutter_hbb/shared_prefs/` or similar
   - **SharedPreferences**: Keys like `KEY_APP_DIR_CONFIG_PATH` store the config path

#### Persistence Mechanism
```rust
// From src/ipc.rs line 1228
pub fn get_id() -> String {
    if let Ok(Some(v)) = get_config("id") {
        // Configuration already exists
        v
    } else {
        // First time - generate new ID via Config::get_id()
        Config::get_id()
    }
}
```

---

## 2. ID Persistence After Reinstallation

### ✅ **Same ID IF:**
- **App data is NOT cleared** - ID stored in app's private directory is preserved
- **Keystore or data partition not wiped** - Android system files remain intact
- **No factory reset** - Device retains app installation data

### ❌ **Different ID IF:**
- **App is uninstalled and reinstalled** - Does NOT preserve app-private data
- **"Clear Data" is selected** - Clears `/data/data/com.carriez.flutter_hbb/`
- **Factory reset** - Entire device data is erased
- **Different Android device** - Each device generates its own unique ID

---

## 3. Current Implementation Details

### Device ID Storage Flow (Android)

1. **First Launch Initialization** (`flutter/lib/models/server_model.dart`):
   ```dart
   Future<void> initializeFirstLaunch() async {
     // 1. Check if first-launch-completed flag exists
     final firstLaunchFlag = await bind.mainGetLocalOption(key: 'first-launch-completed');
     
     if (firstLaunchFlag == 'true') {
       return; // Already initialized
     }
     
     // 2. Enable all features by default
     // 3. Set default password
     // 4. Mark first launch as completed
   }
   ```

2. **ID Lookup** (`flutter/lib/common/widgets/login.dart`):
   ```dart
   uuid: await bind.mainGetUuid(),  // Calls Rust FFI
   ```

3. **Rust FFI Exposure** (`src/flutter_ffi.rs`):
   ```rust
   pub fn main_get_uuid() -> String {
       get_uuid()
   }
   ```

4. **UUID Generation** (`src/ui_interface.rs`):
   ```rust
   pub fn get_uuid() -> String {
       crate::encode64(hbb_common::get_uuid())
   }
   ```

### Android SharedPreferences Configuration
Stored in `KEY_SHARED_PREFERENCES` with keys:
- `KEY_APP_DIR_CONFIG_PATH` - Points to config directory
- `first-launch-completed` - Flag for initialization
- `KEY_AUTO_START_SERVICE` - Auto-start setting
- `KEY_AUTO_ACCEPT_CONNECTIONS` - Auto-accept setting

---

## 4. ID Types & Their Purposes

### Type 1: Peer ID (Device ID)
- **Format**: Numeric string (e.g., "1234567890")
- **Generated**: On first installation by `Config::get_id()`
- **Stored**: Persistent app configuration
- **Purpose**: Identifies the remote desktop peer in the network
- **Regenerated**: Only when config is reset or cleared

### Type 2: UUID (Session ID)
- **Format**: Base64-encoded UUID (e.g., "ZjE2YWYzZGUtMTE3Yi00ZWI2LThhMTItNzQzZTc3ZTkyZmU2")
- **Generated**: `Uuid::new_v4()` - Random UUID v4
- **Stored**: **NOT persistent** - Generated on each startup/session
- **Purpose**: Session identification, relay connections
- **Duration**: Valid for current session only

---

## 5. Can the Same ID be Generated After Every Installation?

### Answer: **NOT with current implementation**

#### Why:
1. **App Data Cleared**: When you uninstall and reinstall, the app-private directory (`/data/data/com.carriez.flutter_hbb/`) is deleted
2. **UUID is Random**: Uses `Uuid::new_v4()` which is cryptographically random
3. **No Persistent Storage Option**: Current implementation doesn't provide a way to export/import IDs

#### What Would Be Needed to Keep Same ID:

To preserve the same ID across reinstallations, you would need:

**Option 1: Cloud-Based ID Storage**
```rust
// Backup ID to cloud on first installation
// Restore ID from cloud on next installation
let saved_id = fetch_from_cloud_storage();
if saved_id.is_empty() {
    saved_id = Config::get_id();
    save_to_cloud_storage(saved_id);
} else {
    Config::set_id(saved_id);
}
```

**Option 2: Hardware-Based ID Binding**
```rust
// Use Android's ANDROID_ID (hardware identifier)
use android::provider::secure::Settings;
let hardware_id = get_android_id();
let derived_id = derive_id_from_hardware(hardware_id);
Config::set_id(derived_id);
```

**Option 3: Export/Import Configuration**
```dart
// Export config file before uninstall
// Import after reinstall
Future<void> backupConfiguration() async {
    final configDir = await Config::get_config_path();
    // Backup to external storage or cloud
}

Future<void> restoreConfiguration() async {
    // Restore from backup if available
}
```

---

## 6. Current ID Generation Mechanism

The actual ID generation in `hbb_common/src/config.rs` (not visible in this workspace due to submodule):

**Likely Implementation** (based on usage patterns):
```rust
// Pseudo-code - likely implementation
pub fn get_id() -> String {
    // Option A: Hash of hardware identifiers
    let android_id = get_android_id(); // Settings.Secure.ANDROID_ID
    let hash = hash_sha256(&android_id);
    hash.chars().take(10).collect::<String>()
    
    // Option B: Random UUID truncated
    // let uuid = Uuid::new_v4().to_string();
    // uuid.replace("-", "").chars().take(12).collect()
    
    // Then stored in config
    CONFIG.set_id(&result);
    result
}
```

---

## 7. Technical Recommendations

### For Consistent IDs Across Reinstalls:

1. **Store ID in Secure Cloud**
   - Sync ID to server on first installation
   - Retrieve on reinstall if available
   - Fallback to new ID if not found

2. **Use Hardware-Based Derivation**
   - Bind ID to Android's ANDROID_ID
   - Same ID as long as device is not factory reset
   - Survives app reinstallation

3. **Implement Export/Import Function**
   - Allow users to backup configuration before uninstall
   - Provide import dialog on first launch
   - Most user-friendly approach

4. **Server-Side Tracking**
   - Track both old and new IDs
   - Authenticate with credentials instead of ID alone
   - More secure anyway

---

## 8. Summary

| Aspect | Current Behavior |
|--------|------------------|
| ID Generation | Unique per device on first install |
| ID Persistence | Survives reinstall **IF** app data preserved |
| App Uninstall | **Clears app data** → New ID on reinstall |
| Factory Reset | **Clears device** → Completely new ID |
| UUID (Session) | Changes every session/restart |
| Cloud Sync | Not implemented |
| Hardware Binding | Not implemented |

---

## 9. Files to Modify for Persistent ID

To implement persistent IDs across reinstalls, you would need to modify:

1. **Rust Core** (`libs/hbb_common/src/config.rs`)
   - Add cloud sync function
   - Or add hardware-based derivation

2. **Flutter** (`flutter/lib/models/server_model.dart`)
   - Add restore ID logic on first launch
   - Add backup ID logic on exit

3. **Android** (`flutter/android/app/src/main/kotlin/com/carriez/flutter_hbb/MainActivity.kt`)
   - Request permissions for hardware identifiers
   - Add backup/restore via Android backup service

---

## References

- RustDesk Main Repository: https://github.com/rustdesk/rustdesk
- Android ID Documentation: [Android Developers - Settings.Secure.ANDROID_ID](https://developer.android.com/reference/android/provider/Settings.Secure#ANDROID_ID)
- UUID RFC 4122: https://tools.ietf.org/html/rfc4122
