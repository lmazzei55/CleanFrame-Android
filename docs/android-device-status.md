# Android TV device status — 22 October 2026

Target: TCL Smart TV Pro, Android TV 14. Device addresses, identifiers, screenshots, baseline records and signing keys stay ignored locally.

## Verified

- Latest tested source: 0.3.8 on `claude/android-home-guards`, from `feat/android-tv`.
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

## Performance

The owner reported the TV feeling slower. Measured causes, largest first:

- **Memory.** The TV has 2.4 GB RAM. With all nine streaming apps opened, zram swap was ~90% full. CleanFrame plus its WebView renderer use ~110 MB, and Google TV's launcher (~115 MB) stays resident because it is deliberately not disabled. Its process sits at cached priority (oom 900), so Android reclaims it under pressure.
- **CleanFrame rendering.** Using a local profiling build (WebView debugging enabled, never committed) and `gfxinfo` over 12 D-pad moves, median frame time was ~32 ms (9% janky) in List/Soft/Glass. Hiding effects one at a time isolated the glass focus plates: each full-stage 1920×1080 field used `translate3d`, which promoted it to its own GPU layer masked by the rounded plate every frame. The fields never animate, so 0.3.6 uses a 2D `translate`. Release build: ~12 ms median, 2–4% janky. In preview screenshots, 18 of 24 look/focus/step cases are pixel-identical; the six List/Glass cases differ only on plate pixels, by at most 14/255.
- Only List/Soft was measured on the TV; the other layouts and materials were checked for pixel parity in preview.

**Cleanup batch 1 (31 October, owner-approved).** Following TV Detox's rules (disable, never uninstall; skip `PERSISTENT` apps and the app that saved Wi-Fi, here the setup app), 15 TCL apps were turned off with `pm disable-user --user 0`: `ttvs`, `interactive`, `channelplus`, `tv.plus`, `messagebox`, `esticker`, `exhibit`, `logkit`, `repairguide`, `usercenter`, `ocean.instructions`, `smartalexa`, `hotelmenu`, `tv.tclhome_passive`, `smartlink.core` (all `com.tcl.`). The undo list is kept privately; `pm enable <package>` reverses each. After a reboot: all 15 stayed off, Wi-Fi and internet worked, no crashes, Home service bound, Netflix → Home returned to CleanFrame, and the four HDMI inputs, Live TV (`com.tcl.tv`) and the input picker remained. At ~6 minutes uptime, `MemAvailable` went from ~880 MB to ~960 MB and used PSS fell only ~8 MB; swap readings were within noise. TCL analytics (`ttvs`, ~32 MB) no longer runs; the other apps were not yet running so soon after boot, so most of the benefit should show after longer uptime. Not yet observed: a physical HDMI switch and remote app buttons.

That boot started on Google TV rather than CleanFrame: Google TV's Home opened ~22 s after boot and ~0.9 s before Android bound the Home service, so the service missed it (the previous boot won this race). Without window access the service cannot see what is in front, so 0.3.8 opens CleanFrame when the service connects within 60 s of boot, through the same setup, stock-choice and launch-loop guards. Installed in place on 31 October; the update rebound the service without opening CleanFrame (uptime past 60 s). Two reboots: each time Google TV's Home opened first and the service started 0.5–0.9 s later, then CleanFrame came to the front; no crashes; Netflix → Home still returned to CleanFrame. Risk: a TV set to power on into an HDMI input or Live TV would be switched to CleanFrame if the service binds within that minute; this TV boots to Home.

Every in-place APK update also unbinds the Home service (the process is killed and TCL blocks the rebind), so the re-enable prompt appears after updates as well as reboots.

## Pending

0.3.7 adds Home-service guards (first-setup check, a stock-Home choice kept until reboot, a stop after three launches that never stayed open). Installed in place over 0.3.6 on 31 October with the persistent local key (preferences kept). Verified:

- ADB readback: Netflix → Home and YouTube → Home returned to CleanFrame; no crashes in the crash log.
- Owner, with the remote: TV Home opened Google TV and it stayed there through 2–3 Home presses (logcat shows each Home reopening Google TV, nothing redirecting).
- Reboot with that choice still saved: the boot count changed, Google TV's Home started at boot and the service redirected it to CleanFrame, so the saved choice correctly ended at reboot. Netflix → Home then returned to CleanFrame.
- Unlike earlier trials, the update did not unbind the service, and after the reboot `AUTO_START` read `allow` and the service bound without the re-enable prompt. One reboot only; TCL's boot guard may still reset it on other boots, so the prompt stays.

Not exercised: the crash-loop stop (needs a deliberately broken build) and the stock choice surviving process death (on this firmware the service dies with the process and is not rebound).

Physical remote feel and Home flash, all nine streaming launches through the UI,  HDMI/CEC and casting. The last rapid-key app-launch probe was inconclusive. Keep the PR draft until device acceptance. After a reboot, Home works only once the owner re-enables the service from the prompt.

## 0.3.9 review fixes — 2 November 2026

The Home failure budget now survives rapid pauses/overlays; success requires a usable WebView for 1.5 seconds or an explicit user-directed exit. Boot takeover is off by default and can be enabled explicitly under Settings → Android TV. The remote Home check now waits for a fresh, stable CleanFrame event instead of failing on transient Google TV. Native policy and transport-free remote regression tests are added to CI. Physical verification of 0.3.9 is pending; the earlier device results above describe their stated versions.
