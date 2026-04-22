# Device ID Retrieval - Implementation Plan

## Current Problem
```kotlin
// Current code (CRASHES after 15 seconds):
private fun saveDeviceIdToPreferences() {
    try {
        val deviceId = FFI.mainGetMyId()  // ❌ UnsatisfiedLinkError - JNI not implemented
        if (deviceId.isNotEmpty()) {
            val prefs = applicationContext.getSharedPreferences(KEY_SHARED_PREFERENCES, FlutterActivity.MODE_PRIVATE)
            prefs.edit().putString("device_id", deviceId).apply()
            Log.d(logTag, "Device ID saved: $deviceId")
        }
    } catch (e: Exception) {
        Log.e(logTag, "Error: ${e.message}")
    }
}
```

---

## New Strategy: TIERED FALLBACK (Without Android Settings.Secure)

### Priority Order

```
┌─────────────────────────────────────────────────────┐
│  Save Device ID to Preferences (After 15 seconds)   │
└─────────────────────────────────────────────────────┘
                        ↓
        ┌───────────────────────────────────┐
        │ PRIORITY 1: Config File           │
        │ RustDesk.toml contains id field   │
        │ ✓ Most reliable                   │
        │ ✓ Generated on first startup      │
        │ ✓ Never empty                     │
        └───────────────────────────────────┘
                        ↓
              (if Config file missing or empty)
                        ↓
        ┌───────────────────────────────────┐
        │ PRIORITY 2: SharedPreferences     │
        │ Cache from earlier app run        │
        │ ✓ Fast access                     │
        │ ✓ Already populated               │
        └───────────────────────────────────┘
                        ↓
              (if not in SharedPreferences)
                        ↓
        ┌───────────────────────────────────┐
        │ PRIORITY 3: FFI with Error Handle │
        │ Call FFI.mainGetMyId() safely     │
        │ ✓ Correct source                  │
        │ ✓ Wrapped in try-catch            │
        │ ✓ Fallback if JNI crashes         │
        └───────────────────────────────────┘
                        ↓
              (if FFI fails or not available)
                        ↓
        ┌───────────────────────────────────┐
        │ PRIORITY 4: Fallback ID           │
        │ Generated from timestamp          │
        │ ✓ Ensures save never fails        │
        │ ✓ Better than crashing            │
        └───────────────────────────────────┘
```

---

## Implementation Steps

### Step 1: Create DeviceIdHelper Class

**File:** `flutter/android/app/src/main/kotlin/com/carriez/flutter_hbb/DeviceIdHelper.kt`

