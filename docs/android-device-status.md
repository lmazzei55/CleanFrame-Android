# Android TV device status — 22 October 2026

Target: TCL Smart TV Pro, Android TV 14. Device addresses, identifiers, screenshots, baseline records and signing keys stay ignored locally.

## Verified

- Latest tested source: `a7bdff8` (0.3.5) on `feat/android-tv`.
- Nine requested apps are installed, including Apple TV and Tubi.
- The APK installed with a persistent local signing key; a subsequent in-place update succeeded.
- Physical screen capture confirms the full dock, clock and controls render correctly. `clientWidth/clientHeight` sizing fixed the WebView cropping; wide-viewport flags alone did not.
- D-pad navigation opened Settings. Settings → Android TV → TV Home visibly opened the original Google/TCL interface.
- 344 upstream browser checks passed before the final viewport-only edit; 27 Android contract checks passed after it. Native boundaries are mocked in browser tests. APK compilation and signing passed.

## Home button

`set-home-activity` made CleanFrame the HOME role holder, but the resolver and the Home key still opened Google TV, so the role alone is not a solution on this firmware. The original Home assignment (Google TV) is kept.

0.3.0 adds the optional CleanFrame Home button accessibility service. It initially stayed unbound because TCL's guard rejected it under the `AUTO_START` app-op; allowing that op bound it with no capabilities and only window-state events from Google TV. Verified by ADB readback on the TV:

- Netflix → Home → CleanFrame, running as a `type=home` task.
- YouTube → Home → CleanFrame.
- Settings → Android TV → TV Home (via D-pad/OK) → Google TV, which stays in front after Home; reopening CleanFrame restores the Home return.
- No Google TV or setup package was disabled; no crashes in logcat.

- Standby and wake (`KEYCODE_SLEEP`/`KEYCODE_WAKEUP`, no reboot) kept the service bound; Home from Netflix after waking returned to CleanFrame.

**Full reboot breaks it.** TCL's app-boot guard runs an "auto management" policy that resets the `AUTO_START` app-op to `ignore` on every boot and also dropped a `deviceidle` allow-list entry. With CleanFrame not running, `TclAppBoot` refuses the accessibility bind (`callee_does't_have_OP_AUTO_START_permission`), so the TV boots to Google TV and Home stays there. The Safety Guard app that some TCL firmware uses to make this permanent is not installed here. Re-enabling the service while CleanFrame is running binds it immediately, and it then lasts until the next reboot.

Automatic recovery was tried and does not work on this firmware: an ADB-granted `WRITE_SECURE_SETTINGS` is revoked at boot, and toggling CleanFrame's own service component (each step waiting for its `PACKAGE_CHANGED` broadcast) never makes Android re-attempt the bind. 0.3.5 instead shows a dialog when CleanFrame opens and its Home service is enabled but not running. Verified across a reboot using only D-pad/OK: the dialog appeared, Open Accessibility opened Android TV Accessibility settings, turning CleanFrame Home button off and on bound the service, and Home from Netflix then returned to CleanFrame. With the service running, no dialog appears. An early build kept a stale dialog after Google TV's post-boot screen briefly covered CleanFrame, and OK then reached the launcher behind it; 0.3.5 closes the dialog on pause and shows it afresh.

Not yet confirmed: how visible the brief Google TV frame is with a real remote.

## Pending

Physical remote feel and Home flash, all nine streaming launches through the UI,  HDMI/CEC and casting. The last rapid-key app-launch probe was inconclusive. Keep the PR draft until device acceptance. After a reboot, Home works only once the owner re-enables the service from the prompt.
