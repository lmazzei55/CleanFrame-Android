# Android TV device status — 22 October 2026

Target: TCL Smart TV Pro, Android TV 14. Device addresses, identifiers, screenshots, baseline records and signing keys stay ignored locally.

## Verified

- Latest tested source: `1b22384` (0.3.0) on `feat/android-tv`.
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

Not yet confirmed: survival across reboot, and how visible the brief Google TV frame is with a real remote.

## Pending

Physical remote feel and Home flash, all nine streaming launches through the UI, Home service after reboot/wake, HDMI/CEC and casting. The last rapid-key app-launch probe was inconclusive. Keep the PR draft until device acceptance. Do not claim Home works after reboot until checked.
