# How to Implement Persistent Android IDs After Reinstall

## Problem Statement

Currently, RustDesk Android generates a new Device ID on each fresh installation because:
1. Uninstalling the app deletes `/data/data/com.carriez.flutter_hbb/`
2. No backup/restore mechanism exists
3. ID is not bound to hardware

Users lose their device identity and connection history after reinstall.

---

## Solution Options

### ✅ Option 1: Android Backup Service (Recommended - Native Android Solution)

#### How it Works
- Android automatically backs up app data when user enables device backup
- On reinstall, Android automatically restores the backed-up config
- Works with Google Drive, Huawei Cloud, etc.
- **Easiest to implement**

#### Implementation Steps

**Step 1: Update AndroidManifest.xml**

```xml
<!-- File: flutter/android/app/src/main/AndroidManifest.xml -->
<manifest ...>
    <uses-permission android:name="android.permission.ACCESS_BACKUP_AGENT" />
    
    <application
        ...
        android:allowBackup="true"
        android:backupAgent=".RustDeskBackupAgent"
        android:backupInAgent="true">
        
        <!-- If targeting API 31+, specify what to backup -->
        <meta-data
            android:name="com.google.android.gms.backup.api.RestoreAnyVersion"
            android:value="true" />
    </application>
</manifest>
```

**Step 2: Create BackupAgent Class**

```kotlin
// File: flutter/android/app/src/main/kotlin/com/carriez/flutter_hbb/RustDeskBackupAgent.kt

package com.carriez.flutter_hbb

import android.app.backup.BackupAgentHelper
import android.app.backup.SharedPreferencesBackupHelper

class RustDeskBackupAgent : BackupAgentHelper() {
    override fun onCreate() {
        super.onCreate()
        
        // Backup SharedPreferences containing ID and config
        addHelper(
            PREFS_BACKUP_KEY,
            SharedPreferencesBackupHelper(this, KEY_SHARED_PREFERENCES)
        )
    }
    
    companion object {
        const val PREFS_BACKUP_KEY = "rustdesk_prefs"
    }
}
```

**Step 3: Get Backup API Key (for Google Play)**

If publishing to Google Play Store:
```bash
# Generate release key
keytool -exportcert -alias my_key_alias -keystore my.keystore | \
    openssl sha1 -binary | openssl base64

# Then add to Google Play Console > App Signing > Backup Service
# Add the backup agent key
```

**Verification:**
```bash
# Test backup on emulator
adb shell bmgr backupnow com.carriez.flutter_hbb

# Check backup agent is recognized
adb shell dumpsys backup com.carriez.flutter_hbb
```

#### Pros & Cons

✅ Pros:
- Native Android mechanism
- User's choice to enable/disable
- Works across versions
- Minimal code changes
- Automatic on many devices

❌ Cons:
- Depends on user enabling backups
- Cloud provider specific
- No control over backup timing
- Limited to 25MB per app

---

### ✅ Option 2: Cloud Sync Implementation (Best User Experience)

#### How it Works
- Explicitly backup ID to RustDesk server when ID is generated
- Restore ID from server on first launch if available
- Requires user authentication
- **More control, better UX**

#### Implementation Steps

**Step 1: Add ID Backup/Restore Methods to Rust**

```rust
// File: src/flutter_ffi.rs or new module src/id_sync.rs

use serde_json::json;
use tokio::time::timeout;
use std::time::Duration;

pub async fn backup_device_id_to_cloud(
    device_id: &str, 
    user_token: &str,
    server_url: &str
) -> ResultType<bool> {
    let url = format!("{}/api/device/backup", server_url);
    
    let client = reqwest::Client::new();
    let response = client
        .post(&url)
        .header("Authorization", format!("Bearer {}", user_token))
        .json(&json!({
            "device_id": device_id,
            "platform": "android",
            "app_version": env!("CARGO_PKG_VERSION"),
        }))
        .timeout(Duration::from_secs(10))
        .send()
        .await?;
    
    Ok(response.status().is_success())
}

pub async fn restore_device_id_from_cloud(
    user_token: &str,
    server_url: &str
) -> ResultType<Option<String>> {
    let url = format!("{}/api/device/restore", server_url);
    
    let client = reqwest::Client::new();
    let response = client
        .get(&url)
        .header("Authorization", format!("Bearer {}", user_token))
        .timeout(Duration::from_secs(10))
        .send()
        .await?;
    
    if response.status().is_success() {
        let data: serde_json::Value = response.json().await?;
        Ok(data.get("device_id").and_then(|v| v.as_str()).map(|s| s.to_string()))
    } else {
        Ok(None)
    }
}
```

**Step 2: Add Flutter/Dart Layer**

