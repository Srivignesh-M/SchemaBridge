import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {createRequire} from 'node:module';
const require=createRequire(new URL('../target/ui-test/package.json',import.meta.url));
const {JSDOM}=require('jsdom');
const dom=new JSDOM(await readFile(new URL('../src/main/resources/static/index.html',import.meta.url),'utf8'),{url:'http://localhost:8098',runScripts:'outside-only'});
const {window}=dom,calls=[];
const plan=(id,state,extra={})=>({id:id.padEnd(36,'0'),state,bytes:1_000_000,expiresAt:'2026-11-13T08:00:00Z',reclaimable:false,source:'172.16.16.98:5432/postgres',target:'localhost:5432/postgres',tables:3,prepared:false,active:false,uncertain:false,...extra});
let plans=[
  plan('aaaaaaaa','NOT_RUN',{tables:791}),
  plan('bbbbbbbb','NOT_RUN',{prepared:true,bytes:620_000_000}),
  plan('cccccccc','SUCCEEDED'),
  plan('dddddddd','RECOVERY_REQUIRED',{uncertain:true}),
  plan('eeeeeeee','RUNNING',{active:true}),
  plan('ffffffff','INTERRUPTED',{tables:5,tablesTotal:9,bytes:2_000_000_000}),
  plan('abababab','INTERRUPTED',{tables:2,tablesTotal:9,active:true}),
];
const reply=body=>({ok:true,json:async()=>body,text:async()=>''});
window.fetch=async(path,options={})=>{
  const method=options.method||'GET';calls.push(`${method} ${path}`);
  if(path==='/api/storage')return reply({stagedBytes:plans.reduce((s,p)=>s+p.bytes,0),freeDiskBytes:4e10,reclaimableBytes:0,plans,orphanedDirectories:[]});
  if(method==='DELETE'&&path.startsWith('/api/plans/')){const id=path.slice('/api/plans/'.length).split('?')[0];plans=plans.filter(p=>p.id!==id);return reply({});}
  return reply(path.endsWith('/jobs')?[]:{});
};
let confirmAnswer=true;const prompts=[];window.confirm=message=>{prompts.push(message);return confirmAnswer;};
const el=id=>window.document.getElementById(id);
const wait=ms=>new Promise(resolve=>setTimeout(resolve,ms));
const rows=()=>Array.from(el('storagePlans').tBodies[0].rows);
const row=prefix=>rows().find(r=>r.dataset.planId.startsWith(prefix));
const deletes=()=>calls.filter(c=>c.startsWith('DELETE'));
const JS_FILES=['format','api','toast','state','status-badge','progress-panel','connection-form','datasource-catalog','flow-and-upload','migration-stepper','table-picker','review-page','execution-page','revert-panel','history-page','storage-page','settings-page','dashboard-page','shell','main'];
let combined='';
for(const file of JS_FILES)combined+=await readFile(new URL(`../src/main/resources/static/js/${file}.js`,import.meta.url),'utf8')+'\n';
window.eval(combined+'\nwindow.migrationState=()=>SchemaBridgeState.migration;');
await wait(25);

// Every saved plan is listed, including ones that never ran.
await window.refreshStorage();
assert.equal(el('storagePlans').hidden,false);assert.equal(rows().length,7);
assert.match(row('aaaaaaaa').textContent,/Not run/);assert.match(row('aaaaaaaa').textContent,/Definitions only/);
assert.match(row('aaaaaaaa').textContent,/791/);assert.match(row('aaaaaaaa').textContent,/172\.16\.16\.98:5432\/postgres → localhost:5432\/postgres/);
assert.match(row('bbbbbbbb').textContent,/Data prepared/);assert.match(row('bbbbbbbb').textContent,/620 MB/);
assert.match(row('cccccccc').textContent,/Completed/);
const running=row('eeeeeeee').querySelector('button');assert.equal(running.disabled,true);assert.equal(running.textContent,'Running');
const uncertain=row('dddddddd').querySelector('button');assert.equal(uncertain.textContent,'Review and discard evidence');assert.ok(uncertain.classList.contains('danger'));
assert.equal(el('deleteUnusedPlans').hidden,false);assert.equal(el('deleteUnusedPlans').textContent,'Delete all plans that never ran (3)');
// Interrupted preparations show how far they got; one being prepared cannot be deleted.
assert.match(row('ffffffff').textContent,/Interrupted/);assert.match(row('ffffffff').textContent,/5 of 9 staged/);assert.match(row('ffffffff').textContent,/Prepare again with the same selection to continue/);
const preparing=row('abababab').querySelector('button');assert.equal(preparing.disabled,true);assert.equal(preparing.textContent,'Preparing');assert.match(row('abababab').textContent,/Preparing now/);
row('ffffffff').querySelector('button').click();await wait(30);
assert.match(prompts.at(-1),/Delete this interrupted preparation and its 2.0 GB of staged tables/);
assert.equal(deletes().at(-1),`DELETE /api/plans/${'ffffffff'.padEnd(36,'0')}`);assert.equal(row('ffffffff'),undefined);
assert.equal(el('deleteUnusedPlans').textContent,'Delete all plans that never ran (2)');

// Cancelling the confirmation deletes nothing.
const before=deletes().length;confirmAnswer=false;row('cccccccc').querySelector('button').click();await wait(20);
assert.equal(deletes().length,before);confirmAnswer=true;

// Deleting the plan open on the migration page also clears it there.
window.migrationState().plan={id:plans.find(p=>p.id.startsWith('cccccccc')).id};
row('cccccccc').querySelector('button').click();await wait(30);
assert.equal(deletes().at(-1),`DELETE /api/plans/${'cccccccc'.padEnd(36,'0')}`);
assert.equal(window.migrationState().plan,null,'Deleted plan must be cleared from the migration page');
assert.equal(row('cccccccc'),undefined,'List must refresh after a delete');

// Uncertain jobs need the explicit evidence-discard acknowledgement.
uncertain.click();await wait(30);
assert.match(prompts.at(-1),/commit outcome is uncertain/);
assert.equal(deletes().at(-1),`DELETE /api/plans/${'dddddddd'.padEnd(36,'0')}?acknowledgeUncertain=true`);

// Bulk delete removes only plans that never ran.
el('deleteUnusedPlans').click();await wait(40);
assert.match(prompts.at(-1),/Delete 2 plans that never ran \(621 MB/);
assert.deepEqual(plans.map(p=>p.id.slice(0,8)),['eeeeeeee','abababab'],'Only the running plan and the preparation in progress must remain');
assert.equal(el('deleteUnusedPlans').hidden,true);

plans=[];await window.refreshStorage();
assert.equal(el('storagePlans').hidden,true);assert.equal(el('storagePlansEmpty').hidden,false);
console.log('Storage UI passed: lists every plan, per-row and bulk delete, uncertain-evidence guard, cancel, running lock and migration-page cleanup.');
