/* Passive experiment card. No account credentials, network requests or vacuum commands. */
(function () {
  'use strict';
  if (!window.FrameProfile || !window.FrameProfile.backup) return;
  var nativeOpened=false;
  var pollTimer = 0, polling = false, command = null, controlsReady = false;
  var card = document.createElement('aside'), data = null, timer = 0, visible = true, onHome = false;
  var demo = !window.CleanFrameAndroid && /(?:\?|&)vacuumDemo=cleaning(?:&|$)/.test(window.location.search);
  var labels = { cleaning: 'Cleaning', docked: 'Docked', charging: 'Charging', returning: 'Returning to dock', paused: 'Paused', idle: 'Idle', error: 'Needs attention', unknown: 'Status unavailable' };
  card.className = 'vacuum-status-content'; card.hidden = true; card.setAttribute('aria-label', 'Vacuum status');
  function line(className, text) { var el = document.createElement('div'); el.className = className; el.textContent = text; return el; }
  function render() {
    clearTimeout(timer);
    card.hidden = !visible || !onHome;
    if (card.hidden) return;
    var age = data ? Date.now() - data.receivedAt : Infinity, fresh = age >= -5000 && age <= 90000;
    var state = fresh ? data.state : 'unknown', text = data ? labels[state] : 'Not connected';
    if (fresh && data.battery !== null) text += ' · ' + data.battery + '%';
    while (card.firstChild) card.removeChild(card.firstChild);

    card.appendChild(line('vacuum-status', text));
    var note = !data ? 'Connect your Eufy account on this TV to see your vacuum here.' : demo ? 'Preview · sample data' : !fresh ? 'No recent status received' : 'Cloud snapshot · checked just now';
    if (!demo && fresh && age >= 15000) note = 'Cloud snapshot · checked ' + Math.floor(age / 1000) + 's ago';
    if (!demo && fresh && data.errorCode !== null) note = data.errorCode === 6011 ? 'Dock clean-water tank empty. Refill it before cleaning. Pause and Return to dock remain available.' : 'Reported error ' + data.errorCode;
    card.appendChild(line('vacuum-note', note));
    var messages={queued:'Connecting…',claimed:'Sending command…',sending:'Sending command…',connection_failed:'Connection failed. Close this screen and reopen to retry.',sent:'Sent. Waiting for a matching state change…',confirmed:'State change confirmed.',failed:'Command failed. Nothing was confirmed.',unconfirmed:'Could not confirm a state change. Check the Eufy app before trying again.'};
    card.appendChild(line('vacuum-command-note',command&&messages[command.phase] ? messages[command.phase] : controlsReady ? 'Controls connected.' : 'Controls unavailable: TV connection offline.'));
    var buttons=document.querySelectorAll('[data-action^="vacuum."]');
    Array.prototype.forEach.call(buttons,function(button){var action=button.getAttribute('data-action');if(action!=='vacuum.back')button.setAttribute('aria-disabled',controlsReady?'false':'true');});
    timer = setTimeout(render, 15000);
  }
  window.FrameVacuumStatus = function (value) {
    if (!value || (value.configured !== true && value.configured !== false)) { render(); return; }
    if (value.configured === false) { data = null; render(); return; }
    controlsReady=value.controlsReady===true;command=value.command||null;
    var battery = typeof value.battery === 'number' && isFinite(value.battery) && value.battery >= 0 && value.battery <= 100 ? Math.round(value.battery) : null;
    var code = typeof value.errorCode === 'number' && isFinite(value.errorCode) && value.errorCode > 0 && value.errorCode % 1 === 0 && value.errorCode <= 2147483647 ? value.errorCode : null;
    data = { state: Object.prototype.hasOwnProperty.call(labels, value.state) ? value.state : 'unknown', battery: battery, errorCode: code,
      receivedAt: typeof value.receivedAt === 'number' && isFinite(value.receivedAt) ? value.receivedAt : 0 };
    if (code !== null) data.state = 'error';
    render();
  };
  function syncNative(){var want=visible&&onHome;if(window.CleanFrameAndroid&&nativeOpened!==want){nativeOpened=want;FrameDevice.vacuumView(want?'open':'close',function(){});}}
  function poll() {
    syncNative();
    clearTimeout(pollTimer);
    if (!window.CleanFrameAndroid || !visible || !onHome || polling) return;
    polling = true;
    window.FrameDevice.vacuumStatus(function (err, value) {
      polling = false;
      if (!err) window.FrameVacuumStatus(value);
      if (visible && onHome) pollTimer = setTimeout(poll, 2000);
    });
  }
  window.FrameVacuumRefresh = poll;
  window.FrameVacuumVisibility = function (value) { visible = !!value; syncNative(); render(); if (visible) poll(); else clearTimeout(pollTimer); };
  window.FrameVacuumView = function (value) { onHome = !!value; syncNative(); var host = document.getElementById('vacuumStatusPanel'); if (onHome && host && card.parentNode !== host) host.appendChild(card); render(); if (onHome && !pollTimer) poll(); else if (!onHome) { clearTimeout(pollTimer); pollTimer = 0; } };
  if (window.CleanFrameAndroid) poll();
  if (demo) { window.FrameVacuumStatus({ configured: true, state: 'cleaning', battery: 72, receivedAt: Date.now() }); window.FrameOpenVacuum(); }
}());
