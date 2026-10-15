import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {createRequire} from 'node:module';
const require=createRequire(new URL('../target/ui-test/package.json',import.meta.url)),{JSDOM}=require('jsdom');
const dom=new JSDOM(await readFile(new URL('../src/main/resources/static/index.html',import.meta.url),'utf8'),{url:'http://localhost:8098',runScripts:'outside-only'});
const {window}=dom,requests=[];
window.fetch=async(path,options={})=>{requests.push({path,body:options.body?JSON.parse(options.body):null});
  const job={id:'job1',planId:'plan1',state:'FAILED',tables:[],progress:{},resumable:true,direct:true,message:'Source table gone'};
  const data=path.endsWith('/execute')||path.endsWith('/resume')||path==='/api/jobs/job1'?job:path.endsWith('/jobs')?[]:path==='/api/storage'?{plans:[],orphanedDirectories:[]}:{};
  return {ok:true,json:async()=>data,text:async()=>''};};
const el=id=>window.document.getElementById(id),settle=()=>new Promise(resolve=>setTimeout(resolve,20));
const method=value=>{const input=window.document.querySelector(`input[name="transferMethod"][value="${value}"]`);input.checked=true;input.dispatchEvent(new window.Event('change'));};
try{
  const JS_FILES=['format','api','toast','state','status-badge','progress-panel','connection-form','datasource-catalog','flow-and-upload','migration-stepper','table-picker','review-page','execution-page','revert-panel','history-page','storage-page','settings-page','dashboard-page','shell','main'];
  let combined='';for(const file of JS_FILES)combined+=await readFile(new URL(`../src/main/resources/static/js/${file}.js`,import.meta.url),'utf8')+'\n';
  window.eval(combined+'\nwindow.migrationState=()=>SchemaBridgeState.migration;');await settle();
  const m=window.migrationState();
  const table={table:'items',status:'NEW',differences:[],warnings:[],rows:0,estimatedRows:300,allowedActions:['CREATE_AND_LOAD','SKIP'],columnComparison:[],dependsOn:[]};
  const plan=(source,target)=>({id:'plan1',sourceDialect:source,targetDialect:target,tables:[table],issues:[],warnings:[],excluded:[],targetChecked:true,orderedScript:false,prepared:false,options:{}});
  const source={dialect:'POSTGRESQL',host:'source-db',port:5432,database:'postgres',username:'reader',password:'pw'};

  // PostgreSQL to PostgreSQL, analysed in this session: both methods offered, staged by default.
  m.lastRequest={source};m.plan=plan('POSTGRESQL','POSTGRESQL');window.renderPlan();
  assert.equal(el('transferMethod').hidden,false);
  assert.equal(window.document.querySelector('input[name="transferMethod"]:checked').value,'staged');
  assert.equal(el('execute').disabled,true,'staged needs prepared data first');
  assert.equal(el('prepareData').hidden,false);
  // Direct needs no preparation.
  method('direct');
  assert.equal(el('execute').disabled,false,'direct runs from the analysed plan');
  assert.equal(el('prepareData').hidden,true,'no preparation step for direct');
  method('staged');assert.equal(el('execute').disabled,true);assert.equal(el('prepareData').hidden,false);
  // The run request carries the analysed source connection.
  method('direct');el('targetUsername').value='writer';el('execute').click();await settle();
  const run=requests.find(r=>r.path==='/api/plans/plan1/execute');
  assert.equal(run.body.direct,true);assert.deepEqual(run.body.source,source);assert.deepEqual(run.body.actions,{items:'CREATE_AND_LOAD'});
  // A failed direct job resumes with both connections.
  assert.equal(el('resumeJob').hidden,false);assert.equal(el('resumeJob').textContent,'Resume with current source and target connections');
  el('sourceUsername').value='reader';el('sourceDatabase').value='postgres';el('resumeJob').click();await settle();
  const resume=requests.find(r=>r.path==='/api/jobs/job1/resume');
  assert.ok(resume.body.target&&resume.body.source,'direct resume sends {target, source}');
  assert.equal(resume.body.source.username,'reader');

  // Not offered for other combinations or without the analysed source.
  m.finalJob=null;m.plan=plan('ORACLE','POSTGRESQL');window.renderPlan();
  assert.equal(el('transferMethod').hidden,true);assert.equal(el('execute').disabled,true);
  m.lastRequest=null;m.plan=plan('POSTGRESQL','POSTGRESQL');window.renderPlan();
  assert.equal(el('transferMethod').hidden,true,'plans opened later have no source connection to stream from');
  assert.equal(window.document.querySelector('input[name="transferMethod"]:checked').value,'staged');
  console.log('Direct transfer UI passed: offered for PostgreSQL to PostgreSQL only, staged default, direct skips preparation, source sent on run and resume.');
  await settle();
}finally{window.close();}
