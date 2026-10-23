# Android TV device status — 22 October 2026

Target: TCL Smart TV Pro, Android TV 14. Device addresses, identifiers, screenshots, baseline records and signing keys stay ignored locally.

## Verified

- Latest tested source: `3b2cc0e` on `feat/android-tv`.
- Nine requested apps are installed, including Apple TV and Tubi.
- The APK installed with a persistent local signing key; a subsequent in-place update succeeded.
- Physical screen capture confirms the full dock, clock and controls render correctly. `clientWidth/clientHeight` sizing fixed the WebView cropping; wide-viewport flags alone did not.
- D-pad navigation opened Settings. Settings → Android TV → TV Home visibly opened the original Google/TCL interface.
- 344 upstream browser checks passed before the final viewport-only edit; 27 Android contract checks passed after it. Native boundaries are mocked in browser tests. APK compilation and signing passed.

## Home button remains unresolved

This firmware accepted CleanFrame as the HOME role holder after `set-home-activity`, but the resolver and injected Home key still opened Google TV. A success response or role-holder readback is insufficient proof. The original Home assignment was restored after this test. No Google TV or setup package was disabled.

The Home button setting opens the system selector, but selecting a Home role is not yet a verified solution on this firmware. A further reversible override needs investigation and actual Home-from-streaming verification.

## Pending

Physical remote feel, all nine streaming launches through the UI, reboot/wake, HDMI/CEC and casting. The last rapid-key app-launch probe was inconclusive. Keep the PR draft until device acceptance. Do not claim physical Home replacement works.
