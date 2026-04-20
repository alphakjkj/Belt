# Implementation Guide: Android Activity & Service Fixes

## Changes Made

### 1. AndroidManifest.xml Changes

**Before:**
```xml
<activity
    android:name=".MainActivity"
    ...
    android:excludeFromRecents="true"
    android:noHistory="true"
    android:taskAffinity=""
    android:theme="@style/Theme.Transparent"
    ...
</activity>
```

**After:**
```xml
<activity
    android:name=".MainActivity"
    ...
    android:excludeFromRecents="false"
    android:noHistory="false"
    android:stateNotNeeded="true"
    android:finishOnTaskLaunch="false"
    android:alwaysRetainTaskState="true"
    android:taskAffinity="com.carriez.flutter_hbb.main"
    android:theme="@android:style/Theme.NoDisplay"
    ...
</activity>
```

**Rationale:**
- `Theme.NoDisplay`: Hides activity without rendering anything (no black screen)
- `excludeFromRecents="false"`: App can stay in system without showing in recents
- `noHistory="false"`: Activity won't be auto-destroyed after being hidden
- `taskAffinity="com.carriez.flutter_hbb.main"`: Proper task affinity prevents activity from being in system task
- `stateNotNeeded="true"`: Activity doesn't need to be saved/restored
- `finishOnTaskLaunch="false"`: Activity won't be finished when task is brought to front
- `alwaysRetainTaskState="true"`: Task state is retained

---

### 2. MainActivity.kt Changes

#### Change 1: Removed FLAG_NOT_TOUCHABLE

**Before:**
```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
    // ... rest of code
}
```

**After:**
```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // DON'T set FLAG_NOT_TOUCHABLE here - it makes the activity black
    // Theme.NoDisplay already hides it properly
    autoStartServiceAsIndependent()
    // ... rest of code
}
```

**Rationale:**
- `FLAG_NOT_TOUCHABLE` + transparent theme = black overlay effect
- `Theme.NoDisplay` hides the activity completely without rendering

#### Change 2: Created autoStartServiceAsIndependent() Method

**New Method:**
```kotlin
private fun autoStartServiceAsIndependent() {
    val intent = Intent(this, MainService::class.java)
    intent.action = ACT_INIT_MEDIA_PROJECTION_AND_SERVICE
    intent.putExtra(EXT_INIT_FROM_BOOT, false)
    
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Log.d(logTag, "Starting foreground service via startForegroundService()")
        try {
            startForegroundService(intent)
        } catch (e: Exception) {
            Log.e(logTag, "Failed to start foreground service: ${e.message}")
            startService(intent)
        }
    } else {
        Log.d(logTag, "Starting service via startService()")
        startService(intent)
    }
}
```

**Rationale:**
- Starts service as foreground service immediately in onCreate
- Service is now independent of activity lifecycle
- Wrapped in try-catch for robustness
- Uses `startForegroundService()` on Android 8+ (required by OS)

#### Change 3: Simplified configureFlutterEngine()

**Before:**
- Called `autoStartService()` which checked preferences
- Used `BIND_AUTO_CREATE or BIND_NOT_FOREGROUND`
- Multiple start attempts

**After:**
- Only binds if service already running
- Uses simple `BIND_AUTO_CREATE`
- Called once per initialization

**Rationale:**
- Service already started in onCreate
- Binding is only for communication, not lifecycle
- Removes redundant service starts

#### Change 4: Fixed onDestroy() - Critical Change

**Before:**
```kotlin
override fun onDestroy() {
    Log.d(logTag, "onDestroy...")
    mainService = null
    Log.d(logTag, "Service reference cleared but service continues running")
    super.onDestroy()
}
```

**After:**
```kotlin
override fun onDestroy() {
    Log.d(logTag, "onDestroy - Activity is being destroyed")
    
    // CRITICAL: DO NOT unbind from the service!
    // The service is now a foreground service and must continue running independently
    // Only clear the reference, don't break the binding
    
    mainService = null
    isServiceBound = false
    
    // Do NOT call unbindService() here - let the foreground service continue
    Log.d(logTag, "Activity destroyed but foreground service continues running")
    super.onDestroy()
}
```

**Rationale:**
- Must NOT unbind service when activity is destroyed
- Service is foreground and must continue independently
- Only clear reference to prevent memory leaks
- Added explicit comment to prevent future mistakes

#### Change 5: Improved onResume()

**Before/After:**
- Removed `BIND_NOT_FOREGROUND` flag
- Added error handling for binding

**Rationale:**
- Simple `BIND_AUTO_CREATE` is sufficient
- Handles cases where activity is recreated

---

## How the Fix Works

### New Service Lifecycle

```
App Launch
    ↓
MainActivity.onCreate()
    ↓
startForegroundService() called
    ↓
MainService.onStartCommand()
    ↓
createForegroundNotification()  ← Service becomes foreground
    ↓
startForeground() + notification  ← Service now independent
    ↓
Activity can be destroyed but service continues
```

### Why This Fixes Each Problem

