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
connectionForm('source'); connectionForm('target');
document.querySelectorAll('input[name="flow"]').forEach(input => input.addEventListener('change', updateFlow));
document.querySelectorAll('[data-step-nav]').forEach(button => button.addEventListener('click',()=>goToStep(Number(button.dataset.stepNav))));
document.querySelectorAll('[data-back]').forEach(button => button.addEventListener('click',()=>goToStep(Number(button.dataset.back))));
$('flowNext').addEventListener('click',()=>{SchemaBridgeState.migration.maxStep=Math.max(SchemaBridgeState.migration.maxStep,2);goToStep(2);});
$('reviewNext').addEventListener('click',()=>{if(!SchemaBridgeState.migration.plan)return;SchemaBridgeState.migration.maxStep=Math.max(SchemaBridgeState.migration.maxStep,4);goToStep(4);});
$('startOver').addEventListener('click',()=>{SchemaBridgeState.migration.plan=null;SchemaBridgeState.migration.lastRequest=null;SchemaBridgeState.migration.maxStep=1;$('reportSection').hidden=true;$('outputActions').hidden=true;$('preparationSection').hidden=true;$('jobSection').hidden=true;goToStep(1);});
$('configuration').addEventListener('input', invalidate);
for (const prefix of ['source','target']) $(prefix + 'Dialect').addEventListener('change', () => { if(prefix==='source')$('columnOptions').replaceChildren(); applyDialect(prefix); fillOptions($(prefix + 'SchemaSelect'), []); if(prefix === 'source') {fillOptions($('sourceTables'), []);$('columnOptions').replaceChildren();renderTablePicker();} else $('targetSchema').value = $('targetDialect').value === 'ORACLE' ? 'APP' : 'public'; });
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
    $('cancelPreparation').hidden=true;$('prepareData').disabled=false;if(current.state==='READY'&&version===SchemaBridgeState.migration.requestVersion){SchemaBridgeState.migration.plan=current.plan;renderPlan();$('preview').textContent=await(await api('/plans/'+SchemaBridgeState.migration.plan.id+'/preview',undefined,'GET')).text();await api('/plans/'+previousPlan,undefined,'DELETE');}
    if(current.state==='READY'&&version!==SchemaBridgeState.migration.requestVersion)await api('/plans/'+current.plan.id,undefined,'DELETE');
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
    if(s.orphanedDirectories.length){orphans.hidden=false;orphans.textContent=`${s.orphanedDirectories.length} orphaned work-directory folder${s.orphanedDirectories.length===1?'':'s'} found with no matching saved plan (left over from an interrupted run). Review and remove manually if no longer needed.`;}
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
async function loadBuildVersion(){try{const response=await api('/capabilities',undefined,'GET');const info=await response.json();$('buildVersion').textContent='Version '+info.version;}catch(_){$('buildVersion').textContent='Version unavailable';}}
updateFlow();refreshHistory();refreshStorage();loadBuildVersion();