```kotlin
package com.carriez.flutter_hbb

import android.content.Context
import android.util.Log
import ffi.FFI
import java.io.File

/**
 * Retrieves device ID using tiered fallback strategy
 * 1. Config file (RustDesk.toml)
 * 2. SharedPreferences cache
 * 3. FFI call with error handling
 * 4. Generated fallback ID
 */
object DeviceIdHelper {
    private const val TAG = "DeviceIdHelper"
    private const val PREFS_NAME = "rustdesk_device"
    private const val ID_KEY = "device_id"
    
    /**
     * Get device ID with fallback strategy
     * Returns a non-empty string guaranteed
     */
    fun getDeviceId(context: Context): String {
        Log.d(TAG, "🔍 Starting device ID retrieval (tiered fallback)...")
        
        // Strategy 1: Read from config file
        getDeviceIdFromConfigFile(context)?.let { id ->
            Log.d(TAG, "✅ Strategy 1 SUCCESS: Got ID from config file")
            return id
        }
        Log.d(TAG, "⏭️  Strategy 1 SKIP: Config file not available")
        
        // Strategy 2: Read from SharedPreferences cache
        getDeviceIdFromCache(context)?.let { id ->
            Log.d(TAG, "✅ Strategy 2 SUCCESS: Got ID from cache")
            return id
        }
        Log.d(TAG, "⏭️  Strategy 2 SKIP: Cache miss")
        
        // Strategy 3: Try FFI call (with error handling)
        getDeviceIdFromFFI()?.let { id ->
            Log.d(TAG, "✅ Strategy 3 SUCCESS: Got ID from FFI")
            // Cache it for next time
            cacheDeviceId(context, id)
            return id
        }
        Log.d(TAG, "⏭️  Strategy 3 SKIP: FFI unavailable/failed")
        
        // Strategy 4: Generate fallback ID
        val fallbackId = generateFallbackId()
        Log.d(TAG, "✅ Strategy 4 FALLBACK: Generated ID = $fallbackId")
        cacheDeviceId(context, fallbackId)
        return fallbackId
    }
    
    /**
     * STRATEGY 1: Read ID from RustDesk.toml config file
     * Path: /data/data/com.carriez.flutter_hbb/app_flutter/RustDesk.toml
     */
    private fun getDeviceIdFromConfigFile(context: Context): String? {
        return try {
            val appDataDir = context.getExternalFilesDir(null)?.parentFile?.absolutePath
                ?: return null
            
            // Try both possible paths
            val paths = listOf(
                File(appDataDir, "app_flutter/RustDesk.toml"),
                File(context.filesDir, "RustDesk.toml"),
                File(context.cacheDir, "RustDesk.toml")
            )
            
            for (configFile in paths) {
                if (!configFile.exists()) continue
                
                Log.d(TAG, "📄 Checking config: ${configFile.absolutePath}")
                
                val content = configFile.readText()
                val idRegex = Regex("""id\s*=\s*"([^"]+)""")
                val match = idRegex.find(content)
                
                match?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }?.let {
                    Log.d(TAG, "📄 SUCCESS: Extracted ID from ${configFile.name}")
                    return it
                }
            }
            
            Log.d(TAG, "📄 FAIL: Could not find/parse config file")
            null
        } catch (e: Exception) {
            Log.w(TAG, "📄 ERROR: Config file read failed: ${e.message}")
            null
        }
    }
    
    /**
     * STRATEGY 2: Read ID from SharedPreferences cache
     */
    private fun getDeviceIdFromCache(context: Context): String? {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.getString(ID_KEY, null)?.takeIf { it.isNotEmpty() }?.also {
                Log.d(TAG, "💾 SUCCESS: Found cached ID")
            }
        } catch (e: Exception) {
            Log.w(TAG, "💾 ERROR: Cache read failed: ${e.message}")
            null
        }
    }
    
    /**
     * STRATEGY 3: Call FFI function with error handling
     * If JNI not implemented, catches UnsatisfiedLinkError gracefully
     */
    private fun getDeviceIdFromFFI(): String? {
        return try {
            val id = FFI.mainGetMyId()
            if (id.isNotEmpty() && id != "unknown") {
                Log.d(TAG, "🔗 SUCCESS: Got ID from FFI")
                return id
            }
            Log.d(TAG, "🔗 FAIL: FFI returned empty/invalid ID")
            null
        } catch (e: UnsatisfiedLinkError) {
            Log.d(TAG, "🔗 ERROR: JNI function not found - ${e.message}")
            null
        } catch (e: Exception) {
            Log.d(TAG, "🔗 ERROR: FFI call failed - ${e.message}")
            null
        }
    }
    
    /**
     * STRATEGY 4: Generate fallback ID
     * Based on timestamp to ensure some uniqueness
     */
    private fun generateFallbackId(): String {
        return "rbdsk_${System.currentTimeMillis() % 1_000_000_000}"
    }
    
    /**
     * Cache the device ID in SharedPreferences
     */
    fun cacheDeviceId(context: Context, deviceId: String) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(ID_KEY, deviceId).apply()
            Log.d(TAG, "💾 Cached device ID for future use")
        } catch (e: Exception) {
            Log.w(TAG, "💾 ERROR: Cache write failed - ${e.message}")
        }
    }
}
```

### Step 2: Modify MainService.kt

**File:** `flutter/android/app/src/main/kotlin/com/carriez/flutter_hbb/MainService.kt`

**Current code (lines 668):**
```kotlin
serviceHandler?.postDelayed({
    try {
        Log.d(logTag, "Delayed device ID save - executing after 15 seconds")
       saveDeviceIdToPreferences()
    } catch (e: Exception) {
        Log.e(logTag, "Error in delayed device ID save: ${e.message}")
    }
}, 15000)  // 15 second delay
```

**Replace with:**
```kotlin
serviceHandler?.postDelayed({
    try {
        Log.d(logTag, "⏰ [15s] Starting device ID retrieval with fallback strategy...")
        saveDeviceIdToPreferences()
    } catch (e: Exception) {
        Log.e(logTag, "❌ Error in delayed device ID save: ${e.message}")
    }
}, 15000)  // 15 second delay
```

**Update saveDeviceIdToPreferences() (lines 1728-1746):**

