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

The Android browser preview uses separate preferences and cannot launch TV apps. Its Samsung-only Art Mode control is omitted. Settings → Android TV contains TV Home, TV settings, Home health, Startup, and Backup and restore. TV Home opens the original TCL interface and keeps it there, even across Home presses, until CleanFrame is opened again. Home health shows Running, Enabled but not running, Off, or Unavailable, with Check again and Open Accessibility actions. In Accessibility, **CleanFrame Home button** turns the Home return on or off. The Tizen preview/packager is retained for upstream compatibility; never use the Tizen device scripts for an Android TV.

## Backup and recovery (0.4.0)

Under **Settings → Android TV → Backup and restore**, Save a snapshot keeps one copy of the current appearance, wallpaper rotation and app arrangement on this TV. Restore snapshot previews the app count and wallpaper, then requires confirmation. Cancel changes nothing. A restore validates the versioned document and commits preferences once; malformed files or failed storage writes preserve the saved preferences. Installed streaming apps/accounts, Home permissions and native startup behavior are excluded. Missing apps still need installation; unavailable fonts fall back to a bundled default.

Local snapshots survive in-place updates, but **not uninstalling CleanFrame or clearing its data**. Export/import uses Android's document picker without broad storage permissions. File export/import is disabled with an explanation when no real picker exists, including the framework placeholder on the tested TCL. File IO is bounded to 128 KB and runs off the UI thread. External file round trips have not been physically tested on a picker-equipped TV; do not treat local snapshots as reinstall recovery on this TCL.

Home health reports service connectivity, not proof of a Home-button press or a diagnosis of TCL's app-op policy. If enabled but not running, it explains the off/on recovery and opens Accessibility without altering permissions itself. Startup choices are now nested under Startup; Follow TV remains the default.

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

If other services were already enabled, append CleanFrame with a `:` separator instead of replacing them. Confirm `dumpsys accessibility` lists it under Bound services. On the tested TCL firmware the guard resets `AUTO_START` at every reboot, so after a reboot the service stays unbound until it is re-enabled while CleanFrame is running; standby and wake are unaffected. When CleanFrame opens in that state it shows a dialog that opens Accessibility settings, where turning CleanFrame Home button off and on again restores it. Android's confirmation warns that any accessibility service could read typed text; this one is configured without window content or key access. To recover, put the recorded value back (or turn the service off in Accessibility) and `appops set com.mazzei.cleanframe AUTO_START default`. Never disable Google TV or setup packages: that can leave the TV without a usable home and require a factory reset.

Since 0.3.7 the service has three guards, adapted from [TV Detox](https://github.com/Ansub/tv-detox) (MIT). It leaves Home alone until the TV's first setup is complete. TV Home's choice of the original interface is saved, so it survives CleanFrame's process being killed or updated, and lasts until CleanFrame is shown again or the TV restarts. In 0.3.9, three unsuccessful redirects stop further redirection. A pause or an overlay no longer resets this budget. A launch counts as successful only after the WebView reports a usable UI continuously for 1.5 seconds, or after an explicit user action opens another app/settings screen. Open CleanFrame manually and let it become usable to re-arm the guard.

At boot, TCL binds the service about a second after Google TV's Home has opened, so the service misses that screen. 0.3.8 opened CleanFrame whenever the service connected within the first 60 seconds, which could interrupt HDMI or Live TV. In 0.3.9, this behavior is **off by default**, including after upgrading from 0.3.8. Settings → Android TV offers two startup choices: keep the TV’s normal destination, or explicitly open CleanFrame at startup. The latter may replace HDMI or Live TV within the first minute and still requires the Home service to bind. Normal Home-button interception is independent of this startup option.
## Remote control without ADB

`scripts/android-remote.py` speaks the Android TV Remote v2 protocol used by the Google TV phone remote, so routine checks do not need debugging left on. It sends keys and app links and reads the foreground app; it never changes Home/startup settings, accessibility services or packages. Pairing shows a code on the TV once. The certificate, key and TV address stay in ignored `.local/android/remote/`.

```sh
python3 scripts/android-remote.py setup
python3 scripts/android-remote.py pair --host TV_ADDRESS
python3 scripts/android-remote.py status
python3 scripts/android-remote.py key DPAD_DOWN DPAD_CENTER
python3 scripts/android-remote.py launch https://www.youtube.com
python3 scripts/android-remote.py home-check
```

`home-check` must start from another known foreground app. It subscribes before pressing Home, allows up to six seconds for intermediate Google TV screens, and requires a fresh CleanFrame foreground event followed by one stable second. Cached state alone cannot pass. A failure means Home return was not confirmed; the original Home may be selected intentionally, so it does not prove the service is unbound. The tested Mac needed Apple’s `/usr/bin/python3` for this transport, so setup defaults to it; connection failures alone do not prove a macOS permission problem. Announce key presses and launches as for any device command. To unpair, delete `.local/android/remote/` and remove the remote under the TV's Remotes & Accessories settings.

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

## Regression tests

`npm run check:native` compiles production HomeLaunchPolicy on JDK 17 and checks rapid pauses, stale callbacks, UI readiness, failure budgets and startup opt-in. `npm run check:remote` tests delayed/unstable foreground updates without network or credentials. `npm run check:android` checks startup preference persistence and failed saves through the real UI with a mocked native boundary. CI runs all three. These are not a substitute for physical remote, reboot and HDMI acceptance.

## Vacuum experiment (separate branch)

`feat/vacuum-status` explores a Eufy Omni C20 status screen, opened by a vacuum button between All apps and Settings, with a read-only Mac helper and shell-authorized ADB delivery. See [setup and verification limits](../docs/vacuum-status.md). It needs an awake Mac and an authorized ADB connection; it is not standalone TV connectivity or an accepted live integration yet.
