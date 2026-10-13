function revertBusy(job){return !!job?.revert && ['QUEUED','RUNNING'].includes(job.revert.state);}
function jobBusy(job){return ['QUEUED','RUNNING'].includes(job.state)||revertBusy(job);}
function clearRevertReview(){SchemaBridgeState.migration.pendingRevert=null;document.getElementById('revertReview').hidden=true;document.getElementById('acknowledgeRevert').checked=false;document.getElementById('confirmRevert').disabled=true;}
function renderRevert(){
  const finalJob = SchemaBridgeState.migration.finalJob;
  const r=finalJob?.revert;
  document.getElementById('revertSection').hidden=!finalJob||(finalJob.state!=='SUCCEEDED'&&!r);
  if(SchemaBridgeState.migration.pendingRevert&&(SchemaBridgeState.migration.pendingRevert.jobId!==finalJob?.id||revertBusy(finalJob)))clearRevertReview();
  const canReview=finalJob?.state==='SUCCEEDED'&&(!r||['FAILED','PARTIAL'].includes(r.state));
  document.getElementById('reviewRevert').hidden=!canReview;document.getElementById('reviewRevert').disabled=false;document.getElementById('cancelRevert').hidden=!revertBusy(finalJob);
  document.getElementById('revertState').textContent=r?`${r.state}: ${r.message} ${r.progress.rowsCommitted.toLocaleString()} rows removed by committed revert work.`:'Select Review revert to check this job against the target before removing anything.';
  document.getElementById('revertResults').replaceChildren();
  for(const table of r?.tables||[]){const item=document.createElement('li');item.textContent=`${table.table}: ${table.status} · ${table.rows.toLocaleString()} original rows · ${table.action==='DROP_TABLE'?'drop migration-created table':'remove transferred rows'}`;document.getElementById('revertResults').append(item);}
}
document.getElementById('reviewRevert').addEventListener('click',async()=>{
  const id=SchemaBridgeState.migration.finalJob.id;clearRevertReview();document.getElementById('reviewRevert').disabled=true;document.getElementById('revertState').textContent='Checking target data and dependencies…';
  try{
    const review=await(await api('/jobs/'+id+'/revert-preview',connection('target'))).json();
    if(SchemaBridgeState.migration.finalJob?.id!==id)return;SchemaBridgeState.migration.pendingRevert=review;
    document.getElementById('revertTarget').textContent=`Job ${review.jobId} · Target: ${review.target}`;
    document.getElementById('revertExpiry').textContent='Review expires at '+new Date(review.expiresAt).toLocaleTimeString()+'. The target is checked again when you confirm.';
    document.getElementById('revertTables').replaceChildren();
    for(const table of review.tables){const item=document.createElement('li');item.textContent=`${table.table}: ${table.action==='DROP_TABLE'?'remove transferred rows and drop table':'remove only transferred rows'} · ${table.rows.toLocaleString()} original rows · ${table.status}`;document.getElementById('revertTables').append(item);}
    document.getElementById('revertReview').hidden=false;document.getElementById('revertState').textContent='Review the removal below. Nothing has been removed.';document.getElementById('revertReview').scrollIntoView({behavior:'smooth',block:'center'});
  }catch(e){document.getElementById('revertState').textContent=e.message;error(e.message);}finally{document.getElementById('reviewRevert').disabled=false;}
});
document.getElementById('acknowledgeRevert').addEventListener('change',()=>{document.getElementById('confirmRevert').disabled=!SchemaBridgeState.migration.pendingRevert||!document.getElementById('acknowledgeRevert').checked;});
document.getElementById('dismissRevert').addEventListener('click',()=>{clearRevertReview();renderRevert();});
document.getElementById('confirmRevert').addEventListener('click',async()=>{
  const m = SchemaBridgeState.migration;
  if(!m.pendingRevert||!document.getElementById('acknowledgeRevert').checked||m.pendingRevert.jobId!==m.finalJob.id)return;
  const review=m.pendingRevert;document.getElementById('confirmRevert').disabled=true;
  try{m.finalJob=await(await api('/jobs/'+review.jobId+'/revert',{target:connection('target'),token:review.token})).json();clearRevertReview();renderJob();pollJob();}
  catch(e){clearRevertReview();document.getElementById('revertState').textContent=e.message;error(e.message);}
});
document.getElementById('cancelRevert').addEventListener('click',async()=>{try{SchemaBridgeState.migration.finalJob=await(await api('/jobs/'+SchemaBridgeState.migration.finalJob.id+'/cancel',{})).json();renderJob();}catch(e){error(e.message);}});
