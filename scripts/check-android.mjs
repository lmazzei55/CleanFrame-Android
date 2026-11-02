// Browser contract tests for the actual Android adapter. No TV contacted.
import assert from 'node:assert/strict';
import { mkdirSync } from 'node:fs';
import { openPreview, root } from './browser.mjs';

const favorites = ['netflix', 'hbomax', 'youtube', 'paramount', 'appletv', 'primevideo', 'peacock', 'disneyplus', 'tubi'];
const labels = ['Netflix', 'HBO Max', 'YouTube', 'Paramount+', 'Apple TV', 'Prime Video', 'Peacock', 'Disney+', 'Tubi'];
const packages = ['com.netflix.ninja', 'com.wbd.stream', 'com.google.android.youtube.tv', 'com.cbs.ott', 'com.apple.atve.androidtv.appletv', 'com.amazon.amazonvideo.livingroom', 'com.peacocktv.peacockandroid', 'com.disney.disneyplus', 'com.tubitv'];
const preview = await openPreview({ publicFonts: true });
let count = 0;
function check(value, message) { assert.ok(value, message); count++; }
try {
  const page = await preview.browser.newPage({ viewport: { width: 1920, height: 1080 } });
  const errors = [];
  page.on('pageerror', error => errors.push(String(error)));
  await page.goto(preview.baseURL + '/home/?platform=android');
  await page.waitForFunction(() => window.ShoreQA?.ready());
  check(JSON.stringify(await page.evaluate(() => ShoreQA.state().home)) === JSON.stringify(favorites), 'All nine requested favorites in order');
  check(!(await page.getByRole('button', { name: 'Art Mode', exact: true }).count()), 'No Samsung Art Mode');
  await page.evaluate(() => FrameRemoteKey('ok'));
  await page.waitForFunction(() => document.body.innerText.includes('browser preview cannot control'));
  check((await page.locator('body').innerText()).includes('Android app'), 'Preview reports no device instead of faking success');
  await page.evaluate(() => FrameRemoteKey('back'));
  await page.evaluate(() => ShoreQA.scene('settings'));
  check((await page.locator('body').innerText()).includes('Android TV'), 'Platform-specific settings label');
  await page.evaluate(() => ShoreQA.scene('home'));
  mkdirSync(root + '.local/qa', { recursive: true });
  await page.screenshot({ path: root + '.local/qa/android-dock.png' });
  await page.close();

  const tv = await preview.browser.newPage({ viewport: { width: 1280, height: 720 } });
  tv.on('pageerror', error => errors.push(String(error)));
  await tv.addInitScript(({ favorites, packages, labels }) => {
    window.calls = [];
    window.failLaunch = false;
    window.failBootSave = false;
    window.CleanFrameAndroid = { request(method, id, target) {
      window.calls.push({ method, target });
      var bootResponse;
      if (method === 'bootLaunch') {
        if (window.failBootSave && target !== 'get') bootResponse = { error: 'The startup preference could not be saved.' };
        else {
          if (target !== 'get') localStorage.setItem('__native_boot', String(target === 'on'));
          bootResponse = { enabled: localStorage.getItem('__native_boot') === 'true' };
        }
      }
      const response = (method === 'backupStatus' ? { filePicker: !window.noPicker } : null) || bootResponse || (method === 'apps' ? { apps: packages.map((pkg, i) => ({ appId: pkg, name: labels[i], brand: favorites[i], mark: !['paramount', 'peacock'].includes(favorites[i]), icon: '' })) }
        : window.failLaunch ? { error: 'This app is not installed.' } : {});
      setTimeout(() => window.CleanFrameAndroidResult(id, response), 0);
    } };
  }, { favorites, packages, labels });
  await tv.goto(preview.baseURL + '/home/');
  await tv.waitForFunction(() => window.ShoreQA?.ready() && ShoreQA.state().catalog.loaded);
  check((await tv.evaluate(() => ShoreQA.state().catalog.ids.length)) === 9, 'Native catalogue discovery');
  for (let i = 0; i < favorites.length; i++) {
    await tv.evaluate(() => FrameRemoteKey('ok'));
    await tv.waitForTimeout(100);
    const launch = await tv.evaluate(() => calls.filter(c => c.method === 'launch').at(-1));
    check(launch.target === packages[i], 'Launch resolved package for ' + favorites[i]);
    if (i < favorites.length - 1) await tv.evaluate(() => FrameRemoteKey('right'));
  }
  check((await tv.evaluate(() => ShoreQA.where().focusCount)) === 1, 'One focused control at 720p');
  await tv.evaluate(() => { window.failLaunch = true; FrameRemoteKey('ok'); });
  await tv.waitForFunction(() => document.body.innerText.includes('not installed'));
  check((await tv.locator('body').innerText()).includes('not installed'), 'Native launch errors shown');
  await tv.evaluate(() => { FrameRemoteKey('back'); ShoreQA.look({ layout: 'list' }); });
  await tv.waitForFunction(() => ShoreQA.ready());
  await tv.reload();
  await tv.waitForFunction(() => window.ShoreQA?.ready());
  check((await tv.evaluate(() => ShoreQA.state().layout)) === 'list', 'Appearance survives reload');
  check(await tv.evaluate(() => localStorage.getItem('cleanframe.android.v1') !== null && localStorage.getItem('cleanframe.home.v1') === null), 'Android storage is independent of Tizen');
  await tv.evaluate(() => { ShoreQA.activate('samsung.home'); }); // Invalid from Home must not dispatch.
  await tv.evaluate(() => new Promise(resolve => FrameDevice.home(() => resolve())));
  check((await tv.evaluate(() => calls.at(-1).method)) === 'home', 'Stock Home bridge action');
  await tv.evaluate(() => new Promise(resolve => FrameDevice.settings(() => resolve())));
  check((await tv.evaluate(() => calls.at(-1).method)) === 'settings', 'TV settings bridge action');
  await tv.evaluate(() => { window.failLaunch = false; ShoreQA.scene('settings'); ShoreQA.activate('settings.group.samsung'); });
  check((await tv.locator('body').innerText()).includes('Home health'), 'Home health is available');
  await tv.evaluate(() => ShoreQA.activate('android.health'));
  await tv.waitForFunction(() => calls.at(-1).method === 'health');
  check((await tv.evaluate(() => calls.at(-1).method)) === 'health', 'Health screen uses its own native action');
  await tv.evaluate(() => ShoreQA.activate('android.startup'));
  check((await tv.locator('[data-action="android.boot.off"]').innerText()).includes('Selected.'), 'Boot takeover defaults off');
  await tv.evaluate(() => ShoreQA.activate('android.boot.on'));
  await tv.waitForFunction(() => document.querySelector('[data-action="android.boot.on"]')?.textContent.includes('Selected.'));
  check(await tv.evaluate(() => localStorage.getItem('__native_boot') === 'true'), 'Boot takeover requires an explicit saved opt-in');
  await tv.reload();
  await tv.waitForFunction(() => window.ShoreQA?.ready() && window.FrameProfile.bootLaunch.loaded);
  await tv.evaluate(() => { ShoreQA.scene('settings'); ShoreQA.activate('settings.group.samsung'); ShoreQA.activate('android.startup'); });
  check((await tv.locator('[data-action="android.boot.on"]').innerText()).includes('Selected.'), 'Native startup setting is read after relaunch');
  await tv.evaluate(() => { window.failBootSave = true; ShoreQA.activate('android.boot.off'); });
  await tv.waitForFunction(() => document.body.innerText.includes('could not be saved'));
  check(await tv.evaluate(() => FrameProfile.bootLaunch.enabled), 'Failed startup save keeps the last applied choice');
  await tv.evaluate(() => { FrameRemoteKey('back'); window.failBootSave = false; ShoreQA.activate('android.boot.off'); });
  await tv.waitForFunction(() => document.querySelector('[data-action="android.boot.off"]')?.textContent.includes('Selected.'));
  check(await tv.evaluate(() => localStorage.getItem('__native_boot') === 'false'), 'Boot takeover can be turned off again');
  await tv.screenshot({ path: root + '.local/qa/android-startup-720.png' });
  await tv.evaluate(() => { FrameRemoteKey('back'); ShoreQA.activate('android.backups'); });
  check((await tv.locator('body').innerText()).includes('Save a snapshot'), 'Backup screen reachable with the remote');
  check((await tv.locator('.appearance .is-focus[data-action]').getAttribute('data-action')) === 'android.backup.save', 'New backup screen initially focuses Save, not Restore');
  await tv.evaluate(() => ShoreQA.activate('android.backup.save'));
  await tv.waitForFunction(() => calls.at(-1).method === 'backup.save');
  const backup = await tv.evaluate(() => JSON.parse(calls.at(-1).target));
  check(backup.format === 'cleanframe.android.backup' && backup.version === 1, 'Versioned Android backup document');
  check(!JSON.stringify(backup).includes('__native_boot') && !('launch_at_boot' in backup.preferences), 'Backup excludes native permissions/startup');
  check(await tv.evaluate(data => FrameBackupCheck(data).ok, backup), 'Exported document validates for restore');
  const original = await tv.evaluate(() => localStorage.getItem('cleanframe.android.v1'));
  for (const bad of [null, { ...backup, version: 999 }, { ...backup, format: 'another-app' },
    { ...backup, preferences: { ...backup.preferences, home: ['__proto__'] } },
    { ...backup, preferences: { ...backup.preferences, home: [] } },
    { ...backup, preferences: { ...backup.preferences, home: ['netflix', 'netflix'] } },
    { ...backup, preferences: { ...backup.preferences, look: { ...backup.preferences.look, bg: 'bad' } } }]) {
    check(!(await tv.evaluate(data => FrameRestore(data).ok, bad)), 'Malformed/unsupported backup rejected');
    check((await tv.evaluate(() => localStorage.getItem('cleanframe.android.v1'))) === original, 'Rejected restore preserves preferences');
  }
  check(await tv.evaluate(data => {
    var old = Storage.prototype.setItem;
    Storage.prototype.setItem = function () { throw new Error('Storage full'); };
    try { return !FrameRestore(data).ok; } finally { Storage.prototype.setItem = old; }
  }, backup), 'Storage failure rejects restore');
  check((await tv.evaluate(() => localStorage.getItem('cleanframe.android.v1'))) === original, 'Failed write preserves saved preferences');
  const changed = structuredClone(backup);
  changed.preferences.home = [...changed.preferences.home].reverse();
  changed.preferences.look.layout = 'dock';
  changed.preferences.mode = '15';
  check(await tv.evaluate(data => FrameRestore(data).ok, changed), 'Valid restore commits preferences');
  await tv.reload();
  await tv.waitForFunction(() => window.ShoreQA?.ready());
  check(JSON.stringify(await tv.evaluate(() => ShoreQA.state().home)) === JSON.stringify([...favorites].reverse()), 'Restored arrangement survives reload');
  check((await tv.evaluate(() => ShoreQA.state().layout)) === 'dock', 'Restored appearance applied');
  check(await tv.evaluate(() => localStorage.getItem('__native_boot') === 'false'), 'Restore leaves startup unchanged');
  await tv.evaluate(() => { ShoreQA.scene('settings'); ShoreQA.activate('settings.group.samsung'); ShoreQA.activate('android.backups'); });
  await tv.screenshot({ path: root + '.local/qa/android-backup-720.png' });
  await tv.evaluate(() => { window.noPicker = true; });
  await tv.evaluate(() => new Promise(resolve => FrameDevice.backupStatus((err, result) => { FrameProfile.backup.filePicker = result.filePicker; resolve(); })));
  await tv.evaluate(() => { FrameRemoteKey('back'); ShoreQA.activate('android.backups'); });
  check((await tv.locator('body').innerText()).includes('Unavailable: this TV has no document picker'), 'Unsupported file picker is explained');
  await tv.evaluate(() => { FrameRemoteKey('down'); FrameRemoteKey('down'); FrameRemoteKey('down'); });
  check((await tv.locator('.appearance .is-focus[data-action]').getAttribute('data-action')) === 'android.backup.restore', 'Unavailable export is skipped by remote focus');
  const callsBeforeDisabled = await tv.evaluate(() => calls.length);
  await tv.evaluate(() => ShoreQA.activate('android.backup.export'));
  check((await tv.evaluate(() => calls.length)) === callsBeforeDisabled, 'Unavailable export cannot dispatch through pointing');
  await tv.evaluate(() => {
    var saved = JSON.parse(localStorage.getItem('cleanframe.android.v1'));
    saved.mode = '5'; localStorage.setItem('cleanframe.android.v1', JSON.stringify(saved));
  });
  await tv.reload();
  await tv.waitForFunction(() => window.ShoreQA?.ready() && ShoreQA.state().rotation.active);
  check(await tv.evaluate(() => ShoreQA.state().rotation.active), 'Wallpaper timer is active before native pause');
  await tv.evaluate(() => FrameVisibility(false));
  check(!(await tv.evaluate(() => ShoreQA.state().rotation.active)), 'Native pause stops wallpaper timer');
  await tv.evaluate(() => FrameVisibility(true));
  check(await tv.evaluate(() => ShoreQA.state().rotation.active), 'Native resume restarts wallpaper timer');
  // Unknown/late replies cannot invoke callbacks or corrupt state.
  await tv.evaluate(() => { CleanFrameAndroidResult('__proto__', {}); CleanFrameAndroidResult('999999', {}); });
  check(errors.length === 0, 'No browser errors: ' + errors.join(', '));
  await tv.screenshot({ path: root + '.local/qa/android-list-720.png' });
  await tv.close();
  const dense = await preview.browser.newPage({ viewport: { width: 960, height: 540 }, deviceScaleFactor: 2, isMobile: true });
  await dense.goto(preview.baseURL + '/home/?platform=android');
  await dense.waitForFunction(() => window.ShoreQA?.ready());
  const bounds = await dense.locator('#stage').boundingBox();
  check(bounds.width <= 960 && bounds.height <= 540, 'High-density viewport shows the whole stage');
  await dense.close();
  console.log(count + ' Android checks passed. Native API is mocked; physical TV validation remains required.');
} finally {
  await preview.close();
}
