function jobStateLabel(state){return ({QUEUED:'Queued',RUNNING:'Running',SUCCEEDED:'Completed',FAILED:'Failed',CANCELLED:'Cancelled',RECOVERY_REQUIRED:'Needs reconciliation'})[state]||state;}
function hasUnfinishedMigrationWork(){
  const m = SchemaBridgeState.migration;
  // A plan exists that isn't the one the current finalJob was executed from — covers both
  // "analyzed but never executed" (finalJob null) and "re-analyzed after an older job already
  // finished" (finalJob stale, still referencing a previous planId).
  if (m.plan && m.plan.id !== m.finalJob?.planId) return true;
  if (m.finalJob && m.lastRequest === null && m.plan === null && m.requestVersion > 0) return true; // invalidate() ran after a finished job, with no re-analysis yet
  return false;
}
// Clears the migration page if it is showing a plan (or that plan's job) that was just deleted.
function forgetDeletedPlan(planId){
  const m = SchemaBridgeState.migration;
  if(m.finalJob?.planId===planId){clearTimeout(m.polling);m.polling=null;m.finalJob=null;document.getElementById('jobSection').hidden=true;document.getElementById('revertSection').hidden=true;clearRevertReview();}
  if(m.plan?.id===planId)invalidate();
}
const UNCERTAIN_DISCARD_PROMPT='This job\'s commit outcome is uncertain — these files are the evidence needed to reconcile the target (see RECOVERY-RUNBOOK.md). Only discard them after you have manually verified and reconciled the target database. Discard anyway?';
function jobHistoryCard(job){
  const card=document.createElement('article');card.className='history-card';card.setAttribute('role','listitem');
  const heading=document.createElement('div');heading.className='history-card-heading';
  const displayState=job.revert?.state||job.state;
  heading.append(statusBadge('job', displayState));
  const id=document.createElement('code');id.textContent=job.id.slice(0,8);heading.append(id);card.append(heading);
  const summary=document.createElement('p');const committed=Number(job.progress?.rowsCommitted||0);summary.className='history-summary';summary.textContent=`${job.tables.length} table${job.tables.length===1?'':'s'} · ${committed.toLocaleString()} ${job.state==='RECOVERY_REQUIRED'?'rows recorded committed':'rows committed'}`;card.append(summary);
  const detail=document.createElement('p');detail.className='history-detail';detail.textContent=job.revert?.message||job.message;card.append(detail);
  const actions=document.createElement('div');actions.className='history-actions';
  const view=document.createElement('button');view.className='secondary';view.textContent='View report / revert';view.addEventListener('click',()=>openHistoryJob(job));actions.append(view);
  if(!jobBusy(job)){
    const uncertain=job.state==='RECOVERY_REQUIRED'||['PARTIAL','RECOVERY_REQUIRED'].includes(job.revert?.state);
    const remove=document.createElement('button');remove.className=uncertain?'secondary danger':'secondary';remove.textContent=uncertain?'Review and discard evidence':'Delete saved files';
    remove.addEventListener('click',async()=>{
      const prompt=uncertain?UNCERTAIN_DISCARD_PROMPT:'Delete this saved migration and its exported data? Target database rows will remain.';
      if(!window.confirm(prompt))return;
      remove.disabled=true;
      try{await api('/plans/'+job.planId+(uncertain?'?acknowledgeUncertain=true':''),undefined,'DELETE');forgetDeletedPlan(job.planId);await refreshHistory();}catch(e){remove.disabled=false;error(e.message);}
    });
    actions.append(remove);
  }
  card.append(actions);return card;
}
async function openHistoryJob(job){
  if (hasUnfinishedMigrationWork() && !window.confirm('Opening this saved migration will replace your current unsaved configuration. Continue, or keep editing?')) return;
  const m = SchemaBridgeState.migration;
  try{
    clearTimeout(m.polling);clearRevertReview();m.finalJob=await(await api('/jobs/'+job.id,undefined,'GET')).json();document.getElementById('jobSection').hidden=false;renderJob();m.plan=await(await api('/plans/'+job.planId,undefined,'GET')).json();renderPlan();document.getElementById('execute').disabled=true;document.getElementById('preview').textContent=await(await api('/plans/'+job.planId+'/preview',undefined,'GET')).text();if(jobBusy(m.finalJob))pollJob();m.maxStep=4;goToStep(4);
    if (typeof navigateToPage === 'function') navigateToPage('migration');
  }catch(e){error(e.message);}
}
async function refreshHistory(){
  const button=document.getElementById('refreshHistory'),status=document.getElementById('jobHistoryStatus');button.disabled=true;status.textContent='Loading saved migrations…';
  try{const jobs=await(await api('/jobs',undefined,'GET')).json();const list=document.getElementById('jobHistory');list.replaceChildren();
    if(!jobs.length){list.textContent='No saved migrations.';status.textContent='History is up to date.';return;}
    for(const job of jobs)list.append(jobHistoryCard(job));status.textContent=`${jobs.length} saved migration${jobs.length===1?'':'s'} loaded.`;
  }catch(e){status.textContent='Could not refresh migration history.';error(e.message);}
  finally{button.disabled=false;}
}
document.getElementById('refreshHistory').addEventListener('click',refreshHistory);
