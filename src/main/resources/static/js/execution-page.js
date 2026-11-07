const validationMethodLabel={FINGERPRINT:'Fingerprint (count + SHA-256)',SAMPLED:'Row count + sampled rows compared',STREAMED:'Row counts (direct transfer)',ROW_COUNT:'Row count only',NONE:'No validation'};
function renderJob(){
  const finalJob = SchemaBridgeState.migration.finalJob;
  renderProgress('job',finalJob.progress);document.getElementById('cancelJob').hidden=!['RUNNING','QUEUED'].includes(finalJob.state);document.getElementById('resumeJob').hidden=!finalJob.resumable;document.getElementById('resumeJob').textContent='Resume migration'; document.getElementById('jobState').textContent=finalJob.state+' — '+finalJob.message; document.getElementById('jobRows').replaceChildren();
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
// Resume has its own connection form: editing it must not invalidate the saved plan.
async function openResumeConnections(){
  const job=SchemaBridgeState.migration.finalJob;
  const button=document.getElementById('resumeJob');if(button.disabled)return;
  button.disabled=true;
  let saved;
  try{saved=await(await api('/jobs/'+job.id+'/connections',{})).json();}
  catch(e){error('Could not load saved connections: '+e.message);return;}
  finally{button.disabled=false;}
  if(SchemaBridgeState.migration.finalJob?.id!==job.id)return;
  const dialog=document.createElement('dialog');dialog.id='resumeConnections';
  dialog.setAttribute('aria-labelledby','resumeConnectionsTitle');
  dialog.innerHTML='<form><h2 id="resumeConnectionsTitle">Resume migration</h2><p>Saved connections, including passwords, are restored from history. You can update them before resuming. Older entries may need credentials entered once.</p><div data-connections></div><p role="status" data-status></p><button type="submit">Resume transfer</button> <button type="button" data-cancel>Cancel</button></form>';
  const form=dialog.querySelector('form'),status=dialog.querySelector('[data-status]'),submit=form.querySelector('[type="submit"]');
  const endpoints=[];
  for(const prefix of job.direct?['source','target']:['target']){
    const fieldset=document.createElement('fieldset');
    fieldset.innerHTML=`<legend>${prefix==='source'?'Source':'Target'} connection</legend><label>Connection source<select data-catalog><option value="">Manual entry</option></select></label><label>Database type<select name="dialect"><option>ORACLE</option><option>POSTGRESQL</option></select></label>`;
    for(const [name,label,type] of [['host','Host','text'],['port','Port','number'],['database','Database / service name','text'],['username','Username','text'],['password','Password','password']]){
      const wrapper=document.createElement('label');wrapper.textContent=label;
      const input=document.createElement('input');input.name=name;input.type=type;input.autocomplete=type==='password'?'new-password':'off';input.required=name!=='password';
      if(name==='port'){input.min='1';input.max='65535';}
      wrapper.append(input);fieldset.append(wrapper);
    }
    const fields=Object.fromEntries(Array.from(fieldset.querySelectorAll('[name]'),field=>[field.name,field]));
    const selected=SchemaBridgeState.migration.catalogSelection[prefix];
    const restored=saved?.[prefix];
    let jdbcUrl=restored?restored.jdbcUrl:selected?.connection.jdbcUrl;
    for(const [name,field] of Object.entries(fields))field.value=restored?(restored[name]??''):document.getElementById(prefix+name[0].toUpperCase()+name.slice(1)).value;
    const chooser=fieldset.querySelector('[data-catalog]');
    // Current LCNC selection preserves special JDBC modes until manual fields change.
    fieldset.addEventListener('input',event=>{if(['host','port','database','dialect'].includes(event.target.name))jdbcUrl=undefined;});
    let version=0,pending=false;
    chooser.addEventListener('change',async()=>{
      const current=++version;pending=!!chooser.value;
      jdbcUrl=undefined;
      if(!chooser.value){for(const field of Object.values(fields))field.disabled=false;return;}
      for(const field of Object.values(fields))field.disabled=true;
      try{
        const selection=await(await api('/datasources/'+encodeURIComponent(chooser.value)+'/select',{})).json();
        if(current!==version||!dialog.isConnected)return;
        for(const [name,field] of Object.entries(fields))field.value=selection.connection[name]??'';
        jdbcUrl=selection.connection.jdbcUrl;status.textContent='Datasource loaded.';
      }catch(e){if(current===version){chooser.value='';for(const field of Object.values(fields))field.disabled=false;status.textContent=e.message;}}
      finally{if(current===version)pending=false;}
    });
    endpoints.push({prefix,chooser,read:()=>{
      if(pending)throw new Error('Wait for the selected datasource to load.');
      const value=Object.fromEntries(Object.entries(fields).map(([name,field])=>[name,field.value]));
      value.port=Number(value.port);value.host=value.host.trim();value.database=value.database.trim();
      if(!value.host||!value.database||!value.username.trim())throw new Error('Complete the '+prefix+' connection fields.');
      return {...value,...(jdbcUrl?{jdbcUrl}:{})};
    }});
    dialog.querySelector('[data-connections]').append(fieldset);
  }
  dialog.querySelector('[data-cancel]').addEventListener('click',()=>dialog.close());
  dialog.addEventListener('close',()=>dialog.remove());
  form.addEventListener('submit',async event=>{
    event.preventDefault();if(submit.disabled||!form.reportValidity())return;
    submit.disabled=true;
    try{
      const connections=Object.fromEntries(endpoints.map(endpoint=>[endpoint.prefix,endpoint.read()]));
      const resumed=await(await api('/jobs/'+job.id+'/resume',job.direct?connections:connections.target)).json();
      SchemaBridgeState.migration.finalJob=resumed;renderJob();dialog.close();pollJob();
    }catch(e){status.textContent=e.message;}
    finally{submit.disabled=false;}
  });
  document.body.append(dialog);dialog.showModal();
  api('/datasources',undefined,'GET').then(response=>response.json()).then(result=>{
    if(!dialog.isConnected)return;
    for(const {chooser} of endpoints)for(const item of result.datasources||[]){
      const option=new Option(item.name+' (#'+item.id+')',String(item.id));option.disabled=!item.supported;chooser.add(option);
    }
  }).catch(()=>{if(dialog.isConnected)status.textContent='Could not load LCNC datasources. You can enter connections manually.';});
}
document.getElementById('resumeJob').addEventListener('click',openResumeConnections);
