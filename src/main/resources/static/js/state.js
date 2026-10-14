const SchemaBridgeState = {
  migration: {
    plan: null, finalJob: null, polling: null, errorTimer: null, lastRequest: null,
    preparationId: null, preparationPoll: null, requestVersion: 0,
    currentStep: 1, maxStep: 1,
    databaseNames: {source: {}, target: {}},
    catalogSelection: {source:null, target:null},
    catalogLoading: false, catalogLoaded: false, pendingRevert: null
  }
};
function invalidate() {
  const m = SchemaBridgeState.migration;
  if (typeof clearRevertReview === 'function') clearRevertReview();
  m.requestVersion++; m.lastRequest = null; m.plan = null;
  if (document.getElementById('reportSection')) document.getElementById('reportSection').hidden = true;
  if (document.getElementById('outputActions')) document.getElementById('outputActions').hidden = true;
  if (document.getElementById('reviewNext')) document.getElementById('reviewNext').disabled = true;
  if (document.getElementById('reviewStatus')) document.getElementById('reviewStatus').textContent = 'Analysis is required before continuing.';
  if (document.getElementById('configStatus')) document.getElementById('configStatus').textContent = 'Settings changed. Analyse to refresh the compatibility report.';
}
