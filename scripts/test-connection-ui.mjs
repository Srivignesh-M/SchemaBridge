import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {createRequire} from 'node:module';

const require=createRequire(new URL('../target/ui-test/package.json',import.meta.url));
const {JSDOM}=require('jsdom');
const html=await readFile(new URL('../src/main/resources/static/index.html',import.meta.url),'utf8');
const app=await readFile(new URL('../src/main/resources/static/app.js',import.meta.url),'utf8');
const dom=new JSDOM(html,{url:'http://localhost:8098',runScripts:'outside-only'});
const {window}=dom, requests=[];
window.fetch=async(path,options)=>{
  requests.push({path,body:options.body?JSON.parse(options.body):undefined});
  return {ok:true,status:200,json:async()=>path.endsWith('/capabilities')?{dialects:['ORACLE','POSTGRESQL'],executionPolicy:'INSERT_ONLY'}:path.endsWith('/schemas')?['public']:['customers']};
};
const el=id=>window.document.getElementById(id);
const change=field=>field.dispatchEvent(new window.Event('change',{bubbles:true}));
const input=field=>field.dispatchEvent(new window.Event('input',{bubbles:true}));
const settle=async()=>{await new Promise(setImmediate);await new Promise(setImmediate);};
try {
  window.eval(app);await settle();
  assert.equal(el('targetDatabase').value,'postgres');
  assert.equal(el('sourceDatabase').disabled,true);
  assert.equal(el('configuration').checkValidity(),true,'Hidden connection fields must not block SQL-only workflows');
  const mode=window.document.querySelector('input[value="db-db"]');mode.checked=true;change(mode);
  el('sourceDialect').value='POSTGRESQL';change(el('sourceDialect'));
  assert.equal(el('sourceDatabase').value,'postgres');
  assert.equal(el('sourcePort').value,'5432');
  assert.equal(el('sourceDatabaseLabel').textContent,'PostgreSQL database name');
  el('sourceUsername').value='test_user';el('sourcePassword').value='test-only';
  el('sourceConnect').click();await settle();
  assert.equal(requests.find(r=>r.path==='/api/schemas').body.database,'postgres');
  assert.equal(requests.find(r=>r.path==='/api/tables').body.connection.database,'postgres');
  const sent=requests.length;
  el('sourceDatabase').value='   ';input(el('sourceDatabase'));el('sourceConnect').click();await settle();
  assert.equal(requests.length,sent,'Blank database names must never be posted');
  assert.match(el('error').textContent,/Source PostgreSQL database name is required/);
  el('sourceDatabase').value='finance';input(el('sourceDatabase'));
  el('sourceDialect').value='ORACLE';change(el('sourceDialect'));
  assert.equal(el('sourceDatabase').value,'');assert.equal(el('sourceDatabaseLabel').textContent,'Oracle service name');
  el('sourceDatabase').value='XEPDB1';input(el('sourceDatabase'));
  el('sourceDialect').value='POSTGRESQL';change(el('sourceDialect'));
  assert.equal(el('sourceDatabase').value,'finance','Custom database names must survive dialect changes');
  el('targetUsername').value='test_user';el('targetPassword').value='test-only';
  el('targetConnect').click();await settle();
  assert.equal(requests.filter(r=>r.path==='/api/schemas').at(-1).body.database,'postgres');
  console.log('Connection UI passed: PostgreSQL defaults, request payloads, blank blocking, Oracle service labels, preserved custom names, hidden-field validation.');
} finally {dom.window.close();}
