// Browser contract tests for the actual Android adapter. No TV contacted.
import assert from 'node:assert/strict';
import { mkdirSync } from 'node:fs';
import { openPreview, root } from './browser.mjs';

const favorites = ['netflix', 'hbomax', 'youtube', 'paramount', 'appletv', 'primevideo', 'peacock', 'disneyplus'];
const labels = ['Netflix', 'HBO Max', 'YouTube', 'Paramount+', 'Apple TV', 'Prime Video', 'Peacock', 'Disney+'];
const packages = ['com.netflix.ninja', 'com.wbd.stream', 'com.google.android.youtube.tv', 'com.cbs.ott', 'com.apple.atve.androidtv.appletv', 'com.amazon.amazonvideo.livingroom', 'com.peacocktv.peacockandroid', 'com.disney.disneyplus'];
const preview = await openPreview({ publicFonts: true });
let count = 0;
function check(value, message) { assert.ok(value, message); count++; }
try {
  const page = await preview.browser.newPage({ viewport: { width: 1920, height: 1080 } });
  const errors = [];
  page.on('pageerror', error => errors.push(String(error)));
  await page.goto(preview.baseURL + '/home/?platform=android');
  await page.waitForFunction(() => window.ShoreQA?.ready());
  check(JSON.stringify(await page.evaluate(() => ShoreQA.state().home)) === JSON.stringify(favorites), 'All eight requested favorites in order');
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
    window.CleanFrameAndroid = { request(method, id, target) {
      window.calls.push({ method, target });
      const response = method === 'apps' ? { apps: packages.map((pkg, i) => ({ appId: pkg, name: labels[i], brand: favorites[i], mark: !['paramount', 'peacock'].includes(favorites[i]), icon: '' })) }
        : window.failLaunch ? { error: 'This app is not installed.' } : {};
      setTimeout(() => window.CleanFrameAndroidResult(id, response), 0);
    } };
  }, { favorites, packages, labels });
  await tv.goto(preview.baseURL + '/home/');
  await tv.waitForFunction(() => window.ShoreQA?.ready() && ShoreQA.state().catalog.loaded);
  check((await tv.evaluate(() => ShoreQA.state().catalog.ids.length)) === 8, 'Native catalogue discovery');
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
  console.log(count + ' Android checks passed. Native API is mocked; physical TV validation remains required.');
} finally {
  await preview.close();
}
