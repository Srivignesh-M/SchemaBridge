import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {createRequire} from 'node:module';
const require=createRequire(new URL('../target/ui-test/package.json',import.meta.url));
const {JSDOM}=require('jsdom');
const html=await readFile(new URL('../src/main/resources/static/index.html',import.meta.url),'utf8');
const dom=new JSDOM(html,{url:'http://localhost:8098',runScripts:'outside-only'});
const {window}=dom;
window.fetch=async()=>({ok:true,status:200,json:async()=>({dialects:['ORACLE','POSTGRESQL'],executionPolicy:'INSERT_ONLY'})});
try {
  const JS_FILES=['format','api','toast','state','status-badge','progress-panel','connection-form','datasource-catalog','flow-and-upload','migration-stepper','table-picker','review-page','execution-page','revert-panel','history-page','storage-page','settings-page','dashboard-page','shell'];
  let combined='';
  for (const file of JS_FILES) combined+=await readFile(new URL(`../src/main/resources/static/js/${file}.js`,import.meta.url),'utf8')+'\n';
  combined+=await readFile(new URL('../src/main/resources/static/js/main.js',import.meta.url),'utf8')+'\nwindow.SchemaBridgeState=SchemaBridgeState;window.invalidate=invalidate;window.hasUnfinishedMigrationWork=hasUnfinishedMigrationWork;window.jobHistoryCard=jobHistoryCard;';
  window.eval(combined);

  // Case 1: fresh session, nothing configured yet -> no unfinished work.
  assert.equal(window.hasUnfinishedMigrationWork(), false, 'a fresh session has no unfinished work');

  // Case 2: plan exists but no job executed yet -> unfinished work.
  window.SchemaBridgeState.migration.plan = {id:'p1'};
  window.SchemaBridgeState.migration.finalJob = null;
  assert.equal(window.hasUnfinishedMigrationWork(), true, 'an analyzed plan with no job is unfinished work');

  // Case 3: a job finished, but the form changed afterward (lastRequest no longer matches a fresh invalidate()) -> still unfinished.
  window.SchemaBridgeState.migration.finalJob = {id:'j1', state:'SUCCEEDED'};
  window.SchemaBridgeState.migration.lastRequest = {sourceDialect:'ORACLE'};
  window.invalidate(); // simulates an input change firing after the job completed
  assert.equal(window.hasUnfinishedMigrationWork(), true, 'a finished job whose configuration later changed must still count as unfinished work (Review Focus #2)');

  // Case 4: a job already finished (planId 'old-plan'), then the user re-analyzed into a brand new
  // plan without executing it -> still unfinished, even though a (stale) finalJob is present.
  window.SchemaBridgeState.migration.finalJob = {id:'j1', state:'SUCCEEDED', planId:'old-plan'};
  window.SchemaBridgeState.migration.plan = {id:'new-plan'};
  assert.equal(window.hasUnfinishedMigrationWork(), true, 're-analyzing after an older finished job must still count as unfinished work, not be masked by the stale finalJob');

  // Case 4b: the finalJob actually corresponds to the current plan (just executed it) -> no guard needed.
  window.SchemaBridgeState.migration.plan = {id:'old-plan'};
  assert.equal(window.hasUnfinishedMigrationWork(), false, 'a plan that matches its own just-executed job is not unfinished work');

  // Case 5: everything is clean and matches -> opening history proceeds without a guard prompt.
  window.SchemaBridgeState.migration.plan = null;
  window.SchemaBridgeState.migration.finalJob = null;
  window.SchemaBridgeState.migration.lastRequest = null;
  assert.equal(window.hasUnfinishedMigrationWork(), false);

  // Review finding (related to #6): deleting the saved migration that `finalJob` currently points
  // to must clear `finalJob`, so Migration doesn't keep polling a job that no longer exists.
  window.SchemaBridgeState.migration.finalJob = {id:'doomed-job', state:'SUCCEEDED', planId:'plan-x'};
  const doomedJob = {id:'doomed-job', state:'SUCCEEDED', planId:'plan-x', tables:[{table:'t'}], progress:{rowsCommitted:1}, message:'done'};
  window.confirm = () => true;
  window.fetch = async (path, options) => { if (options?.method === 'DELETE') return {ok:true,status:200,json:async()=>({})}; return {ok:true,status:200,json:async()=>({dialects:['ORACLE','POSTGRESQL'],executionPolicy:'INSERT_ONLY'})}; };
  const card = window.jobHistoryCard(doomedJob);
  window.document.body.append(card);
  const removeButton = Array.from(card.querySelectorAll('button')).find(b => /Delete saved files/.test(b.textContent));
  removeButton.click();
  await new Promise(setImmediate); await new Promise(setImmediate); await new Promise(setImmediate);
  assert.equal(window.SchemaBridgeState.migration.finalJob, null, 'deleting the job finalJob points to must clear finalJob, or Migration keeps polling a 404');

  console.log('history-guard-ui: all assertions passed');
  await new Promise(resolve=>setTimeout(resolve,50));
} finally { window.close(); }
