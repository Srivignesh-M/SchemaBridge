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
  const JS_FILES=['format','api','toast','state','status-badge','progress-panel','connection-form','datasource-catalog','flow-and-upload','migration-stepper','table-picker','review-page','execution-page','revert-panel','history-page'];
  let combined='';
  for (const file of JS_FILES) combined+=await readFile(new URL(`../src/main/resources/static/js/${file}.js`,import.meta.url),'utf8')+'\n';
  combined+=await readFile(new URL('../src/main/resources/static/app.js',import.meta.url),'utf8')+'\nwindow.SchemaBridgeState=SchemaBridgeState;window.renderPlan=renderPlan;';
  window.eval(combined);

  window.SchemaBridgeState.migration.plan = {
    id:'plan-1', orderedScript:false, issues:[], warnings:[], targetChecked:true, prepared:true,
    options:{maxRows:2000000}, tables:[
      {table:'customers', status:'MATCH', rows:12, estimatedRows:null, differences:[], warnings:[], dependsOn:[], columnComparison:[], allowedActions:['DML_ONLY','SKIP']},
      {table:'orders', status:'MISMATCH', rows:0, estimatedRows:null, differences:[], warnings:[], dependsOn:['customers'], columnComparison:[{column:'total',kind:'TYPE_MISMATCH',expected:'NUMERIC(10,2)',actual:'NUMERIC(8,2)',correction:'Widen the target column to NUMERIC(10,2).'}], allowedActions:['SKIP']}
    ]
  };
  window.renderPlan();
  const rows = window.document.querySelectorAll('#reportRows tr');
  assert.equal(rows.length, 2, 'one row per table');
  assert.ok(window.document.getElementById('reportRows').textContent.includes('customers'));
  const badge = window.document.querySelector('#reportRows .status-badge');
  assert.ok(badge, 'table status must render via the shared statusBadge component, not an ad-hoc span');
  const correction = window.document.querySelector('#reportRows .muted, #reportRows ul.column-comparison li span');
  assert.ok(window.document.getElementById('reportRows').textContent.includes('Widen the target column'), 'correction text must render');
  console.log('review-page-ui: all assertions passed');
  await new Promise(resolve=>setTimeout(resolve,50));
} finally { window.close(); }
