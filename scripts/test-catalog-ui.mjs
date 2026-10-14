import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {createRequire} from 'node:module';
const require=createRequire(new URL('../target/ui-test/package.json',import.meta.url)),{JSDOM}=require('jsdom');
const dom=new JSDOM(await readFile(new URL('../src/main/resources/static/index.html',import.meta.url),'utf8'),{url:'http://localhost:8098',runScripts:'outside-only'});
const {window}=dom, requests=[];
window.fetch=async(path,options)=>{
  requests.push({path,body:options.body?JSON.parse(options.body):null});
  let data;
  if(path==='/api/capabilities') data={dialects:['ORACLE','POSTGRESQL'],executionPolicy:'INSERT_ONLY'};
  else if(path==='/api/datasources') data={configured:true,datasources:[{id:100,name:'Audit',dialect:'ORACLE',supported:true}]};
  else if(path.endsWith('/select')) data={id:100,name:'Audit',mode:'SID',schema:'APP',connection:{dialect:'ORACLE',host:'test-db',port:1524,database:'demo',username:'APP',password:'test-only',jdbcUrl:'jdbc:oracle:thin:@test-db:1524:demo'}};
  else if(path==='/api/schemas') data=['OTHER','APP'];
  else data=['TABLE1'];
  return {ok:true,json:async()=>data};
};
const el=id=>window.document.getElementById(id),change=element=>element.dispatchEvent(new window.Event('change',{bubbles:true}));
const settle=async()=>{await new Promise(setImmediate);await new Promise(setImmediate);};
try {
  const JS_FILES=['format','api','toast','state','status-badge','progress-panel','connection-form','datasource-catalog','flow-and-upload','migration-stepper','table-picker','review-page','execution-page','revert-panel','history-page','storage-page','settings-page','dashboard-page','shell'];
  let combined='';
  for (const file of JS_FILES) combined+=await readFile(new URL(`../src/main/resources/static/js/${file}.js`,import.meta.url),'utf8')+'\n';
  combined+=await readFile(new URL('../src/main/resources/static/js/main.js',import.meta.url),'utf8');
  window.eval(combined);await settle();
  const catalogRequests=()=>requests.filter(r=>r.path==='/api/datasources').length;
  // Nothing loads while the migration page or a connection form is off screen.
  const mode=window.document.querySelector('input[value="db-db"]');mode.checked=true;change(mode);await settle();
  assert.equal(catalogRequests(),0,'Catalogue must not load before the migration page is opened');
  // Opening the migration page with a database workflow loads the catalogue automatically, once.
  await window.navigateToPage('migration');await settle();
  assert.equal(catalogRequests(),1,'Catalogue must load automatically on the migration page');
  assert.equal(el('targetCatalog').options.length,2);
  assert.equal(el('sourceCatalog').value,'','Manual entry must stay the default');
  assert.match(el('sourceCatalogStatus').textContent,/1 LCNC datasource available/);
  await window.navigateToPage('dashboard');await window.navigateToPage('migration');change(mode);await settle();
  assert.equal(catalogRequests(),1,'Catalogue must load only once per session');
  // Choosing a datasource fills the form, connects and lists schemas without pressing Connect.
  el('sourceCatalog').value='100';change(el('sourceCatalog'));await settle();await settle();
  assert.equal(el('sourceDatabaseLabel').textContent,'Oracle SID');
  assert.equal(el('sourceHost').value,'test-db');assert.equal(el('sourcePassword').value,'test-only');
  assert.equal(el('sourceHost').readOnly,true);assert.equal(el('sourceDialect').disabled,true);
  assert.equal(requests.filter(r=>r.path==='/api/schemas').length,1,'Choosing a datasource must connect automatically');
  assert.equal(requests.find(r=>r.path==='/api/schemas').body.jdbcUrl,'jdbc:oracle:thin:@test-db:1524:demo');
  assert.equal(el('sourceSchemaSelect').value,'APP');
  // Manual entry still connects only when asked.
  el('sourceCatalog').value='';change(el('sourceCatalog'));await settle();
  assert.equal(el('sourceHost').readOnly,false);assert.equal(el('sourceDialect').disabled,false);
  assert.equal(requests.filter(r=>r.path==='/api/schemas').length,1,'Manual entry must not auto-connect');
  el('sourceConnect').click();await settle();assert.equal(requests.filter(r=>r.path==='/api/schemas').at(-1).body.jdbcUrl,undefined);
  // An explicit refresh reloads the list.
  el('sourceCatalogRefresh').click();await settle();assert.equal(catalogRequests(),2);
  console.log('Catalogue UI passed: automatic one-time load, manual default, auto-connect on selection, Oracle SID URL, preferred schema, manual entry fallback.');
} finally {window.close();}