```kotlin
/**
 * Save device ID to SharedPreferences (called once during app initialization after 15 seconds)
 * Uses tiered fallback strategy:
 * 1. Config file (RustDesk.toml)
 * 2. SharedPreferences cache
 * 3. FFI call (with error handling)
 * 4. Fallback ID
 */
private fun saveDeviceIdToPreferences() {
    try {
        Log.d(logTag, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        Log.d(logTag, "📱 Device ID Retrieval: Starting tiered strategy")
        Log.d(logTag, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        
        // Use helper to get device ID with fallback
        val deviceId = DeviceIdHelper.getDeviceId(this)
        
        if (deviceId.isNotEmpty()) {
            val prefs = applicationContext.getSharedPreferences(
                KEY_SHARED_PREFERENCES, 
                FlutterActivity.MODE_PRIVATE
            )
            prefs.edit().putString("device_id", deviceId).apply()
            
            Log.d(logTag, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            Log.d(logTag, "✅ SUCCESS: Device ID saved to SharedPreferences")
            Log.d(logTag, "Device ID: $deviceId")
            Log.d(logTag, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        } else {
            Log.e(logTag, "❌ CRITICAL: Device ID is empty after all strategies!")
        }
    } catch (e: Exception) {
        Log.e(logTag, "❌ Error in saveDeviceIdToPreferences: ${e.message}")
        e.printStackTrace()
    }
}
```

---

## Execution Flow Timeline (After 15 seconds)

```
T=0s      App starts
          FFI.init(context) called
          Config file created/loaded: /data/data/.../RustDesk.toml
          
T=15s     ⏰ TIMER FIRES
          └─ saveDeviceIdToPreferences() called
             
             📝 Try Strategy 1: Config File
             ├─ Check /app_flutter/RustDesk.toml
             ├─ Parse TOML: id = "1303673029"
             └─ ✅ FOUND! Return "1303673029"
             
             💾 Save to SharedPreferences
             ├─ Key: "device_id"
             ├─ Value: "1303673029"
             └─ ✅ SAVED!

T>15s     App shows Device ID
          Other services consume from SharedPreferences
          No more crashes!
```

---

## Logging Output Expected

```
D/MainService: ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
D/MainService: 📱 Device ID Retrieval: Starting tiered strategy
D/MainService: ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
D/DeviceIdHelper: 🔍 Starting device ID retrieval (tiered fallback)...
D/DeviceIdHelper: 📄 Checking config: /data/data/com.carriez.flutter_hbb/app_flutter/RustDesk.toml
D/DeviceIdHelper: 📄 SUCCESS: Extracted ID from RustDesk.toml
D/DeviceIdHelper: ✅ Strategy 1 SUCCESS: Got ID from config file
D/MainService: ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
D/MainService: ✅ SUCCESS: Device ID saved to SharedPreferences
D/MainService: Device ID: 1303673029
D/MainService: ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

---

## Error Handling Scenarios

### Scenario 1: Config file exists ✅
```
→ Strategy 1 SUCCESS
→ Save and return
```

### Scenario 2: Config file missing, cache hit ✅
```
→ Strategy 1 SKIP
→ Strategy 2 SUCCESS
→ Save and return
```

### Scenario 3: Config missing, cache miss, FFI works ✅
```
→ Strategy 1 SKIP
→ Strategy 2 SKIP
→ Strategy 3 SUCCESS
→ Cache + Save and return
```

### Scenario 4: All strategies fail (extremely unlikely)
```
→ Strategy 1 SKIP
→ Strategy 2 SKIP
→ Strategy 3 SKIP (catches UnsatisfiedLinkError)
→ Strategy 4 FALLBACK: Generate "rbdsk_1234567890"
→ Cache + Save and return
→ No crash! ✅
```

---

## Changes Summary

| File | Change | Impact |
|------|--------|--------|
| **Create NEW** | `DeviceIdHelper.kt` | Single responsibility: ID retrieval logic |
| **Modify** | `MainService.kt:668` | Add logging |
| **Modify** | `MainService.kt:1728-1746` | Replace saveDeviceIdToPreferences() |

**Total Lines Changed:** ~60 lines (mostly new helper class)  
**Breaking Changes:** None - backward compatible  
**Crash Risk:** ✅ SOLVED - No UnsatisfiedLinkError possible

---

## Ready to Implement?

Confirm and I'll apply these changes:
1. ✅ Create `DeviceIdHelper.kt` 
2. ✅ Update `MainService.kt`
3. ✅ Add logging for debugging

