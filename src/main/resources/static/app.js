'use strict';
const $ = id => document.getElementById(id);
let plan = null, finalJob = null, polling = null, errorTimer = null, lastRequest = null, preparationId = null, preparationPoll = null, requestVersion = 0;
const databaseNames = {source: {}, target: {}};
const catalogSelection = {source:null,target:null};
let catalogLoading = false;
function setCatalogMode(prefix, selection) {
  catalogSelection[prefix] = selection;
  $(prefix+'Dialect').disabled = !!selection;
  for (const suffix of ['Host','Port','Database','Username','Password']) $(prefix+suffix).readOnly = !!selection;
  if (selection) {
    $(prefix+'Dialect').value = selection.connection.dialect; applyDialect(prefix);
    for (const [suffix, key] of [['Host','host'],['Port','port'],['Database','database'],['Username','username'],['Password','password']]) $(prefix+suffix).value = selection.connection[key];
    $(prefix+'DatabaseLabel').textContent = selection.connection.dialect === 'ORACLE' ? 'Oracle '+selection.mode : 'PostgreSQL database name';
    $(prefix+'DatabaseHelp').textContent = 'Loaded from LCNC: '+selection.name+'. Choose Manual entry to edit connection values.';
  } else { applyDialect(prefix); $(prefix+'Catalog').value=''; }
  fillOptions($(prefix+'SchemaSelect'), []);
  if(prefix === 'source') {fillOptions($('sourceTables'), []);$('columnOptions').replaceChildren();}
  else $('targetSchema').value = selection?.schema || ($(prefix+'Dialect').value === 'ORACLE' ? 'APP' : 'public');
  invalidate();
}
async function loadCatalog() {
  if (catalogLoading) return;
  catalogLoading = true;
  for(const prefix of ['source','target']) $(prefix+'CatalogRefresh').disabled = true;
  try {
    const result = await (await api('/datasources',undefined,'GET')).json();
    if (!result.configured) throw new Error('LCNC catalogue is not configured. Manual entry remains available.');
    for(const prefix of ['source','target']) {
      const select=$(prefix+'Catalog'), selected=select.value; select.replaceChildren(new Option('Manual entry',''));
      for(const item of result.datasources) { const option=new Option(`${item.name} (#${item.id})${item.supported?' — '+item.dialect:' — '+item.finding}`,String(item.id)); option.disabled=!item.supported; option.title=item.description || ''; select.add(option); }
      select.value=selected;
      if (!select.value && catalogSelection[prefix]) setCatalogMode(prefix,null);
      $(prefix+'CatalogStatus').textContent = result.datasources.length+' active, approved master JDBC entries. Select one, then connect.';
    }
  } catch(e) { error(e.message); }
  finally { catalogLoading=false; for(const prefix of ['source','target']) $(prefix+'CatalogRefresh').disabled = $(prefix+'Connection').hidden; }
}
const flow = () => document.querySelector('input[name="flow"]:checked').value;
const usesDatabase = () => flow().startsWith('db-');
const writesDatabase = () => flow().endsWith('-db');
const error = message => { $('error').textContent = message; $('error').hidden = false; clearTimeout(errorTimer); errorTimer = setTimeout(() => $('error').hidden = true, 15000); };
async function api(path, body, method = 'POST') {
  const response = await fetch('/api' + path, {method, headers: {'Content-Type':'application/json','X-Migration-Client':'migration-ui'}, ...(body === undefined ? {} : {body:JSON.stringify(body)})});
  if (!response.ok) { let message = 'Request failed (' + response.status + ')'; try { message = (await response.json()).message || message; } catch (_) {} throw new Error(message); }
  return response;
}
function connectionForm(prefix) {
  const host = document.createElement('div');
  // Template contains only fixed application markup, never database or user content.
  host.innerHTML = `<div class="connection-grid"><label>Host<input id="${prefix}Host" value="localhost" autocomplete="off" required></label><label>Port<input id="${prefix}Port" type="number" min="1" max="65535" value="${prefix === 'source' ? 1521 : 5432}" required></label></div><label><span id="${prefix}DatabaseLabel">Database name</span><input id="${prefix}Database" autocomplete="off" aria-describedby="${prefix}DatabaseHelp" required></label><p id="${prefix}DatabaseHelp" class="muted"></p><label>Username<input id="${prefix}Username" autocomplete="off" required></label><label>Password<input id="${prefix}Password" type="password" autocomplete="new-password"></label><button type="button" id="${prefix}Connect" class="secondary">Connect & load schemas</button><label>${prefix === 'source' ? 'Source schema' : 'Available target schemas'}<select id="${prefix}SchemaSelect"><option value="">Connect first</option></select></label>`;
  $(prefix + 'Connection').append(host);
  const chooser=document.createElement('div');
  chooser.innerHTML=`<label>Connection source<select id="${prefix}Catalog"><option value="">Manual entry</option></select></label><button type="button" class="secondary" id="${prefix}CatalogRefresh">Load / refresh LCNC datasources</button><p class="muted" id="${prefix}CatalogStatus">Use manual entry or load saved connections from fg_datasource.</p>`;
  host.prepend(chooser);
  $(prefix+'CatalogRefresh').addEventListener('click',loadCatalog);
  let selectionVersion=0;
  $(prefix+'Catalog').addEventListener('change',async()=>{
    const id=$(prefix+'Catalog').value, version=++selectionVersion;
    if(!id) { setCatalogMode(prefix,null); $(prefix+'Connect').disabled=$(prefix+'Connection').hidden; return; }
    $(prefix+'Connect').disabled=true;
    try { const selected=await(await api('/datasources/'+encodeURIComponent(id)+'/select',{})).json(); if(version===selectionVersion) setCatalogMode(prefix,selected); }
    catch(e) { if(version===selectionVersion) { setCatalogMode(prefix,null); error(e.message); } }
    finally { if(version===selectionVersion) $(prefix+'Connect').disabled=$(prefix+'Connection').hidden; }
  });
  applyDialect(prefix);
  $(prefix + 'Database').addEventListener('input', () => $(prefix + 'Database').setCustomValidity(''));
  $(prefix + 'Connect').addEventListener('click', async () => {
    const button = $(prefix + 'Connect'); button.disabled = true;
    try { const schemas = await (await api('/schemas', connection(prefix))).json(); fillOptions($(prefix + 'SchemaSelect'), schemas); const preferred=catalogSelection[prefix]?.schema; if(preferred && schemas.includes(preferred)) $(prefix+'SchemaSelect').value=preferred; $('configStatus').textContent = 'Connected. Select a schema.'; invalidate(); if (prefix === 'source') await loadTables(); else $('targetSchema').value = $(prefix + 'SchemaSelect').value; }
    catch (e) { error(e.message); } finally { button.disabled = false; }
  });
  $(prefix + 'SchemaSelect').addEventListener('change', async () => { invalidate(); if (prefix === 'source') await loadTables(); else $('targetSchema').value = $(prefix + 'SchemaSelect').value; });
}
function applyDialect(prefix) {
  const dialect = $(prefix + 'Dialect').value, field = $(prefix + 'Database');
  if (field.dataset.dialect) databaseNames[prefix][field.dataset.dialect] = field.value;
  field.value = databaseNames[prefix][dialect] ?? (dialect === 'POSTGRESQL' ? 'postgres' : '');
  field.dataset.dialect = dialect; field.setCustomValidity('');
  field.placeholder = dialect === 'POSTGRESQL' ? 'postgres or your application database' : 'For example, XEPDB1';
  $(prefix + 'DatabaseLabel').textContent = dialect === 'POSTGRESQL' ? 'PostgreSQL database name' : 'Oracle service name';
  $(prefix + 'DatabaseHelp').textContent = dialect === 'POSTGRESQL'
    ? 'postgres is the default connection database. Replace it with the database containing your schemas; selecting PostgreSQL only chooses the engine.'
    : 'Enter the Oracle service name, for example XEPDB1. Select the schema after connecting.';
  $(prefix + 'Port').value = dialect === 'ORACLE' ? '1521' : '5432';
}
function connection(prefix) {
  if ($(prefix+'Catalog').value && String(catalogSelection[prefix]?.id) !== $(prefix+'Catalog').value) throw new Error('Wait for the selected LCNC datasource to load.');
  const dialect = $(prefix + 'Dialect').value, databaseField = $(prefix + 'Database'), database = databaseField.value.trim();
  if (!database) {
    const message = `${prefix === 'source' ? 'Source' : 'Target'} ${dialect === 'POSTGRESQL' ? 'PostgreSQL database name' : 'Oracle service name'} is required.`;
    databaseField.setCustomValidity(message); databaseField.reportValidity(); throw new Error(message);
  }
  databaseField.setCustomValidity('');
  for (const suffix of ['Host', 'Port', 'Username']) { const field = $(prefix + suffix); if (!field.checkValidity()) { field.reportValidity(); throw new Error(`Check the ${prefix} ${suffix.toLowerCase()} field.`); } }
  return {dialect,host:$(prefix + 'Host').value.trim(),port:Number($(prefix + 'Port').value),database,username:$(prefix + 'Username').value,password:$(prefix + 'Password').value,...(catalogSelection[prefix]?{jdbcUrl:catalogSelection[prefix].connection.jdbcUrl}:{})};
}
function fillOptions(select, values) { select.replaceChildren(); for (const value of values) { const option = document.createElement('option'); option.value = option.textContent = value; select.append(option); } }
async function loadTables() { $('columnOptions').replaceChildren(); try { const schema = $('sourceSchemaSelect').value; if (schema) fillOptions($('sourceTables'), await (await api('/tables', {connection:connection('source'),schema})).json()); } catch(e) { error(e.message); } }
function invalidate() { requestVersion++; lastRequest=null; plan = null; $('reportSection').hidden = true; $('configStatus').textContent = 'Settings changed. Analyse to refresh the compatibility report.'; }
function updateFlow() {
  $('sourceConnection').hidden = !usesDatabase(); $('tableSelection').hidden = !usesDatabase(); $('filesInput').hidden = usesDatabase(); $('targetConnection').hidden = !writesDatabase();
  for (const prefix of ['source','target']) $(prefix + 'Connection').querySelectorAll('input,select,button').forEach(field => field.disabled = $(prefix + 'Connection').hidden);
  invalidate();
}
connectionForm('source'); connectionForm('target');
document.querySelectorAll('input[name="flow"]').forEach(input => input.addEventListener('change', updateFlow));
$('configuration').addEventListener('input', invalidate);
for (const prefix of ['source','target']) $(prefix + 'Dialect').addEventListener('change', () => { if(prefix==='source')$('columnOptions').replaceChildren(); applyDialect(prefix); fillOptions($(prefix + 'SchemaSelect'), []); if(prefix === 'source') {fillOptions($('sourceTables'), []);$('columnOptions').replaceChildren();} else $('targetSchema').value = $('targetDialect').value === 'ORACLE' ? 'APP' : 'public'; });
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
    const request = {options:transferOptions(),selections:usesDatabase()?tableSelections():{},metadataOnly:usesDatabase(),sourceDialect:$('sourceDialect').value,targetDialect:$('targetDialect').value,targetSchema:$('targetSchema').value.trim(),sql:usesDatabase()?null:$('sql').value,source:usesDatabase()?connection('source'):null,sourceSchema:usesDatabase()?$('sourceSchemaSelect').value:null,tables:usesDatabase()?Array.from($('sourceTables').selectedOptions).map(o=>o.value):[],includeData:$('includeData').checked,target:writesDatabase()?connection('target'):null};
    lastRequest=request; const version=requestVersion; const result = await (await api('/plans', request)).json(); if(version!==requestVersion){await api('/plans/'+result.id,undefined,'DELETE');return;} plan=result; renderPlan(); $('preview').textContent = await (await api('/plans/' + plan.id + '/preview', undefined, 'GET')).text(); $('configStatus').textContent = 'Analysis complete. Review the table actions below.'; $('reportSection').scrollIntoView({block:'start'});
  } catch(e) { error(e.message); $('configStatus').textContent = 'Analysis failed. Check the message and try again.'; } finally { $('analyse').disabled = false; }
});
function cell(row,text) { const td = document.createElement('td'); td.textContent = text; row.append(td); return td; }
function renderPlan() {
  $('reportSection').hidden = false; $('reportMessages').replaceChildren(); $('reportRows').replaceChildren();
  $('reportTitle').textContent = plan.orderedScript ? 'Review the script in execution order' : 'Review every table';
  $('matchAll').hidden = !!plan.orderedScript; $('tableReport').hidden = !!plan.orderedScript;
  $('statementSection').hidden = !plan.orderedScript; $('statementRows').replaceChildren();
  $('previewTitle').textContent = plan.orderedScript ? 'Preview full converted script in original order' : 'Preview converted table definitions';
  $('download').textContent = plan.orderedScript ? 'Download ordered review draft' : 'Download SQL package';
  $('executionNote').textContent = plan.orderedScript ? 'This lifecycle script includes operations whose order matters. Download preserves all converted statements and marks source errors in place. Per-table actions and automatic execution are disabled; no target changes are made.' : 'Run selected plan writes to the inspected target database. DDL and previously committed tables can remain after failure. Prepared plans are retained for 30 days. Job history remains until deleted.';
  if (plan.orderedScript) {
    const statements = plan.statements || [], errors = statements.filter(s => ['ERROR','UNSUPPORTED'].includes(s.status)).length;
    $('statementSummary').textContent = `${statements.length} statements · ${statements.length - errors} converted (including review warnings) · ${errors} errors / unsupported statements. No statements are silently omitted.`;
    for (const statement of statements) { const row = document.createElement('tr'); cell(row, statement.number); cell(row, statement.kind); cell(row, statement.status); cell(row, statement.messages.join(' · ') || 'Converted. See the full preview below.'); $('statementRows').append(row); }
  }
  for (const [messages, kind] of [[plan.issues,'issue'],[plan.warnings,'warning']]) for (const text of messages) { const p = document.createElement('p'); p.className = 'message ' + kind; p.textContent = text; $('reportMessages').append(p); }
  for (const table of plan.tables) {
    const tr = document.createElement('tr'); cell(tr,table.table); const status = cell(tr,''); const badge = document.createElement('span'); badge.className = 'badge ' + table.status; badge.textContent = ({NEW:'New table',MATCH:'Already exists · match',MISMATCH:'Already exists / review',UNCHECKED:'Not inspected'})[table.status]; status.append(badge); const rowSummary=plan.prepared===false?(table.estimatedRows==null?'Not extracted; estimate unavailable':`~${table.estimatedRows.toLocaleString()} source rows estimated`):table.rows; cell(tr,rowSummary);
    cell(tr,[...table.differences,...table.warnings].join(' · ') || (table.status === 'MATCH' ? 'Definitions match. DML-only is available.' : 'Ready for selected action.'));
    const td = cell(tr,''); const select = document.createElement('select'); select.dataset.table = table.table;
    for(const action of table.allowedActions) { const option = document.createElement('option'); option.value = action; option.textContent = ({CREATE_AND_LOAD:'Create + load',DML_ONLY:'DML only',SKIP:'Skip table'})[action]; select.append(option); }
    select.value = table.status === 'MATCH' || table.status === 'MISMATCH' ? 'SKIP' : 'CREATE_AND_LOAD'; td.append(select); $('reportRows').append(tr);
  }
  $('execute').hidden = !plan.targetChecked || plan.orderedScript; $('execute').disabled = plan.issues.length > 0 || plan.orderedScript || plan.prepared===false; $('download').disabled = plan.prepared===false; $('prepareData').hidden=plan.prepared!==false;
}
function actions() { return plan?.orderedScript ? {} : Object.fromEntries(Array.from($('reportRows').querySelectorAll('select')).map(select => [select.dataset.table,select.value])); }
$('matchAll').addEventListener('click', () => $('reportRows').querySelectorAll('select').forEach(select => { if (Array.from(select.options).some(o=>o.value==='DML_ONLY')) select.value='DML_ONLY'; }));
function save(blob,name) { const url=URL.createObjectURL(blob), link=document.createElement('a'); link.href=url;link.download=name;link.click();setTimeout(()=>URL.revokeObjectURL(url),1000); }
$('reportDownload').addEventListener('click',()=>save(new Blob([JSON.stringify({report:plan,actions:actions()},null,2)],{type:'application/json'}),'compatibility-report.json'));
$('download').addEventListener('click',async()=>{try{
  $('download').disabled=true;
  const ticket=await(await api('/plans/'+plan.id+'/download-ticket',actions())).json();
  const link=document.createElement('a');link.href=ticket.url;link.download='migration.zip';document.body.append(link);link.click();link.remove();
}catch(e){error(e.message);}finally{$('download').disabled=false;}});
$('execute').addEventListener('click',async()=>{ try { $('execute').disabled=true; finalJob=await(await api('/plans/'+plan.id+'/execute',{target:connection('target'),actions:actions()})).json(); $('jobSection').hidden=false; renderJob(); pollJob(); } catch(e){error(e.message);$('execute').disabled=false;} });
function renderJob(){ $('jobProgress').textContent=progressText(finalJob.progress);$('cancelJob').hidden=!['RUNNING','QUEUED'].includes(finalJob.state);$('resumeJob').hidden=!finalJob.resumable; $('jobState').textContent=finalJob.state+' — '+finalJob.message; $('jobRows').replaceChildren(); for(const table of finalJob.tables){const tr=document.createElement('tr');[table.table,table.action,table.status,table.rows,table.message].forEach(value=>cell(tr,value));$('jobRows').append(tr);} }
async function pollJob(){ clearTimeout(polling); try{finalJob=await(await api('/jobs/'+finalJob.id,undefined,'GET')).json();renderJob();if(['RUNNING','QUEUED'].includes(finalJob.state))polling=setTimeout(pollJob,1500);}catch(e){error(e.message);polling=setTimeout(pollJob,5000);} }
$('jobDownload').addEventListener('click',()=>save(new Blob([JSON.stringify(finalJob,null,2)],{type:'application/json'}),'execution-report.json'));
function transferOptions(){return {maxRows:Number($('maxRows').value),maxTableBytes:Math.round(Number($('maxTableGb').value)*1e9),diskReserveBytes:Math.round(Number($('diskReserveMb').value)*1e6),fetchSize:Number($('fetchSize').value),batchRows:Number($('batchRows').value),batchBytes:Math.round(Number($('batchMb').value)*1e6),queryTimeoutSeconds:Number($('queryTimeout').value),readTimeoutSeconds:Number($('readTimeout').value),validateData:$('validateData').checked,useCopy:$('useCopy').checked};}
function progressText(p){if(!p)return '';return `${p.phase} ${p.table||''} | Read ${p.rowsRead.toLocaleString()} | Sent ${p.rowsSent.toLocaleString()} | Committed ${p.rowsCommitted.toLocaleString()} | ${(p.bytes/1e6).toFixed(1)} MB | ${p.elapsedSeconds}s | ${Math.round(p.rowsPerSecond).toLocaleString()} rows/s`+(p.remainingSeconds!=null?` | Estimated ${p.remainingSeconds}s remaining`:'')+(p.cancelRequested?' | Cancellation requested':'');}
$('prepareData').addEventListener('click',async()=>{if(!lastRequest)return;try{
  const version=requestVersion;const previousPlan=plan.id;const request={...lastRequest,metadataOnly:false};
  const started=await(await api('/preparations',request)).json();preparationId=started.id;$('preparationSection').hidden=false;$('cancelPreparation').hidden=false;$('prepareData').disabled=true;
  async function poll(){try{const current=await(await api('/preparations/'+started.id,undefined,'GET')).json();$('preparationState').textContent=current.message+' '+progressText(current.progress);
    if(['QUEUED','PREPARING'].includes(current.state)){preparationPoll=setTimeout(poll,1000);return;}
    $('cancelPreparation').hidden=true;$('prepareData').disabled=false;if(current.state==='READY'&&version===requestVersion){plan=current.plan;renderPlan();$('preview').textContent=await(await api('/plans/'+plan.id+'/preview',undefined,'GET')).text();await api('/plans/'+previousPlan,undefined,'DELETE');}
    if(current.state==='READY'&&version!==requestVersion)await api('/plans/'+current.plan.id,undefined,'DELETE');
    if(current.state==='FAILED')error(current.message);
  }catch(e){$('prepareData').disabled=false;error(e.message);}}
  await poll();
}catch(e){error(e.message);$('prepareData').disabled=false;}});
$('cancelPreparation').addEventListener('click',async()=>{try{if(preparationId)await api('/preparations/'+preparationId+'/cancel',{});}catch(e){error(e.message);}});
$('cancelJob').addEventListener('click',async()=>{try{finalJob=await(await api('/jobs/'+finalJob.id+'/cancel',{})).json();renderJob();}catch(e){error(e.message);}});
$('resumeJob').addEventListener('click',async()=>{try{finalJob=await(await api('/jobs/'+finalJob.id+'/resume',connection('target'))).json();renderJob();pollJob();}catch(e){error(e.message);}});
$('refreshHistory').addEventListener('click',async()=>{try{
  const jobs=await(await api('/jobs',undefined,'GET')).json();$('jobHistory').replaceChildren();
  if(!jobs.length){$('jobHistory').textContent='No saved migrations.';return;}
  for(const job of jobs){const button=document.createElement('button');button.className='secondary';button.textContent=job.state+' | '+job.id;
    button.addEventListener('click',async()=>{try{finalJob=job;$('jobSection').hidden=false;renderJob();pollJob();plan=await(await api('/plans/'+job.planId,undefined,'GET')).json();renderPlan();$('execute').disabled=true;$('preview').textContent=await(await api('/plans/'+job.planId+'/preview',undefined,'GET')).text();}catch(e){error(e.message);}});const row=document.createElement('div');row.className='history-row';row.append(button);if(!['RUNNING','QUEUED'].includes(job.state)){const remove=document.createElement('button');remove.className='secondary';remove.textContent='Delete saved files';remove.addEventListener('click',async()=>{if(!window.confirm('Delete this saved migration and its exported data? Target database rows will remain.'))return;try{await api('/plans/'+job.planId,undefined,'DELETE');row.remove();if(finalJob?.id===job.id){clearTimeout(polling);$('jobSection').hidden=true;}if(plan?.id===job.planId)invalidate();}catch(e){error(e.message);}});row.append(remove);}$('jobHistory').append(row);}
}catch(e){error(e.message);}});
function tableSelections(){const result={};const selected=new Set(Array.from($('sourceTables').selectedOptions).map(o=>o.value));for(const group of $('columnOptions').children){if(!selected.has(group.dataset.table))continue;const columns=[],rename={};for(const row of group.querySelectorAll('[data-column]')){if(row.querySelector('input[type=checkbox]').checked){columns.push(row.dataset.column);const destination=row.querySelector('input[type=text]').value.trim();if(destination!==row.dataset.column)rename[row.dataset.column]=destination;}}
  if(!columns.length)throw new Error('Select at least one column for '+group.dataset.table);const column=group.querySelector('.filter-column').value,operator=group.querySelector('.filter-operator').value,value=group.querySelector('.filter-value').value;
  result[group.dataset.table]={columns,rename,filters:column?[{column,operator,value}]:[]};}return result;}
