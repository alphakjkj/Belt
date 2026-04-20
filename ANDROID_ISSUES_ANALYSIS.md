# Android Activity & Service Issues - Deep Analysis

## Current Problems

### Problem 1: Black Activity Floating Instead of Transparent
**Symptoms**: When app is clicked, a black activity appears instead of being transparent

**Root Causes**:
1. **FLAG_NOT_TOUCHABLE Set Too Early**: In `MainActivity.onCreate()`, `window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)` is set unconditionally. This makes the window opaque.
2. **Conflicting Theme Attributes**: The `Theme.Transparent` style has conflicting attributes:
   - `android:windowFullscreen="true"` - Makes activity fullscreen and visible
   - `android:windowIsFloating="true"` - Makes it appear as a floating window
   - `android:windowBackground="@android:color/transparent"` - Set to transparent, but window is still rendered
3. **Activity Still Visible**: With `android:noHistory="true"` and `android:excludeFromRecents="true"`, the activity is technically "hidden" from the recent apps list, but the window itself is still rendered and visible as a black overlay when the user clicks the app.

**Why It Happens**:
- When `excludeFromRecents=true` is combined with `noHistory=true`, Android still needs to show the activity UI temporarily
- The transparent theme makes the background transparent but Flutter's native platform channel might not be initialized yet
- No content is being drawn to the transparent surface, resulting in a black color being default-filled

---

### Problem 2: Touches Pass Through But Activity Doesn't Respond
**Symptoms**: User clicks app, black activity appears, touches pass through to something underneath

**Root Causes**:
1. **FLAG_NOT_TOUCHABLE**: Makes the window not respond to touch events at all
2. **Activity Has No Content**: The activity is set to transparent, so it shows nothing visible
3. **Touch Routing**: Touches go through the transparent activity to whatever is behind it

**Impact**: User can't interact with the app through the UI

---

### Problem 3: App Disappears from Recent Apps & Services Get Killed
**Symptoms**: 
- When user opens Recent Apps and the app isn't there (expected due to `excludeFromRecents="true"`)
- But when the activity is destroyed (due to memory pressure or system cleanup), the services are also killed
- The foreground service notification disappears
- Screen capture/input service stops working

**Root Causes**:
1. **Incorrect Service Binding Flags**: 
   - Using `Context.BIND_AUTO_CREATE` alone isn't enough
   - Should use `Context.BIND_NOT_FOREGROUND` to prevent the binding from keeping the service in foreground
   
2. **Service Not Truly Foreground**: 
   - `MainService` may not be properly started as a foreground service with a notification
   - If the activity is destroyed before the service properly initializes its foreground notification, the service might be treated as a background service
   
3. **Activity Destruction Kills Binding**:
   - When activity is destroyed (due to `noHistory="true"`), the service connection is lost
   - The unbind happens implicitly when activity is destroyed
   - If this is the only connection keeping the service alive, the service dies

4. **Task Affinity Issue**:
   - `android:taskAffinity=""` separates the activity from the app's default task
   - This can cause the activity to be killed independently from the service
   - When the activity is killed, its service binding is severed

---

## Proposed Solutions

### Solution 1: Hide Activity Properly Without Showing Black Screen
**Option A: Keep Activity Truly Hidden**
```kotlin
// In MainActivity
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    
    // Don't set FLAG_NOT_TOUCHABLE here - let the activity be invisible instead
    // Make activity invisible immediately
    window.setBackgroundDrawableResource(android.R.color.transparent)
    
    // Start the service
    startBackgroundService()
}

override fun onResume() {
    super.onResume()
    // Immediately hide the window if service is ready
    if (MainService.isReady) {
        try {
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            WindowCompat.setDecorFitsSystemWindows(window, false)
            // Make the window stay behind all other windows
            window.setType(WindowManager.LayoutParams.TYPE_BASE_APPLICATION)
        } catch (e: Exception) {
            Log.e(logTag, "Failed to hide window: ${e.message}")
        }
    }
}
```

