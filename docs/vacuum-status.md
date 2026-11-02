# Eufy Omni C20 status experiment

Branch: `feat/vacuum-status`, based on `main` after PR #2. Target: Eufy Omni C20 / T2280, Eufy app, no Home Assistant. Experimental APK version: `0.5.0-vacuum.1`.

## Architecture

The Mac authenticates with Eufy using a separately prepared community SDK, reads cloud device snapshots, decodes only work status, battery and error code, and sends those fields over the already-authorized Android ADB connection. No MQTT connection is opened and no cleaning, docking, scene, sound or map command is sent. Cloud snapshots are labeled as snapshots, not guaranteed live robot telemetry.

The TV has no added Internet permission or HTTP listener. A receiver protected by `android.permission.DUMP` accepts the ADB shell feed without launching the app. A vacuum utility button sits between All apps and Settings. It opens a dedicated read-only status screen; Back returns focus to that button. Status reads stop when the screen is closed. The Home page has no persistent card, and status is excluded from preference backups. Old data becomes unavailable after 90 seconds, including after a restart. Receiving an already-old snapshot does not renew its age. The Mac must be awake and ADB must remain authorized/reachable. This transport is a development experiment, not a consumer deployment architecture.

## Private setup

Use Node 22+, Python 3.10+, git/npm and the project's existing authorized ADB setup. From the repository root:

```sh
python3 scripts/eufy/setup.py --prepare
python3 scripts/eufy/setup.py --gui   # macOS dialogs, hidden password
# Or omit --gui to use terminal input and getpass.
```

The SDK is pinned to `martijnpoppen/eufy-clean@8872307101a7032aa9f359781a3783fc50b0d380`, installed separately under ignored `.local/eufy-sdk-review`; dependency lifecycle scripts are disabled and its compiler/resource-copy steps run explicitly. Its own source/license stay there. No Eufy dependency is added to the TV package or root npm dependencies. Setup checks the revision before use. SDK diagnostics are discarded in an isolated worker because upstream can print sensitive account/device data. Only whitelisted stage/HTTP codes and normalized results cross that boundary.

Credentials, session tokens and discovery records stay under ignored `.local/eufy-status/`, directory mode 0700, files mode 0600. Credentials are stored locally in plaintext for this experiment; deleting that directory removes the saved login. Never paste its contents or SDK diagnostics into chat/issues. An SDK workaround turns an empty login response into an error so its intended newer-to-legacy fallback actually runs. A local 15-minute guard prevents retries after HTTP 429; this is a conservative client cooldown, not a promise about Eufy's reset time. Successful MQTT-account HTTP sessions are cached locally for one hour; the watcher uses HTTP reads, not MQTT.

Create private `.local/eufy-status/tv.json` using your existing ADB server and device target (never commit real values):

```json
{"port":5048,"serial":"YOUR_TV_HOST:5555"}
```

After the experimental APK is installed with the existing signing key:

```sh
node scripts/eufy/watch.mjs          # foreground status helper; Ctrl+C stops
node scripts/eufy/watch.mjs --once   # one reading and TV delivery
node scripts/eufy/watch.mjs --clear  # hide the card; no Eufy login
```

Do not repeatedly run setup/probes to poll status. The watcher reuses one session and reads every 30 seconds. If the provider stops responding, the TV expires its last sample. Do not disable account two-factor protection to make the connector work.

## Preview and checks

Browser only: `/home/?platform=android&vacuumDemo=cleaning` shows clearly labeled sample data. The demo is refused when the real Android bridge exists. It does not connect to Eufy or configure the TV.

```sh
npm run check
npm run check:vacuum
npm run check:android
npm run check:native
CLEANFRAME_PUBLIC_FONTS=1 npm run check:browser
```

Verification on 2 November 2026: corrected local login identified exactly one C20/T2280 and returned novel-protocol status fields. An incorrect-password attempt preceded it; later authentication retries received HTTP 429, so automatic authentication retries were stopped. After the local cooldown elapsed, the watcher authenticated successfully and decoded Docked / 100% from the C20. The physical TV displayed that reading, matching the owner’s phone app (100%, fully charged). A second cloud read 30 seconds later delivered the same state/battery. The helper remained running on the awake Mac. This validates the first real status path; it is not long-term polling acceptance. The SDK convenience getter that defaults failed status decoding to Charging is not used.

Physical TV receiver test: experimental APK installed in place; the protected receiver acknowledged an explicitly unavailable sample and the Home card visibly showed Status unavailable / No recent status received. The test feed was cleared afterward. The initial Home-card design was then replaced with an on-demand screen at the owner’s request. No successful fake cleaning/battery reading was pushed to the TV. Pure policy tests cover stale delivery, clock skew, restart invalidation and overflow; isolated SDK tests cover diagnostic secret suppression and ambiguous discovery. The on-demand button placement and dedicated status screen were physically verified after replacing the initial card design. Transitions during a cleaning cycle, long-running sessions/token expiry and physical Mac-sleep behavior remain pending. Native/JS freshness policies have automated coverage; the robot was never commanded to start cleaning for this test.

## Sources

- [Community Eufy Clean integration](https://github.com/jeppesens/eufy-clean): lists C20/T2280; connection transport depends on discovered device/account metadata; map support differs by transport. Listing is not physical acceptance of this unit.
- [Eufy Clean SDK](https://github.com/martijnpoppen/eufy-clean): independent community connector, requires account login; retain its license if redistributing its code.
- [Home Assistant EufyHome](https://www.home-assistant.io/integrations/eufy/): built-in integration currently lists lights/switches, so it is not the vacuum integration needed here.
- [Android receiver permissions](https://developer.android.com/guide/topics/manifest/receiver-element#prmsn): sender must hold the receiver's declared permission.
