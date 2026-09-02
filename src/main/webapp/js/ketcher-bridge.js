(function () {
  var activeSessionId = null;
  var changeMonitorTimer = null;
  var lastReportedMolfile = null;
  var monitorBusy = false;

  function post(type, payload) {
    if (window.parent && window.parent !== window) {
      window.parent.postMessage(Object.assign({
        source: 'chemvantage-ketcher-bridge',
        type: type,
        sessionId: activeSessionId
      }, payload || {}), '*');
    }
  }

  function isEmptyTemplate(molfile) {
    if (!molfile || !String(molfile).trim()) return true;
    var text = String(molfile);
    if (/\n\s*0\s+0\s+0\s+0\s+0\s+0\s+0\s+0\s+0\s+0999\s+V2000/.test(text)) return true;
    if (/M\s+V30\s+COUNTS\s+0\s+0\s+0\s+0\s+0/.test(text)) return true;
    return false;
  }

  function wait(ms) {
    return new Promise(function (resolve) {
      window.setTimeout(resolve, ms);
    });
  }

  async function readMolfileWithRetry(attempts, delayMs) {
    var molfile = '';
    for (var i = 0; i < attempts; i++) {
      molfile = await Promise.resolve(window.ketcher.getMolfile());
      if (!isEmptyTemplate(molfile)) return molfile || '';
      await wait(delayMs);
    }
    return molfile || '';
  }

  async function applyMolfileRobustly(molfile) {
    var target = molfile || '';
    if (!target.trim()) {
      await Promise.resolve(window.ketcher.setMolecule(''));
      return '';
    }

    await Promise.resolve(window.ketcher.setMolecule(''));
    await wait(80);
    await Promise.resolve(window.ketcher.setMolecule(target));
    await wait(180);

    var loaded = await readMolfileWithRetry(4, 180);
    if (!isEmptyTemplate(loaded)) return loaded;

    await Promise.resolve(window.ketcher.setMolecule(target));
    await wait(220);
    loaded = await readMolfileWithRetry(5, 220);
    if (!isEmptyTemplate(loaded)) return loaded;

    await Promise.resolve(window.ketcher.setMolecule(''));
    await wait(260);
    await Promise.resolve(window.ketcher.setMolecule(target));
    await wait(480);
    return await readMolfileWithRetry(6, 260);
  }

  async function warmupEditor() {
    await Promise.resolve(window.ketcher.getMolfile());
    await wait(120);
    await Promise.resolve(window.ketcher.getMolfile());
  }

  async function pollAndReportChanges() {
    if (monitorBusy) return;
    monitorBusy = true;
    try {
      var current = await Promise.resolve(window.ketcher.getMolfile());
      current = current || '';
      if (lastReportedMolfile === null || current !== lastReportedMolfile) {
        lastReportedMolfile = current;
        post('structureChanged', { molfile: current, empty: isEmptyTemplate(current) });
      }
    } catch (err) {
      // Ignore transient polling errors; explicit requests still report errors.
    } finally {
      monitorBusy = false;
    }
  }

  function startChangeMonitor() {
    if (changeMonitorTimer) return;
    changeMonitorTimer = window.setInterval(function () {
      pollAndReportChanges();
    }, 700);
  }

  function whenKetcherReady(callback, attempts) {
    if (window.ketcher && typeof window.ketcher.getMolfile === 'function' && typeof window.ketcher.setMolecule === 'function') {
      callback();
      return;
    }
    if (attempts <= 0) {
      post('error', { message: 'Ketcher did not initialize in time.' });
      return;
    }
    window.setTimeout(function () {
      whenKetcherReady(callback, attempts - 1);
    }, 250);
  }

  window.addEventListener('message', function (event) {
    var data = event.data || {};
    if (data.source !== 'chemvantage-ketcher-host') return;
    if (data.sessionId) activeSessionId = data.sessionId;

    whenKetcherReady(async function () {
      try {
        startChangeMonitor();
        if (data.type === 'readyCheck') {
          await warmupEditor();
          await pollAndReportChanges();
          post('ready', { requestId: data.requestId || null });
        } else if (data.type === 'setMolfile') {
          var loadedMolfile = await applyMolfileRobustly(data.molfile || '');
          lastReportedMolfile = loadedMolfile || '';
          post('structureChanged', { molfile: loadedMolfile || '', empty: isEmptyTemplate(loadedMolfile || '') });
          post('setMolfileResult', { requestId: data.requestId || null, molfile: loadedMolfile || '' });
        } else if (data.type === 'setStructure') {
          var loadedStructure = await applyMolfileRobustly(data.structure || '');
          lastReportedMolfile = loadedStructure || '';
          post('structureChanged', { molfile: loadedStructure || '', empty: isEmptyTemplate(loadedStructure || '') });
          post('setStructureResult', { requestId: data.requestId || null, molfile: loadedStructure || '' });
        } else if (data.type === 'getMolfile') {
          var molfile = await readMolfileWithRetry(5, 140);
          lastReportedMolfile = molfile || '';
          post('molfile', { requestId: data.requestId || null, molfile: molfile || '' });
        } else if (data.type === 'clear') {
          await Promise.resolve(window.ketcher.setMolecule(''));
          lastReportedMolfile = '';
          post('structureChanged', { molfile: '', empty: true });
          post('setMolfileResult', { requestId: data.requestId || null });
        }
      } catch (err) {
        post('error', { requestId: data.requestId || null, message: err && err.message ? err.message : String(err) });
      }
    }, 120);
  });
})();
