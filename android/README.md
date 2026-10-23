# CleanFrame for Android TV — first device trial

This fork keeps CleanFrame's UI and adds an offline Android TV shell. It can be selected as the default Home app through Android's Home settings. No system apps need to be disabled. Minimum Android API 26; initial target TCL Smart TV Pro on Android TV 14. Rendering and the original-home return were verified; this firmware still opens Google TV on physical Home despite accepting the HOME role. See [device status](../docs/android-device-status.md).

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

The Android browser preview uses separate preferences and cannot launch TV apps. Its Samsung-only Art Mode control is omitted. Settings → Android TV contains TV Home, TV settings and Home button. TV Home opens the original TCL interface once. Home button opens Android’s default-Home selector so you can select CleanFrame or permanently restore the original launcher. The Tizen preview/packager is retained for upstream compatibility; never use the Tizen device scripts for an Android TV.

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

## Physical acceptance

- Launch each installed favorite; confirm missing apps report an honest error.
- Use actual D-pad, OK and Back in Dock, List, Settings and the editor; verify one focused control and no double launch when holding OK.
- After selecting CleanFrame as default, Home must return to CleanFrame. Check TV Home opens the original interface and Home button can restore it as default. Back within a streaming app belongs to that app.
- Change wallpaper/order, exit and reopen, then restart the TV and reopen the app; confirm saved preferences.
- Confirm wallpaper timers pause outside CleanFrame and the TV's screensaver/sleep remain functional.
- Confirm volume, dedicated app buttons, HDMI/CEC and casting still work.
- Test offline launcher rendering. Streaming still needs the streaming apps' normal network access.

Turn debugging off when finished. Undo this ordinary-app trial by uninstalling only `com.mazzei.cleanframe` through Settings; first restore the original launcher through Home button if CleanFrame is the default. Keep a private record of the original Home component before changing the default.

## Bridge boundaries

WebView loads only bundled assets on a synthetic HTTPS origin, with no network permission, file/content access, remote frames, external navigation or arbitrary intent/shell bridge. The native boundary supports app discovery, launch of installed TV-launchable packages, TV settings and stock Home. App icons are loaded locally from installed packages. Android pause/resume explicitly controls UI timers. Device callbacks acknowledge Android intent dispatch, not visible success inside another app.

Code and asset licences remain those of upstream; see LICENSE and THIRD_PARTY_NOTICES.md.
