import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {createRequire} from 'node:module';
const require=createRequire(new URL('../target/ui-test/package.json',import.meta.url)),{JSDOM}=require('jsdom');
const dom=new JSDOM(await readFile(new URL('../src/main/resources/static/index.html',import.meta.url),'utf8'),{url:'http://localhost:8098',runScripts:'outside-only'});
const {window}=dom;
window.fetch=async path=>({ok:true,json:async()=>path==='/api/storage'?{plans:[],orphanedDirectories:[]}:path.endsWith('/jobs')?[]:{},text:async()=>''});
const el=id=>window.document.getElementById(id);
try{
  const JS_FILES=['format','api','toast','state','status-badge','progress-panel','connection-form','datasource-catalog','flow-and-upload','migration-stepper','table-picker','review-page','execution-page','revert-panel','history-page','storage-page','settings-page','dashboard-page','shell','main'];
  let combined='';for(const file of JS_FILES)combined+=await readFile(new URL(`../src/main/resources/static/js/${file}.js`,import.meta.url),'utf8')+'\n';
  window.eval(combined);
  const count=prefix=>el(`${prefix}TableProgress`).querySelector('.table-progress-count').textContent;
  const width=prefix=>el(`${prefix}TableProgress`).querySelector('.table-progress-bar').firstElementChild.style.width;

  // Data extraction during Prepare data.
  window.renderProgress('preparation',{phase:'EXTRACTING',table:'fg_dms_content',tablesDone:311,tablesTotal:857,rowsRead:2179316,elapsedSeconds:401});
  assert.equal(el('preparationTableProgress').hidden,false);
  assert.equal(count('preparation'),'Table 312 of 857 · 36% of tables done');assert.equal(width('preparation'),'36%');
  assert.equal(el('preparationTable').textContent,'Table: fg_dms_content');
  assert.equal(el('preparationTableProgress').previousElementSibling.className,'progress-current','The counter sits under the current table');

  // Parallel readers: the range of tables in progress.
  window.renderProgress('preparation',{phase:'EXTRACTING',table:'a, b, c',tablesDone:311,tablesTotal:857,tablesActive:3});
  assert.equal(count('preparation'),'Tables 312–314 of 857 · 36% of tables done');
  window.renderProgress('preparation',{phase:'EXTRACTING',table:'y, z',tablesDone:855,tablesTotal:857,tablesActive:3});
  assert.equal(count('preparation'),'Tables 856–857 of 857 · 99% of tables done','the range never passes the total');

  // The run: each pass counts its own tables; verification gets a readable label.
  window.renderProgress('job',{phase:'LOADING',table:'codes',tablesDone:0,tablesTotal:857});
  assert.equal(count('job'),'Table 1 of 857 · 0% of tables done');
  window.renderProgress('job',{phase:'VALIDATING',table:'codes',tablesDone:500,tablesTotal:857});
  assert.equal(count('job'),'Table 501 of 857 · 58% of tables done');assert.equal(el('jobPhase').textContent,'Verifying loaded data');
  window.renderProgress('job',{phase:'SUCCEEDED',table:'codes',tablesDone:857,tablesTotal:857});
  assert.equal(count('job'),'857 of 857 tables');assert.equal(width('job'),'100%');
  assert.equal(window.document.querySelectorAll('#jobTableProgress').length,1,'Re-rendering reuses one counter');

  // COPY exposes bytes/s and stage timing, not a stale row-rate ETA.
  window.renderProgress('job',{phase:'COPYING',table:'docs',tablesDone:2,tablesTotal:10,bytesPerSecond:1048576,remainingSeconds:999,
    phaseSeconds:{COPYING:65,FINGERPRINTING:30},rowsPerSecond:100});
  assert.match(el('jobRate').textContent,/MB\/s$/);
  assert.equal(el('jobEta').textContent,'\u2014');
  assert.match(el('jobTransferDetails').textContent,/Row totals update when the table finishes/);
  assert.match(el('jobTransferDetails').textContent,/Receipt verification/);
  window.renderProgress('job',{phase:'FINGERPRINTING',tablesDone:2,tablesTotal:10,rowsPerSecond:999});
  assert.equal(el('jobPhase').textContent,'Verifying rows and saving revert receipt');
  assert.equal(el('jobRate').textContent,'\u2014');

  // Older jobs and SQL-file plans report no table count: no counter.
  window.renderProgress('job',{phase:'LOADING',table:'codes'});
  assert.equal(el('jobTableProgress').hidden,true);
  console.log('Table progress UI passed: extraction and run counters, pass labels, completion, reuse, hidden without counts.');
  await new Promise(resolve=>setTimeout(resolve,50)); // let startup requests settle before closing the window
}finally{window.close();}
