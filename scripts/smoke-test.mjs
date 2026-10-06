import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';

const base = process.env.MIGRATION_URL || 'http://127.0.0.1:8098';
const headers = {'Content-Type':'application/json','X-Migration-Client':'migration-ui'};
async function request(path, body, method = 'POST') {
  const response = await fetch(base + path, {method, headers, ...(body === undefined ? {} : {body:JSON.stringify(body)})});
  assert.equal(response.status, 200, `${path}: ${await (response.ok ? Promise.resolve('') : response.text())}`);
  return response;
}
const page = await request('/', undefined, 'GET');
assert.match(await page.text(), /SQL Migration Studio/);
assert.match(page.headers.get('content-security-policy'), /frame-ancestors 'none'/);
const capabilities = await (await request('/api/capabilities', undefined, 'GET')).json();
assert.deepEqual(capabilities.dialects, ['ORACLE','POSTGRESQL']);
const ddl = await readFile(new URL('../examples/oracle-ddl.sql', import.meta.url), 'utf8');
const dml = await readFile(new URL('../examples/oracle-dml.sql', import.meta.url), 'utf8');
let plan;
try {
  plan = await (await request('/api/plans', {sourceDialect:'ORACLE',targetDialect:'POSTGRESQL',targetSchema:'public',sql:ddl+'\n'+dml})).json();
  assert.deepEqual(plan.issues, []);
  assert.equal(plan.tables.length, 2);
  assert.equal(plan.tables.reduce((n,t)=>n+t.rows,0), 4);
  const preview = await (await request(`/api/plans/${plan.id}/preview`, undefined, 'GET')).text();
  assert.match(preview, /TIMESTAMP\(0\)/);
  assert.match(preview, /NUMERIC\(12,2\)/);
  const archive = await request(`/api/plans/${plan.id}/download`, {customers:'CREATE_AND_LOAD',orders:'CREATE_AND_LOAD'});
  const bytes = new Uint8Array(await archive.arrayBuffer());
  assert.equal(bytes[0], 0x50); assert.equal(bytes[1], 0x4b); assert.ok(bytes.length > 1000);
  console.log(`HTTP smoke passed: UI, capabilities, 2-table / 4-row conversion, preview, ZIP download (${bytes.length} bytes).`);
} finally {
  if (plan) await request(`/api/plans/${plan.id}`, undefined, 'DELETE');
}
