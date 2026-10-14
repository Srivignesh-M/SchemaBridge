import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {createRequire} from 'node:module';
const require=createRequire(new URL('../target/ui-test/package.json',import.meta.url)),{JSDOM}=require('jsdom');
const dom=new JSDOM(await readFile(new URL('../src/main/resources/static/index.html',import.meta.url),'utf8'),{url:'http://localhost:8098',runScripts:'outside-only'});
const {window}=dom,requests=[];
const responses={'/api/datasources':{configured:false,datasources:[]},'/api/jobs':[],'/api/storage':{stagedBytes:0,freeDiskBytes:0,reclaimableBytes:0,plans:[],orphanedDirectories:[]}};
window.fetch=async path=>{requests.push(path);return {ok:true,json:async()=>responses[path]??{}};};
const el=id=>window.document.getElementById(id),settle=async()=>{await new Promise(setImmediate);await new Promise(setImmediate);};
const toasts=()=>Array.from(window.document.querySelectorAll('[data-toast-type="error"]')).map(t=>t.textContent);
const errors=()=>toasts().length;
try{
  const JS_FILES=['format','api','toast','state','status-badge','progress-panel','connection-form','datasource-catalog','flow-and-upload','migration-stepper','table-picker','review-page','execution-page','revert-panel','history-page','storage-page','settings-page','dashboard-page','shell','main'];
  let combined='';for(const file of JS_FILES)combined+=await readFile(new URL(`../src/main/resources/static/js/${file}.js`,import.meta.url),'utf8')+'\n';
  window.eval(combined);await settle();
  const mode=window.document.querySelector('input[value="db-db"]');mode.checked=true;mode.dispatchEvent(new window.Event('change',{bubbles:true}));
  await window.navigateToPage('migration');await settle();
  assert.equal(requests.filter(p=>p==='/api/datasources').length,1);
  assert.deepEqual(toasts(),[],'The automatic load must not raise an error toast when the catalogue is not configured');
  assert.match(el('sourceCatalogStatus').textContent,/not configured/);assert.equal(el('sourceCatalog').options.length,1);
  el('sourceCatalogRefresh').click();await settle();
  assert.equal(errors(),1,'An explicit refresh still reports that the catalogue is not configured');
  console.log('Unconfigured catalogue UI passed: quiet automatic load, status message, explicit refresh still reports.');
}finally{window.close();}
