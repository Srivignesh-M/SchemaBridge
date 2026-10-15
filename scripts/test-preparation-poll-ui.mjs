import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {createRequire} from 'node:module';
const require=createRequire(new URL('../target/ui-test/package.json',import.meta.url)),{JSDOM}=require('jsdom');
const dom=new JSDOM(await readFile(new URL('../src/main/resources/static/index.html',import.meta.url),'utf8'),{url:'http://localhost:8098',runScripts:'outside-only'});
const {window}=dom,calls=[];
const table={table:'codes',status:'NEW',differences:[],warnings:[],rows:2,allowedActions:['CREATE_AND_LOAD','SKIP'],columnComparison:[],dependsOn:[]};
const prepared={id:'prepared',tables:[table],issues:[],warnings:[],excluded:[],targetChecked:true,orderedScript:false,prepared:true,options:{}};
let next=[];   // queued responses for GET /api/preparations/p1
const ok=body=>({ok:true,status:200,json:async()=>body,text:async()=>'CREATE TABLE codes(...)'});
window.fetch=async(path,options={})=>{
  const method=options.method||'GET';calls.push(`${method} ${path}`);
  if(path==='/api/preparations'&&method==='POST')return ok({id:'p1',state:'QUEUED',progress:{}});
  if(path==='/api/preparations/p1'){const r=next.length>1?next.shift():next[0];return r==='fail'?{ok:false,status:500,json:async()=>({message:'Request failed (500)'})}:ok(r);}
  if(path==='/api/storage')return ok({plans:[],orphanedDirectories:[]});
  return ok(path.endsWith('/jobs')?[]:{});
};
const el=id=>window.document.getElementById(id),wait=ms=>new Promise(resolve=>setTimeout(resolve,ms));
const polls=()=>calls.filter(c=>c==='GET /api/preparations/p1').length;
const running=done=>({id:'p1',state:'PREPARING',message:'Reading source and preparing an immutable export',progress:{phase:'EXTRACTING',table:`t${done}`,tablesDone:done,tablesTotal:857}});
try{
  const JS_FILES=['format','api','toast','state','status-badge','progress-panel','connection-form','datasource-catalog','flow-and-upload','migration-stepper','table-picker','review-page','execution-page','revert-panel','history-page','storage-page','settings-page','dashboard-page','shell','main'];
  let combined='';for(const file of JS_FILES)combined+=await readFile(new URL(`../src/main/resources/static/js/${file}.js`,import.meta.url),'utf8')+'\n';
  window.eval(combined+'\nwindow.migrationState=()=>SchemaBridgeState.migration;');
  const m=window.migrationState();
  await window.navigateToPage('migration');
  m.lastRequest={metadataOnly:true};m.plan={id:'metadata-only'};

  // Prepare data starts polling.
  next=[running(10)];el('prepareData').click();await wait(50);
  assert.equal(m.preparationId,'p1');assert.ok(polls()>=1);
  assert.match(el('preparationTableProgress').textContent,/Table 11 of 857/);

  // Leaving the migration page pauses updates...
  await window.navigateToPage('storage');const paused=polls();await wait(1300);
  assert.equal(polls(),paused,'No updates while another page is open');
  // ...and returning resumes them (this was the bug: they never restarted).
  next=[running(200)];await window.navigateToPage('migration');await wait(50);
  assert.ok(polls()>paused,'Returning to Migration must resume updates');
  assert.match(el('preparationTableProgress').textContent,/Table 201 of 857/);

  // A failed update is retried instead of ending updates for good.
  next=['fail',running(500)];await wait(1100);
  assert.match(el('preparationState').textContent,/Lost contact with SchemaBridge; retrying/);
  await wait(3200);
  assert.match(el('preparationTableProgress').textContent,/Table 501 of 857/,'Updates continue after a failed request');

  // Returning while a request is in flight must not start a second update loop.
  const before=polls();await window.navigateToPage('storage');await window.navigateToPage('migration');await window.navigateToPage('migration');await wait(1100);
  assert.ok(polls()-before<=3,'Only one update loop may run');

  // Completion loads the prepared plan and removes the superseded metadata-only plan.
  next=[{id:'p1',state:'READY',message:'Prepared for review',progress:{phase:'EXTRACTING',tablesDone:857,tablesTotal:857},plan:prepared}];await wait(1200);
  assert.equal(m.plan.id,'prepared');assert.equal(m.preparationId,null);
  assert.ok(calls.includes('DELETE /api/plans/metadata-only'));
  assert.equal(el('cancelPreparation').hidden,true);assert.equal(el('prepareData').disabled,false);
  const settled=polls();await wait(1200);assert.equal(polls(),settled,'Polling stops once the preparation has finished');
  console.log('Preparation polling UI passed: pause on leave, resume on return, retry after failure, single loop, plan loaded on completion.');
  await wait(50);
}finally{window.close();}
