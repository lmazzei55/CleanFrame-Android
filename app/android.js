/* Android-only profile and narrow native bridge. Preview never fakes device success. */
(function () {
  'use strict';
  if (!window.CleanFrameAndroid && !/(?:\?|&)platform=android(?:&|$)/.test(window.location.search)) return;
  var pending = {}, sequence = 0;
  var favorites = [
    ['netflix', 'Netflix', true], ['hbomax', 'HBO Max', true],
    ['youtube', 'YouTube', true], ['paramount', 'Paramount+', false],
    ['appletv', 'Apple TV', true], ['primevideo', 'Prime Video', true],
    ['peacock', 'Peacock', false], ['disneyplus', 'Disney+', true], ['tubi', 'Tubi', true]
  ];
  window.FrameProfile = {
    storageKey: 'cleanframe.android.v1', systemName: 'Android TV', homeLabel: 'TV Home',
    homeDescription: 'Open the original TCL home screen', utilities: ['apps', 'settings'], defaultHome: true,
    favorites: favorites.map(function (item) {
      return { id: item[0], name: item[1], mark: item[2], appId: 'brand.' + item[0] };
    })
  };
  function request(method, target, done) {
    if (!window.CleanFrameAndroid) {
      done({ message: 'Open the Android app on your TV to use this control.', detail: 'This browser preview cannot control the TV.' }); return;
    }
    var id = String(++sequence), timer;
    function finish(err, value) { clearTimeout(timer); delete pending[id]; done(err, value); }
    pending[id] = finish;
    timer = setTimeout(function () { finish({ message: 'The TV did not respond. Please try again.' }); }, 8000);
    try { window.CleanFrameAndroid.request(method, id, target || ''); }
    catch (err) { finish({ message: 'The TV could not complete this action.', detail: String(err.message || err) }); }
  }
  window.CleanFrameAndroidResult = function (id, response) {
    if (!Object.prototype.hasOwnProperty.call(pending, id)) return;
    pending[id](response.error ? { message: response.error } : null, response.apps);
  };
  window.FrameAndroidDevice = {
    launchApp: function (id, done) { request('launch', id, done); },
    launch: function (brand, done) { request('launch', 'brand.' + brand, done); },
    apps: function (done) { request('apps', '', done); },
    home: function (done) { request('home', '', done); },
    settings: function (done) { request('settings', '', done); },
    defaultHome: function (done) { request('defaultHome', '', done); },
    art: function (done) { done({ message: 'Samsung Art Mode is not available on Android TV.' }); }
  };
}());
