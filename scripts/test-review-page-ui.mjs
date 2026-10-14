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
  combined+=await readFile(new URL('../src/main/resources/static/js/main.js',import.meta.url),'utf8')+'\nwindow.SchemaBridgeState=SchemaBridgeState;window.renderPlan=renderPlan;';
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
  assert.match(window.document.getElementById('quotaSummary').textContent, /^12 rows extracted\.$/, 'plans with explicit options just report the row count');

  // Automatic plans state the chosen settings, overall and per table.
  window.SchemaBridgeState.migration.plan = {
    id:'plan-2', orderedScript:false, issues:[], warnings:[], targetChecked:true, prepared:false, automatic:true,
    options:{maxRows:1000000000, diskReserveBytes:24000000000, useCopy:true, validateData:true},
    tableOptions:{fg_dms_content:{fetchSize:10, queryTimeoutSeconds:11600}, codes:{fetchSize:10000, queryTimeoutSeconds:1800}},
    tables:[
      {table:'fg_dms_content', status:'NEW', rows:0, estimatedRows:13809, differences:[], warnings:[], dependsOn:[], columnComparison:[], allowedActions:['CREATE_AND_LOAD','SKIP']},
      {table:'codes', status:'NEW', rows:0, estimatedRows:50000, differences:[], warnings:[], dependsOn:[], columnComparison:[], allowedActions:['CREATE_AND_LOAD','SKIP']}
    ]
  };
  window.renderPlan();
  const summary = window.document.getElementById('quotaSummary').textContent;
  assert.match(summary, /^About 63,809 rows estimated, not yet extracted\./);
  assert.match(summary, /chosen automatically/); assert.match(summary, /keeps at least 24 GB free/); assert.match(summary, /PostgreSQL COPY/);
  assert.doesNotMatch(summary, /budget/i, 'no row budget for automatic plans');
  const tuning = Array.from(window.document.querySelectorAll('#reportRows .table-tuning')).map(e=>e.textContent);
  assert.deepEqual(tuning, ['10 rows per fetch · up to 3h 13m per step', '10,000 rows per fetch · up to 30m per step']);
  assert.equal(window.document.getElementById('maxRows'), null, 'transfer settings are no longer user inputs');
  console.log('review-page-ui: all assertions passed');
  await new Promise(resolve=>setTimeout(resolve,50));
} finally { window.close(); }
