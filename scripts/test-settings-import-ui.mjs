import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {createRequire} from 'node:module';
const require=createRequire(new URL('../target/ui-test/package.json',import.meta.url));
const {JSDOM}=require('jsdom');
const html=await readFile(new URL('../src/main/resources/static/index.html',import.meta.url),'utf8');
const dom=new JSDOM(html,{url:'http://localhost:8098',runScripts:'outside-only'});
const {window}=dom;
window.fetch = async () => ({ok:true,status:200,json:async()=>[]});
try {
  const JS_FILES=['format','api','toast','state','status-badge','progress-panel','connection-form','datasource-catalog','flow-and-upload','migration-stepper','table-picker','review-page','execution-page','revert-panel','history-page','storage-page','settings-page','dashboard-page','shell'];
  let combined='';
  for (const file of JS_FILES) combined+=await readFile(new URL(`../src/main/resources/static/js/${file}.js`,import.meta.url),'utf8')+'\n';
  combined+=await readFile(new URL('../src/main/resources/static/app.js',import.meta.url),'utf8');
  window.eval(combined);

  // Simulate a PostgreSQL source with a table already selected and a column-settings panel loaded.
  window.document.getElementById('sourceDialect').value = 'POSTGRESQL';
  const opt = new window.Option('legacy_customers','legacy_customers'); window.document.getElementById('sourceTables').add(opt); opt.selected = true;
  window.renderTablePicker();
  assert.equal(window.selectedSourceTables().length, 1, 'baseline: one PostgreSQL table selected before import');

  // Import a profile for a different dialect (Oracle).
  const file = { text: async () => JSON.stringify({version:1, sourceDialect:'ORACLE', targetDialect:'POSTGRESQL'}) };
  Object.defineProperty(window.document.getElementById('importSettings'), 'files', {value:[file], configurable:true});
  window.document.getElementById('importSettings').dispatchEvent(new window.Event('change'));
  await new Promise(setImmediate); await new Promise(setImmediate);

  assert.equal(window.document.getElementById('sourceDialect').value, 'ORACLE', 'dialect must switch to the imported value');
  assert.equal(window.selectedSourceTables().length, 0, 'the previous dialect\'s table selection must not survive a dialect-changing import (Review Focus #3)');
  assert.equal(window.document.getElementById('columnOptions').children.length, 0, 'stale per-table settings panels must be cleared too');
  console.log('settings-import-ui: dialect-switch guard passed');
  await new Promise(resolve=>setTimeout(resolve,50));
} finally { window.close(); }
