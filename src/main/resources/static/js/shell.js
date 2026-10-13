const PAGE_IDS = ['dashboard','migration','history','storage','settings'];
let activePage = 'dashboard';
function currentPageId(){ return activePage; }
async function navigateToPage(pageId){
  if (!PAGE_IDS.includes(pageId)) return;
  const leaving = activePage;
  if (leaving === 'migration' && pageId !== 'migration') {
    clearTimeout(SchemaBridgeState.migration.polling); SchemaBridgeState.migration.polling = null;
    clearTimeout(SchemaBridgeState.migration.preparationPoll); SchemaBridgeState.migration.preparationPoll = null;
  }
  activePage = pageId;
  for (const id of PAGE_IDS) { const el = document.getElementById(id + 'Page'); if (el) el.hidden = id !== pageId; }
  document.getElementById('migrationSteps').hidden = pageId !== 'migration';
  document.querySelectorAll('[data-nav-page]').forEach(link => {
    const active = link.dataset.navPage === pageId;
    link.classList.toggle('active', active);
    if (active) link.setAttribute('aria-current','page'); else link.removeAttribute('aria-current');
  });
  if (pageId === 'migration' && SchemaBridgeState.migration.finalJob) {
    try {
      SchemaBridgeState.migration.finalJob = await (await api('/jobs/'+SchemaBridgeState.migration.finalJob.id, undefined, 'GET')).json();
      renderJob();
      if (jobBusy(SchemaBridgeState.migration.finalJob)) pollJob();
    } catch (e) { error(e.message); }
  }
  if (pageId === 'dashboard') await renderDashboard();
  if (pageId === 'history') await refreshHistory();
  if (pageId === 'storage') await refreshStorage();
}
document.querySelectorAll('[data-nav-page]').forEach(link => link.addEventListener('click', (event) => { event.preventDefault(); navigateToPage(link.dataset.navPage); }));
