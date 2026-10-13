function cell(row,text) { const td = document.createElement('td'); td.textContent = text; row.append(td); return td; }
function renderPlan() {
  const plan = SchemaBridgeState.migration.plan;
  document.getElementById('reportSection').hidden = false; document.getElementById('reportMessages').replaceChildren(); document.getElementById('reportRows').replaceChildren();
  document.getElementById('outputActions').hidden = false; document.getElementById('reviewNext').disabled = false; document.getElementById('reviewStatus').textContent = 'Review complete. Continue when you are ready to export or run the plan.';
  document.getElementById('outputTitle').textContent = plan.orderedScript ? 'Download converted script' : 'Ready to export or execute';
  document.getElementById('outputDescription').textContent = plan.orderedScript ? 'This script preserves statement order and is provided as a review package.' : 'Choose an export or run the reviewed plan against the target database.';
  document.getElementById('reportTitle').textContent = plan.orderedScript ? 'Review the script in execution order' : 'Review every table';
  document.getElementById('matchAll').hidden = !!plan.orderedScript; document.getElementById('tableReport').hidden = !!plan.orderedScript;
  document.getElementById('statementSection').hidden = !plan.orderedScript; document.getElementById('statementRows').replaceChildren();
  document.getElementById('previewTitle').textContent = plan.orderedScript ? 'Preview full converted script in original order' : 'Preview converted table definitions';
  document.getElementById('download').textContent = plan.orderedScript ? 'Download ordered review draft' : 'Download SQL package';
  document.getElementById('executionNote').textContent = plan.orderedScript ? 'This lifecycle script includes operations whose order matters. Download preserves all converted statements and marks source errors in place. Per-table actions and automatic execution are disabled; no target changes are made.' : 'Run selected plan writes to the inspected target database. DDL and previously committed tables can remain after failure. Prepared plans are retained for 30 days. Job history remains until deleted.';
  if (plan.orderedScript) {
    const statements = plan.statements || [], errors = statements.filter(s => ['ERROR','UNSUPPORTED'].includes(s.status)).length;
    document.getElementById('statementSummary').textContent = `${statements.length} statements · ${statements.length - errors} converted (including review warnings) · ${errors} errors / unsupported statements. No statements are silently omitted.`;
    for (const statement of statements) { const row = document.createElement('tr'); cell(row, statement.number); cell(row, statement.kind); cell(row, statement.status); cell(row, statement.messages.join(' · ') || 'Converted. See the full preview below.'); document.getElementById('statementRows').append(row); }
  }
  for (const [messages, kind] of [[plan.issues,'issue'],[plan.warnings,'warning']]) for (const text of messages) { const p = document.createElement('p'); p.className = 'message ' + kind; p.textContent = text; document.getElementById('reportMessages').append(p); }
  const maxRows = plan.options && plan.options.maxRows;
  if (maxRows && !plan.orderedScript) {
    const totalRows = plan.tables.reduce((sum,t) => sum + (t.rows||0), 0);
    const hasUnextracted = plan.tables.some(t => (t.rows||0)===0 && t.estimatedRows!=null);
    document.getElementById('quotaSummary').textContent = plan.prepared===false
      ? `Row budget: ${maxRows.toLocaleString()} total across this plan. Per-table counts are not yet extracted.`
      : `Row budget used: ${totalRows.toLocaleString()} / ${maxRows.toLocaleString()} (${((totalRows/maxRows)*100).toFixed(1)}%)${hasUnextracted?' — some tables show an estimate only':''}.`;
  } else document.getElementById('quotaSummary').textContent = '';
  const findingKindLabel = {MISSING_TARGET_COLUMN:'Missing column',EXTRA_TARGET_COLUMN:'Extra column',TYPE_MISMATCH:'Type mismatch',NULLABILITY_MISMATCH:'Nullability',IDENTITY_MISMATCH:'Identity',DEFAULT_MISMATCH:'Default value',KEY_OR_INDEX_MISMATCH:'Keys/indexes',ORACLE_IDENTITY_DML_BLOCKED:'Identity + DML only'};
  for (const table of plan.tables) {
    const tr = document.createElement('tr'); cell(tr,table.table); const status = cell(tr,''); status.append(statusBadge('table', table.status));
    const rowSummary=plan.prepared===false?(table.estimatedRows==null?'Not extracted; estimate unavailable':`~${table.estimatedRows.toLocaleString()} estimated (not extracted)`):`${table.rows.toLocaleString()} extracted`;
    const rowCell = cell(tr, rowSummary);
    if (maxRows && table.rows) { const share = document.createElement('div'); share.className='muted'; share.textContent = `${((table.rows/maxRows)*100).toFixed(2)}% of budget`; rowCell.append(share); }
    const findings = cell(tr, ''); findings.textContent = '';
    if (table.dependsOn && table.dependsOn.length) { const dep = document.createElement('div'); dep.className='muted'; dep.textContent = 'Depends on: ' + table.dependsOn.join(', '); findings.append(dep); }
    if (table.columnComparison && table.columnComparison.length) {
      const list = document.createElement('ul'); list.className='column-comparison';
      for (const d of table.columnComparison) {
        const item = document.createElement('li');
        const label = findingKindLabel[d.kind] || d.kind;
        const strong = document.createElement('strong'); strong.textContent = d.column ? `${d.column} — ${label}` : label; item.append(strong);
        if (d.expected!=null || d.actual!=null) item.append(document.createTextNode(` (expected ${d.expected!=null?d.expected:'—'}, target ${d.actual!=null?d.actual:'—'})`));
        item.append(document.createElement('br'));
        const correction = document.createElement('span'); correction.className='muted'; correction.textContent = d.correction; item.append(correction);
        list.append(item);
      }
      findings.append(list);
    } else if (table.differences.length || table.warnings.length) {
      const p = document.createElement('div'); p.textContent = [...table.differences,...table.warnings].join(' · '); findings.append(p);
    } else { const p = document.createElement('div'); p.textContent = table.status === 'MATCH' ? 'Definitions match. DML-only is available.' : 'Ready for selected action.'; findings.append(p); }
    const td = cell(tr,''); const select = document.createElement('select'); select.dataset.table = table.table;
    for(const action of table.allowedActions) { const option = document.createElement('option'); option.value = action; option.textContent = ({CREATE_AND_LOAD:'Create + load',DML_ONLY:'DML only',SKIP:'Skip table'})[action]; select.append(option); }
    select.value = table.status === 'MATCH' || table.status === 'MISMATCH' ? 'SKIP' : 'CREATE_AND_LOAD'; td.append(select); document.getElementById('reportRows').append(tr);
  }
  document.getElementById('execute').hidden = !plan.targetChecked || plan.orderedScript; document.getElementById('execute').disabled = plan.issues.length > 0 || plan.orderedScript || plan.prepared===false; document.getElementById('download').disabled = plan.prepared===false; document.getElementById('prepareData').hidden=plan.prepared!==false;
}
function actions() { const plan = SchemaBridgeState.migration.plan; return plan?.orderedScript ? {} : Object.fromEntries(Array.from(document.getElementById('reportRows').querySelectorAll('select')).map(select => [select.dataset.table,select.value])); }
document.getElementById('matchAll').addEventListener('click', () => document.getElementById('reportRows').querySelectorAll('select').forEach(select => { if (Array.from(select.options).some(o=>o.value==='DML_ONLY')) select.value='DML_ONLY'; }));
function clearDownloadLink(){const panel=document.getElementById('downloadHelp'),link=document.getElementById('downloadLink');panel.hidden=true;link.hidden=true;link.removeAttribute('href');document.getElementById('downloadStatus').textContent='';}
document.getElementById('reportRows').addEventListener('change',event=>{if(event.target.matches('select[data-table]'))clearDownloadLink();});
document.getElementById('matchAll').addEventListener('click',clearDownloadLink);
document.getElementById('reportDownload').addEventListener('click',()=>save(new Blob([JSON.stringify({report:SchemaBridgeState.migration.plan,actions:actions()},null,2)],{type:'application/json'}),'compatibility-report.json'));
document.getElementById('download').addEventListener('click',async()=>{try{
  const plan = SchemaBridgeState.migration.plan;
  document.getElementById('download').disabled=true;clearDownloadLink();document.getElementById('downloadStatus').textContent='Creating a secure, short-lived download link…';document.getElementById('downloadHelp').hidden=false;
  const ticket=await(await api('/plans/'+plan.id+'/download-ticket',actions())).json();
  const link=document.getElementById('downloadLink');link.href=ticket.url;link.textContent=plan.orderedScript?'Save review package':'Save migration ZIP';link.hidden=false;document.getElementById('downloadStatus').textContent='Download link ready. It expires in two minutes; click the link to save the ZIP.';
}catch(e){clearDownloadLink();error(e.message);}finally{document.getElementById('download').disabled=false;}});
document.getElementById('downloadLink').addEventListener('click',()=>{setTimeout(()=>{clearDownloadLink();document.getElementById('downloadStatus').textContent='Download requested. If it did not start, prepare a fresh link.';document.getElementById('downloadHelp').hidden=false;},0);});
document.getElementById('execute').addEventListener('click',async()=>{ try { document.getElementById('execute').disabled=true; SchemaBridgeState.migration.finalJob=await(await api('/plans/'+SchemaBridgeState.migration.plan.id+'/execute',{target:connection('target'),actions:actions()})).json(); document.getElementById('jobSection').hidden=false; renderJob(); pollJob(); } catch(e){error(e.message);document.getElementById('execute').disabled=false;} });
