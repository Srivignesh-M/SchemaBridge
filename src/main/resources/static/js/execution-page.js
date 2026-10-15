const validationMethodLabel={FINGERPRINT:'Fingerprint (count + SHA-256)',SAMPLED:'Row count + sampled rows compared',STREAMED:'Row counts (direct transfer)',ROW_COUNT:'Row count only',NONE:'No validation'};
function renderJob(){
  const finalJob = SchemaBridgeState.migration.finalJob;
  renderProgress('job',finalJob.progress);document.getElementById('cancelJob').hidden=!['RUNNING','QUEUED'].includes(finalJob.state);document.getElementById('resumeJob').hidden=!finalJob.resumable;document.getElementById('resumeJob').textContent=finalJob.direct?'Resume with current source and target connections':'Resume with current target connection'; document.getElementById('jobState').textContent=finalJob.state+' — '+finalJob.message; document.getElementById('jobRows').replaceChildren();
  for(const table of finalJob.tables){
    const tr=document.createElement('tr');[table.table,table.action,table.status,table.rows].forEach(value=>cell(tr,value));
    const details=cell(tr,'');
    const v=table.validation;
    if(v){
      const summary=document.createElement('div');
      const scope=v.method==='NONE'?(v.skippedReason||''):`${validationMethodLabel[v.method]||v.method} · ${v.rowsChecked.toLocaleString()} rows checked · ${v.durationSeconds.toFixed(2)}s`;
      summary.textContent=scope; details.append(summary);
    }
    const message=document.createElement('div'); message.className='muted'; message.textContent=table.message; details.append(message);
    document.getElementById('jobRows').append(tr);
  }
  renderRevert();
}
async function pollJob(){
  const m = SchemaBridgeState.migration;
  clearTimeout(m.polling);
  try{m.finalJob=await(await api('/jobs/'+m.finalJob.id,undefined,'GET')).json();renderJob();if(jobBusy(m.finalJob)){if(currentPageId()==='migration')m.polling=setTimeout(pollJob,1500);}else refreshHistory();}catch(e){error(e.message);if(currentPageId()==='migration')m.polling=setTimeout(pollJob,5000);}
}
document.getElementById('jobDownload').addEventListener('click',()=>save(new Blob([JSON.stringify(SchemaBridgeState.migration.finalJob,null,2)],{type:'application/json'}),'execution-report.json'));
document.getElementById('prepareData').addEventListener('click',async()=>{
  const m = SchemaBridgeState.migration;
  if(!m.lastRequest)return;try{
  const request={...m.lastRequest,metadataOnly:false};
  const started=await(await api('/preparations',request)).json();
  m.preparationId=started.id;m.preparationContext={version:m.requestVersion,previousPlan:m.plan.id};
  document.getElementById('preparationSection').hidden=false;document.getElementById('cancelPreparation').hidden=false;document.getElementById('prepareData').disabled=true;
  await pollPreparation();
}catch(e){error(e.message);document.getElementById('prepareData').disabled=false;}});
// Follows the running preparation. Leaving the migration page pauses it (shell.js resumes it on return),
// and a failed request is retried instead of silently ending the updates.
async function pollPreparation(){
  const m=SchemaBridgeState.migration,id=m.preparationId;clearTimeout(m.preparationPoll);m.preparationPoll=null;
  if(!id||m.preparationInFlight)return;
  const again=delay=>{if(currentPageId()==='migration'&&m.preparationId===id)m.preparationPoll=setTimeout(pollPreparation,delay);};
  let current;m.preparationInFlight=true;
  try{current=await(await api('/preparations/'+id,undefined,'GET')).json();}
  catch(e){document.getElementById('preparationState').textContent='Lost contact with SchemaBridge; retrying… ('+e.message+')';again(3000);return;}
  finally{m.preparationInFlight=false;}
  if(m.preparationId!==id)return;
  document.getElementById('preparationState').textContent=current.message;renderProgress('preparation',current.progress);
  if(['QUEUED','PREPARING'].includes(current.state)){again(1000);return;}
  const {version,previousPlan}=m.preparationContext||{};m.preparationId=null;m.preparationContext=null;
  document.getElementById('cancelPreparation').hidden=true;document.getElementById('prepareData').disabled=false;
  try{
    if(current.state==='READY'&&version===m.requestVersion){m.plan=current.plan;renderPlan();document.getElementById('preview').textContent=await(await api('/plans/'+m.plan.id+'/preview',undefined,'GET')).text();if(previousPlan)await api('/plans/'+previousPlan,undefined,'DELETE');}
    if(current.state==='READY'&&version!==m.requestVersion)await api('/plans/'+current.plan.id,undefined,'DELETE');
    if(current.state==='FAILED')error(current.message);
  }catch(e){error(e.message);}
}
document.getElementById('cancelPreparation').addEventListener('click',async()=>{try{const id=SchemaBridgeState.migration.preparationId;if(id)await api('/preparations/'+id+'/cancel',{});}catch(e){error(e.message);}});
document.getElementById('cancelJob').addEventListener('click',async()=>{try{SchemaBridgeState.migration.finalJob=await(await api('/jobs/'+SchemaBridgeState.migration.finalJob.id+'/cancel',{})).json();renderJob();}catch(e){error(e.message);}});
document.getElementById('resumeJob').addEventListener('click',async()=>{try{const job=SchemaBridgeState.migration.finalJob;SchemaBridgeState.migration.finalJob=await(await api('/jobs/'+job.id+'/resume',job.direct?{target:connection('target'),source:connection('source')}:connection('target'))).json();renderJob();pollJob();}catch(e){error(e.message);}});