$('configureColumns').addEventListener('click',async()=>{try{
  $('columnOptions').replaceChildren();for(const option of $('sourceTables').selectedOptions){const table=option.value;const columns=await(await api('/columns',{connection:connection('source'),schema:$('sourceSchemaSelect').value,table})).json();
    const group=document.createElement('details');group.dataset.table=table;group.open=true;const summary=document.createElement('summary');summary.textContent=table;group.append(summary);
    for(const column of columns){const row=document.createElement('label');row.className='inline';row.dataset.column=column.name.value;const check=document.createElement('input');check.type='checkbox';check.checked=true;const title=document.createElement('span');title.textContent=column.name.value+' ? ';const destination=document.createElement('input');destination.type='text';destination.value=column.name.value;destination.setAttribute('aria-label','Destination name for '+column.name.value);row.append(check,title,destination);group.append(row);}
    const filter=document.createElement('label');filter.textContent='Only transfer rows matching';const field=document.createElement('select');field.className='filter-column';fillOptions(field,['',...columns.map(c=>c.name.value)]);const operator=document.createElement('select');operator.className='filter-operator';fillOptions(operator,['=','<>','>','>=','<','<=','IS NULL','IS NOT NULL']);const value=document.createElement('input');value.className='filter-value';value.placeholder='Value';filter.append(field,operator,value);group.append(filter);$('columnOptions').append(group);
  }invalidate();
}catch(e){error(e.message);}});
$('sourceTables').addEventListener('change',()=>{$('columnOptions').replaceChildren();});
updateFlow();
