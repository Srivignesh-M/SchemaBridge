const validationMethodLabel={FINGERPRINT:'Fingerprint (count + SHA-256)',ROW_COUNT:'Row count only',NONE:'No validation'};
function renderJob(){
  const finalJob = SchemaBridgeState.migration.finalJob;
  renderProgress('job',finalJob.progress);document.getElementById('cancelJob').hidden=!['RUNNING','QUEUED'].includes(finalJob.state);document.getElementById('resumeJob').hidden=!finalJob.resumable; document.getElementById('jobState').textContent=finalJob.state+' — '+finalJob.message; document.getElementById('jobRows').replaceChildren();
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
  try{m.finalJob=await(await api('/jobs/'+m.finalJob.id,undefined,'GET')).json();renderJob();if(jobBusy(m.finalJob))m.polling=setTimeout(pollJob,1500);else refreshHistory();}catch(e){error(e.message);m.polling=setTimeout(pollJob,5000);}
}
document.getElementById('jobDownload').addEventListener('click',()=>save(new Blob([JSON.stringify(SchemaBridgeState.migration.finalJob,null,2)],{type:'application/json'}),'execution-report.json'));
document.getElementById('prepareData').addEventListener('click',async()=>{
  const m = SchemaBridgeState.migration;
  if(!m.lastRequest)return;try{
  const version=m.requestVersion;const previousPlan=m.plan.id;const request={...m.lastRequest,metadataOnly:false};
  const started=await(await api('/preparations',request)).json();m.preparationId=started.id;document.getElementById('preparationSection').hidden=false;document.getElementById('cancelPreparation').hidden=false;document.getElementById('prepareData').disabled=true;
  async function poll(){try{const current=await(await api('/preparations/'+started.id,undefined,'GET')).json();document.getElementById('preparationState').textContent=current.message;renderProgress('preparation',current.progress);
    if(['QUEUED','PREPARING'].includes(current.state)){m.preparationPoll=setTimeout(poll,1000);return;}
    document.getElementById('cancelPreparation').hidden=true;document.getElementById('prepareData').disabled=false;if(current.state==='READY'&&version===m.requestVersion){m.plan=current.plan;renderPlan();document.getElementById('preview').textContent=await(await api('/plans/'+m.plan.id+'/preview',undefined,'GET')).text();await api('/plans/'+previousPlan,undefined,'DELETE');}
    if(current.state==='READY'&&version!==m.requestVersion)await api('/plans/'+current.plan.id,undefined,'DELETE');
    if(current.state==='FAILED')error(current.message);
  }catch(e){document.getElementById('prepareData').disabled=false;error(e.message);}}
  await poll();
}catch(e){error(e.message);document.getElementById('prepareData').disabled=false;}});
document.getElementById('cancelPreparation').addEventListener('click',async()=>{try{const id=SchemaBridgeState.migration.preparationId;if(id)await api('/preparations/'+id+'/cancel',{});}catch(e){error(e.message);}});
document.getElementById('cancelJob').addEventListener('click',async()=>{try{SchemaBridgeState.migration.finalJob=await(await api('/jobs/'+SchemaBridgeState.migration.finalJob.id+'/cancel',{})).json();renderJob();}catch(e){error(e.message);}});
document.getElementById('resumeJob').addEventListener('click',async()=>{try{SchemaBridgeState.migration.finalJob=await(await api('/jobs/'+SchemaBridgeState.migration.finalJob.id+'/resume',connection('target'))).json();renderJob();pollJob();}catch(e){error(e.message);}});
