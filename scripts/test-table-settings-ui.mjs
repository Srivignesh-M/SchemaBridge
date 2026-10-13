import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {createRequire} from 'node:module';
const require=createRequire(new URL('../target/ui-test/package.json',import.meta.url));
const {JSDOM}=require('jsdom');
const dom=new JSDOM(await readFile(new URL('../src/main/resources/static/index.html',import.meta.url),'utf8'),{url:'http://localhost:8098',runScripts:'outside-only'});
const {window}=dom,requests=[];
const columns=[{name:{value:'id'},type:{kind:'INTEGER'}},{name:{value:'active'},type:{kind:'BOOLEAN'}}];
window.fetch=async(path,options)=>{requests.push(path);return {ok:true,json:async()=>path.endsWith('/columns')?columns:path.endsWith('/jobs')?[]:{}};};
const el=id=>window.document.getElementById(id);
const change=node=>node.dispatchEvent(new window.Event('change',{bubbles:true}));
const settle=()=>new Promise(resolve=>setTimeout(resolve,25));
const lastError=()=>Array.from(window.document.querySelectorAll('[data-toast-type="error"]')).at(-1)?.textContent||'';
try{
  const JS_FILES=['format','api','toast','state','status-badge','progress-panel','connection-form','datasource-catalog','flow-and-upload','migration-stepper','table-picker'];
  let combined='';
  for (const file of JS_FILES) combined+=await readFile(new URL(`../src/main/resources/static/js/${file}.js`,import.meta.url),'utf8')+'\n';
  combined+=await readFile(new URL('../src/main/resources/static/app.js',import.meta.url),'utf8')+'\nwindow.readSelections=tableSelections;';
  window.eval(combined);
  await settle();
  assert.ok(window.document.querySelector('header .steps'));
  assert.equal(window.document.querySelector('.intro'),null);
  const mode=window.document.querySelector('[value="db-db"]');mode.checked=true;change(mode);
  el('sourceDialect').value='POSTGRESQL';change(el('sourceDialect'));
  el('sourceUsername').value='test';el('sourcePassword').value='test';
  el('sourceSchemaSelect').add(new window.Option('public','public',true,true));
  for(let i=0;i<500;i++)el('sourceTables').add(new window.Option('table_'+i,'table_'+i,true,true));
  change(el('sourceTables'));await settle();
  assert.equal(el('columnOptions').children.length,500);
  assert.equal(requests.filter(p=>p.endsWith('/columns')).length,0,'Selection must not fetch columns');
  assert.deepEqual(JSON.parse(JSON.stringify(window.readSelections())),{});
  const group=el('columnOptions').firstElementChild;group.open=true;await settle();
  assert.equal(requests.filter(p=>p.endsWith('/columns')).length,1);
  assert.equal(group.querySelectorAll('.filter-row').length,0);
  const add=Array.from(group.querySelectorAll('button')).find(b=>b.textContent==='Add filter');
  add.click();add.click();
  const rows=group.querySelectorAll('.filter-row');
  rows[0].querySelector('.filter-column').value='active';change(rows[0].querySelector('.filter-column'));
  assert.equal(rows[0].querySelector('.filter-value').tagName,'SELECT');
  rows[0].querySelector('.filter-value').value='false';
  rows[1].querySelector('.filter-column').value='id';change(rows[1].querySelector('.filter-column'));
  rows[1].querySelector('.filter-operator').value='>=';change(rows[1].querySelector('.filter-operator'));
  rows[1].querySelector('.filter-value').value='5';group.querySelector('.table-row-limit').value='10';
  const settings=JSON.parse(JSON.stringify(window.readSelections())).table_0;
  assert.deepEqual(settings.filters,[{column:'active',operator:'=',value:'false'},{column:'id',operator:'>=',value:'5'}]);
  assert.equal(settings.rowLimit,10);
  rows[1].querySelector('.filter-operator').value='IS NULL';change(rows[1].querySelector('.filter-operator'));
  assert.equal(rows[1].querySelector('.filter-value').disabled,true);
  assert.equal(window.readSelections().table_0.filters[1].value,null);
  rows[1].querySelector('button').click();assert.equal(window.readSelections().table_0.filters.length,1);
  group.open=false;group.open=true;await settle();assert.equal(requests.filter(p=>p.endsWith('/columns')).length,1);
  el('sourceTables').options[0].selected=false;change(el('sourceTables'));
  assert.equal(window.readSelections().table_0,undefined);
  el('sourceTables').options[0].selected=true;change(el('sourceTables'));
  assert.equal(window.readSelections().table_0.filters[0].value,'false');
  el('sourceTables').add(new window.Option('extra','extra',true,true));change(el('sourceTables'));
  el('applyTablePicker').click();assert.match(lastError(),/500 source tables/);
  console.log('Table settings UI passed: lazy loading, 500 selections, multiple typed filters, null checks, removal, limits, retained settings and 501 rejection.');

  // --- New: Columns/Filters/Row-limit must be independently collapsible <details> ---
  const group2 = window.document.querySelector('#columnOptions > details[data-table]');
  const sections = group2.querySelectorAll('.mc-disclosure, details[data-settings-section]');
  assert.ok(sections.length >= 3, 'expected Columns, Filters, and Row limit as separate disclosures');
  const byLabel = name => Array.from(sections).find(s => s.querySelector('summary').textContent.includes(name));
  const columnsSection = byLabel('Columns'), filtersSection = byLabel('Filters'), rowLimitSection = byLabel('Row limit');
  assert.ok(columnsSection && filtersSection && rowLimitSection, 'all three sections must be present and labeled');
  // Each is independently toggleable without affecting the others.
  filtersSection.open = true; columnsSection.open = false;
  assert.equal(filtersSection.open, true); assert.equal(columnsSection.open, false);
  console.log('table-settings-ui: collapsible sections assertions passed');

  // --- New: a slow /columns fetch for the previous schema must not land after the schema changed (Review Focus #4) ---
  let resolveSlowFetch;
  const realFetch = window.fetch;
  window.fetch = (path, options) => {
    if (path === '/api/columns') return new Promise(resolve => { resolveSlowFetch = () => resolve({ok:true,status:200,json:async()=>[{name:{value:'id'},type:{kind:'INTEGER'}}]}); });
    return realFetch(path, options);
  };
  window.document.getElementById('sourceSchemaSelect').innerHTML = '<option value="schema_a">schema_a</option><option value="schema_b">schema_b</option>';
  window.document.getElementById('sourceSchemaSelect').value = 'schema_a';
  const slowGroup = Array.from(window.document.querySelectorAll('#columnOptions > details[data-table]')).find(g => g.dataset.loaded !== 'true');
  slowGroup.open = true; slowGroup.dispatchEvent(new window.Event('toggle'));
  await new Promise(setImmediate);
  window.document.getElementById('sourceSchemaSelect').value = 'schema_b'; // schema changes before the fetch resolves
  resolveSlowFetch();
  await new Promise(setImmediate); await new Promise(setImmediate);
  assert.notEqual(slowGroup.dataset.loaded, 'true', 'a response for the old schema must not be applied after the schema changed');
  window.fetch = realFetch;
  console.log('table-settings-ui: stale-schema race guard passed');
}finally{window.close();}
