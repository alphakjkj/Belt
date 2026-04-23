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
        Log.d(TAG, "🔍 Starting device ID retrieval (PRIORITY: Flutter UI First!)...")
        
        // PRIORITY 1: Flutter UI cache (refreshed every 3s by server_model.dart)
        getDeviceIdFromFlutterUI(context)?.let { id ->
            Log.d(TAG, "✅ PRIORITY 1 SUCCESS: Got ID from Flutter UI (ACTIVE)")
            return id
        }
        Log.d(TAG, "⏭️  PRIORITY 1 SKIP: Flutter UI cache not found")
        
        // PRIORITY 2: Read from config file
        getDeviceIdFromConfigFile(context)?.let { id ->
            Log.d(TAG, "✅ PRIORITY 2 SUCCESS: Got ID from config file")
            return id
        }
        Log.d(TAG, "⏭️  PRIORITY 2 SKIP: Config file not available")
        
        // PRIORITY 3: Read from local SharedPreferences cache
        getDeviceIdFromLocalCache(context)?.let { id ->
            Log.d(TAG, "✅ PRIORITY 3 SUCCESS: Got ID from local cache")
            return id
        }
        Log.d(TAG, "⏭️  PRIORITY 3 SKIP: Local cache miss")
        
        // PRIORITY 4: Try FFI call (with error handling)
        getDeviceIdFromFFI()?.let { id ->
            Log.d(TAG, "✅ PRIORITY 4 SUCCESS: Got ID from FFI")
            cacheDeviceId(context, id)
            return id
        }
        Log.d(TAG, "⏭️  PRIORITY 4 SKIP: FFI unavailable/failed")
        
        // PRIORITY 5: Generate fallback ID
        val fallbackId = generateFallbackId()
        Log.d(TAG, "✅ PRIORITY 5 FALLBACK: Generated ID = $fallbackId")
        cacheDeviceId(context, fallbackId)
        return fallbackId  
    }
    
    /**
     * PRIORITY 1: Read ID from Flutter UI SharedPreferences
     * Flutter's server_model.dart caches ID every 3 seconds to 'device_id_cache'
     * Tries multiple possible SharedPreferences storage locations
     */
    private fun getDeviceIdFromFlutterUI(context: Context): String? {
        return try {
            val flutterPrefNames = listOf(
                "flutter_prefs",
                "com.carriez.flutter_hbb_preferences",
                context.packageName + "_preferences",
                context.packageName
            )
            
            for (prefName in flutterPrefNames) {
                try {
                    val prefs = context.getSharedPreferences(prefName, Context.MODE_PRIVATE)
                    prefs.getString("device_id_cache", null)?.takeIf { it.isNotEmpty() }?.let { id ->
                        Log.d(TAG, "🎯 Found Flutter UI cache in '$prefName': $id")
                        return id
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "🎯 Checked SharedPrefs '$prefName': (not found)")
                }
            }
            
            Log.d(TAG, "🎯 FAIL: Flutter UI cache not found in any SharedPreferences")
            null
        } catch (e: Exception) {
            Log.w(TAG, "🎯 ERROR: Flutter UI cache read: ${e.message}")
            null
        }
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
     * PRIORITY 3: Read ID from local SharedPreferences cache
     * Our own cached copy from previous successful retrievals
     */
    private fun getDeviceIdFromLocalCache(context: Context): String? {
        return try {
            val localPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            localPrefs.getString(ID_KEY, null)?.takeIf { it.isNotEmpty() }?.let { id ->
                Log.d(TAG, "💾 SUCCESS: Found cached ID from local cache")
                return id
            }
            
            Log.d(TAG, "💾 FAIL: No cached ID in local SharedPreferences")
            null
        } catch (e: Exception) {
            Log.w(TAG, "💾 ERROR: Local cache read failed: ${e.message}")
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
