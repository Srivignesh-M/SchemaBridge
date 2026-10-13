'use strict';
const $ = id => document.getElementById(id);
function revertBusy(job){return !!job?.revert && ['QUEUED','RUNNING'].includes(job.revert.state);}
function jobBusy(job){return ['QUEUED','RUNNING'].includes(job.state)||revertBusy(job);}
function clearRevertReview(){SchemaBridgeState.migration.pendingRevert=null;$('revertReview').hidden=true;$('acknowledgeRevert').checked=false;$('confirmRevert').disabled=true;}
function renderRevert(){
  const r=SchemaBridgeState.migration.finalJob?.revert;
  $('revertSection').hidden=!SchemaBridgeState.migration.finalJob||(SchemaBridgeState.migration.finalJob.state!=='SUCCEEDED'&&!r);
  if(SchemaBridgeState.migration.pendingRevert&&(SchemaBridgeState.migration.pendingRevert.jobId!==SchemaBridgeState.migration.finalJob?.id||revertBusy(SchemaBridgeState.migration.finalJob)))clearRevertReview();
  const canReview=SchemaBridgeState.migration.finalJob?.state==='SUCCEEDED'&&(!r||['FAILED','PARTIAL'].includes(r.state));
  $('reviewRevert').hidden=!canReview;$('reviewRevert').disabled=false;$('cancelRevert').hidden=!revertBusy(SchemaBridgeState.migration.finalJob);
  $('revertState').textContent=r?`${r.state}: ${r.message} ${r.progress.rowsCommitted.toLocaleString()} rows removed by committed revert work.`:'Select Review revert to check this job against the target before removing anything.';
  $('revertResults').replaceChildren();
  for(const table of r?.tables||[]){const item=document.createElement('li');item.textContent=`${table.table}: ${table.status} · ${table.rows.toLocaleString()} original rows · ${table.action==='DROP_TABLE'?'drop migration-created table':'remove transferred rows'}`;$('revertResults').append(item);}
}
$('reviewRevert').addEventListener('click',async()=>{
  const id=SchemaBridgeState.migration.finalJob.id;clearRevertReview();$('reviewRevert').disabled=true;$('revertState').textContent='Checking target data and dependencies…';
  try{
    const review=await(await api('/jobs/'+id+'/revert-preview',connection('target'))).json();
    if(SchemaBridgeState.migration.finalJob?.id!==id)return;SchemaBridgeState.migration.pendingRevert=review;
    $('revertTarget').textContent=`Job ${review.jobId} · Target: ${review.target}`;
    $('revertExpiry').textContent='Review expires at '+new Date(review.expiresAt).toLocaleTimeString()+'. The target is checked again when you confirm.';
    $('revertTables').replaceChildren();
    for(const table of review.tables){const item=document.createElement('li');item.textContent=`${table.table}: ${table.action==='DROP_TABLE'?'remove transferred rows and drop table':'remove only transferred rows'} · ${table.rows.toLocaleString()} original rows · ${table.status}`;$('revertTables').append(item);}
    $('revertReview').hidden=false;$('revertState').textContent='Review the removal below. Nothing has been removed.';$('revertReview').scrollIntoView({behavior:'smooth',block:'center'});
  }catch(e){$('revertState').textContent=e.message;error(e.message);}finally{$('reviewRevert').disabled=false;}
});
$('acknowledgeRevert').addEventListener('change',()=>{$('confirmRevert').disabled=!SchemaBridgeState.migration.pendingRevert||!$('acknowledgeRevert').checked;});
$('dismissRevert').addEventListener('click',()=>{clearRevertReview();renderRevert();});
$('confirmRevert').addEventListener('click',async()=>{
  if(!SchemaBridgeState.migration.pendingRevert||!$('acknowledgeRevert').checked||SchemaBridgeState.migration.pendingRevert.jobId!==SchemaBridgeState.migration.finalJob.id)return;
  const review=SchemaBridgeState.migration.pendingRevert;$('confirmRevert').disabled=true;
  try{SchemaBridgeState.migration.finalJob=await(await api('/jobs/'+review.jobId+'/revert',{target:connection('target'),token:review.token})).json();clearRevertReview();renderJob();pollJob();}
  catch(e){clearRevertReview();$('revertState').textContent=e.message;error(e.message);}
});
$('cancelRevert').addEventListener('click',async()=>{try{SchemaBridgeState.migration.finalJob=await(await api('/jobs/'+SchemaBridgeState.migration.finalJob.id+'/cancel',{})).json();renderJob();}catch(e){error(e.message);}});
const flow = () => document.querySelector('input[name="flow"]:checked').value;
const usesDatabase = () => flow().startsWith('db-');
const writesDatabase = () => flow().endsWith('-db');
async function loadTables() { $('columnOptions').replaceChildren();fillOptions($('sourceTables'),[]); try { const schema = $('sourceSchemaSelect').value; if (schema) fillOptions($('sourceTables'), await (await api('/tables', {connection:connection('source'),schema})).json()); renderTablePicker(); } catch(e) { renderTablePicker();error(e.message); } }
function goToStep(step, scroll=true) {
  if (step > SchemaBridgeState.migration.maxStep || step < 1) return;
  SchemaBridgeState.migration.currentStep = step;
  document.querySelectorAll('[data-step-page]').forEach(page => page.hidden = Number(page.dataset.stepPage) !== step);
  document.querySelectorAll('[data-step-nav]').forEach(button => {
    const number = Number(button.dataset.stepNav), active = number === step;
    button.classList.toggle('active', active); button.classList.toggle('complete', number < step);
    button.disabled = number > SchemaBridgeState.migration.maxStep;
    if (active) button.setAttribute('aria-current','step'); else button.removeAttribute('aria-current');
  });
  if (scroll) window.scrollTo({top:0,behavior:'smooth'});
}
function updateFlow() {
  $('sourceConnection').hidden = !usesDatabase(); $('tableSelection').hidden = !usesDatabase(); $('filesInput').hidden = usesDatabase(); $('targetConnection').hidden = !writesDatabase();
  for (const prefix of ['source','target']) $(prefix + 'Connection').querySelectorAll('input,select,button').forEach(field => field.disabled = $(prefix + 'Connection').hidden);
  $('openTablePicker').disabled = !usesDatabase() || !$('sourceSchemaSelect').value;
  invalidate();
}
connectionForm('source'); connectionForm('target');
document.querySelectorAll('input[name="flow"]').forEach(input => input.addEventListener('change', updateFlow));
document.querySelectorAll('[data-step-nav]').forEach(button => button.addEventListener('click',()=>goToStep(Number(button.dataset.stepNav))));
document.querySelectorAll('[data-back]').forEach(button => button.addEventListener('click',()=>goToStep(Number(button.dataset.back))));
$('flowNext').addEventListener('click',()=>{SchemaBridgeState.migration.maxStep=Math.max(SchemaBridgeState.migration.maxStep,2);goToStep(2);});
$('reviewNext').addEventListener('click',()=>{if(!SchemaBridgeState.migration.plan)return;SchemaBridgeState.migration.maxStep=Math.max(SchemaBridgeState.migration.maxStep,4);goToStep(4);});
$('startOver').addEventListener('click',()=>{SchemaBridgeState.migration.plan=null;SchemaBridgeState.migration.lastRequest=null;SchemaBridgeState.migration.maxStep=1;$('reportSection').hidden=true;$('outputActions').hidden=true;$('preparationSection').hidden=true;$('jobSection').hidden=true;goToStep(1);});
$('configuration').addEventListener('input', invalidate);
for (const prefix of ['source','target']) $(prefix + 'Dialect').addEventListener('change', () => { if(prefix==='source')$('columnOptions').replaceChildren(); applyDialect(prefix); fillOptions($(prefix + 'SchemaSelect'), []); if(prefix === 'source') {fillOptions($('sourceTables'), []);$('columnOptions').replaceChildren();renderTablePicker();} else $('targetSchema').value = $('targetDialect').value === 'ORACLE' ? 'APP' : 'public'; });
let uploadVersion = 0;
async function loadSqlFiles(input) {
  const selected = Array.from(input.files);
  if (!selected.length) return;
  const version = ++uploadVersion;
  invalidate(); $('sql').value = ''; $('fileNames').textContent = 'Reading files…'; $('analyse').disabled = true;
  $('sql').disabled = true; $('example').disabled = true;
  try {
    const path = file => file.webkitRelativePath || file.name;
    const files = selected.filter(file => /\.(sql|ddl|dml|txt)$/i.test(file.name)).sort((a,b) => path(a).localeCompare(path(b), 'en', {numeric:true}) || path(a).localeCompare(path(b), 'en', {sensitivity:'variant'}));
    if (!files.length) throw new Error('No .sql, .ddl, .dml or .txt files found in the selection.');
    if (files.reduce((size,file) => size + file.size,0) > 10000000) throw new Error('Files exceed 10 MB total. Choose a smaller batch.');
    const parts = [];
    for (const file of files) {
      const text = await file.text();
      if (version !== uploadVersion) return;
      parts.push('-- Source file: ' + path(file).replace(/[\r\n\u2028\u2029]/g,' ') + '\n' + text.replace(/^\uFEFF/,''));
    }
    const combined = parts.join('\n;\n');
    if (new TextEncoder().encode(combined).length > 10000000) throw new Error('Combined SQL exceeds 10 MB including file markers. Choose a smaller batch.');
    $('sql').value = combined;
    $('fileNames').textContent = `${files.length} files loaded; ${selected.length-files.length} other files ignored. Order: ` + files.map(path).join(' → ');
    $(input.id === 'folder' ? 'files' : 'folder').value = '';
  } catch(e) {
    if (version === uploadVersion) { $('fileNames').textContent = 'No files loaded.'; error(e.message); }
  } finally {
    if (version === uploadVersion) { $('analyse').disabled = false; $('sql').disabled = false; $('example').disabled = false; }
  }
}
for (const id of ['files','folder']) $(id).addEventListener('change', () => loadSqlFiles($(id)));
$('example').addEventListener('click', () => { $('sourceDialect').value = 'ORACLE'; $('targetDialect').value = 'POSTGRESQL'; $('sql').value = "CREATE TABLE customers (\n  id NUMBER(10,0) PRIMARY KEY,\n  name VARCHAR2(100) NOT NULL,\n  joined_at DATE\n);\nINSERT INTO customers (id,name,joined_at) VALUES (1,'Anita',TIMESTAMP '2026-01-15 09:30:00');\nINSERT INTO customers (id,name,joined_at) VALUES (2,'Sam',NULL);"; invalidate(); });
$('configuration').addEventListener('submit', async event => {
  event.preventDefault(); $('analyse').disabled = true; $('configStatus').textContent = 'Reading source and comparing target definitions…';
  try {
    if(usesDatabase()&&(!selectedSourceTables().length||selectedSourceTables().length>500))throw new Error('Select between 1 and 500 source tables before analysis.');
    const request = {options:transferOptions(),selections:usesDatabase()?tableSelections():{},metadataOnly:usesDatabase(),sourceDialect:$('sourceDialect').value,targetDialect:$('targetDialect').value,targetSchema:$('targetSchema').value.trim(),sql:usesDatabase()?null:$('sql').value,source:usesDatabase()?connection('source'):null,sourceSchema:usesDatabase()?$('sourceSchemaSelect').value:null,tables:usesDatabase()?Array.from($('sourceTables').selectedOptions).map(o=>o.value):[],includeData:$('includeData').checked,target:writesDatabase()?connection('target'):null};
    SchemaBridgeState.migration.lastRequest=request; const version=SchemaBridgeState.migration.requestVersion; const result = await (await api('/plans', request)).json(); if(version!==SchemaBridgeState.migration.requestVersion){await api('/plans/'+result.id,undefined,'DELETE');return;} SchemaBridgeState.migration.plan=result; renderPlan(); $('preview').textContent = await (await api('/plans/' + SchemaBridgeState.migration.plan.id + '/preview', undefined, 'GET')).text(); $('configStatus').textContent = 'Analysis complete. Review the proposed changes.'; SchemaBridgeState.migration.maxStep=Math.max(SchemaBridgeState.migration.maxStep,3);goToStep(3);
  } catch(e) { error(e.message); $('configStatus').textContent = 'Analysis failed. Check the message and try again.'; } finally { $('analyse').disabled = false; }
});
function cell(row,text) { const td = document.createElement('td'); td.textContent = text; row.append(td); return td; }
function renderPlan() {
  $('reportSection').hidden = false; $('reportMessages').replaceChildren(); $('reportRows').replaceChildren();
  $('outputActions').hidden = false; $('reviewNext').disabled = false; $('reviewStatus').textContent = 'Review complete. Continue when you are ready to export or run the SchemaBridgeState.migration.plan.';
  $('outputTitle').textContent = SchemaBridgeState.migration.plan.orderedScript ? 'Download converted script' : 'Ready to export or execute';
  $('outputDescription').textContent = SchemaBridgeState.migration.plan.orderedScript ? 'This script preserves statement order and is provided as a review package.' : 'Choose an export or run the reviewed SchemaBridgeState.migration.plan against the target database.';
  $('reportTitle').textContent = SchemaBridgeState.migration.plan.orderedScript ? 'Review the script in execution order' : 'Review every table';
  $('matchAll').hidden = !!SchemaBridgeState.migration.plan.orderedScript; $('tableReport').hidden = !!SchemaBridgeState.migration.plan.orderedScript;
  $('statementSection').hidden = !SchemaBridgeState.migration.plan.orderedScript; $('statementRows').replaceChildren();
  $('previewTitle').textContent = SchemaBridgeState.migration.plan.orderedScript ? 'Preview full converted script in original order' : 'Preview converted table definitions';
  $('download').textContent = SchemaBridgeState.migration.plan.orderedScript ? 'Download ordered review draft' : 'Download SQL package';
  $('executionNote').textContent = SchemaBridgeState.migration.plan.orderedScript ? 'This lifecycle script includes operations whose order matters. Download preserves all converted statements and marks source errors in place. Per-table actions and automatic execution are disabled; no target changes are made.' : 'Run selected SchemaBridgeState.migration.plan writes to the inspected target database. DDL and previously committed tables can remain after failure. Prepared plans are retained for 30 days. Job history remains until deleted.';
  if (SchemaBridgeState.migration.plan.orderedScript) {
    const statements = SchemaBridgeState.migration.plan.statements || [], errors = statements.filter(s => ['ERROR','UNSUPPORTED'].includes(s.status)).length;
    $('statementSummary').textContent = `${statements.length} statements · ${statements.length - errors} converted (including review warnings) · ${errors} errors / unsupported statements. No statements are silently omitted.`;
    for (const statement of statements) { const row = document.createElement('tr'); cell(row, statement.number); cell(row, statement.kind); cell(row, statement.status); cell(row, statement.messages.join(' · ') || 'Converted. See the full preview below.'); $('statementRows').append(row); }
  }
  for (const [messages, kind] of [[SchemaBridgeState.migration.plan.issues,'issue'],[SchemaBridgeState.migration.plan.warnings,'warning']]) for (const text of messages) { const p = document.createElement('p'); p.className = 'message ' + kind; p.textContent = text; $('reportMessages').append(p); }
  const maxRows = SchemaBridgeState.migration.plan.options && SchemaBridgeState.migration.plan.options.maxRows;
  if (maxRows && !SchemaBridgeState.migration.plan.orderedScript) {
    const totalRows = SchemaBridgeState.migration.plan.tables.reduce((sum,t) => sum + (t.rows||0), 0);
    const hasUnextracted = SchemaBridgeState.migration.plan.tables.some(t => (t.rows||0)===0 && t.estimatedRows!=null);
    $('quotaSummary').textContent = SchemaBridgeState.migration.plan.prepared===false
      ? `Row budget: ${maxRows.toLocaleString()} total across this SchemaBridgeState.migration.plan. Per-table counts are not yet extracted.`
      : `Row budget used: ${totalRows.toLocaleString()} / ${maxRows.toLocaleString()} (${((totalRows/maxRows)*100).toFixed(1)}%)${hasUnextracted?' — some tables show an estimate only':''}.`;
  } else $('quotaSummary').textContent = '';
  const findingKindLabel = {MISSING_TARGET_COLUMN:'Missing column',EXTRA_TARGET_COLUMN:'Extra column',TYPE_MISMATCH:'Type mismatch',NULLABILITY_MISMATCH:'Nullability',IDENTITY_MISMATCH:'Identity',DEFAULT_MISMATCH:'Default value',KEY_OR_INDEX_MISMATCH:'Keys/indexes',ORACLE_IDENTITY_DML_BLOCKED:'Identity + DML only'};
  for (const table of SchemaBridgeState.migration.plan.tables) {
    const tr = document.createElement('tr'); cell(tr,table.table); const status = cell(tr,''); const badge = document.createElement('span'); badge.className = 'badge ' + table.status; badge.textContent = ({NEW:'New table',MATCH:'Already exists · match',MISMATCH:'Already exists / review',UNCHECKED:'Not inspected'})[table.status]; status.append(badge);
    const rowSummary=SchemaBridgeState.migration.plan.prepared===false?(table.estimatedRows==null?'Not extracted; estimate unavailable':`~${table.estimatedRows.toLocaleString()} estimated (not extracted)`):`${table.rows.toLocaleString()} extracted`;
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
    select.value = table.status === 'MATCH' || table.status === 'MISMATCH' ? 'SKIP' : 'CREATE_AND_LOAD'; td.append(select); $('reportRows').append(tr);
  }
  $('execute').hidden = !SchemaBridgeState.migration.plan.targetChecked || SchemaBridgeState.migration.plan.orderedScript; $('execute').disabled = SchemaBridgeState.migration.plan.issues.length > 0 || SchemaBridgeState.migration.plan.orderedScript || SchemaBridgeState.migration.plan.prepared===false; $('download').disabled = SchemaBridgeState.migration.plan.prepared===false; $('prepareData').hidden=SchemaBridgeState.migration.plan.prepared!==false;
}
function actions() { return SchemaBridgeState.migration.plan?.orderedScript ? {} : Object.fromEntries(Array.from($('reportRows').querySelectorAll('select')).map(select => [select.dataset.table,select.value])); }
$('matchAll').addEventListener('click', () => $('reportRows').querySelectorAll('select').forEach(select => { if (Array.from(select.options).some(o=>o.value==='DML_ONLY')) select.value='DML_ONLY'; }));
function clearDownloadLink(){const panel=$('downloadHelp'),link=$('downloadLink');panel.hidden=true;link.hidden=true;link.removeAttribute('href');$('downloadStatus').textContent='';}
$('reportRows').addEventListener('change',event=>{if(event.target.matches('select[data-table]'))clearDownloadLink();});
$('matchAll').addEventListener('click',clearDownloadLink);
$('reportDownload').addEventListener('click',()=>save(new Blob([JSON.stringify({report:SchemaBridgeState.migration.plan,actions:actions()},null,2)],{type:'application/json'}),'compatibility-report.json'));
$('download').addEventListener('click',async()=>{try{
  $('download').disabled=true;clearDownloadLink();$('downloadStatus').textContent='Creating a secure, short-lived download link…';$('downloadHelp').hidden=false;
  const ticket=await(await api('/plans/'+SchemaBridgeState.migration.plan.id+'/download-ticket',actions())).json();
  const link=$('downloadLink');link.href=ticket.url;link.textContent=SchemaBridgeState.migration.plan.orderedScript?'Save review package':'Save migration ZIP';link.hidden=false;$('downloadStatus').textContent='Download link ready. It expires in two minutes; click the link to save the ZIP.';
}catch(e){clearDownloadLink();error(e.message);}finally{$('download').disabled=false;}});
$('downloadLink').addEventListener('click',()=>{setTimeout(()=>{clearDownloadLink();$('downloadStatus').textContent='Download requested. If it did not start, prepare a fresh link.';$('downloadHelp').hidden=false;},0);});
$('execute').addEventListener('click',async()=>{ try { $('execute').disabled=true; SchemaBridgeState.migration.finalJob=await(await api('/plans/'+SchemaBridgeState.migration.plan.id+'/execute',{target:connection('target'),actions:actions()})).json(); $('jobSection').hidden=false; renderJob(); pollJob(); } catch(e){error(e.message);$('execute').disabled=false;} });
const validationMethodLabel={FINGERPRINT:'Fingerprint (count + SHA-256)',ROW_COUNT:'Row count only',NONE:'No validation'};
function renderJob(){ renderProgress('job',SchemaBridgeState.migration.finalJob.progress);$('cancelJob').hidden=!['RUNNING','QUEUED'].includes(SchemaBridgeState.migration.finalJob.state);$('resumeJob').hidden=!SchemaBridgeState.migration.finalJob.resumable; $('jobState').textContent=SchemaBridgeState.migration.finalJob.state+' — '+SchemaBridgeState.migration.finalJob.message; $('jobRows').replaceChildren();
  for(const table of SchemaBridgeState.migration.finalJob.tables){
    const tr=document.createElement('tr');[table.table,table.action,table.status,table.rows].forEach(value=>cell(tr,value));
    const details=cell(tr,'');
    const v=table.validation;
    if(v){
      const summary=document.createElement('div');
      const scope=v.method==='NONE'?(v.skippedReason||''):`${validationMethodLabel[v.method]||v.method} · ${v.rowsChecked.toLocaleString()} rows checked · ${v.durationSeconds.toFixed(2)}s`;
      summary.textContent=scope; details.append(summary);
    }
    const message=document.createElement('div'); message.className='muted'; message.textContent=table.message; details.append(message);
    $('jobRows').append(tr);
  }
  renderRevert(); }
