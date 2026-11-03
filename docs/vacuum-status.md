# Eufy Omni C20 — TV-only experiment

Branch `feat/vacuum-status`, draft PR #3. Version `0.5.0-vacuum.4` / code17. The vacuum button between All apps and Settings opens status and Start cleaning, Pause, Return to dock, and Back. Home has no permanent card. Start requires a native confirmation with Cancel focused by default.

## Runtime

The TV connects directly to Eufy through native HTTPS and certificate/hostname-verified TLS MQTT. It identifies exactly one C20/T2280, decodes bounded protobuf status fields, reads cloud status every30s and receives MQTT updates. Commands use request status1, a stable application session ID separate from the MQTT connection ID, and an increasing envelope sequence. Commands publish once with QoS0; there is no automatic replay or offline queue. Sent is not success: the UI waits for a matching observed state transition or reports an unconfirmed result. Offline/stale conditions reject commands. Faults block Start; Pause remains available when a fault is reported, and Return to dock is allowed when all reported codes are 6011 (dock clean-water tank empty). Other faults still block docking; already-docked status prevents redundant commands. Activity is retained separately from reported faults, so clearing a fault does not leave a sticky error state. Proto3 standby and nested paused states decode explicitly; mapping, remote control and cruising are recognized. Pause accepts active mapping as well as cleaning/returning. Immediate state updates during a send can confirm the command without being overwritten by the send completion. Connections close when leaving the screen or pausing the app; no boot/background service is added. Eufy cloud connectivity is still required, but the Mac and ADB are not runtime dependencies.

Android Internet permission is used by native code only. The WebView still blocks networking and external navigation. Account/session data is encrypted with an Android Keystore AES-GCM key, never passed to JavaScript or included in preference backups. TLS client keys are parsed in memory. Uninstall/clear data removes the connection.

## One-time setup

The experiment currently uses the previously configured private account on the Mac for one-time provisioning. After installing the APK with the same persistent signing key and opening CleanFrame:

```sh
python3 scripts/eufy/provision-tv.py
```

Requires Python cryptography and the existing private `.local/eufy-status/` setup. The helper fetches a TV Keystore public key, hybrid-encrypts the account/session material and sends ciphertext through the shell-permission-protected provisioning receiver. Plain credentials never enter ADB arguments or output. The TV presents a Connect/Cancel consent dialog before saving. The previous Mac status helper must be stopped. A full on-TV account setup/disconnect flow remains a product improvement; no account-management claim is made here.

Legacy Mac discovery/status scripts remain for reference. Their SDK is pinned to `martijnpoppen/eufy-clean@8872307101a7032aa9f359781a3783fc50b0d380` in ignored `.local/eufy-sdk-review`; they are not packaged or required by the native runtime. Credentials, tokens, device targets, signing keys and captures remain ignored. Do not print or publish `.local/` contents.

## Verification and limits

On2 November2026, the physical TCL connected directly with the Mac helper stopped and displayed Docked/100% plus Controls connected. Encrypted provisioning and Keystore storage worked. Start confirmation defaulted Cancel; Cancel was exercised and Home restored. The owner explicitly requested keeping the robot docked and will test commands later: no physical Start/Pause/Dock command was sent.

Follow-up: the owner reported Start working. The TV then showed code6011 at94% battery; the pinned SDK schema identifies this as clean-water tank empty. The old blanket fault guard blocked Pause and Dock before sending either. Code16 was installed in place: Pause was sent (physical effect not yet confirmed), and Dock reported already at dock. The persistent tank fault prevented a fresh cleaning run. Do not count this as a verified Pause/return journey.

The owner then confirmed code16 Pause had no physical effect and refilled the clean-water tank. A sanitized read-only snapshot showed WorkStatus4 (fast mapping), with the tank fault cleared. Code17 corrects the envelope request marker (previously2, now1), separates stable session identity from the connection ID, increments message sequence, recognizes mapping/nested pause, and preserves immediate confirmations. The payload bytes for Pause and Dock were independently compared with the pinned SDK protobuf encoder and matched. On the physical TV, the code17 controls produced confirmed transitions Paused -> Returning, then Pause -> Idle, then Dock -> Returning. The owner confirmed that the vacuum physically stopped and then headed back to its dock. Final dock arrival was not yet observed at this checkpoint. No new Start command was issued in this follow-up.

Passed locally for code17:344 interaction checks,24 vacuum UI checks,8 isolated SDK/decoder tests,25 Home-policy checks,10 freshness checks and23 native protocol/control checks, plus source/public checks/export and APK compilation/signing. Browser checks mock native APIs; protocol checks do not prove robot movement. Initial native connection reused the existing encrypted session. Fresh native login/session renewal, command execution/transition matching, long-running reconnect, background/standby and broader device compatibility remain unverified. Keep PR draft pending acceptance and independent review of the native networking/crypto/lifecycle code.

The community API is unofficial and may change. HTTP429 causes a15-minute local backoff. Unsupported/malformed status stays unknown; failed decoding never defaults to Charging. Commands use the vacuum's existing Eufy cleaning settings. No maps, rooms or settings commands are exposed.

## Sources

- [Community integration](https://github.com/jeppesens/eufy-clean) — C20/T2280 support and protocol reference.
- [Community SDK](https://github.com/martijnpoppen/eufy-clean) — authentication and command schemas. Retain its license if distributing its source; SDK source remains separate locally.
- [Android receiver permissions](https://developer.android.com/guide/topics/manifest/receiver-element#prmsn) — provisioning sender restrictions.