```dart
// File: flutter/lib/models/server_model.dart

import 'package:flutter_ffi/src/id_sync.dart';  // Pseudo import

class ServerModel {
  Future<void> initializeFirstLaunch() async {
    if (!isMobile || !isAndroid) {
      return;
    }

    try {
      // Check if first launch completed
      final firstLaunchFlag = await bind.mainGetLocalOption(
          key: 'first-launch-completed');

      if (firstLaunchFlag == 'true') {
        debugPrint("First launch already completed");
        return;
      }

      debugPrint("Initializing first launch setup...");

      // NEW: Restore device ID from cloud if available
      await _restoreDeviceIdIfNeeded();

      // ... rest of initialization ...
    } catch (e) {
      debugPrint("Error during first launch: $e");
    }
  }

  Future<void> _restoreDeviceIdIfNeeded() async {
    try {
      // Check if user is logged in
      final userToken = await _getUserAuthToken();
      if (userToken == null) {
        debugPrint("No user token, skipping ID restore");
        return;
      }

      // Try to restore ID from cloud
      final restoredId = await bind.restoreDeviceIdFromCloud(
        userToken: userToken,
        serverUrl: _getServerUrl(),
      );

      if (restoredId != null && restoredId.isNotEmpty) {
        await bind.mainSetId(id: restoredId);
        debugPrint("Device ID restored from cloud: $restoredId");
      }
    } catch (e) {
      debugPrint("Error restoring device ID: $e");
      // Silently fail - let new ID be generated
    }
  }

  Future<void> _backupDeviceIdOnLogin(String deviceId) async {
    try {
      final userToken = await _getUserAuthToken();
      if (userToken == null) return;

      await bind.backupDeviceIdToCloud(
        deviceId: deviceId,
        userToken: userToken,
        serverUrl: _getServerUrl(),
      );
      debugPrint("Device ID backed up to cloud");
    } catch (e) {
      debugPrint("Error backing up device ID: $e");
      // Non-critical failure
    }
  }

  // Helper methods
  Future<String?> _getUserAuthToken() async {
    // Get from local storage or current session
    return null; // Implementation specific
  }

  String _getServerUrl() {
    return "https://api.rustdesk.com"; // Or from config
  }
}
```

**Step 3: Update FFI Bridge**

```rust
// File: src/flutter_ffi.rs

#[flutter_rust_bridge::frb(sync)]
pub fn main_set_id(id: String) {
    Config::set_id(&id);
}

pub async fn backup_device_id_to_cloud(
    device_id: String,
    user_token: String,
    server_url: String,
) -> SyncReturn<bool> {
    let result = crate::id_sync::backup_device_id_to_cloud(&device_id, &user_token, &server_url)
        .await
        .unwrap_or(false);
    SyncReturn(result)
}

pub async fn restore_device_id_from_cloud(
    user_token: String,
    server_url: String,
) -> SyncReturn<Option<String>> {
    let result = crate::id_sync::restore_device_id_from_cloud(&user_token, &server_url)
        .await
        .unwrap_or(None);
    SyncReturn(result)
}
```

#### Pros & Cons

✅ Pros:
- Full control over backup timing
- Works offline (partial)
- User sees what's being backed up
- Can backup other data too (settings, etc.)
- Independent of Android backup

❌ Cons:
- Requires server infrastructure
- Requires user authentication
- Network dependency
- Privacy/security considerations

---

### ✅ Option 3: Hardware-Based ID Derivation (Most Reliable)

#### How it Works
- Instead of random UUID, derive ID from hardware identifiers
- Same ID every time on same device
- Survives reinstalls, only changes on factory reset
- **Most reliable, but less flexible**

#### Implementation Steps

**Step 1: Get Android Hardware ID**

```kotlin
// File: flutter/android/app/src/main/kotlin/com/carriez/flutter_hbb/HardwareUtil.kt

package com.carriez.flutter_hbb

import android.content.Context
import android.provider.Settings
import android.os.Build
import java.security.MessageDigest
import java.util.*

object HardwareUtil {
    
    /**
     * Get a reliable hardware-based device identifier
     * Survives app reinstalls, changes only on factory reset
     */
    fun getHardwareDeviceId(context: Context): String {
        return try {
            val ids = listOf(
                // Primary source: Android's persistent ANDROID_ID
                Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ANDROID_ID
                ) ?: "",
                
                // Fallback: Serial number (if available)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Build.getSerial() ?: ""
                } else {
                    @Suppress("DEPRECATION")
                    Build.SERIAL ?: ""
                },
                
                // Device model for differentiation
                Build.DEVICE,
                Build.MANUFACTURER
            )
            
            // Combine multiple sources
            val combined = ids.filter { it.isNotEmpty() }.joinToString("|")
            
            // Hash to get consistent length
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(combined.toByteArray())
            
            // Convert to hex and take first 32 chars
            hash.joinToString("") { "%02x".format(it) }.substring(0, 32)
        } catch (e: Exception) {
            // Fallback: use UUID if hardware ID fails
            UUID.randomUUID().toString().replace("-", "").substring(0, 32)
        }
    }
}
```

**Step 2: Integrate with Rust Config**

