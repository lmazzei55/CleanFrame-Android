# CleanFrame for Android TV — first device trial

This fork keeps CleanFrame's UI and adds an offline Android TV shell. An optional, narrowly scoped accessibility service returns the remote Home button to CleanFrame. No system apps are disabled. Minimum Android API 26; initial target TCL Smart TV Pro on Android TV 14. This firmware ignores the HOME role for the Home button, so the service is the supported route there; it was verified on that TV. See [device status](../docs/android-device-status.md).

Favorites: Netflix, HBO Max, YouTube, Paramount+, Apple TV, Prime Video, Peacock, Disney+ and Tubi, in that order. Existing wallpapers, List/Dock, app arrangement and appearance settings are retained. Paramount+ and Peacock use installed app icons (initials before discovery); other favorites use existing licensed bundled marks. Missing apps produce an error and can be replaced through All apps. Install streaming apps and sign in through their official TV apps.

## Preview and checks

```sh
npm ci
npm run dev:public
# Open http://127.0.0.1:4795/home/?platform=android
npm run check
CLEANFRAME_PUBLIC_FONTS=1 npm run check:browser
npm run check:android
npm run check:public
```

The Android browser preview uses separate preferences and cannot launch TV apps. Its Samsung-only Art Mode control is omitted. Settings → Android TV contains TV Home, TV settings and Home button. TV Home opens the original TCL interface and keeps it there, even across Home presses, until CleanFrame is opened again. Home button opens Accessibility settings, where **CleanFrame Home button** turns the Home return on or off. The Tizen preview/packager is retained for upstream compatibility; never use the Tizen device scripts for an Android TV.

## Build

GitHub Actions → Android produces a signed **test** APK and SHA256 file. The Android workflow uses JDK 17, SDK platform 34 and build tools 34.0.0. To build locally with those installed:

```sh
export ANDROID_SDK_ROOT='/path/to/android-sdk'
python3 scripts/build-android.py
```

Output: `dist/CleanFrame-Android-test.apk`; app ID: `com.mazzei.cleanframe`. The first local build creates `.local/android/debug.keystore`. Keep this ignored key for in-place local updates. CI generates an ephemeral test certificate: different runs will generally **not** update each other with `adb install -r`. Preserve the installed APK/certificate or arrange a stable private signing key before future updates. Do not automatically uninstall to work around a signature error; uninstall clears this app's preferences. No release signing or Play Store distribution is configured.

## Device installation

For device updates, re-sign each downloaded CI APK with the same persistent local key before installing:

```sh
python3 scripts/sign-android.py --jdk '/path/to/jdk/home' --build-tools '/path/to/build-tools/34.0.0' --input '/path/to/CleanFrame-Android-test.apk'
```

This produces `dist/CleanFrame-Android-local.apk`. The first invocation creates a private installation key and random password under ignored `.local/android/signing/`; subsequent invocations reuse it. Back up that folder securely and never upload it. Re-signing keeps the CI artifact intact. A TV already using an ephemeral CI certificate needs a one-time app replacement before adopting this identity; confirm the preference reset first. Future locally signed updates use `adb install -r` and retain preferences.

Connect the Mac and TV to the same trusted LAN. Enable the debugging option exposed by the TV and accept its authorization prompt. For Wireless debugging, use its displayed pairing and connection ports. Do not assume port 5555. Select a single verified device explicitly for every command:

```sh
adb devices -l
adb -s DEVICE_SERIAL shell getprop ro.product.model
adb -s DEVICE_SERIAL shell getprop ro.build.version.release
adb -s DEVICE_SERIAL shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
adb -s DEVICE_SERIAL install -r dist/CleanFrame-Android-test.apk
adb -s DEVICE_SERIAL shell am start -n com.mazzei.cleanframe/.MainActivity
```

Record the model, firmware, package list and Home handler privately before installation. Confirm the target and announce installation/launch. Save addresses, serials and authorization details only under ignored `.local/`, never in issues, PRs, screenshots or committed documents.

## Home button on TCL Google TV

TCL's Android 14 firmware accepts `cmd package set-home-activity` and reports CleanFrame as HOME role holder, yet the remote Home button still resolves to Google TV. CleanFrame therefore ships `HomeService`: when Google TV's `HomeActivity` comes to the front, it relaunches CleanFrame with `CATEGORY_HOME`, so Android treats it as the home task. TCL's app guard blocks the service from binding unless the `AUTO_START` app-op is allowed (`APP_AUTO_START` on some firmware). Enable it in TV Settings → Accessibility, or with ADB after recording the current values privately:

```sh
adb -s DEVICE_SERIAL shell settings get secure enabled_accessibility_services
adb -s DEVICE_SERIAL shell appops set com.mazzei.cleanframe AUTO_START allow
adb -s DEVICE_SERIAL shell settings put secure enabled_accessibility_services com.mazzei.cleanframe/com.mazzei.cleanframe.HomeService
adb -s DEVICE_SERIAL shell settings put secure accessibility_enabled 1
```

If other services were already enabled, append CleanFrame with a `:` separator instead of replacing them. Confirm `dumpsys accessibility` lists it under Bound services. To recover, put the recorded value back (or turn the service off in Accessibility) and `appops set com.mazzei.cleanframe AUTO_START default`. Never disable Google TV or setup packages: that can leave the TV without a usable home and require a factory reset.

## Physical acceptance

- Launch each installed favorite; confirm missing apps report an honest error.
- Use actual D-pad, OK and Back in Dock, List, Settings and the editor; verify one focused control and no double launch when holding OK.
- With CleanFrame Home button on, Home from a streaming app must return to CleanFrame (a brief Google TV frame may show first). TV Home must open and keep the original interface. Back within a streaming app belongs to that app. Repeat after a reboot.
- Change wallpaper/order, exit and reopen, then restart the TV and reopen the app; confirm saved preferences.
- Confirm wallpaper timers pause outside CleanFrame and the TV's screensaver/sleep remain functional.
- Confirm volume, dedicated app buttons, HDMI/CEC and casting still work.
- Test offline launcher rendering. Streaming still needs the streaming apps' normal network access.

Turn debugging off when finished. Undo the trial by turning off CleanFrame Home button in Accessibility, then uninstalling only `com.mazzei.cleanframe` through Settings. Keep a private record of the original accessibility services and Home component before changing either.

## Bridge boundaries

WebView loads only bundled assets on a synthetic HTTPS origin, with no network permission, file/content access, remote frames, external navigation or arbitrary intent/shell bridge. The native boundary supports app discovery, launch of installed TV-launchable packages, TV settings, Accessibility settings and stock Home. The Home service has no capabilities beyond `TYPE_WINDOW_STATE_CHANGED` events from `com.google.android.apps.tv.launcherx`: no key filtering, window content or gestures. App icons are loaded locally from installed packages. Android pause/resume explicitly controls UI timers. Device callbacks acknowledge Android intent dispatch, not visible success inside another app.

Code and asset licences remain those of upstream; see LICENSE and THIRD_PARTY_NOTICES.md.
