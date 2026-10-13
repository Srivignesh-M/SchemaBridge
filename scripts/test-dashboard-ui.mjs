import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {createRequire} from 'node:module';
const require=createRequire(new URL('../target/ui-test/package.json',import.meta.url));
const {JSDOM}=require('jsdom');
const html=await readFile(new URL('../src/main/resources/static/index.html',import.meta.url),'utf8');
const dom=new JSDOM(html,{url:'http://localhost:8098',runScripts:'outside-only'});
const {window}=dom;
const JS_FILES=['format','api','toast','state','status-badge','progress-panel','connection-form','datasource-catalog','flow-and-upload','migration-stepper','table-picker','review-page','execution-page','revert-panel','history-page','storage-page','settings-page','dashboard-page'];
window.fetch = async (path) => ({
  ok:true, status:200,
  json: async () => path.endsWith('/jobs') ? [
    {id:'aaaa1111', state:'SUCCEEDED', message:'Completed.', tables:[{table:'customers'}], progress:{rowsCommitted:12480}},
    {id:'bbbb2222', state:'RUNNING', message:'Loading orders.', tables:[{table:'orders'}], progress:{rowsCommitted:3102}}
  ] : path.endsWith('/storage') ? {stagedBytes:128000000, freeDiskBytes:212000000000, reclaimableBytes:32000, plans:[1,2,3], orphanedDirectories:[]} : {}
});
try {
  let combined='';
  for (const file of JS_FILES) combined+=await readFile(new URL(`../src/main/resources/static/js/${file}.js`,import.meta.url),'utf8')+'\n';
  combined+=await readFile(new URL('../src/main/resources/static/app.js',import.meta.url),'utf8');
  window.eval(combined);

  await window.renderDashboard();
  const activityRows = window.document.querySelectorAll('#dashboardActivity tr');
  assert.equal(activityRows.length, 2, 'dashboard shows recent activity rows from GET /jobs, not invented data');
  assert.ok(window.document.getElementById('dashboardActivity').textContent.includes('12,480'), 'real committed-row counts must render');
  assert.ok(window.document.getElementById('dashboardStorage').textContent.includes('128'), 'real storage figures must render (no fabricated metrics)');
  const quickStart = window.document.querySelectorAll('#dashboardQuickStart [data-flow]');
  assert.equal(quickStart.length, 4, 'exactly the 4 real flow types, no invented quick actions');
  console.log('dashboard-ui: all assertions passed');
  await new Promise(resolve=>setTimeout(resolve,50));
} finally { window.close(); }
