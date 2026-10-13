const FLOW_QUICK_START = [
  {value:'files-download', label:'SQL → Download', hint:'Convert scripts into a review package.'},
  {value:'files-db', label:'SQL → Database', hint:'Convert scripts and run a selected plan.'},
  {value:'db-download', label:'Database → Download', hint:'Export selected tables and their data.'},
  {value:'db-db', label:'Database → Database', hint:'Move selected tables to another database.'}
];
function renderQuickStart(){
  const host = document.getElementById('dashboardQuickStart'); host.replaceChildren();
  for (const item of FLOW_QUICK_START) {
    const card = document.createElement('button'); card.type = 'button'; card.className = 'dashboard-qcard'; card.dataset.flow = item.value;
    const title = document.createElement('b'); title.textContent = item.label; const hint = document.createElement('span'); hint.textContent = item.hint;
    card.append(title, hint);
    card.addEventListener('click', () => {
      const radio = document.querySelector(`input[name="flow"][value="${item.value}"]`);
      if (radio) { radio.checked = true; radio.dispatchEvent(new Event('change', {bubbles:true})); }
      if (typeof navigateToPage === 'function') navigateToPage('migration');
    });
    host.append(card);
  }
}
async function renderDashboard(){
  renderQuickStart();
  try {
    const jobs = await (await api('/jobs', undefined, 'GET')).json();
    const body = document.getElementById('dashboardActivity'); body.replaceChildren();
    for (const job of jobs.slice(0, 8)) {
      const tr = document.createElement('tr');
      const sourceCell = document.createElement('td'); sourceCell.textContent = job.tables.map(t=>t.table).slice(0,2).join(', ') || '—';
      const targetCell = document.createElement('td'); targetCell.textContent = job.id.slice(0,8);
      const statusCell = document.createElement('td'); statusCell.append(statusBadge('job', job.revert?.state || job.state));
      const rowsCell = document.createElement('td'); rowsCell.textContent = Number(job.progress?.rowsCommitted || 0).toLocaleString();
      tr.append(sourceCell, targetCell, statusCell, rowsCell); body.append(tr);
    }
    if (!jobs.length) { body.replaceChildren(); const tr = document.createElement('tr'); const td = document.createElement('td'); td.colSpan = 4; td.className = 'muted'; td.textContent = 'No saved migrations yet.'; tr.append(td); body.append(tr); }
  } catch (e) { error(e.message); }
  try {
    const s = await (await api('/storage', undefined, 'GET')).json();
    const metrics = document.getElementById('dashboardStorage'); metrics.replaceChildren();
    for (const [label, value] of [['Staged data', formatBytes(s.stagedBytes)], ['Free disk', formatBytes(s.freeDiskBytes)], ['Reclaimable', formatBytes(s.reclaimableBytes)], ['Saved plans', String(s.plans.length)]]) {
      const div = document.createElement('div'); const dt = document.createElement('dt'); dt.textContent = label; const dd = document.createElement('dd'); dd.textContent = value; div.append(dt, dd); metrics.append(div);
    }
  } catch (e) { error(e.message); }
}
document.getElementById('dashboardRefresh').addEventListener('click', renderDashboard);
