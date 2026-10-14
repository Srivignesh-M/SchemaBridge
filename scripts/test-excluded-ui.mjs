import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {createRequire} from 'node:module';
const require=createRequire(new URL('../target/ui-test/package.json',import.meta.url)),{JSDOM}=require('jsdom');
const dom=new JSDOM(await readFile(new URL('../src/main/resources/static/index.html',import.meta.url),'utf8'),{url:'http://localhost:8098',runScripts:'outside-only'});
const {window}=dom,requests=[];
window.fetch=async(path,options={})=>{requests.push({path,body:options.body?JSON.parse(options.body):null});
  const data=path.endsWith('/execute')?{id:'job1',planId:'plan1',state:'QUEUED',tables:[],progress:{}}:path.endsWith('/jobs')?[]:path==='/api/storage'?{plans:[],orphanedDirectories:[]}:{id:'job1',planId:'plan1',state:'RUNNING',tables:[],progress:{}};
  return {ok:true,json:async()=>data,text:async()=>''};};
const el=id=>window.document.getElementById(id),settle=()=>new Promise(resolve=>setTimeout(resolve,20));
try{
  const JS_FILES=['format','api','toast','state','status-badge','progress-panel','connection-form','datasource-catalog','flow-and-upload','migration-stepper','table-picker','review-page','execution-page','revert-panel','history-page','storage-page','settings-page','dashboard-page','shell','main'];
  let combined='';for(const file of JS_FILES)combined+=await readFile(new URL(`../src/main/resources/static/js/${file}.js`,import.meta.url),'utf8')+'\n';
  window.eval(combined+'\nwindow.migrationState=()=>SchemaBridgeState.migration;');await settle();
  const m=window.migrationState();
  const table={table:'standalone',status:'NEW',differences:[],warnings:[],rows:2,allowedActions:['CREATE_AND_LOAD','SKIP'],columnComparison:[],dependsOn:[]};
  const plan=(excluded)=>({id:'plan1',tables:[table],issues:[],warnings:[],excluded,targetChecked:true,orderedScript:false,prepared:true,options:{maxRows:2000000}});

  // A plan with excluded tables lists them and keeps Run disabled until confirmed.
  m.plan=plan([{table:'parent',reason:'Unsupported catalog type: jsonb'},{table:'child',reason:'References parent, which cannot be migrated'}]);window.renderPlan();
  assert.equal(el('excludedSection').hidden,false);
  assert.equal(el('excludedTitle').textContent,"2 tables can't be migrated");
  assert.deepEqual(Array.from(el('excludedRows').rows).map(r=>r.cells[0].textContent),['parent','child']);
  assert.match(el('excludedRows').rows[1].cells[1].textContent,/References parent/);
  assert.equal(el('skipExcludedLabel').hidden,false);assert.match(el('skipExcludedText').textContent,/Skip the 2 excluded tables/);
  assert.equal(el('execute').disabled,true,'Run must wait for confirmation');
  el('skipExcluded').checked=true;el('skipExcluded').dispatchEvent(new window.Event('change'));
  assert.equal(el('execute').disabled,false,'Confirming enables Run');
  el('skipExcluded').checked=false;el('skipExcluded').dispatchEvent(new window.Event('change'));
  assert.equal(el('execute').disabled,true,'Unticking disables Run again');

  // The confirmation is sent to the server with the run request.
  el('targetUsername').value='tester';el('skipExcluded').checked=true;el('skipExcluded').dispatchEvent(new window.Event('change'));
  el('execute').click();await settle();
  const run=requests.find(r=>r.path==='/api/plans/plan1/execute');
  assert.ok(run,'Run must call execute');assert.equal(run.body.skipExcluded,true);assert.deepEqual(run.body.actions,{standalone:'CREATE_AND_LOAD'});

  // Re-rendering a plan resets the confirmation; a plan that already ran stays locked.
  window.renderPlan();assert.equal(el('skipExcluded').checked,false);
  el('skipExcluded').checked=true;el('skipExcluded').dispatchEvent(new window.Event('change'));
  assert.equal(el('execute').disabled,true,'A plan that already has a job must not be runnable again');

  // A plan with nothing excluded shows no panel and needs no confirmation.
  m.finalJob=null;m.plan=plan([]);window.renderPlan();
  assert.equal(el('excludedSection').hidden,true);assert.equal(el('skipExcludedLabel').hidden,true);assert.equal(el('execute').disabled,false);
  // Older saved plans have no excluded field at all.
  const legacy=plan([]);delete legacy.excluded;m.plan=legacy;window.renderPlan();
  assert.equal(el('excludedSection').hidden,true);assert.equal(el('execute').disabled,false);
  console.log('Excluded tables UI passed: list and reasons, Run gated on confirmation, confirmation sent, reset on re-render, already-run lock, legacy plans.');
}finally{window.close();}