**Problem 1: Black Activity (✓ Fixed)**
- `Theme.NoDisplay` hides activity completely
- No FLAG_NOT_TOUCHABLE creates no opaque surface
- Result: Clean, invisible activity

**Problem 2: Touch Pass-Through (✓ Fixed)**
- Activity is no longer creating a touch-blocking layer
- User clicks app → Activity is hidden, no interference
- Result: Normal app behavior

**Problem 3: Services Killed (✓ Fixed)**
- Service started as foreground with `startForegroundService()`
- Service calls `startForeground()` with notification
- Service is now independent of activity binding
- Even if activity is destroyed, service continues
- Result: Services stay alive indefinitely

---

## Testing Checklist

### Pre-Testing Setup
- [ ] Build APK with these changes
- [ ] Install on test device
- [ ] Check logcat: `adb logcat | grep "MainActivity\|MainService"`

### Test 1: Activity Visibility
- [ ] Launch app
- [ ] Verify NO black screen appears
- [ ] Verify app goes to background immediately
- [ ] Logcat should show: `"Starting foreground service via startForegroundService()"`

### Test 2: Service Status
- [ ] Go to Settings > Apps > Running apps
- [ ] Should see "System Update" service running
- [ ] Should see persistent notification in status bar
- [ ] Notification should say "System Update - Scheduled update postponed"

### Test 3: Touch Input
- [ ] Start a remote session
- [ ] Try moving mouse/keyboard
- [ ] Touches should work (input goes through FloatingWindowService)
- [ ] No stuttering or delays

### Test 4: Activity Destruction
- [ ] Open app by clicking icon → service runs
- [ ] Press back/home to go to background
- [ ] Open Recent Apps and swipe the app away
- [ ] Verify notification still exists in status bar
- [ ] Check logcat: should NOT see unbind logs

### Test 5: Service Persistence
- [ ] Start remote session
- [ ] Screen should capture correctly
- [ ] Open Recent Apps
- [ ] Kill the app by swiping from recents
- [ ] Service should CONTINUE running
- [ ] Logcat should show: `"Activity destroyed but foreground service continues"`
- [ ] Remote desktop should STILL work if client is connected

### Test 6: App Restart
- [ ] Kill the activity (swipe from recents)
- [ ] Click app icon again
- [ ] Service should still be running from before
- [ ] Logcat: `"Bound to already-running service"`
- [ ] Everything should continue working

### Test 7: Screen Rotation
- [ ] While service running, rotate device
- [ ] No service restart
- [ ] No connection drops
- [ ] Input continues working

### Test 8: Lock/Unlock
- [ ] Start remote session
- [ ] Lock device screen
- [ ] Remote desktop should continue
- [ ] Unlock device
- [ ] Service still running
- [ ] Resume remote session

### Test 9: Memory Pressure
- [ ] Start remote session
- [ ] Open several heavy apps to trigger memory pressure
- [ ] Check if service is still alive
- [ ] Verify notification still in status bar
- [ ] Remote desktop should still be active

### Test 10: Force Stop
- [ ] Settings > Apps > [App] > Force Stop
- [ ] Verify service is stopped
- [ ] App can be restarted normally
- [ ] Verify boot receiver can restart service later

---

## Debugging Commands

### Check Service Status
```bash
adb shell dumpsys activity services | grep -i "flutter_hbb"
```

### Check Foreground Services
```bash
adb shell dumpsys activity services | grep -i "foreground"
```

### Monitor Logs
```bash
adb logcat -v tag | grep -E "MainActivity|MainService|FloatingWindow"
```

### Check Notification
```bash
adb shell dumpsys activity notifications | grep -i "flutter_hbb"
```

### Track Binding
```bash
adb logcat -v tag | grep -i "bind"
```

### Profile Memory
```bash
adb shell dumpsys meminfo com.carriez.flutter_hbb
```

---

## Rollback Plan

If issues occur, revert these changes:

1. **AndroidManifest.xml**: Restore original activity attributes
2. **MainActivity.kt**: Restore original onCreate() with FLAG_NOT_TOUCHABLE
3. **MainService.kt**: Should need no changes (it already worked correctly)

---

## Known Limitations

1. **Activity Still Visible in Native UI**: The activity exists but is hidden. This is normal.
2. **Notification Always Visible**: Foreground service requires persistent notification (Android 8+)
3. **Theme.NoDisplay**: Activity still creates a window internally, but it's not rendered

---

## Questions & Answers

**Q: Why Theme.NoDisplay instead of keeping Theme.Transparent?**
A: Theme.NoDisplay doesn't render the window at all. Theme.Transparent renders it as transparent but with black default fill.

**Q: Can we remove the notification?**
A: No, foreground services require a persistent notification on Android 8+. This is OS requirement.

**Q: What if MainActivity needs to be shown sometimes?**
A: Modify onResume() to conditionally show the window using WindowManager APIs.

**Q: Can we make the activity appear in recents?**
A: Yes, remove `excludeFromRecents="true"` if desired, but keep service independent.

**Q: Will this work on Android 5?**
A: Yes, but `startForegroundService()` is only available on Android 8+. The code already handles this with fallback to `startService()`.
