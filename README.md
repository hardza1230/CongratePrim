# AutoTapper

An educational Android app that demonstrates how to build a **generic
auto-tapper / macro tool**: it taps configured screen points on a loop with a
"tap → wait → tap" pattern.

It's built around Android's **AccessibilityService**, which is the only
sanctioned mechanism an app has for tapping on top of other apps. The service
must be turned on by hand in *Settings → Accessibility* — nothing in the code
grants itself that power, which is exactly how a permission-honest automation
tool should behave.

> Use this on software you own or are authorized to automate (your own app,
> UI test flows, accessibility needs, kiosk demos). Don't use it to break the
> Terms of Service of games or other apps — automated/unattended play gets
> accounts banned and is unfair to other players.

## What it teaches

- **`AccessibilityService` + `dispatchGesture`** — injecting synthetic taps
  (`GestureDescription` / `StrokeDescription`).
- **`TYPE_ACCESSIBILITY_OVERLAY` windows** — drawing a floating control panel
  and a full-screen "capture" overlay *without* the SYSTEM_ALERT_WINDOW
  permission.
- **A small scheduling engine** — looping a list of steps with `Handler`
  callbacks, honouring a per-step delay and a loop limit.
- **Sharing config** between an Activity and a Service via SharedPreferences.

## Project layout

```
app/src/main/java/com/example/autotapper/
├── model/TapStep.kt          # one action: tap (x,y) then wait
├── data/ConfigStore.kt       # JSON-in-SharedPreferences persistence
├── service/AutoTapService.kt # the engine: taps + floating panel + capture
└── ui/MainActivity.kt        # edit the step list & loop count
```

## Build & run

You need **Android Studio** (Giraffe or newer) or the Android SDK + Gradle 8.7.

1. Open this folder in Android Studio and let it sync, **or** from the command
   line generate the wrapper once (`gradle wrapper`) then build:
   ```
   ./gradlew assembleDebug
   ```
   The APK lands in `app/build/outputs/apk/debug/`.
2. Install it on a device (`adb install -r app-debug.apk`) or run from the IDE.

## How to use it

1. Open the app → tap **Enable Accessibility Service** → switch on *AutoTapper*
   in the list. A floating panel appears.
2. Add tap points, either:
   - **Capture**: press *Capture* on the floating panel, then touch the exact
     spot on screen you want to record. Repeat for each point.
   - **Manual**: use *Add step manually* in the app and type X / Y / wait(ms).
     (Enable *Developer options → Pointer location* to read coordinates.)
3. Set **Loops** (`0` = run forever) and tweak the wait times.
4. Press **Start** on the floating panel. Press **Stop** any time.

## Notes & limits

- `minSdk 26` (Android 8.0) for `TYPE_ACCESSIBILITY_OVERLAY` and reliable
  gesture dispatch.
- Coordinates are absolute pixels, so a macro recorded on one screen size won't
  line up on a very different one.
- The service performs **taps only** (single-point gestures). Swipes/drags are a
  natural next exercise: add a second `moveTo`/`lineTo` to the `Path`.
