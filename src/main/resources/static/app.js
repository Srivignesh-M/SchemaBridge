'use strict';
const $ = id => document.getElementById(id);
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
function transferOptions(){return {maxRows:Number($('maxRows').value),maxTableBytes:Math.round(Number($('maxTableGb').value)*1e9),diskReserveBytes:Math.round(Number($('diskReserveMb').value)*1e6),fetchSize:Number($('fetchSize').value),batchRows:Number($('batchRows').value),batchBytes:Math.round(Number($('batchMb').value)*1e6),queryTimeoutSeconds:Number($('queryTimeout').value),readTimeoutSeconds:Number($('readTimeout').value),validateData:$('validateData').checked,useCopy:$('useCopy').checked};}
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
