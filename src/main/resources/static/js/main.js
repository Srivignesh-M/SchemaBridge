function loadBuildVersion(){ api('/capabilities', undefined, 'GET').then(r => r.json()).then(info => { document.getElementById('buildVersion').textContent = 'Version ' + info.version; }).catch(() => { document.getElementById('buildVersion').textContent = 'Version unavailable'; }); }
connectionForm('source'); connectionForm('target');
document.querySelectorAll('input[name="flow"]').forEach(input => input.addEventListener('change', updateFlow));
document.querySelectorAll('[data-step-nav]').forEach(button => button.addEventListener('click',()=>goToStep(Number(button.dataset.stepNav))));
document.querySelectorAll('[data-back]').forEach(button => button.addEventListener('click',()=>goToStep(Number(button.dataset.back))));
document.getElementById('flowNext').addEventListener('click',()=>{SchemaBridgeState.migration.maxStep=Math.max(SchemaBridgeState.migration.maxStep,2);goToStep(2);});
document.getElementById('reviewNext').addEventListener('click',()=>{if(!SchemaBridgeState.migration.plan)return;SchemaBridgeState.migration.maxStep=Math.max(SchemaBridgeState.migration.maxStep,4);goToStep(4);});
document.getElementById('startOver').addEventListener('click',()=>{const m=SchemaBridgeState.migration;m.plan=null;m.lastRequest=null;m.maxStep=1;document.getElementById('reportSection').hidden=true;document.getElementById('outputActions').hidden=true;document.getElementById('preparationSection').hidden=true;document.getElementById('jobSection').hidden=true;goToStep(1);});
document.getElementById('configuration').addEventListener('input', invalidate);
for (const prefix of ['source','target']) document.getElementById(prefix + 'Dialect').addEventListener('change', () => { if(prefix==='source'){document.getElementById('columnOptions').replaceChildren();fillOptions(document.getElementById('sourceTables'), []);if(document.getElementById('tablePickerList'))renderTablePicker();} applyDialect(prefix); fillOptions(document.getElementById(prefix + 'SchemaSelect'), []); if(prefix !== 'source') document.getElementById('targetSchema').value = document.getElementById('targetDialect').value === 'ORACLE' ? 'APP' : 'public'; });
for (const id of ['files','folder']) document.getElementById(id).addEventListener('change', () => loadSqlFiles(document.getElementById(id)));
document.getElementById('example').addEventListener('click', () => { document.getElementById('sourceDialect').value = 'ORACLE'; document.getElementById('targetDialect').value = 'POSTGRESQL'; document.getElementById('sql').value = "CREATE TABLE customers (\n  id NUMBER(10,0) PRIMARY KEY,\n  name VARCHAR2(100) NOT NULL,\n  joined_at DATE\n);\nINSERT INTO customers (id,name,joined_at) VALUES (1,'Anita',TIMESTAMP '2026-01-15 09:30:00');\nINSERT INTO customers (id,name,joined_at) VALUES (2,'Sam',NULL);"; invalidate(); });
document.getElementById('configuration').addEventListener('submit', async event => {
  const m = SchemaBridgeState.migration;
  event.preventDefault(); document.getElementById('analyse').disabled = true; document.getElementById('configStatus').textContent = 'Reading source and comparing target definitions…';
  try {
    if(usesDatabase()&&!selectedSourceTables().length)throw new Error('Select at least one source table before analysis.');
    const request = {options:null,selections:usesDatabase()?tableSelections():{},metadataOnly:usesDatabase(),sourceDialect:document.getElementById('sourceDialect').value,targetDialect:document.getElementById('targetDialect').value,targetSchema:document.getElementById('targetSchema').value.trim(),sql:usesDatabase()?null:document.getElementById('sql').value,source:usesDatabase()?connection('source'):null,sourceSchema:usesDatabase()?document.getElementById('sourceSchemaSelect').value:null,tables:usesDatabase()?Array.from(document.getElementById('sourceTables').selectedOptions).map(o=>o.value):[],includeData:document.getElementById('includeData').checked,target:writesDatabase()?connection('target'):null};
    m.lastRequest=request; const version=m.requestVersion;
    const context={database:usesDatabase(),target:Boolean(request.target)};
    const started=await(await api('/preparations',request)).json();m.analysisId=started.id;showAnalysis(context);
    let current=started;
    while(['QUEUED','PREPARING'].includes(current.state)){await new Promise(resolve=>setTimeout(resolve,600));current=await(await api('/preparations/'+started.id,undefined,'GET')).json();renderAnalysis(current,context);}
    if(current.state==='CANCELLED'){document.getElementById('configStatus').textContent='Analysis cancelled.';return;}
    if(current.state!=='READY')throw new Error(current.message);
    const result=current.plan; if(version!==m.requestVersion){await api('/plans/'+result.id,undefined,'DELETE');return;} m.plan=result; renderPlan(); document.getElementById('preview').textContent = await (await api('/plans/' + m.plan.id + '/preview', undefined, 'GET')).text(); document.getElementById('configStatus').textContent = 'Analysis complete. Review the proposed changes.'; m.maxStep=Math.max(m.maxStep,3);goToStep(3);
  } catch(e) { error(e.message); document.getElementById('configStatus').textContent = 'Analysis failed. Check the message and try again.'; } finally { m.analysisId=null; hideAnalysis(); document.getElementById('analyse').disabled = false; }
});
document.getElementById('cancelAnalysis').addEventListener('click',async()=>{try{const id=SchemaBridgeState.migration.analysisId;if(id){document.getElementById('cancelAnalysis').disabled=true;await api('/preparations/'+id+'/cancel',{});}}catch(e){error(e.message);}});
updateFlow();
loadBuildVersion();
navigateToPage('dashboard');
