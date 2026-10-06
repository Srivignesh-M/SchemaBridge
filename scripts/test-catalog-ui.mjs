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
  window.eval(await readFile(new URL('../src/main/resources/static/app.js',import.meta.url),'utf8'));await settle();
  const mode=window.document.querySelector('input[value="db-db"]');mode.checked=true;change(mode);
  el('sourceCatalogRefresh').click();await settle();
  assert.equal(requests.filter(r=>r.path==='/api/datasources').length,1);
  assert.equal(el('targetCatalog').options.length,2);
  el('sourceCatalog').value='100';change(el('sourceCatalog'));await settle();
  assert.equal(el('sourceDatabaseLabel').textContent,'Oracle SID');
  assert.equal(el('sourceHost').value,'test-db');assert.equal(el('sourcePassword').value,'test-only');
  assert.equal(el('sourceHost').readOnly,true);assert.equal(el('sourceDialect').disabled,true);
  el('sourceConnect').click();await settle();
  assert.equal(requests.find(r=>r.path==='/api/schemas').body.jdbcUrl,'jdbc:oracle:thin:@test-db:1524:demo');
  assert.equal(el('sourceSchemaSelect').value,'APP');
  el('sourceCatalog').value='';change(el('sourceCatalog'));
  assert.equal(el('sourceHost').readOnly,false);assert.equal(el('sourceDialect').disabled,false);
  el('sourceConnect').click();await settle();assert.equal(requests.filter(r=>r.path==='/api/schemas').at(-1).body.jdbcUrl,undefined);
  console.log('Catalogue UI passed: shared listing, selected credential population, Oracle SID URL, preferred schema, manual entry fallback.');
} finally {window.close();}
