import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {createRequire} from 'node:module';
const require=createRequire(new URL('../target/ui-test/package.json',import.meta.url));
const {JSDOM}=require('jsdom');
const base=process.env.MIGRATION_URL || 'http://localhost:8098';
const dom=new JSDOM(await readFile(new URL('../src/main/resources/static/index.html',import.meta.url),'utf8'),{url:'http://localhost:8098',runScripts:'outside-only'});
const {window}=dom;
window.TextEncoder=TextEncoder;
window.fetch=async()=>({ok:true,json:async()=>({dialects:['ORACLE','POSTGRESQL'],executionPolicy:'INSERT_ONLY'})});
window.eval(await readFile(new URL('../src/main/resources/static/app.js',import.meta.url),'utf8') + '\nwindow.loadSqlFiles = loadSqlFiles;');
const el=id=>window.document.getElementById(id);
const file=(name,text,path=name)=>({name,webkitRelativePath:path,size:Buffer.byteLength(text),text:async()=>text});
const load=async(files,id='folder')=>{Object.defineProperty(el(id),'files',{value:files,configurable:true});await window.loadSqlFiles(el(id));};
try {
  assert.ok(el('folder').hasAttribute('webkitdirectory'));
  const files=Array.from({length:10},(_,i)=>file(`${i+1}.sql`,`CREATE TABLE t${i+1} (id NUMBER(5)); INSERT INTO t${i+1} VALUES (${i+1});`,`batch/${i+1}.sql`));
  await load([...files.reverse(),file('photo.png','ignored')]);
  assert.match(el('fileNames').textContent,/10 files loaded; 1 other files ignored/);
  assert.ok(el('sql').value.indexOf('t2 ')<el('sql').value.indexOf('t10 '));
  const request={sourceDialect:'ORACLE',targetDialect:'POSTGRESQL',targetSchema:'public',sql:el('sql').value};
  // Exercise the real backend with the combined folder contents when the application is running.
  const response=await fetch(`${base}/api/plans`,{method:'POST',headers:{'Content-Type':'application/json','X-Migration-Client':'migration-ui'},body:JSON.stringify(request)});
  assert.ok(response.ok);const plan=await response.json();
  try { assert.equal(plan.tables.length,10);assert.equal(plan.issues.length,0);assert.ok(plan.tables.every(t=>t.rows===1)); }
  finally { await fetch(`${base}/api/plans/${plan.id}`,{method:'DELETE',headers:{'X-Migration-Client':'migration-ui'}}); }
  await load([{...file('huge.sql',''),size:10000001}]);
  assert.equal(el('sql').value,'');assert.match(el('error').textContent,/10 MB/);
  await load([file('image.png','ignored')]);assert.match(el('error').textContent,/No .sql/);
  await load([file('one.SQL','\uFEFFCREATE TABLE one (id NUMBER(5));','nested/one.SQL')]);
  assert.match(el('sql').value,/Source file: nested\/one.SQL/);assert.ok(!el('sql').value.includes('\uFEFF'));
  let finishOld;
  Object.defineProperty(el('folder'),'files',{value:[{...file('old.sql',''),text:()=>new Promise(resolve=>finishOld=resolve)}],configurable:true});
  const old=window.loadSqlFiles(el('folder'));
  await load([file('new.sql','CREATE TABLE newest (id NUMBER(5));')],'files');
  finishOld('old content');await old;
  assert.match(el('sql').value,/newest/);assert.equal(el('analyse').disabled,false);
  console.log('Bulk upload passed: 10-file backend conversion, natural ordering, filtering, size limits, BOM, replacement and stale-read protection.');
} finally { window.close(); }