**Option B: Use Hidden Activity Pattern**
```xml
<!-- In AndroidManifest.xml -->
<activity
    android:name=".MainActivity"
    android:theme="@android:style/Theme.NoDisplay"
    android:launchMode="singleTop"
    android:excludeFromRecents="true"
    android:noHistory="false" 
    android:taskAffinity=""
    android:stateNotNeeded="true">
    <!-- ... intent-filters ... -->
</activity>
```

### Solution 2: Keep Services Alive Independently of Activity
**Key Changes**:

1. **Make MainService a True Foreground Service**:
```kotlin
override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    super.onStartCommand(intent, flags, startId)
    
    // Create foreground notification IMMEDIATELY
    createForegroundNotification()
    startForeground(DEFAULT_NOTIFY_ID, createNotification())
    
    // Service is now independent of activity lifecycle
    return START_NOT_STICKY
}
```

2. **Don't Bind Service from Activity If It's Already Running**:
```kotlin
// In MainActivity.onCreate()
if (!MainService.isRunning()) {
    // Start service - don't bind
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        startForegroundService(Intent(this, MainService::class.java))
    } else {
        startService(Intent(this, MainService::class.java))
    }
}
// Don't bind unless we need to communicate with the service
```

3. **Don't Unbind on Activity Destroy**:
```kotlin
override fun onDestroy() {
    // DON'T unbind the service!
    // Just clear the reference
    if (isServiceBound) {
        try {
            unbindService(serviceConnection)
        } catch (e: Exception) {
            Log.w(logTag, "Failed to unbind service: ${e.message}")
        }
        isServiceBound = false
    }
    mainService = null
    super.onDestroy()
}
```

### Solution 3: Fix Task Affinity & Activity Lifecycle
**In AndroidManifest.xml**:
```xml
<activity
    android:name=".MainActivity"
    android:configChanges="orientation|keyboardHidden|keyboard|screenSize|smallestScreenSize|locale|layoutDirection|fontScale|screenLayout|density|uiMode|navigation"
    android:exported="true"
    android:hardwareAccelerated="true"
    android:launchMode="singleTop"
    android:excludeFromRecents="false"
    android:noHistory="false"
    android:stateNotNeeded="true"
    android:finishOnTaskLaunch="false"
    android:alwaysRetainTaskState="true"
    android:taskAffinity="com.carriez.flutter_hbb.main"
    android:theme="@android:style/Theme.NoDisplay">
    <!-- intent-filters -->
</activity>
```

### Solution 4: Lifecycle Management Strategy

**Recommended Flow**:
```
App Launch
    ↓
Activity.onCreate() → Start MainService as Foreground Service
    ↓
MainService.onStartCommand() → Create foreground notification
    ↓
Activity.onResume() → If service ready, hide/minimize activity
    ↓
User clicks Recent App → Activity resumes (service continues)
    ↓
Activity destroyed/swiped → Service continues independently
    ↓
Input/Media services stay alive because MainService is foreground
```

---

## Implementation Checklist

- [ ] **Remove FLAG_NOT_TOUCHABLE** from onCreate
- [ ] **Change theme** to `@android:style/Theme.NoDisplay` or create proper transparent theme
- [ ] **Make MainService always start as foreground** with notification
- [ ] **Don't bind if service already running**
- [ ] **Fix task affinity** - use app-specific affinity or remove empty string
- [ ] **Change noHistory to false** - let activity stay in history but just not in recents
- [ ] **Implement proper service lifecycle** - service should NOT depend on activity binding
- [ ] **Add mechanism to keep MainActivity hidden** after first launch
- [ ] **Test service persistence** when activity is destroyed
- [ ] **Verify floating window service** starts correctly when activity is hidden

---

## Additional Notes

1. **Foreground Service Requirement**: Services that need to run indefinitely must be foreground services with a persistent notification (Android 8+)

2. **Task Affinity**: Empty task affinity (`""`) is dangerous - it puts activity in system default task. Use app-specific affinity instead.

3. **noHistory + excludeFromRecents**: These together make the activity ghost-like - it exists but shouldn't be visible. This is causing the black screen issue.

4. **Service Independence**: The key insight is that **services must NOT depend on activity bindings for lifecycle**. Use explicit `startService()` + foreground notification instead.

5. **Alternative Approach**: Consider using Android 12+ `foregroundServiceType` attribute in manifest for proper service management.
