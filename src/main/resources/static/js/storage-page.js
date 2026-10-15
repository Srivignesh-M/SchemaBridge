function storageDate(iso){const date=new Date(iso);return Number.isNaN(date.getTime())?'—':date.toLocaleDateString(undefined,{day:'numeric',month:'short',year:'numeric'});}
async function deleteStoredPlan(plan,button){
  const prompt=plan.uncertain?UNCERTAIN_DISCARD_PROMPT
    :plan.state==='INTERRUPTED'?`Delete this interrupted preparation and its ${formatBytes(plan.bytes)} of staged tables? Preparing again then starts from the first table. Nothing changes in any database.`
    :plan.state==='NOT_RUN'?`Delete this plan that never ran and its ${formatBytes(plan.bytes)} of local files? Nothing changes in any database.`
    :'Delete this saved migration and its exported data? Target database rows will remain.';
  if(!window.confirm(prompt))return;
  button.disabled=true;
  try{await api('/plans/'+plan.id+(plan.uncertain?'?acknowledgeUncertain=true':''),undefined,'DELETE');forgetDeletedPlan(plan.id);await refreshStorage();}
  catch(e){button.disabled=false;error(e.message);}
}
function storagePlanRow(plan){
  const row=document.createElement('tr');row.dataset.planId=plan.id;
  const cell=(...content)=>{const td=document.createElement('td');td.append(...content);row.append(td);return td;};
  const status=cell(statusBadge('plan',plan.state));
  if(plan.state==='NOT_RUN'){const note=document.createElement('div');note.className='muted';note.textContent=plan.prepared?'Data prepared':'Definitions only';status.append(note);}
  if(plan.state==='INTERRUPTED'){const note=document.createElement('div');note.className='muted';note.textContent=plan.active?'Preparing now':'Prepare again with the same selection to continue';status.append(note);}
  const id=document.createElement('code');id.textContent=plan.id.slice(0,8);id.title=plan.id;cell(id);
  cell(`${plan.source||'—'} → ${plan.target||'—'}`).className='storage-route';
  cell(plan.state==='INTERRUPTED'?`${Number(plan.tables||0).toLocaleString()} of ${Number(plan.tablesTotal||0).toLocaleString()} staged`:Number(plan.tables||0).toLocaleString());
  cell(formatBytes(plan.bytes));
  cell(storageDate(plan.expiresAt));
  const button=document.createElement('button');button.type='button';
  if(plan.active){button.className='secondary';button.textContent=plan.state==='INTERRUPTED'?'Preparing':'Running';button.disabled=true;}
  else{button.className=plan.uncertain?'secondary danger':'secondary';button.textContent=plan.uncertain?'Review and discard evidence':'Delete';button.setAttribute('aria-label',`${button.textContent} plan ${plan.id.slice(0,8)}`);button.addEventListener('click',()=>deleteStoredPlan(plan,button));}
  cell(button);
  return row;
}
async function deleteUnusedPlans(plans){
  const total=plans.reduce((sum,plan)=>sum+Number(plan.bytes||0),0);
  if(!window.confirm(`Delete ${plans.length} plan${plans.length===1?'':'s'} that never ran (${formatBytes(total)} of local files)? Nothing changes in any database.`))return;
  const button=document.getElementById('deleteUnusedPlans');button.disabled=true;const failed=[];
  for(const plan of plans){try{await api('/plans/'+plan.id,undefined,'DELETE');forgetDeletedPlan(plan.id);}catch(e){failed.push(`${plan.id.slice(0,8)}: ${e.message}`);}}
  if(failed.length)error(`Could not delete ${failed.length} plan${failed.length===1?'':'s'}. ${failed.join('; ')}`);
  button.disabled=false;await refreshStorage();
}
async function refreshStorage(){
  const button=document.getElementById('refreshStorage');button.disabled=true;
  try{
    const s=await(await api('/storage',undefined,'GET')).json();
    const metrics=document.getElementById('storageMetrics');metrics.replaceChildren();
    for(const [label,value] of [['Staged data',formatBytes(s.stagedBytes)],['Free disk',formatBytes(s.freeDiskBytes)],['Reclaimable on next cleanup',formatBytes(s.reclaimableBytes)],['Saved plans',String(s.plans.length)]]){
      const div=document.createElement('div');const dt=document.createElement('dt');dt.textContent=label;const dd=document.createElement('dd');dd.textContent=value;div.append(dt,dd);metrics.append(div);
    }
    const orphans=document.getElementById('storageOrphans');
    if(s.orphanedDirectories.length){orphans.hidden=false;orphans.textContent=`${s.orphanedDirectories.length} orphaned work-directory folder${s.orphanedDirectories.length===1?'':'s'} found with no matching saved plan (left over from an interrupted run). Review and remove manually if no longer needed.`;}
    else orphans.hidden=true;
    const table=document.getElementById('storagePlans'),body=table.tBodies[0];body.replaceChildren(...s.plans.map(storagePlanRow));
    table.hidden=!s.plans.length;document.getElementById('storagePlansEmpty').hidden=Boolean(s.plans.length);
    const unused=s.plans.filter(plan=>['NOT_RUN','INTERRUPTED'].includes(plan.state)&&!plan.active),bulk=document.getElementById('deleteUnusedPlans');
    bulk.hidden=!unused.length;bulk.textContent=`Delete all plans that never ran (${unused.length})`;bulk.onclick=()=>deleteUnusedPlans(unused);
  }catch(e){error(e.message);}
  finally{button.disabled=false;}
}
document.getElementById('refreshStorage').addEventListener('click',refreshStorage);