async function pollJob(){ clearTimeout(SchemaBridgeState.migration.polling); try{SchemaBridgeState.migration.finalJob=await(await api('/jobs/'+SchemaBridgeState.migration.finalJob.id,undefined,'GET')).json();renderJob();if(jobBusy(SchemaBridgeState.migration.finalJob))SchemaBridgeState.migration.polling=setTimeout(pollJob,1500);else refreshHistory();}catch(e){error(e.message);SchemaBridgeState.migration.polling=setTimeout(pollJob,5000);} }
$('jobDownload').addEventListener('click',()=>save(new Blob([JSON.stringify(SchemaBridgeState.migration.finalJob,null,2)],{type:'application/json'}),'execution-report.json'));
function transferOptions(){return {maxRows:Number($('maxRows').value),maxTableBytes:Math.round(Number($('maxTableGb').value)*1e9),diskReserveBytes:Math.round(Number($('diskReserveMb').value)*1e6),fetchSize:Number($('fetchSize').value),batchRows:Number($('batchRows').value),batchBytes:Math.round(Number($('batchMb').value)*1e6),queryTimeoutSeconds:Number($('queryTimeout').value),readTimeoutSeconds:Number($('readTimeout').value),validateData:$('validateData').checked,useCopy:$('useCopy').checked};}
$('prepareData').addEventListener('click',async()=>{if(!SchemaBridgeState.migration.lastRequest)return;try{
  const version=SchemaBridgeState.migration.requestVersion;const previousPlan=SchemaBridgeState.migration.plan.id;const request={...SchemaBridgeState.migration.lastRequest,metadataOnly:false};
  const started=await(await api('/preparations',request)).json();SchemaBridgeState.migration.preparationId=started.id;$('preparationSection').hidden=false;$('cancelPreparation').hidden=false;$('prepareData').disabled=true;
  async function poll(){try{const current=await(await api('/preparations/'+started.id,undefined,'GET')).json();$('preparationState').textContent=current.message;renderProgress('preparation',current.progress);
    if(['QUEUED','PREPARING'].includes(current.state)){SchemaBridgeState.migration.preparationPoll=setTimeout(poll,1000);return;}
    $('cancelPreparation').hidden=true;$('prepareData').disabled=false;if(current.state==='READY'&&version===SchemaBridgeState.migration.requestVersion){SchemaBridgeState.migration.plan=current.SchemaBridgeState.migration.plan;renderPlan();$('preview').textContent=await(await api('/plans/'+SchemaBridgeState.migration.plan.id+'/preview',undefined,'GET')).text();await api('/plans/'+previousPlan,undefined,'DELETE');}
    if(current.state==='READY'&&version!==SchemaBridgeState.migration.requestVersion)await api('/plans/'+current.SchemaBridgeState.migration.plan.id,undefined,'DELETE');
    if(current.state==='FAILED')error(current.message);
  }catch(e){$('prepareData').disabled=false;error(e.message);}}
  await poll();
}catch(e){error(e.message);$('prepareData').disabled=false;}});
$('cancelPreparation').addEventListener('click',async()=>{try{if(SchemaBridgeState.migration.preparationId)await api('/preparations/'+SchemaBridgeState.migration.preparationId+'/cancel',{});}catch(e){error(e.message);}});
$('cancelJob').addEventListener('click',async()=>{try{SchemaBridgeState.migration.finalJob=await(await api('/jobs/'+SchemaBridgeState.migration.finalJob.id+'/cancel',{})).json();renderJob();}catch(e){error(e.message);}});
$('resumeJob').addEventListener('click',async()=>{try{SchemaBridgeState.migration.finalJob=await(await api('/jobs/'+SchemaBridgeState.migration.finalJob.id+'/resume',connection('target'))).json();renderJob();pollJob();}catch(e){error(e.message);}});
function jobStateLabel(state){return ({QUEUED:'Queued',RUNNING:'Running',SUCCEEDED:'Completed',FAILED:'Failed',CANCELLED:'Cancelled',RECOVERY_REQUIRED:'Needs reconciliation'})[state]||state;}
function jobHistoryCard(job){
  const card=document.createElement('article');card.className='history-card';card.setAttribute('role','listitem');
  const heading=document.createElement('div');heading.className='history-card-heading';
  const displayState=job.revert?.state||job.state;
  const state=document.createElement('span');state.className='history-state state-'+displayState.toLowerCase().replaceAll('_','-');state.textContent=job.revert?(displayState==='REVERTED'?'Reverted':'Revert: '+jobStateLabel(displayState)):jobStateLabel(job.state);
  const id=document.createElement('code');id.textContent=job.id.slice(0,8);heading.append(state,id);card.append(heading);
  const summary=document.createElement('p');const committed=Number(job.progress?.rowsCommitted||0);summary.className='history-summary';summary.textContent=`${job.tables.length} table${job.tables.length===1?'':'s'} · ${committed.toLocaleString()} ${job.state==='RECOVERY_REQUIRED'?'rows recorded committed':'rows committed'}`;card.append(summary);
  const detail=document.createElement('p');detail.className='history-detail';detail.textContent=job.revert?.message||job.message;card.append(detail);
  const actions=document.createElement('div');actions.className='history-actions';
  const view=document.createElement('button');view.className='secondary';view.textContent='View report / revert';view.addEventListener('click',async()=>{try{clearTimeout(SchemaBridgeState.migration.polling);clearRevertReview();SchemaBridgeState.migration.finalJob=await(await api('/jobs/'+job.id,undefined,'GET')).json();$('jobSection').hidden=false;renderJob();SchemaBridgeState.migration.plan=await(await api('/plans/'+job.planId,undefined,'GET')).json();renderPlan();$('execute').disabled=true;$('preview').textContent=await(await api('/plans/'+job.planId+'/preview',undefined,'GET')).text();if(jobBusy(SchemaBridgeState.migration.finalJob))pollJob();SchemaBridgeState.migration.maxStep=4;goToStep(4);}catch(e){error(e.message);}});actions.append(view);
  if(!jobBusy(job)){
    const uncertain=job.state==='RECOVERY_REQUIRED'||['PARTIAL','RECOVERY_REQUIRED'].includes(job.revert?.state);
    const remove=document.createElement('button');remove.className=uncertain?'secondary danger':'secondary';remove.textContent=uncertain?'Review and discard evidence':'Delete saved files';
    remove.addEventListener('click',async()=>{
      const prompt=uncertain
        ?'This job\'s commit outcome is uncertain — these files are the evidence needed to reconcile the target (see RECOVERY-RUNBOOK.md). Only discard them after you have manually verified and reconciled the target database. Discard anyway?'
        :'Delete this saved migration and its exported data? Target database rows will remain.';
      if(!window.confirm(prompt))return;
      remove.disabled=true;
      try{await api('/plans/'+job.planId+(uncertain?'?acknowledgeUncertain=true':''),undefined,'DELETE');if(SchemaBridgeState.migration.finalJob?.id===job.id){clearTimeout(SchemaBridgeState.migration.polling);$('jobSection').hidden=true;$('revertSection').hidden=true;clearRevertReview();}if(SchemaBridgeState.migration.plan?.id===job.planId)invalidate();await refreshHistory();}catch(e){remove.disabled=false;error(e.message);}
    });
    actions.append(remove);
  }
  card.append(actions);return card;
}
async function refreshHistory(){
  const button=$('refreshHistory'),status=$('jobHistoryStatus');button.disabled=true;status.textContent='Loading saved migrations…';
  try{const jobs=await(await api('/jobs',undefined,'GET')).json();const list=$('jobHistory');list.replaceChildren();
    if(!jobs.length){list.textContent='No saved migrations.';status.textContent='History is up to date.';return;}
    for(const job of jobs)list.append(jobHistoryCard(job));status.textContent=`${jobs.length} saved migration${jobs.length===1?'':'s'} loaded.`;
  }catch(e){status.textContent='Could not refresh migration history.';error(e.message);}
  finally{button.disabled=false;}
}
$('refreshHistory').addEventListener('click',refreshHistory);
async function refreshStorage(){
  const button=$('refreshStorage');button.disabled=true;
  try{
    const s=await(await api('/storage',undefined,'GET')).json();
    const metrics=$('storageMetrics');metrics.replaceChildren();
    for(const [label,value] of [['Staged data',formatBytes(s.stagedBytes)],['Free disk',formatBytes(s.freeDiskBytes)],['Reclaimable on next cleanup',formatBytes(s.reclaimableBytes)],['Saved plans',String(s.plans.length)]]){
      const div=document.createElement('div');const dt=document.createElement('dt');dt.textContent=label;const dd=document.createElement('dd');dd.textContent=value;div.append(dt,dd);metrics.append(div);
    }
    const orphans=$('storageOrphans');
    if(s.orphanedDirectories.length){orphans.hidden=false;orphans.textContent=`${s.orphanedDirectories.length} orphaned work-directory folder${s.orphanedDirectories.length===1?'':'s'} found with no matching saved SchemaBridgeState.migration.plan (left over from an interrupted run). Review and remove manually if no longer needed.`;}
    else orphans.hidden=true;
  }catch(e){error(e.message);}
  finally{button.disabled=false;}
}
$('refreshStorage').addEventListener('click',refreshStorage);
function settingsProfile(){
  const value=id=>$(id)?$(id).value:undefined;
  return {version:1,sourceDialect:value('sourceDialect'),targetDialect:value('targetDialect'),targetSchema:value('targetSchema'),
    source:{host:value('sourceHost'),port:value('sourcePort'),database:value('sourceDatabase'),username:value('sourceUsername')},
    target:{host:value('targetHost'),port:value('targetPort'),database:value('targetDatabase'),username:value('targetUsername')},
    options:{maxRows:value('maxRows'),maxTableGb:value('maxTableGb'),diskReserveMb:value('diskReserveMb'),fetchSize:value('fetchSize'),batchRows:value('batchRows'),batchMb:value('batchMb'),queryTimeout:value('queryTimeout'),readTimeout:value('readTimeout'),validateData:$('validateData')?.checked,useCopy:$('useCopy')?.checked}};
}
$('exportSettings').addEventListener('click',()=>{save(new Blob([JSON.stringify(settingsProfile(),null,2)],{type:'application/json'}),'schemabridge-settings.json');});
$('importSettings').addEventListener('change',async()=>{
  const file=$('importSettings').files[0];if(!file)return;
  try{
    const p=JSON.parse(await file.text());
    if(p.sourceDialect&&$('sourceDialect'))$('sourceDialect').value=p.sourceDialect;
    if(p.targetDialect&&$('targetDialect'))$('targetDialect').value=p.targetDialect;
    if(p.targetSchema!=null&&$('targetSchema'))$('targetSchema').value=p.targetSchema;
    for(const prefix of ['source','target']){const side=p[prefix];if(!side)continue;
      for(const field of ['Host','Port','Database','Username'])if(side[field.toLowerCase()]!=null&&$(prefix+field))$(prefix+field).value=side[field.toLowerCase()];
    }
    if(p.options)for(const [key,id] of [['maxRows','maxRows'],['maxTableGb','maxTableGb'],['diskReserveMb','diskReserveMb'],['fetchSize','fetchSize'],['batchRows','batchRows'],['batchMb','batchMb'],['queryTimeout','queryTimeout'],['readTimeout','readTimeout']])if(p.options[key]!=null&&$(id))$(id).value=p.options[key];
    if(p.options&&$('validateData'))$('validateData').checked=!!p.options.validateData;
    if(p.options&&$('useCopy'))$('useCopy').checked=!!p.options.useCopy;
    invalidate();
    $('configStatus').textContent='Settings imported. Passwords were not included — enter them before connecting.';
  }catch(e){error('Could not import settings: '+e.message);}
  finally{$('importSettings').value='';}
});
function selectedSourceTables(){return Array.from($('sourceTables').selectedOptions).map(option=>option.value);}
function renderTablePicker(){
  if(!$('tablePickerList'))return;
  const selected=new Set(selectedSourceTables()),list=$('tablePickerList');list.replaceChildren();
  for(const option of $('sourceTables').options){const label=document.createElement('label');label.className='table-picker-option';label.dataset.name=option.value.toLocaleLowerCase();const check=document.createElement('input');check.type='checkbox';check.checked=selected.has(option.value);check.value=option.value;const name=document.createElement('span');name.textContent=option.value;label.append(check,name);list.append(label);}
  const query=$('tableSearch').value.trim().toLocaleLowerCase();for(const row of list.querySelectorAll('.table-picker-option'))row.hidden=!row.dataset.name.includes(query);
  updateTablePickerSummary();syncTableSettings();
}
function updateTablePickerSummary(){
  const selected=selectedSourceTables();
  if($('tableSelectionSummary'))$('tableSelectionSummary').textContent=selected.length?`${selected.length} table${selected.length===1?'':'s'} selected`:'No tables selected yet.';
  if($('pickerCount'))$('pickerCount').textContent=selected.length?`${selected.length} table${selected.length===1?'':'s'} selected for this migration.`:'No tables selected.';
  if($('openTablePicker'))$('openTablePicker').disabled=!$('sourceSchemaSelect').value||!$('sourceTables').options.length;
  if($('selectAllTables')){const visible=Array.from($('tablePickerList').querySelectorAll('.table-picker-option')).filter(row=>!row.hidden),checked=visible.filter(row=>row.querySelector('input').checked).length;$('selectAllTables').checked=visible.length>0&&checked===visible.length;$('selectAllTables').indeterminate=checked>0&&checked<visible.length;$('selectAllTables').disabled=visible.length===0;}
}
function tableSelections(){
  const result={},selected=new Set(selectedSourceTables());
  for(const group of $('columnOptions').children){if(!selected.has(group.dataset.table)||group.dataset.loaded!=='true')continue;
    const columns=[],rename={};
    for(const row of group.querySelectorAll('[data-column]'))if(row.querySelector('input[type=checkbox]').checked){columns.push(row.dataset.column);const destination=row.querySelector('input[type=text]').value.trim();if(!destination)throw new Error('Enter a destination column name.');if(destination!==row.dataset.column)rename[row.dataset.column]=destination;}
    if(!columns.length)throw new Error('Select at least one column for '+group.dataset.table);
    const tableName=group.querySelector('.target-table-name').value.trim();if(!tableName)throw new Error('Enter a destination table name for '+group.dataset.table);
    const filters=[];
    for(const row of group.querySelectorAll('.filter-row')){const column=row.querySelector('.filter-column').value,operator=row.querySelector('.filter-operator').value,value=row.querySelector('.filter-value').value;if(!column)continue;filters.push({column,operator,value:operator.includes('NULL')?null:value});}
    const rawLimit=group.querySelector('.table-row-limit').value;
    const rowLimit=rawLimit===''?null:Number(rawLimit);if(rowLimit!==null&&(!Number.isInteger(rowLimit)||rowLimit<1||rowLimit>2147483647))throw new Error('Enter a positive whole-number row limit for '+group.dataset.table);
    result[group.dataset.table]={columns,rename,filters,rowLimit,...(tableName!==group.dataset.table?{tableName}:{})};
  }
  return result;
}
function syncTableSettings(){
  const selected=new Set(selectedSourceTables()),container=$('columnOptions');
  const existing=new Map(Array.from(container.children,group=>[group.dataset.table,group]));
  for(const group of container.children){group.hidden=!selected.has(group.dataset.table);group.querySelector("fieldset").disabled=group.hidden;}
  for(const table of selected){
    if(existing.has(table))continue;
    const group=document.createElement('details');group.dataset.table=table;
    const summary=document.createElement('summary');summary.textContent=table+' ? optional settings';group.append(summary);
    const body=document.createElement('fieldset');group.append(body);container.append(group);
    group.addEventListener('toggle',async()=>{
      if(!group.open||group.dataset.loaded==='true'||group.dataset.loading==='true')return;
      group.dataset.loading='true';body.textContent='Loading columns?';
      try{
        const columns=await(await api('/columns',{connection:connection('source'),schema:$('sourceSchemaSelect').value,table})).json();
        if(!group.isConnected)return;
        body.replaceChildren();
        const label=document.createElement('label');label.textContent='Destination table name';const name=document.createElement('input');name.className='target-table-name';name.value=table;name.maxLength=128;label.append(name);body.append(label);
        const mapping=document.createElement('details'),title=document.createElement('summary');title.textContent='Columns and destination names';mapping.append(title);
        for(const column of columns){const row=document.createElement('label');row.className='column-mapping';row.dataset.column=column.name.value;const check=document.createElement('input');check.type='checkbox';check.checked=true;const text=document.createElement('span');text.textContent=column.name.value;const destination=document.createElement('input');destination.type='text';destination.value=column.name.value;destination.setAttribute('aria-label','Destination name for '+column.name.value);row.append(check,text,destination);mapping.append(row);}body.append(mapping);
        const limitLabel=document.createElement('label');limitLabel.textContent='Row limit (optional)';const limit=document.createElement('input');limit.type='number';limit.min='1';limit.max='2147483647';limit.step='1';limit.className='table-row-limit';limit.placeholder='All matching rows';limitLabel.append(limit);body.append(limitLabel);
        const note=document.createElement('p');note.className='muted';note.textContent='All filters must match (AND). No filters means all rows. A row limit selects up to that many matching rows without a guaranteed order.';body.append(note);
        const rows=document.createElement('div');rows.className='filter-rows';body.append(rows);
        const add=document.createElement('button');add.type='button';add.className='secondary';add.textContent='Add filter';body.append(add);
        add.addEventListener('click',()=>{addFilterRow(rows,columns,add);invalidate();});
        group.dataset.loaded='true';
      }catch(e){body.textContent='Could not load settings: '+e.message+' Close and reopen to retry.';}
      finally{delete group.dataset.loading;}
    });
  }
}
function addFilterRow(rows,columns,add){
  if(rows.children.length>=20)return;
  const row=document.createElement('div');row.className='filter-row';
  const field=document.createElement('select');field.className='filter-column';field.setAttribute('aria-label','Filter column');fillOptions(field,['',...columns.filter(c=>!['TEXT','BINARY'].includes(c.type.kind)).map(c=>c.name.value)]);field.options[0].textContent='Choose column';
  const operator=document.createElement('select');operator.className='filter-operator';operator.setAttribute('aria-label','Filter operator');
  const holder=document.createElement('span');holder.className='filter-value-holder';
  function updateValue(){
    const boolean=columns.find(c=>c.name.value===field.value)?.type.kind==='BOOLEAN';
    const old=holder.firstElementChild;const value=document.createElement(boolean?'select':'input');value.className='filter-value';value.setAttribute('aria-label','Filter value');
    if(boolean)fillOptions(value,['true','false']);else{value.type='text';value.placeholder='Value';}
    if(old&&old.tagName===value.tagName)value.value=old.value;
    value.disabled=operator.value.includes('NULL')||!field.value;holder.replaceChildren(value);
  }
  field.addEventListener('change',()=>{const previous=operator.value,boolean=columns.find(c=>c.name.value===field.value)?.type.kind==='BOOLEAN';fillOptions(operator,boolean?['=','<>','IS NULL','IS NOT NULL']:['=','<>','>','>=','<','<=','IS NULL','IS NOT NULL']);if(Array.from(operator.options).some(o=>o.value===previous))operator.value=previous;updateValue();});
  fillOptions(operator,['=','<>','>','>=','<','<=','IS NULL','IS NOT NULL']);operator.addEventListener('change',updateValue);updateValue();
  const remove=document.createElement('button');remove.type='button';remove.className='secondary';remove.textContent='Remove';remove.setAttribute('aria-label','Remove filter');remove.addEventListener('click',()=>{row.remove();add.disabled=false;invalidate();});
  row.append(field,operator,holder,remove);rows.append(row);add.disabled=rows.children.length>=20;
}
$('sourceTables').addEventListener('change',renderTablePicker);
let pickerOriginalSelection=[];
$('openTablePicker').addEventListener('click',()=>{pickerOriginalSelection=selectedSourceTables();renderTablePicker();$('tablePicker').showModal();});
function closeTablePicker(apply){
  if(!apply){const selected=new Set(pickerOriginalSelection);for(const option of $('sourceTables').options)option.selected=selected.has(option.value);renderTablePicker();}
  else if(!selectedSourceTables().length||selectedSourceTables().length>500){error('Select between 1 and 500 source tables to continue.');return;}
  $('tablePicker').close();updateTablePickerSummary();
}
$('closeTablePicker').addEventListener('click',()=>closeTablePicker(false));
$('cancelTablePicker').addEventListener('click',()=>closeTablePicker(false));
$('applyTablePicker').addEventListener('click',()=>closeTablePicker(true));
$('tablePicker').addEventListener('cancel',()=>{const selected=new Set(pickerOriginalSelection);for(const option of $('sourceTables').options)option.selected=selected.has(option.value);renderTablePicker();});
$('tablePickerList').addEventListener('change',event=>{
  if(!event.target.matches('input[type=checkbox]'))return;
  const option=Array.from($('sourceTables').options).find(item=>item.value===event.target.value);if(option)option.selected=event.target.checked;
  $('sourceTables').dispatchEvent(new Event('change',{bubbles:true}));updateTablePickerSummary();
});
$('tableSearch').addEventListener('input',()=>{const query=$('tableSearch').value.trim().toLocaleLowerCase();for(const row of $('tablePickerList').querySelectorAll('.table-picker-option'))row.hidden=!row.dataset.name.includes(query);updateTablePickerSummary();});
$('selectAllTables').addEventListener('change',()=>{
  const visible=Array.from($('tablePickerList').querySelectorAll('.table-picker-option')).filter(row=>!row.hidden),turnOn=$('selectAllTables').checked;
  for(const row of visible){const checkbox=row.querySelector('input');checkbox.checked=turnOn;const option=Array.from($('sourceTables').options).find(item=>item.value===checkbox.value);if(option)option.selected=turnOn;}
  $('sourceTables').dispatchEvent(new Event('change',{bubbles:true}));updateTablePickerSummary();
});
async function loadBuildVersion(){try{const response=await api('/capabilities',undefined,'GET');const info=await response.json();$('buildVersion').textContent='Version '+info.version;}catch(_){$('buildVersion').textContent='Version unavailable';}}
updateFlow();refreshHistory();refreshStorage();loadBuildVersion();