```rust
// File: src/flutter_ffi.rs

#[cfg(target_os = "android")]
pub fn main_init_hardware_device_id() {
    // Called during app initialization
    // Retrieves hardware ID from Android
    if let Some(hw_id) = get_android_hardware_id() {
        let config_id = derive_id_from_hardware(&hw_id);
        Config::set_id(&config_id);
    }
}

#[cfg(target_os = "android")]
fn get_android_hardware_id() -> Option<String> {
    // FFI call to Kotlin implementation
    // Returns hardware-based ID
    None // Placeholder
}

fn derive_id_from_hardware(hw_id: &str) -> String {
    use sha2::{Sha256, Digest};
    
    let mut hasher = Sha256::new();
    hasher.update(hw_id.as_bytes());
    let result = hasher.finalize();
    
    // Convert to numeric string for compatibility
    format!("{:x}", result)
        .chars()
        .take(16)
        .map(|c| match c {
            'a'..='f' => ((c as u8 - b'a' + 10) as u32).to_string(),
            '0'..='9' => c.to_string(),
            _ => "0".to_string(),
        })
        .collect::<String>()
        .parse::<u64>()
        .map(|n| n.to_string())
        .unwrap_or_default()
}
```

**Step 3: Call During App Startup**

```dart
// File: flutter/lib/main.dart

void main() async {
  // On Android, initialize hardware-based ID first
  if (isAndroid) {
    await bind.mainInitHardwareDeviceId();
  }
  
  // Rest of app initialization
  runApp(const MyApp());
}
```

#### Pros & Cons

✅ Pros:
- Survives app reinstalls
- Survives app updates
- Only changes on factory reset
- No server required
- No cloud privacy concerns

❌ Cons:
- Cannot change ID if user wants
- More complex implementation
- Requires Android permissions
- Different per device (not across devices)

---

## Implementation Comparison

| Feature | Backup Service | Cloud Sync | Hardware-Based |
|---------|---|---|---|
| Survives reinstall | ✅ Yes | ✅ Yes | ✅ Yes |
| Implementation complexity | ⭐ Easy | ⭐⭐⭐ Hard | ⭐⭐ Medium |
| Server required | ❌ No | ✅ Yes | ❌ No |
| User choice | ⚙️ Automatic | ⚙️ Automatic | ⚙️ Automatic |
| Works offline | ❌ No | ❌ No* | ✅ Yes |
| Can change ID | ✅ Yes | ✅ Yes | ❌ No |
| Best for | Casual users | Power users | Enterprise |

*Cloud Sync can work offline if previously synced.

---

## Testing Procedures

### Test Case 1: Backup Service

```bash
#!/bin/bash
set -e

PACKAGE="com.carriez.flutter_hbb"

# Enable backup on device
adb shell bmgr enable true
echo "Backup enabled"

# Get ID before
BEFORE=$(adb shell grep '"uid"' /data/data/$PACKAGE/shared_prefs/KEY_SHARED_PREFERENCES.xml | head -1)
echo "ID Before: $BEFORE"

# Trigger backup
adb shell bmgr backupnow $PACKAGE
sleep 5
echo "Backup triggered"

# Uninstall
adb uninstall $PACKAGE
echo "App uninstalled"

# Reinstall
adb install rustdesk-1.4.5-aarch64-unsigned.apk
sleep 5
echo "App reinstalled"

# Trigger restore
adb shell am start -n $PACKAGE/$PACKAGE.MainActivity
adb shell bmgr restore $PACKAGE
sleep 5
echo "Restore triggered"

# Get ID after
AFTER=$(adb shell grep '"uid"' /data/data/$PACKAGE/shared_prefs/KEY_SHARED_PREFERENCES.xml | head -1)
echo "ID After: $AFTER"

# Verify
if [ "$BEFORE" = "$AFTER" ]; then
    echo "✅ TEST PASSED: ID persisted!"
else
    echo "❌ TEST FAILED: ID changed!"
fi
```

### Test Case 2: Hardware-Based ID

```bash
#!/bin/bash
set -e

PACKAGE="com.carriez.flutter_hbb"

# Get ID before
adb logcat -c
adb install rustdesk-1.4.5-aarch64-unsigned.apk
adb shell am start -n $PACKAGE/$PACKAGE.MainActivity
sleep 3
BEFORE=$(adb logcat | grep "Device ID" | head -1)
echo "ID Before: $BEFORE"

# Uninstall and reinstall
adb uninstall $PACKAGE
sleep 2
adb install rustdesk-1.4.5-aarch64-unsigned.apk
adb logcat -c
adb shell am start -n $PACKAGE/$PACKAGE.MainActivity
sleep 3
AFTER=$(adb logcat | grep "Device ID" | head -1)
echo "ID After: $AFTER"

# Verify
if [ "$BEFORE" = "$AFTER" ]; then
    echo "✅ TEST PASSED: Hardware ID persisted!"
else
    echo "❌ TEST FAILED: IDs differ!"
fi
```

---

## Recommendation

**For RustDesk:** Implement **Option 1 (Backup Service)** first because:
1. ✅ Easiest to implement (~50 lines of code)
2. ✅ Zero server infrastructure needed
3. ✅ Works with existing Android ecosystem
4. ✅ User has control (can disable backups if needed)
5. ✅ Can add cloud sync later as optional feature

Then optionally add **Option 2 (Cloud Sync)** for users who want explicit control.

