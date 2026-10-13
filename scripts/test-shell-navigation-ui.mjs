import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {createRequire} from 'node:module';
const require=createRequire(new URL('../target/ui-test/package.json',import.meta.url));
const {JSDOM}=require('jsdom');
const html=await readFile(new URL('../src/main/resources/static/index.html',import.meta.url),'utf8');
const dom=new JSDOM(html,{url:'http://localhost:8098',runScripts:'outside-only'});
const {window}=dom;
let fetchCount = 0;
let jobState = 'RUNNING'; // mutable, so the mock can simulate the server reporting a finished job
window.fetch = async (path) => { fetchCount++; return {ok:true, status:200, json: async () => path.endsWith('/jobs/running-job-1') ? {id:'running-job-1', state:jobState, message:'x', tables:[], progress:{}} : path.endsWith('/jobs') ? [] : {stagedBytes:0,freeDiskBytes:0,reclaimableBytes:0,plans:[],orphanedDirectories:[]}}; };
const JS_FILES=['format','api','toast','state','status-badge','progress-panel','connection-form','datasource-catalog','flow-and-upload','migration-stepper','table-picker','review-page','execution-page','revert-panel','history-page','storage-page','settings-page','dashboard-page','shell'];
try {
  let combined='';
  for (const file of JS_FILES) combined+=await readFile(new URL(`../src/main/resources/static/js/${file}.js`,import.meta.url),'utf8')+'\n';
  combined+=await readFile(new URL('../src/main/resources/static/js/main.js',import.meta.url),'utf8')+'\nwindow.SchemaBridgeState=SchemaBridgeState;window.renderJob=renderJob;window.pollJob=pollJob;window.navigateToPage=navigateToPage;window.currentPageId=currentPageId;';
  window.eval(combined);

  // The user is on the Migration page, as if they had just started a job there.
  await window.navigateToPage('migration');
  // Simulate a job mid-flight, as if the user had just started it.
  window.SchemaBridgeState.migration.finalJob = {id:'running-job-1', state:'RUNNING', message:'x', tables:[], progress:{}};
  window.document.getElementById('jobSection').hidden = false;
  window.renderJob(); await window.pollJob();
  const pollingTimerBeforeLeaving = window.SchemaBridgeState.migration.polling;
  assert.ok(pollingTimerBeforeLeaving, 'pollJob() must have armed a timer');

  // Leave Migration -> polling timer must be cleared (not just overwritten later).
  await window.navigateToPage('history');
  assert.equal(window.SchemaBridgeState.migration.polling, null, 'leaving Migration must stop the UI polling timer');
  assert.equal(window.currentPageId(), 'history');

  // Return to Migration -> must fetch once immediately, then resume polling because the job is still RUNNING.
  const fetchCountBeforeReturn = fetchCount;
  await window.navigateToPage('migration');
  await new Promise(setImmediate); await new Promise(setImmediate); // let the fire-and-forget pollJob() inside navigateToPage finish its own fetch
  assert.ok(fetchCount > fetchCountBeforeReturn, 'returning to Migration must fetch current status immediately, not wait for the next tick');
  assert.ok(window.SchemaBridgeState.migration.polling, 'polling must resume because the job is still active');

  // Now the job is finished (the server, via the mocked fetch, reports SUCCEEDED); leaving and returning again must NOT resume polling.
  jobState = 'SUCCEEDED';
  await window.navigateToPage('history');
  await window.navigateToPage('migration');
  await new Promise(setImmediate); await new Promise(setImmediate); await new Promise(setImmediate);
  assert.equal(window.SchemaBridgeState.migration.polling, null, 'a finished job must not resume polling on return');

  // The migration-only stepper must not show (or be interactive) on other pages (review finding #3).
  await window.navigateToPage('history');
  assert.equal(window.document.getElementById('migrationSteps').hidden, true, 'the Workflow/Configure/Review/Results stepper is migration-only and must be hidden elsewhere');
  await window.navigateToPage('migration');
  assert.equal(window.document.getElementById('migrationSteps').hidden, false, 'the stepper must be visible again on Migration');

  // Review finding #2: navigating away WHILE a poll's fetch is in flight must not let the
  // continuation re-arm the timer after we've already left (it would otherwise leak forever).
  jobState = 'RUNNING';
  window.SchemaBridgeState.migration.finalJob = {id:'running-job-1', state:'RUNNING', message:'x', tables:[], progress:{}};
  let resolveSlowPoll;
  const realFetch2 = window.fetch;
  window.fetch = (path) => { fetchCount++; if (path.endsWith('/jobs/running-job-1')) return new Promise(resolve => { resolveSlowPoll = () => resolve({ok:true,status:200,json:async()=>({id:'running-job-1',state:jobState,message:'x',tables:[],progress:{}})}); }); return realFetch2(path); };
  const pollPromise = window.pollJob(); // fetch is now in flight, not yet resolved
  await window.navigateToPage('history'); // leave before the fetch settles
  resolveSlowPoll();
  await pollPromise;
  await new Promise(setImmediate); await new Promise(setImmediate);
  window.fetch = realFetch2;
  assert.equal(window.SchemaBridgeState.migration.polling, null, 'a poll whose fetch resolves after navigating away must not re-arm the timer (Review Focus #1)');

  console.log('shell-navigation-ui: all assertions passed');
  await new Promise(resolve=>setTimeout(resolve,50));
} finally { window.close(); }
