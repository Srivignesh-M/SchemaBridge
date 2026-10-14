# Schema Bridge UI/UX Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Redesign SchemaBridge's frontend into a dark, developer-tool-style sidebar application (Dashboard/Migration/History/Storage/Settings) while preserving every existing behavior exactly, with zero backend or packaging changes.

**Architecture:** Split the single 493-line `app.js` into ~18 small classic (non-module) JavaScript files, each loaded via its own `<script src="...">` tag in `index.html`, in dependency order. There is no bundler and no namespace-wrapper object — top-level `let`/`const`/function declarations behave exactly as they do today split across one file, because sequential `<script>` tags (and the jsdom test harnesses' sequential `window.eval()` calls) share one global lexical environment. `styles.css` is rewritten around a CSS custom-property token system (dark theme). `index.html` gains a sidebar/topbar shell; the existing 4-step wizard becomes the content of the "Migration" page; History, Storage, and Settings are promoted from embedded cards to their own pages; Dashboard is new (built only from already-existing `GET /jobs` and `GET /storage` data).

**Tech Stack:** Vanilla JS (ES2020, classic scripts), vanilla CSS (custom properties), Node.js + jsdom for the existing UI test harnesses (`scripts/test-*-ui.mjs`), Spring Boot static resource serving (unchanged).

**Spec:** `docs/superpowers/specs/2026-10-09-schema-bridge-ui-redesign-design.md`

## Global Constraints

- Zero changes to `MigrationController.java`, `MigrationService.java`, `DatabaseGateway.java`, `Model.java`, `WebBoundary.java`, or any packaging script / GitHub Actions workflow. Only `src/main/resources/static/**` and `scripts/test-*-ui.mjs` change.
- No build step, no new Maven or npm dependencies. Plain `<script src="...">` tags, no `type="module"`, no `import`/`export` (the jsdom harnesses run files via `window.eval()`, which cannot execute ES module syntax).
- No CSS framework. Hand-written `:root` custom-property tokens. System font stack only — no `@font-face`/webfont loading.
- 500-table selection cap; up to 20 AND-combined filters per table; all existing numeric option ranges (`maxRows`, `fetchSize`, `batchRows`, etc.) unchanged.
- Every status indicator (job state, table status, connection state) pairs a color with text or an icon — never color alone. `CANCELLED` renders as neutral/warning; `FAILED`/`MISMATCH`/`RECOVERY_REQUIRED` render as error.
- Error-type toasts never auto-dismiss (manual close only, `role="alert"`); success/info toasts auto-fade after ~6–8s.
- `/api` requests always send the `X-Migration-Client: migration-ui` header and parse `{message}` from error bodies — exactly as today's `api()` function.

## Review Focus

- Navigating away from Migration mid-`RUNNING` job and back: polling must stop exactly once (no leaked `setTimeout` causing duplicate fetches) and resume exactly once on return, only if the job is still active.
- Opening a History job while Migration has an analyzed plan whose inputs have since changed (`lastRequest` no longer matches the current form) — not just "a plan exists" — must still trigger the unfinished-work guard.
- Importing a settings JSON profile for a different dialect than the one currently selected must not silently leave stale table selections/filters from the old dialect's picker state.
- Rapidly opening the table picker, cancelling, and reopening before a newly-connected schema's table list finishes loading must not restore the *previous* schema's selection onto the *new* schema's table list.
- Triggering two errors in quick succession must show two stacked toasts, not have the second silently clear the first before it's read.

---

## File structure

```
src/main/resources/static/
  index.html                 (rewritten: sidebar shell + 5 page containers)
  styles.css                 (rewritten: tokens + layout + components)
  js/
    format.js                 formatBytes, formatDuration
    api.js                     api(), save()
    toast.js                   toast(message, {type}) — replaces single #error banner
    state.js                   migration wizard state singleton + invalidate()
    status-badge.js            statusBadge(kind, value) -> DOM node
    progress-panel.js          renderProgress(prefix, progress)
    connection-form.js         connectionForm(), applyDialect(), connection()
    datasource-catalog.js      loadCatalog(), setCatalogMode()
    flow-and-upload.js         flow(), usesDatabase(), writesDatabase(), updateFlow(), loadSqlFiles()
    migration-stepper.js       goToStep() and stepper nav wiring
    table-picker.js            table picker dialog: search/select-all/settings (now collapsible)
    review-page.js             renderPlan(), actions(), matchAll, download-ticket flow
    execution-page.js          renderJob(), pollJob(), prepareData flow, cancel/resume
    revert-panel.js            renderRevert() and its handlers
    history-page.js            jobHistoryCard(), refreshHistory(), unfinished-work guard
    storage-page.js            refreshStorage()
    settings-page.js           settingsProfile(), export/import
    dashboard-page.js          NEW: quick-start cards + recent activity + storage teaser
    shell.js                   NEW: sidebar/topbar, page switching, polling lifecycle
    main.js                    bootstrap: wires everything, loadBuildVersion()
scripts/
  test-connection-ui.mjs       (eval-list updated)
  test-bulk-ui.mjs             (eval-list updated)
  test-catalog-ui.mjs          (eval-list updated)
  test-table-settings-ui.mjs   (eval-list updated + new collapsible-section assertions)
  test-history-guard-ui.mjs    NEW
  test-settings-import-ui.mjs  NEW
  test-dashboard-ui.mjs        NEW
  test-shell-navigation-ui.mjs NEW
```

Script tag / eval order (dependency order, same in every file): `format, api, toast, state, status-badge, progress-panel, connection-form, datasource-catalog, flow-and-upload, migration-stepper, table-picker, review-page, execution-page, revert-panel, history-page, storage-page, settings-page, dashboard-page, shell, main`.

---

### Task 1: Shared foundation — format, api, toast, state

**Files:**
- Create: `src/main/resources/static/js/format.js`
- Create: `src/main/resources/static/js/api.js`
- Create: `src/main/resources/static/js/toast.js`
- Create: `src/main/resources/static/js/state.js`
- Modify: `src/main/resources/static/index.html` (add 4 `<script>` tags before the existing inline `app.js` reference; remove the corresponding code from `app.js` once ported)
- Modify: `src/main/resources/static/app.js` (remove ported code)
- Test: `scripts/test-toast-ui.mjs` (new)

**Interfaces:**
- Produces: `formatBytes(value): string`, `formatDuration(value): string`, `api(path, body, method='POST'): Promise<Response>`, `save(blob, name): void`, `toast(message, options?): {id, dismiss()}` where `options.type` is `'error'|'success'|'info'` (default `'error'`), `SchemaBridgeState.migration` (mutable object), `invalidate(): void`.
- Consumes: nothing (this is the dependency floor).

- [ ] **Step 1: Write the failing test for toast persistence**

Create `scripts/test-toast-ui.mjs`:
```js
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {createRequire} from 'node:module';

const require=createRequire(new URL('../target/ui-test/package.json',import.meta.url));
const {JSDOM}=require('jsdom');
const html=await readFile(new URL('../src/main/resources/static/index.html',import.meta.url),'utf8');
const dom=new JSDOM(html,{url:'http://localhost:8098',runScripts:'outside-only'});
const {window}=dom;
const files=['format','api','toast'];
for (const file of files) window.eval(await readFile(new URL(`../src/main/resources/static/js/${file}.js`,import.meta.url),'utf8'));

// Error toasts must not auto-dismiss.
const handle = window.toast('Something failed', {type:'error'});
let banner = window.document.querySelector('[data-toast-type="error"]');
assert.ok(banner, 'error toast should render');
assert.equal(banner.getAttribute('role'), 'alert');
assert.ok(banner.querySelector('[data-toast-close]'), 'error toast needs a manual close control');

// Two errors in a row must stack, not replace each other.
window.toast('A second failure', {type:'error'});
assert.equal(window.document.querySelectorAll('[data-toast-type="error"]').length, 2, 'errors must stack, not replace');

// Success/info toasts render but are marked for auto-dismiss (checked by presence of data-toast-autofade).
const successHandle = window.toast('Download ready', {type:'success'});
const success = window.document.querySelector('[data-toast-type="success"]');
assert.ok(success.hasAttribute('data-toast-autofade'), 'success toasts auto-fade, errors do not');
assert.ok(!banner.hasAttribute('data-toast-autofade'), 'error toasts must never auto-fade');

// Manual dismiss removes exactly one toast.
handle.dismiss();
assert.equal(window.document.querySelectorAll('[data-toast-type="error"]').length, 1, 'dismiss() removes only its own toast');

console.log('toast-ui: all assertions passed');
```

- [ ] **Step 2: Run test to verify it fails**

Run: `node scripts/test-toast-ui.mjs`
Expected: FAIL — `window.toast is not a function` (files don't exist yet).

- [ ] **Step 3: Write `js/format.js`** (ported verbatim from current `app.js` lines 299-300)

```js
function formatBytes(value){let size=Number(value||0),unit='B';for(const next of ['KB','MB','GB','TB']){if(size<1000)break;size/=1000;unit=next;}return `${size<10&&unit!=='B'?size.toFixed(1):Math.round(size).toLocaleString()} ${unit}`;}
function formatDuration(value){let seconds=Math.max(0,Math.floor(Number(value)||0));if(seconds<60)return `${seconds}s`;const units=[['d',86400],['h',3600],['m',60]];const parts=[];for(const [label,size] of units){if(seconds>=size){const amount=Math.floor(seconds/size);parts.push(`${amount}${label}`);seconds%=size;if(parts.length===2)break;}}if(parts.length<2&&seconds)parts.push(`${seconds}s`);return parts.join(' ');}
```

- [ ] **Step 4: Write `js/api.js`** (ported verbatim from current `app.js` lines 78-82 and 271)

```js
async function api(path, body, method = 'POST') {
  const response = await fetch('/api' + path, {method, headers: {'Content-Type':'application/json','X-Migration-Client':'migration-ui'}, ...(body === undefined ? {} : {body:JSON.stringify(body)})});
  if (!response.ok) { let message = 'Request failed (' + response.status + ')'; try { message = (await response.json()).message || message; } catch (_) {} throw new Error(message); }
  return response;
}
function save(blob,name) { const url=URL.createObjectURL(blob), link=document.createElement('a'); link.href=url;link.download=name;link.click();setTimeout(()=>URL.revokeObjectURL(url),1000); }
```

- [ ] **Step 5: Write `js/toast.js`** (new behavior: replaces the single `#error` banner with a stacking toast list; errors never auto-dismiss, success/info do)

```js
let toastSeq = 0;
function toastContainer() {
  let host = document.getElementById('toastHost');
  if (!host) { host = document.createElement('div'); host.id = 'toastHost'; host.setAttribute('aria-live','polite'); document.body.append(host); }
  return host;
}
function toast(message, options = {}) {
  const type = options.type || 'error';
  const id = 'toast-' + (++toastSeq);
  const el = document.createElement('div');
  el.id = id; el.className = 'toast toast-' + type; el.dataset.toastType = type;
  if (type === 'error') el.setAttribute('role','alert'); else el.setAttribute('role','status');
  const text = document.createElement('span'); text.textContent = message; el.append(text);
  const close = document.createElement('button'); close.type = 'button'; close.className = 'toast-close'; close.setAttribute('data-toast-close',''); close.setAttribute('aria-label','Dismiss'); close.textContent = '×';
  const dismiss = () => { el.remove(); };
  close.addEventListener('click', dismiss);
  el.append(close);
  toastContainer().append(el);
  if (type !== 'error') { el.setAttribute('data-toast-autofade',''); setTimeout(dismiss, 7000); }
  return { id, dismiss };
}
// Backward-compatible alias: existing call sites currently say error(message).
function error(message) { return toast(message, {type:'error'}); }
```

- [ ] **Step 6: Write `js/state.js`** (ported from current `app.js` line 3-7 plus `invalidate()` from lines 147-156; `invalidate()` is adapted to not force a step change — that now happens only in the shell's navigation logic, never as a side effect of this function)

```js
const SchemaBridgeState = {
  migration: {
    plan: null, finalJob: null, polling: null, errorTimer: null, lastRequest: null,
    preparationId: null, preparationPoll: null, requestVersion: 0,
    currentStep: 1, maxStep: 1,
    databaseNames: {source: {}, target: {}},
    catalogSelection: {source:null, target:null},
    catalogLoading: false, pendingRevert: null
  }
};
function invalidate() {
  const m = SchemaBridgeState.migration;
  if (typeof clearRevertReview === 'function') clearRevertReview();
  m.requestVersion++; m.lastRequest = null; m.plan = null;
  if (document.getElementById('reportSection')) document.getElementById('reportSection').hidden = true;
  if (document.getElementById('outputActions')) document.getElementById('outputActions').hidden = true;
  if (document.getElementById('reviewNext')) document.getElementById('reviewNext').disabled = true;
  if (document.getElementById('reviewStatus')) document.getElementById('reviewStatus').textContent = 'Analysis is required before continuing.';
  if (document.getElementById('configStatus')) document.getElementById('configStatus').textContent = 'Settings changed. Analyse to refresh the compatibility report.';
}
```

- [ ] **Step 7: Add the 4 script tags to `index.html`**, remove the ported code from `app.js`, and add toast-host CSS placeholder (styling comes in Task 13):

In `index.html`, replace `<script src="/app.js" defer></script>` with:
```html
<script src="/js/format.js"></script>
<script src="/js/api.js"></script>
<script src="/js/toast.js"></script>
<script src="/js/state.js"></script>
<script src="/app.js" defer></script>
```
In `app.js`, delete lines 1-9 (the `$`, `plan`/`finalJob`/etc. globals, `databaseNames`, `catalogSelection`), delete `formatBytes`/`formatDuration` (now lines ~299-300), delete `api`/`error` (lines 77-82), delete `save` (line 271), delete `invalidate` (lines 147-156). Everywhere `plan`, `finalJob`, `polling`, `errorTimer`, `lastRequest`, `preparationId`, `preparationPoll`, `requestVersion`, `currentStep`, `maxStep`, `databaseNames`, `catalogSelection`, `catalogLoading`, `pendingRevert` are referenced in the remaining `app.js`, prefix with `SchemaBridgeState.migration.` (e.g. `plan = result` becomes `SchemaBridgeState.migration.plan = result`). This is a mechanical rename across the remaining file — do it with a careful find/replace per identifier, not a blanket regex, since `plan` also appears as a local parameter name in some functions (e.g. `renderPlan()`'s own body uses the global `plan`; `jobHistoryCard(job)` does not).

- [ ] **Step 8: Run test to verify it passes**

Run: `node scripts/test-toast-ui.mjs`
Expected: PASS — `toast-ui: all assertions passed`.

- [ ] **Step 9: Run the 4 existing jsdom harnesses to confirm no regression yet**

They still `eval()` only `app.js` at this point and will fail because `app.js` now references `SchemaBridgeState`/`api`/`formatBytes` etc. that aren't defined in their isolated eval. This is expected and fixed in Task 2's first step — note it here so the next engineer isn't alarmed, but do not fix it in this task.

- [ ] **Step 10: Commit**

```bash
git add src/main/resources/static/js/format.js src/main/resources/static/js/api.js src/main/resources/static/js/toast.js src/main/resources/static/js/state.js src/main/resources/static/index.html src/main/resources/static/app.js scripts/test-toast-ui.mjs
git commit -m "Extract format/api/toast/state foundation from app.js into js/ modules"
```

---

### Task 2: Shared display components — status-badge, progress-panel

**Files:**
- Create: `src/main/resources/static/js/status-badge.js`
- Create: `src/main/resources/static/js/progress-panel.js`
- Modify: `index.html`, `app.js`
- Modify: `scripts/test-connection-ui.mjs`, `scripts/test-bulk-ui.mjs`, `scripts/test-catalog-ui.mjs`, `scripts/test-table-settings-ui.mjs` (update their eval-list to include every file created so far, fixing Task 1 Step 9's expected failure)

**Interfaces:**
- Consumes: `formatBytes`, `formatDuration` (Task 1).
- Produces: `statusBadge(kind, value): HTMLElement` where `kind` is `'job'|'table'|'connection'` and `value` is the raw state string; `renderProgress(prefix, progress): void`.

- [ ] **Step 1: Update the 4 existing harnesses' eval-list** (this is the fix deferred from Task 1 Step 9)

In each of `scripts/test-connection-ui.mjs`, `scripts/test-bulk-ui.mjs`, `scripts/test-catalog-ui.mjs`, `scripts/test-table-settings-ui.mjs`, replace the single line:
```js
window.eval(await readFile(new URL('../src/main/resources/static/app.js',import.meta.url),'utf8'));
```
(or the variant with an appended `+'\nwindow.xxx=yyy;'`) with a loop that evals every file in dependency order, keeping any appended line:
```js
const JS_FILES=['format','api','toast','state','status-badge','progress-panel','connection-form','datasource-catalog','flow-and-upload','migration-stepper','table-picker','review-page','execution-page','revert-panel','history-page','storage-page','settings-page','dashboard-page','shell'];
for (const file of JS_FILES) window.eval(await readFile(new URL(`../src/main/resources/static/js/${file}.js`,import.meta.url),'utf8'));
window.eval(await readFile(new URL('../src/main/resources/static/app.js',import.meta.url),'utf8') /* + any appended exposer line stays here */);
```
At this point in the plan only `format`/`api`/`toast`/`state` exist; list only those for now and extend `JS_FILES` in each subsequent task as new files are created (every later task that touches these 4 scripts adds its new filename(s) to this same array). Keep `app.js` last since it still holds everything not yet extracted.

- [ ] **Step 2: Run the 4 harnesses to verify they now pass again**

Run: `node scripts/test-connection-ui.mjs && node scripts/test-bulk-ui.mjs && node scripts/test-catalog-ui.mjs && node scripts/test-table-settings-ui.mjs`
Expected: all 4 print their success output (no assertion changed, behavior is identical — this proves the split so far is transparent).

- [ ] **Step 3: Write `js/status-badge.js`** (new shared component; previously each call site built its own `<span class="badge ...">` ad hoc — e.g. `renderPlan()`'s `badge.className = 'badge ' + table.status'`, and `jobHistoryCard()`'s `state.className='history-state state-'+displayState.toLowerCase()...`. This centralizes that into one function so every badge pairs an icon/dot with text and uses the correct semantic color, per the Global Constraints.)

```js
const STATUS_LABELS = {
  NEW:'New table', MATCH:'Already exists · match', MISMATCH:'Already exists / review', UNCHECKED:'Not inspected',
  QUEUED:'Queued', RUNNING:'Running', SUCCEEDED:'Completed', FAILED:'Failed', CANCELLED:'Cancelled', RECOVERY_REQUIRED:'Needs reconciliation',
  REVERTED:'Reverted', PARTIAL:'Partially reverted'
};
const STATUS_SEMANTIC = {
  NEW:'info', MATCH:'success', MISMATCH:'warning', UNCHECKED:'info',
  QUEUED:'info', RUNNING:'info', SUCCEEDED:'success', FAILED:'error', CANCELLED:'warning', RECOVERY_REQUIRED:'error',
  REVERTED:'success', PARTIAL:'warning'
};
function statusBadge(kind, value) {
  const span = document.createElement('span');
  const semantic = STATUS_SEMANTIC[value] || 'info';
  span.className = 'status-badge status-' + semantic;
  const dot = document.createElement('span'); dot.className = 'status-dot'; dot.setAttribute('aria-hidden','true');
  const text = document.createElement('span'); text.textContent = STATUS_LABELS[value] || value;
  span.append(dot, text);
  span.dataset.statusKind = kind; span.dataset.statusValue = value;
  return span;
}
```

- [ ] **Step 4: Write `js/progress-panel.js`** (ported verbatim from current `app.js` line 298)

```js
function renderProgress(prefix,p){if(!p)return;const phases={QUEUED:'Queued',PREPARING:'Preparing',EXTRACTING:'Reading source',CHECKING:'Checking target and staged data',CREATING:'Creating tables',LOADING:'Loading data',FINALIZING:'Adding indexes and constraints',SUCCEEDED:'Complete',FAILED:'Failed',CANCELLED:'Cancelled',RECOVERY_REQUIRED:'Needs reconciliation'};document.getElementById(`${prefix}Phase`).textContent=phases[p.phase]||p.phase||'Waiting';document.getElementById(`${prefix}Table`).textContent=p.table?`Table: ${p.table}`:'';document.getElementById(`${prefix}Read`).textContent=Number(p.rowsRead||0).toLocaleString();document.getElementById(`${prefix}Sent`).textContent=prefix==='preparation'?'—':Number(p.rowsSent||0).toLocaleString();document.getElementById(`${prefix}Committed`).textContent=prefix==='preparation'?'—':Number(p.rowsCommitted||0).toLocaleString();document.getElementById(`${prefix}Bytes`).textContent=formatBytes(p.bytes);document.getElementById(`${prefix}Elapsed`).textContent=formatDuration(p.elapsedSeconds);document.getElementById(`${prefix}Rate`).textContent=Number(p.rowsPerSecond)>0?`${Math.round(p.rowsPerSecond).toLocaleString()} rows/s`:'—';document.getElementById(`${prefix}Eta`).textContent=p.remainingSeconds==null?'—':formatDuration(p.remainingSeconds);}
```

- [ ] **Step 5: Add script tags to `index.html`** after `state.js`, before `app.js`:
```html
<script src="/js/status-badge.js"></script>
<script src="/js/progress-panel.js"></script>
```
Remove the ported `renderProgress` function body from `app.js` (keep the call sites — they're unchanged, just now calling the extracted function). `badge`/`state` DOM-building inline in `renderPlan()`/`jobHistoryCard()` is **not** changed in this task — that happens in Tasks 6 and 8 when those functions are themselves extracted and get to call `statusBadge()` instead of building badges inline.

- [ ] **Step 6: Run all 4 harnesses again to verify still passing**

Run: `node scripts/test-connection-ui.mjs && node scripts/test-bulk-ui.mjs && node scripts/test-catalog-ui.mjs && node scripts/test-table-settings-ui.mjs`
Expected: all PASS (progress-panel/status-badge aren't exercised by these 4 tests directly, but `app.js` must still parse and run without error for any of them to pass at all).

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/static/js/status-badge.js src/main/resources/static/js/progress-panel.js src/main/resources/static/index.html src/main/resources/static/app.js scripts/test-*-ui.mjs
git commit -m "Extract status-badge and progress-panel components"
```

---

### Task 3: Connection form + datasource catalog

**Files:**
- Create: `src/main/resources/static/js/connection-form.js`
- Create: `src/main/resources/static/js/datasource-catalog.js`
- Modify: `index.html`, `app.js`, `scripts/test-connection-ui.mjs`, `scripts/test-catalog-ui.mjs` (add new filenames to `JS_FILES`)

**Interfaces:**
- Consumes: `api`, `toast`/`error`, `SchemaBridgeState.migration`, `invalidate`.
- Produces: `connectionForm(prefix): void`, `applyDialect(prefix): void`, `connection(prefix): object`, `loadCatalog(): Promise<void>`, `setCatalogMode(prefix, selection): void`.

- [ ] **Step 1: Add filenames to the two harnesses' `JS_FILES`**

In `scripts/test-connection-ui.mjs` and `scripts/test-catalog-ui.mjs`, append `'connection-form','datasource-catalog'` to the `JS_FILES` array from Task 2.

- [ ] **Step 2: Run both to verify they fail** (functions not yet moved, but `app.js` still has them — so this actually still passes; the real regression check is after extraction)

Run: `node scripts/test-connection-ui.mjs && node scripts/test-catalog-ui.mjs`
Expected: PASS (nothing moved yet — this step just confirms the baseline before the extraction below).

- [ ] **Step 3: Write `js/datasource-catalog.js`** (ported verbatim from current `app.js` lines 42-73, with `plan`/global state references already rewritten to `SchemaBridgeState.migration.*` per Task 1's rename, and `error(...)` calls unchanged since `toast.js` exposes an `error` alias)

```js
function setCatalogMode(prefix, selection) {
  const m = SchemaBridgeState.migration;
  m.catalogSelection[prefix] = selection;
  document.getElementById(prefix+'Dialect').disabled = !!selection;
  for (const suffix of ['Host','Port','Database','Username','Password']) document.getElementById(prefix+suffix).readOnly = !!selection;
  if (selection) {
    document.getElementById(prefix+'Dialect').value = selection.connection.dialect; applyDialect(prefix);
    for (const [suffix, key] of [['Host','host'],['Port','port'],['Database','database'],['Username','username'],['Password','password']]) document.getElementById(prefix+suffix).value = selection.connection[key];
    document.getElementById(prefix+'DatabaseLabel').textContent = selection.connection.dialect === 'ORACLE' ? 'Oracle '+selection.mode : 'PostgreSQL database name';
    document.getElementById(prefix+'DatabaseHelp').textContent = 'Loaded from LCNC: '+selection.name+'. Choose Manual entry to edit connection values.';
  } else { applyDialect(prefix); document.getElementById(prefix+'Catalog').value=''; }
  fillOptions(document.getElementById(prefix+'SchemaSelect'), []);
  if(prefix === 'source') {fillOptions(document.getElementById('sourceTables'), []);document.getElementById('columnOptions').replaceChildren();if(document.getElementById('tablePickerList'))renderTablePicker();}
  else document.getElementById('targetSchema').value = selection?.schema || (document.getElementById(prefix+'Dialect').value === 'ORACLE' ? 'APP' : 'public');
  invalidate();
}
async function loadCatalog() {
  const m = SchemaBridgeState.migration;
  if (m.catalogLoading) return;
  m.catalogLoading = true;
  for(const prefix of ['source','target']) document.getElementById(prefix+'CatalogRefresh').disabled = true;
  try {
    const result = await (await api('/datasources',undefined,'GET')).json();
    if (!result.configured) throw new Error('LCNC catalogue is not configured. Manual entry remains available.');
    for(const prefix of ['source','target']) {
      const select=document.getElementById(prefix+'Catalog'), selected=select.value; select.replaceChildren(new Option('Manual entry',''));
      for(const item of result.datasources) { const option=new Option(`${item.name} (#${item.id})${item.supported?' — '+item.dialect:' — '+item.finding}`,String(item.id)); option.disabled=!item.supported; option.title=item.description || ''; select.add(option); }
      select.value=selected;
      if (!select.value && m.catalogSelection[prefix]) setCatalogMode(prefix,null);
      document.getElementById(prefix+'CatalogStatus').textContent = result.datasources.length+' active, approved master JDBC entries. Select one, then connect.';
    }
  } catch(e) { error(e.message); }
  finally { m.catalogLoading=false; for(const prefix of ['source','target']) document.getElementById(prefix+'CatalogRefresh').disabled = document.getElementById(prefix+'Connection').hidden; }
}
```

- [ ] **Step 4: Write `js/connection-form.js`** (ported verbatim from current `app.js` lines 83-133, same state-prefix rewrite)

```js
function connectionForm(prefix) {
  const host = document.createElement('div');
  host.innerHTML = `<div class="connection-grid"><label>Host<input id="${prefix}Host" value="localhost" autocomplete="off" required></label><label>Port<input id="${prefix}Port" type="number" min="1" max="65535" value="${prefix === 'source' ? 1521 : 5432}" required></label></div><label><span id="${prefix}DatabaseLabel">Database name</span><input id="${prefix}Database" autocomplete="off" aria-describedby="${prefix}DatabaseHelp" required></label><p id="${prefix}DatabaseHelp" class="muted"></p><label>Username<input id="${prefix}Username" autocomplete="off" required></label><label>Password<input id="${prefix}Password" type="password" autocomplete="new-password"></label><button type="button" id="${prefix}Connect" class="secondary">Connect & load schemas</button><label>${prefix === 'source' ? 'Source schema' : 'Available target schemas'}<select id="${prefix}SchemaSelect"><option value="">Connect first</option></select></label>`;
  document.getElementById(prefix + 'Connection').append(host);
  const chooser=document.createElement('div');
  chooser.innerHTML=`<label>Connection source<select id="${prefix}Catalog"><option value="">Manual entry</option></select></label><button type="button" class="secondary" id="${prefix}CatalogRefresh">Load / refresh LCNC datasources</button><p class="muted" id="${prefix}CatalogStatus">Use manual entry or load saved connections from fg_datasource.</p>`;
  host.prepend(chooser);
  document.getElementById(prefix+'CatalogRefresh').addEventListener('click',loadCatalog);
  let selectionVersion=0;
  document.getElementById(prefix+'Catalog').addEventListener('change',async()=>{
    const id=document.getElementById(prefix+'Catalog').value, version=++selectionVersion;
    if(!id) { setCatalogMode(prefix,null); document.getElementById(prefix+'Connect').disabled=document.getElementById(prefix+'Connection').hidden; return; }
    document.getElementById(prefix+'Connect').disabled=true;
    try { const selected=await(await api('/datasources/'+encodeURIComponent(id)+'/select',{})).json(); if(version===selectionVersion) setCatalogMode(prefix,selected); }
    catch(e) { if(version===selectionVersion) { setCatalogMode(prefix,null); error(e.message); } }
    finally { if(version===selectionVersion) document.getElementById(prefix+'Connect').disabled=document.getElementById(prefix+'Connection').hidden; }
  });
  applyDialect(prefix);
  document.getElementById(prefix + 'Database').addEventListener('input', () => document.getElementById(prefix + 'Database').setCustomValidity(''));
  document.getElementById(prefix + 'Connect').addEventListener('click', async () => {
    const button = document.getElementById(prefix + 'Connect'); button.disabled = true;
    try { const schemas = await (await api('/schemas', connection(prefix))).json(); fillOptions(document.getElementById(prefix + 'SchemaSelect'), schemas); const preferred=SchemaBridgeState.migration.catalogSelection[prefix]?.schema; if(preferred && schemas.includes(preferred)) document.getElementById(prefix+'SchemaSelect').value=preferred; document.getElementById('configStatus').textContent = 'Connected. Select a schema.'; invalidate(); if (prefix === 'source') await loadTables(); else document.getElementById('targetSchema').value = document.getElementById(prefix + 'SchemaSelect').value; }
    catch (e) { error(e.message); } finally { button.disabled = false; }
  });
  document.getElementById(prefix + 'SchemaSelect').addEventListener('change', async () => { invalidate(); if (prefix === 'source') await loadTables(); else document.getElementById('targetSchema').value = document.getElementById(prefix + 'SchemaSelect').value; });
}
function applyDialect(prefix) {
  const dialect = document.getElementById(prefix + 'Dialect').value, field = document.getElementById(prefix + 'Database');
  if (field.dataset.dialect) SchemaBridgeState.migration.databaseNames[prefix][field.dataset.dialect] = field.value;
  field.value = SchemaBridgeState.migration.databaseNames[prefix][dialect] ?? (dialect === 'POSTGRESQL' ? 'postgres' : '');
  field.dataset.dialect = dialect; field.setCustomValidity('');
  field.placeholder = dialect === 'POSTGRESQL' ? 'postgres or your application database' : 'For example, XEPDB1';
  document.getElementById(prefix + 'DatabaseLabel').textContent = dialect === 'POSTGRESQL' ? 'PostgreSQL database name' : 'Oracle service name';
  document.getElementById(prefix + 'DatabaseHelp').textContent = dialect === 'POSTGRESQL'
    ? 'postgres is the default connection database. Replace it with the database containing your schemas; selecting PostgreSQL only chooses the engine.'
    : 'Enter the Oracle service name, for example XEPDB1. Select the schema after connecting.';
  document.getElementById(prefix + 'Port').value = dialect === 'ORACLE' ? '1521' : '5432';
}
function connection(prefix) {
  const m = SchemaBridgeState.migration;
  if (document.getElementById(prefix+'Catalog').value && String(m.catalogSelection[prefix]?.id) !== document.getElementById(prefix+'Catalog').value) throw new Error('Wait for the selected LCNC datasource to load.');
  const dialect = document.getElementById(prefix + 'Dialect').value, databaseField = document.getElementById(prefix + 'Database'), database = databaseField.value.trim();
  if (!database) {
    const message = `${prefix === 'source' ? 'Source' : 'Target'} ${dialect === 'POSTGRESQL' ? 'PostgreSQL database name' : 'Oracle service name'} is required.`;
    databaseField.setCustomValidity(message); databaseField.reportValidity(); throw new Error(message);
  }
  databaseField.setCustomValidity('');
  for (const suffix of ['Host', 'Port', 'Username']) { const field = document.getElementById(prefix + suffix); if (!field.checkValidity()) { field.reportValidity(); throw new Error(`Check the ${prefix} ${suffix.toLowerCase()} field.`); } }
  return {dialect,host:document.getElementById(prefix + 'Host').value.trim(),port:Number(document.getElementById(prefix + 'Port').value),database,username:document.getElementById(prefix + 'Username').value,password:document.getElementById(prefix + 'Password').value,...(m.catalogSelection[prefix]?{jdbcUrl:m.catalogSelection[prefix].connection.jdbcUrl}:{})};
}
function fillOptions(select, values) { select.replaceChildren(); for (const value of values) { const option = document.createElement('option'); option.value = option.textContent = value; select.append(option); } }
```

- [ ] **Step 5: Add script tags to `index.html`**, remove the ported code from `app.js` (same mechanical process as Task 1 Step 7).

- [ ] **Step 6: Run `test-connection-ui.mjs` and `test-catalog-ui.mjs` to verify they pass**

Run: `node scripts/test-connection-ui.mjs && node scripts/test-catalog-ui.mjs`
Expected: both PASS — identical output to before the extraction (this is the real regression check).

- [ ] **Step 7: Also run the other two harnesses** (they depend on `connectionForm` indirectly through `app.js`'s top-level `connectionForm('source'); connectionForm('target');` call)

Run: `node scripts/test-bulk-ui.mjs && node scripts/test-table-settings-ui.mjs`
Expected: both PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/resources/static/js/connection-form.js src/main/resources/static/js/datasource-catalog.js src/main/resources/static/index.html src/main/resources/static/app.js scripts/test-connection-ui.mjs scripts/test-catalog-ui.mjs
git commit -m "Extract connection-form and datasource-catalog modules"
```

---

### Task 4: Flow/upload + migration stepper

**Files:**
- Create: `src/main/resources/static/js/flow-and-upload.js`
- Create: `src/main/resources/static/js/migration-stepper.js`
- Modify: `index.html`, `app.js`, `scripts/test-bulk-ui.mjs` (add filenames to `JS_FILES`; it also appends `window.loadSqlFiles = loadSqlFiles;` — keep that line, now exposing the function from the new file)

**Interfaces:**
- Consumes: `invalidate`, `SchemaBridgeState.migration`, `error`.
- Produces: `flow(): string`, `usesDatabase(): boolean`, `writesDatabase(): boolean`, `updateFlow(): void`, `loadSqlFiles(input): Promise<void>`, `goToStep(step, scroll=true): void`.

- [ ] **Step 1: Add filenames to `test-bulk-ui.mjs`'s `JS_FILES`**

Append `'connection-form','datasource-catalog','flow-and-upload','migration-stepper'`.

- [ ] **Step 2: Run to confirm current baseline passes**

Run: `node scripts/test-bulk-ui.mjs`
Expected: PASS (nothing moved yet).

- [ ] **Step 3: Write `js/flow-and-upload.js`** (ported verbatim from current `app.js` lines 74-76, 157-162, 172-201 — note `goToStep` moves to `migration-stepper.js`, so `updateFlow`'s call to it stays as a plain function call, resolved at runtime since both files load before `app.js` and `goToStep` is declared by then)

```js
const flow = () => document.querySelector('input[name="flow"]:checked').value;
const usesDatabase = () => flow().startsWith('db-');
const writesDatabase = () => flow().endsWith('-db');
function updateFlow() {
  document.getElementById('sourceConnection').hidden = !usesDatabase(); document.getElementById('tableSelection').hidden = !usesDatabase(); document.getElementById('filesInput').hidden = usesDatabase(); document.getElementById('targetConnection').hidden = !writesDatabase();
  for (const prefix of ['source','target']) document.getElementById(prefix + 'Connection').querySelectorAll('input,select,button').forEach(field => field.disabled = document.getElementById(prefix + 'Connection').hidden);
  document.getElementById('openTablePicker').disabled = !usesDatabase() || !document.getElementById('sourceSchemaSelect').value;
  invalidate();
}
let uploadVersion = 0;
async function loadSqlFiles(input) {
  const selected = Array.from(input.files);
  if (!selected.length) return;
  const version = ++uploadVersion;
  invalidate(); document.getElementById('sql').value = ''; document.getElementById('fileNames').textContent = 'Reading files…'; document.getElementById('analyse').disabled = true;
  document.getElementById('sql').disabled = true; document.getElementById('example').disabled = true;
  try {
    const path = file => file.webkitRelativePath || file.name;
    const files = selected.filter(file => /\.(sql|ddl|dml|txt)$/i.test(file.name)).sort((a,b) => path(a).localeCompare(path(b), 'en', {numeric:true}) || path(a).localeCompare(path(b), 'en', {sensitivity:'variant'}));
    if (!files.length) throw new Error('No .sql, .ddl, .dml or .txt files found in the selection.');
    if (files.reduce((size,file) => size + file.size,0) > 10000000) throw new Error('Files exceed 10 MB total. Choose a smaller batch.');
    const parts = [];
    for (const file of files) {
      const text = await file.text();
      if (version !== uploadVersion) return;
      parts.push('-- Source file: ' + path(file).replace(/[\r\n  ]/g,' ') + '\n' + text.replace(/^﻿/,''));
    }
    const combined = parts.join('\n;\n');
    if (new TextEncoder().encode(combined).length > 10000000) throw new Error('Combined SQL exceeds 10 MB including file markers. Choose a smaller batch.');
    document.getElementById('sql').value = combined;
    document.getElementById('fileNames').textContent = `${files.length} files loaded; ${selected.length-files.length} other files ignored. Order: ` + files.map(path).join(' → ');
    document.getElementById(input.id === 'folder' ? 'files' : 'folder').value = '';
  } catch(e) {
    if (version === uploadVersion) { document.getElementById('fileNames').textContent = 'No files loaded.'; error(e.message); }
  } finally {
    if (version === uploadVersion) { document.getElementById('analyse').disabled = false; document.getElementById('sql').disabled = false; document.getElementById('example').disabled = false; }
  }
}
```

- [ ] **Step 4: Write `js/migration-stepper.js`** (ported verbatim from current `app.js` lines 135-146, with `currentStep`/`maxStep` rewritten to `SchemaBridgeState.migration.*`)

```js
function goToStep(step, scroll=true) {
  const m = SchemaBridgeState.migration;
  if (step > m.maxStep || step < 1) return;
  m.currentStep = step;
  document.querySelectorAll('[data-step-page]').forEach(page => page.hidden = Number(page.dataset.stepPage) !== step);
  document.querySelectorAll('[data-step-nav]').forEach(button => {
    const number = Number(button.dataset.stepNav), active = number === step;
    button.classList.toggle('active', active); button.classList.toggle('complete', number < step);
    button.disabled = number > m.maxStep;
    if (active) button.setAttribute('aria-current','step'); else button.removeAttribute('aria-current');
  });
  if (scroll) window.scrollTo({top:0,behavior:'smooth'});
}
```

- [ ] **Step 5: Add script tags to `index.html`**, remove the ported code from `app.js`. All remaining references to `currentStep`/`maxStep` in `app.js` (e.g. `maxStep=Math.max(maxStep,2)` in the `flowNext` handler) must be rewritten to `SchemaBridgeState.migration.maxStep` etc., same mechanical rename as Task 1.

- [ ] **Step 6: Run `test-bulk-ui.mjs` to verify it passes**

Run: `node scripts/test-bulk-ui.mjs`
Expected: PASS, identical to before extraction.

- [ ] **Step 7: Run the other 3 harnesses too** (sanity check — none of them exercise upload/stepper directly, but all load the full page)

Run: `node scripts/test-connection-ui.mjs && node scripts/test-catalog-ui.mjs && node scripts/test-table-settings-ui.mjs`
Expected: all PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/resources/static/js/flow-and-upload.js src/main/resources/static/js/migration-stepper.js src/main/resources/static/index.html src/main/resources/static/app.js scripts/test-bulk-ui.mjs
git commit -m "Extract flow-and-upload and migration-stepper modules"
```

---

### Task 5: Table picker with collapsible Columns/Filters/Row-limit sections

**Files:**
- Create: `src/main/resources/static/js/table-picker.js`
- Modify: `index.html` (table picker dialog markup gains `<details>` wrappers), `styles.css` (disclosure chevron/summary styling — minimal, full token pass is Task 13), `app.js`, `scripts/test-table-settings-ui.mjs`

**Interfaces:**
- Consumes: `api`, `error`, `invalidate`, `connection`, `SchemaBridgeState.migration`.
- Produces: `loadTables(): Promise<void>`, `selectedSourceTables(): string[]`, `renderTablePicker(): void`, `updateTablePickerSummary(): void`, `tableSelections(): object`, `syncTableSettings(): void`, `addFilterRow(rows, columns, add): void`, `closeTablePicker(apply): void`.

- [ ] **Step 1: Write the failing tests** — collapsible sections, plus the rapid-reopen race guard (Review Focus item 4)

Read the current `scripts/test-table-settings-ui.mjs` first (54 lines) to see its existing assertions and mocked `/columns` response, then append both new blocks below the existing assertions, without removing any existing assertion:
```js
// --- New: Columns/Filters/Row-limit must be independently collapsible <details> ---
const group = window.document.querySelector('#columnOptions > details[data-table]');
const sections = group.querySelectorAll('.mc-disclosure, details[data-settings-section]');
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
const slowGroup = window.document.querySelector('#columnOptions > details[data-table]');
slowGroup.open = true; slowGroup.dispatchEvent(new window.Event('toggle'));
await new Promise(setImmediate);
window.document.getElementById('sourceSchemaSelect').value = 'schema_b'; // schema changes before the fetch resolves
resolveSlowFetch();
await new Promise(setImmediate); await new Promise(setImmediate);
assert.notEqual(slowGroup.dataset.loaded, 'true', 'a response for the old schema must not be applied after the schema changed');
window.fetch = realFetch;
console.log('table-settings-ui: stale-schema race guard passed');
```

- [ ] **Step 2: Run test to verify both new blocks fail**

Run: `node scripts/test-table-settings-ui.mjs`
Expected: FAIL on the first new block — `sections.length` is 0 (no `<details data-settings-section>` markup exists yet; today's per-table settings body is one flat `<fieldset>`). The race-guard block is written but not yet reachable as a distinct failure until the first block passes — that's fine, fix both in Step 3.

- [ ] **Step 3: Write `js/table-picker.js`**, porting the current logic from `app.js` lines 134 (`loadTables`), 391-490 (`selectedSourceTables` through the picker event wiring), and restructuring `syncTableSettings`'s per-table body into 3 `<details>` sections instead of one flat `<fieldset>`:

```js
async function loadTables() { document.getElementById('columnOptions').replaceChildren();fillOptions(document.getElementById('sourceTables'),[]); try { const schema = document.getElementById('sourceSchemaSelect').value; if (schema) fillOptions(document.getElementById('sourceTables'), await (await api('/tables', {connection:connection('source'),schema})).json()); renderTablePicker(); } catch(e) { renderTablePicker();error(e.message); } }
function selectedSourceTables(){return Array.from(document.getElementById('sourceTables').selectedOptions).map(option=>option.value);}
function renderTablePicker(){
  if(!document.getElementById('tablePickerList'))return;
  const selected=new Set(selectedSourceTables()),list=document.getElementById('tablePickerList');list.replaceChildren();
  for(const option of document.getElementById('sourceTables').options){const label=document.createElement('label');label.className='table-picker-option';label.dataset.name=option.value.toLocaleLowerCase();const check=document.createElement('input');check.type='checkbox';check.checked=selected.has(option.value);check.value=option.value;const name=document.createElement('span');name.textContent=option.value;label.append(check,name);list.append(label);}
  const query=document.getElementById('tableSearch').value.trim().toLocaleLowerCase();for(const row of list.querySelectorAll('.table-picker-option'))row.hidden=!row.dataset.name.includes(query);
  updateTablePickerSummary();syncTableSettings();
}
function updateTablePickerSummary(){
  const selected=selectedSourceTables();
  if(document.getElementById('tableSelectionSummary'))document.getElementById('tableSelectionSummary').textContent=selected.length?`${selected.length} table${selected.length===1?'':'s'} selected`:'No tables selected yet.';
  if(document.getElementById('pickerCount'))document.getElementById('pickerCount').textContent=selected.length?`${selected.length} table${selected.length===1?'':'s'} selected for this migration.`:'No tables selected.';
  if(document.getElementById('openTablePicker'))document.getElementById('openTablePicker').disabled=!document.getElementById('sourceSchemaSelect').value||!document.getElementById('sourceTables').options.length;
  if(document.getElementById('selectAllTables')){const visible=Array.from(document.getElementById('tablePickerList').querySelectorAll('.table-picker-option')).filter(row=>!row.hidden),checked=visible.filter(row=>row.querySelector('input').checked).length;document.getElementById('selectAllTables').checked=visible.length>0&&checked===visible.length;document.getElementById('selectAllTables').indeterminate=checked>0&&checked<visible.length;document.getElementById('selectAllTables').disabled=visible.length===0;}
}
function tableSelections(){
  const result={},selected=new Set(selectedSourceTables());
  for(const group of document.getElementById('columnOptions').children){if(!selected.has(group.dataset.table)||group.dataset.loaded!=='true')continue;
    const columns=[],rename={};
    for(const row of group.querySelectorAll('[data-column]'))if(row.querySelector('input[type=checkbox]').checked){columns.push(row.dataset.column);const destination=row.querySelector('input[type=text]').value.trim();if(!destination)throw new Error('Enter a destination column name.');if(destination!==row.dataset.column)rename[row.dataset.column]=destination;}
    if(!columns.length)throw new Error('Select at least one column for '+group.dataset.table);
    const tableName=group.querySelector('.target-table-name').value.trim();if(!tableName)throw new Error('Enter a destination table name for '+group.dataset.table);
    const filters=[];
    for(const row of group.querySelectorAll('.filter-row')){const column=row.querySelector('.filter-column').value,operator=row.querySelector('.filter-operator').value,value=row.querySelector('.filter-value').value;if(!column)continue;filters.push({column,operator,value:operator.includes('NULL')?null:value});}
    const rawLimit=group.querySelector('.table-row-limit').value;
    const rowLimit=rawLimit===''?null:Number(rawLimit);if(rowLimit!==null&&(!Number.isInteger(rowLimit)||rowLimit<1||rowLimit>2147483647))throw new Error('Enter a positive whole-number row limit for '+group.dataset.table);
    result[group.dataset.table]={columns,rename,filters,rowLimit,...(tableName!==group.dataset.table?{tableName}:{})};
  }
  return result;
}
function disclosure(label, countText, open) {
  const details = document.createElement('details'); details.className = 'mc-disclosure'; details.dataset.settingsSection = label.toLowerCase(); details.open = open;
  const summary = document.createElement('summary');
  const chev = document.createElement('span'); chev.className = 'chev'; chev.setAttribute('aria-hidden','true'); chev.textContent = '▸';
  const title = document.createElement('span'); title.append(chev, document.createTextNode(' ' + label));
  const count = document.createElement('span'); count.className = 'count'; count.textContent = countText;
  summary.append(title, count); details.append(summary);
  const body = document.createElement('div'); body.className = 'mc-dbody'; details.append(body);
  return {details, body, setCount(text){ count.textContent = text; }};
}
function syncTableSettings(){
  const selected=new Set(selectedSourceTables()),container=document.getElementById('columnOptions');
  const existing=new Map(Array.from(container.children,group=>[group.dataset.table,group]));
  for(const group of container.children){group.hidden=!selected.has(group.dataset.table);const fieldset=group.querySelector('fieldset');if(fieldset)fieldset.disabled=group.hidden;}
  for(const table of selected){
    if(existing.has(table))continue;
    const group=document.createElement('details');group.dataset.table=table;
    const summary=document.createElement('summary');summary.textContent=table+' — optional settings';group.append(summary);
    const body=document.createElement('fieldset');group.append(body);container.append(group);
    group.addEventListener('toggle',async()=>{
      if(!group.open||group.dataset.loaded==='true'||group.dataset.loading==='true')return;
      group.dataset.loading='true';body.textContent='Loading columns…';
      try{
        const schemaAtRequestTime=document.getElementById('sourceSchemaSelect').value;
        const columns=await(await api('/columns',{connection:connection('source'),schema:schemaAtRequestTime,table})).json();
        if(!group.isConnected||document.getElementById('sourceSchemaSelect').value!==schemaAtRequestTime)return;
        body.replaceChildren();
        const label=document.createElement('label');label.textContent='Destination table name';const name=document.createElement('input');name.className='target-table-name';name.value=table;name.maxLength=128;label.append(name);body.append(label);

        const includedCount = () => columns.filter(c => mapping.querySelector(`[data-column="${c.name.value}"] input[type=checkbox]`)?.checked).length;
        const cols = disclosure('Columns', `${columns.length} of ${columns.length} included`, true); body.append(cols.details);
        const mapping=cols.body;
        for(const column of columns){const row=document.createElement('label');row.className='column-mapping';row.dataset.column=column.name.value;const check=document.createElement('input');check.type='checkbox';check.checked=true;const text=document.createElement('span');text.textContent=column.name.value;const destination=document.createElement('input');destination.type='text';destination.value=column.name.value;destination.setAttribute('aria-label','Destination name for '+column.name.value);row.append(check,text,destination);mapping.append(row);check.addEventListener('change',()=>cols.setCount(`${includedCount()} of ${columns.length} included`));}

        const filters = disclosure('Filters (all must match)', '0 active', false); body.append(filters.details);
        const note=document.createElement('p');note.className='muted';note.textContent='All filters must match (AND). No filters means all rows. A row limit selects up to that many matching rows without a guaranteed order.';filters.body.append(note);
        const rows=document.createElement('div');rows.className='filter-rows';filters.body.append(rows);
        const add=document.createElement('button');add.type='button';add.className='secondary';add.textContent='Add filter';filters.body.append(add);
        const refreshFilterCount = () => filters.setCount(`${rows.children.length} active`);
        add.addEventListener('click',()=>{addFilterRow(rows,columns,add);refreshFilterCount();invalidate();});

        const limit = disclosure('Row limit', 'All matching rows', false); body.append(limit.details);
        const limitLabel=document.createElement('label');limitLabel.textContent='Row limit (optional)';const limitInput=document.createElement('input');limitInput.type='number';limitInput.min='1';limitInput.max='2147483647';limitInput.step='1';limitInput.className='table-row-limit';limitInput.placeholder='All matching rows';limitLabel.append(limitInput);limit.body.append(limitLabel);
        limitInput.addEventListener('input',()=>limit.setCount(limitInput.value?Number(limitInput.value).toLocaleString():'All matching rows'));

        group.dataset.loaded='true';
      }catch(e){body.textContent='Could not load settings: '+e.message+' Close and reopen to retry.';}
      finally{delete group.dataset.loading;}
    });
  }
}
function addFilterRow(rows,columns,add){
  if(rows.children.length>=20)return;
  const row=document.createElement('div');row.className='filter-row';
  const field=document.createElement('select');field.className='filter-column';field.setAttribute('aria-label','Filter column');fillOptions(field,['',...columns.filter(c=>!['TEXT','BINARY'].includes(c.type.kind)).map(c=>c.name.value)]);field.options[0].textContent='Choose column';
  const operator=document.createElement('select');operator.className='filter-operator';operator.setAttribute('aria-label','Filter operator');
  const holder=document.createElement('span');holder.className='filter-value-holder';
  function updateValue(){
    const boolean=columns.find(c=>c.name.value===field.value)?.type.kind==='BOOLEAN';
    const old=holder.firstElementChild;const value=document.createElement(boolean?'select':'input');value.className='filter-value';value.setAttribute('aria-label','Filter value');
    if(boolean)fillOptions(value,['true','false']);else{value.type='text';value.placeholder='Value';}
    if(old&&old.tagName===value.tagName)value.value=old.value;
    value.disabled=operator.value.includes('NULL')||!field.value;holder.replaceChildren(value);
  }
  field.addEventListener('change',()=>{const previous=operator.value,boolean=columns.find(c=>c.name.value===field.value)?.type.kind==='BOOLEAN';fillOptions(operator,boolean?['=','<>','IS NULL','IS NOT NULL']:['=','<>','>','>=','<','<=','IS NULL','IS NOT NULL']);if(Array.from(operator.options).some(o=>o.value===previous))operator.value=previous;updateValue();});
  fillOptions(operator,['=','<>','>','>=','<','<=','IS NULL','IS NOT NULL']);operator.addEventListener('change',updateValue);updateValue();
  const remove=document.createElement('button');remove.type='button';remove.className='secondary';remove.textContent='Remove';remove.setAttribute('aria-label','Remove filter');remove.addEventListener('click',()=>{row.remove();add.disabled=false;const section=row.closest('.mc-disclosure');if(section)section.querySelector('.count').textContent=`${rows.children.length} active`;invalidate();});
  row.append(field,operator,holder,remove);rows.append(row);add.disabled=rows.children.length>=20;
}
function closeTablePicker(apply){
  const m = SchemaBridgeState.migration;
  if(!apply){const selected=new Set(m.pickerOriginalSelection||[]);for(const option of document.getElementById('sourceTables').options)option.selected=selected.has(option.value);renderTablePicker();}
  else if(!selectedSourceTables().length||selectedSourceTables().length>500){error('Select between 1 and 500 source tables to continue.');return;}
  document.getElementById('tablePicker').close();updateTablePickerSummary();
}
document.getElementById('sourceTables').addEventListener('change',renderTablePicker);
document.getElementById('openTablePicker').addEventListener('click',()=>{SchemaBridgeState.migration.pickerOriginalSelection=selectedSourceTables();renderTablePicker();document.getElementById('tablePicker').showModal();});
document.getElementById('closeTablePicker').addEventListener('click',()=>closeTablePicker(false));
document.getElementById('cancelTablePicker').addEventListener('click',()=>closeTablePicker(false));
document.getElementById('applyTablePicker').addEventListener('click',()=>closeTablePicker(true));
document.getElementById('tablePicker').addEventListener('cancel',()=>{const selected=new Set(SchemaBridgeState.migration.pickerOriginalSelection||[]);for(const option of document.getElementById('sourceTables').options)option.selected=selected.has(option.value);renderTablePicker();});
document.getElementById('tablePickerList').addEventListener('change',event=>{
  if(!event.target.matches('input[type=checkbox]'))return;
  const option=Array.from(document.getElementById('sourceTables').options).find(item=>item.value===event.target.value);if(option)option.selected=event.target.checked;
  document.getElementById('sourceTables').dispatchEvent(new Event('change',{bubbles:true}));updateTablePickerSummary();
});
document.getElementById('tableSearch').addEventListener('input',()=>{const query=document.getElementById('tableSearch').value.trim().toLocaleLowerCase();for(const row of document.getElementById('tablePickerList').querySelectorAll('.table-picker-option'))row.hidden=!row.dataset.name.includes(query);updateTablePickerSummary();});
document.getElementById('selectAllTables').addEventListener('change',()=>{
  const visible=Array.from(document.getElementById('tablePickerList').querySelectorAll('.table-picker-option')).filter(row=>!row.hidden),turnOn=document.getElementById('selectAllTables').checked;
  for(const row of visible){const checkbox=row.querySelector('input');checkbox.checked=turnOn;const option=Array.from(document.getElementById('sourceTables').options).find(item=>item.value===checkbox.value);if(option)option.selected=turnOn;}
  document.getElementById('sourceTables').dispatchEvent(new Event('change',{bubbles:true}));updateTablePickerSummary();
});
```

Note on **Review Focus item 4** (rapid reopen before load finishes): the `toggle` handler above now captures `schemaAtRequestTime` before the async `/columns` fetch and re-checks it on return, discarding the response if the source schema changed in the meantime — this is a genuinely new guard, not present in the original code, added specifically to close that gap. Also note `pickerOriginalSelection` moves from a bare module-level `let` (original line 470) into `SchemaBridgeState.migration` so it survives page navigation consistently with everything else in Task 1's state consolidation.

- [ ] **Step 4: Add script tag to `index.html`**, remove the ported code from `app.js`. In `index.html`'s table picker dialog markup, no structural change is needed — `syncTableSettings` now builds the 3 disclosures dynamically, so the static markup stays the same (`<div id="columnOptions">` container).

- [ ] **Step 5: Run test to verify it passes**

Run: `node scripts/test-table-settings-ui.mjs`
Expected: PASS, including the pre-existing assertions, the collapsible-section assertions, and the stale-schema race-guard assertion, all from Step 1.

- [ ] **Step 6: Run the other 3 harnesses**

Run: `node scripts/test-connection-ui.mjs && node scripts/test-bulk-ui.mjs && node scripts/test-catalog-ui.mjs`
Expected: all PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/static/js/table-picker.js src/main/resources/static/index.html src/main/resources/static/app.js scripts/test-table-settings-ui.mjs
git commit -m "Extract table-picker module with collapsible Columns/Filters/Row-limit sections"
```

---

### Task 6: Review page (comparison, download-ticket)

**Files:**
- Create: `src/main/resources/static/js/review-page.js`
- Create: `scripts/test-review-page-ui.mjs` (new)
- Modify: `index.html`, `app.js`

**Interfaces:**
- Consumes: `api`, `error`, `save`, `statusBadge`, `SchemaBridgeState.migration`.
- Produces: `cell(row,text)`, `renderPlan(): void`, `actions(): object`, `clearDownloadLink(): void`.

- [ ] **Step 1: Write the failing test**

Create `scripts/test-review-page-ui.mjs`:
```js
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {createRequire} from 'node:module';
const require=createRequire(new URL('../target/ui-test/package.json',import.meta.url));
const {JSDOM}=require('jsdom');
const html=await readFile(new URL('../src/main/resources/static/index.html',import.meta.url),'utf8');
const dom=new JSDOM(html,{url:'http://localhost:8098',runScripts:'outside-only'});
const {window}=dom;
const JS_FILES=['format','api','toast','state','status-badge','progress-panel','connection-form','datasource-catalog','flow-and-upload','migration-stepper','table-picker','review-page'];
for (const file of JS_FILES) window.eval(await readFile(new URL(`../src/main/resources/static/js/${file}.js`,import.meta.url),'utf8'));

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
console.log('review-page-ui: all assertions passed');
```

- [ ] **Step 2: Run test to verify it fails**

Run: `node scripts/test-review-page-ui.mjs`
Expected: FAIL — `window.renderPlan is not a function`.

- [ ] **Step 3: Write `js/review-page.js`**, porting `app.js` lines 211-279 (`cell`, `renderPlan`, `actions`, `clearDownloadLink`, `matchAll`/`reportRows`/`reportDownload`/`download`/`downloadLink`/`execute` handlers), with the table-status badge now built via `statusBadge('table', table.status)` instead of the inline `badge.className='badge '+table.status` construction:

```js
function cell(row,text) { const td = document.createElement('td'); td.textContent = text; row.append(td); return td; }
function renderPlan() {
  const plan = SchemaBridgeState.migration.plan;
  document.getElementById('reportSection').hidden = false; document.getElementById('reportMessages').replaceChildren(); document.getElementById('reportRows').replaceChildren();
  document.getElementById('outputActions').hidden = false; document.getElementById('reviewNext').disabled = false; document.getElementById('reviewStatus').textContent = 'Review complete. Continue when you are ready to export or run the plan.';
  document.getElementById('outputTitle').textContent = plan.orderedScript ? 'Download converted script' : 'Ready to export or execute';
  document.getElementById('outputDescription').textContent = plan.orderedScript ? 'This script preserves statement order and is provided as a review package.' : 'Choose an export or run the reviewed plan against the target database.';
  document.getElementById('reportTitle').textContent = plan.orderedScript ? 'Review the script in execution order' : 'Review every table';
  document.getElementById('matchAll').hidden = !!plan.orderedScript; document.getElementById('tableReport').hidden = !!plan.orderedScript;
  document.getElementById('statementSection').hidden = !plan.orderedScript; document.getElementById('statementRows').replaceChildren();
  document.getElementById('previewTitle').textContent = plan.orderedScript ? 'Preview full converted script in original order' : 'Preview converted table definitions';
  document.getElementById('download').textContent = plan.orderedScript ? 'Download ordered review draft' : 'Download SQL package';
  document.getElementById('executionNote').textContent = plan.orderedScript ? 'This lifecycle script includes operations whose order matters. Download preserves all converted statements and marks source errors in place. Per-table actions and automatic execution are disabled; no target changes are made.' : 'Run selected plan writes to the inspected target database. DDL and previously committed tables can remain after failure. Prepared plans are retained for 30 days. Job history remains until deleted.';
  if (plan.orderedScript) {
    const statements = plan.statements || [], errors = statements.filter(s => ['ERROR','UNSUPPORTED'].includes(s.status)).length;
    document.getElementById('statementSummary').textContent = `${statements.length} statements · ${statements.length - errors} converted (including review warnings) · ${errors} errors / unsupported statements. No statements are silently omitted.`;
    for (const statement of statements) { const row = document.createElement('tr'); cell(row, statement.number); cell(row, statement.kind); cell(row, statement.status); cell(row, statement.messages.join(' · ') || 'Converted. See the full preview below.'); document.getElementById('statementRows').append(row); }
  }
  for (const [messages, kind] of [[plan.issues,'issue'],[plan.warnings,'warning']]) for (const text of messages) { const p = document.createElement('p'); p.className = 'message ' + kind; p.textContent = text; document.getElementById('reportMessages').append(p); }
  const maxRows = plan.options && plan.options.maxRows;
  if (maxRows && !plan.orderedScript) {
    const totalRows = plan.tables.reduce((sum,t) => sum + (t.rows||0), 0);
    const hasUnextracted = plan.tables.some(t => (t.rows||0)===0 && t.estimatedRows!=null);
    document.getElementById('quotaSummary').textContent = plan.prepared===false
      ? `Row budget: ${maxRows.toLocaleString()} total across this plan. Per-table counts are not yet extracted.`
      : `Row budget used: ${totalRows.toLocaleString()} / ${maxRows.toLocaleString()} (${((totalRows/maxRows)*100).toFixed(1)}%)${hasUnextracted?' — some tables show an estimate only':''}.`;
  } else document.getElementById('quotaSummary').textContent = '';
  const findingKindLabel = {MISSING_TARGET_COLUMN:'Missing column',EXTRA_TARGET_COLUMN:'Extra column',TYPE_MISMATCH:'Type mismatch',NULLABILITY_MISMATCH:'Nullability',IDENTITY_MISMATCH:'Identity',DEFAULT_MISMATCH:'Default value',KEY_OR_INDEX_MISMATCH:'Keys/indexes',ORACLE_IDENTITY_DML_BLOCKED:'Identity + DML only'};
  for (const table of plan.tables) {
    const tr = document.createElement('tr'); cell(tr,table.table); const status = cell(tr,''); status.append(statusBadge('table', table.status));
    const rowSummary=plan.prepared===false?(table.estimatedRows==null?'Not extracted; estimate unavailable':`~${table.estimatedRows.toLocaleString()} estimated (not extracted)`):`${table.rows.toLocaleString()} extracted`;
    const rowCell = cell(tr, rowSummary);
    if (maxRows && table.rows) { const share = document.createElement('div'); share.className='muted'; share.textContent = `${((table.rows/maxRows)*100).toFixed(2)}% of budget`; rowCell.append(share); }
    const findings = cell(tr, ''); findings.textContent = '';
    if (table.dependsOn && table.dependsOn.length) { const dep = document.createElement('div'); dep.className='muted'; dep.textContent = 'Depends on: ' + table.dependsOn.join(', '); findings.append(dep); }
    if (table.columnComparison && table.columnComparison.length) {
      const list = document.createElement('ul'); list.className='column-comparison';
      for (const d of table.columnComparison) {
        const item = document.createElement('li');
        const label = findingKindLabel[d.kind] || d.kind;
        const strong = document.createElement('strong'); strong.textContent = d.column ? `${d.column} — ${label}` : label; item.append(strong);
        if (d.expected!=null || d.actual!=null) item.append(document.createTextNode(` (expected ${d.expected!=null?d.expected:'—'}, target ${d.actual!=null?d.actual:'—'})`));
        item.append(document.createElement('br'));
        const correction = document.createElement('span'); correction.className='muted'; correction.textContent = d.correction; item.append(correction);
        list.append(item);
      }
      findings.append(list);
    } else if (table.differences.length || table.warnings.length) {
      const p = document.createElement('div'); p.textContent = [...table.differences,...table.warnings].join(' · '); findings.append(p);
    } else { const p = document.createElement('div'); p.textContent = table.status === 'MATCH' ? 'Definitions match. DML-only is available.' : 'Ready for selected action.'; findings.append(p); }
    const td = cell(tr,''); const select = document.createElement('select'); select.dataset.table = table.table;
    for(const action of table.allowedActions) { const option = document.createElement('option'); option.value = action; option.textContent = ({CREATE_AND_LOAD:'Create + load',DML_ONLY:'DML only',SKIP:'Skip table'})[action]; select.append(option); }
    select.value = table.status === 'MATCH' || table.status === 'MISMATCH' ? 'SKIP' : 'CREATE_AND_LOAD'; td.append(select); document.getElementById('reportRows').append(tr);
  }
  document.getElementById('execute').hidden = !plan.targetChecked || plan.orderedScript; document.getElementById('execute').disabled = plan.issues.length > 0 || plan.orderedScript || plan.prepared===false; document.getElementById('download').disabled = plan.prepared===false; document.getElementById('prepareData').hidden=plan.prepared!==false;
}
function actions() { const plan = SchemaBridgeState.migration.plan; return plan?.orderedScript ? {} : Object.fromEntries(Array.from(document.getElementById('reportRows').querySelectorAll('select')).map(select => [select.dataset.table,select.value])); }
document.getElementById('matchAll').addEventListener('click', () => document.getElementById('reportRows').querySelectorAll('select').forEach(select => { if (Array.from(select.options).some(o=>o.value==='DML_ONLY')) select.value='DML_ONLY'; }));
function clearDownloadLink(){const panel=document.getElementById('downloadHelp'),link=document.getElementById('downloadLink');panel.hidden=true;link.hidden=true;link.removeAttribute('href');document.getElementById('downloadStatus').textContent='';}
document.getElementById('reportRows').addEventListener('change',event=>{if(event.target.matches('select[data-table]'))clearDownloadLink();});
document.getElementById('matchAll').addEventListener('click',clearDownloadLink);
document.getElementById('reportDownload').addEventListener('click',()=>save(new Blob([JSON.stringify({report:SchemaBridgeState.migration.plan,actions:actions()},null,2)],{type:'application/json'}),'compatibility-report.json'));
document.getElementById('download').addEventListener('click',async()=>{try{
  const plan = SchemaBridgeState.migration.plan;
  document.getElementById('download').disabled=true;clearDownloadLink();document.getElementById('downloadStatus').textContent='Creating a secure, short-lived download link…';document.getElementById('downloadHelp').hidden=false;
  const ticket=await(await api('/plans/'+plan.id+'/download-ticket',actions())).json();
  const link=document.getElementById('downloadLink');link.href=ticket.url;link.textContent=plan.orderedScript?'Save review package':'Save migration ZIP';link.hidden=false;document.getElementById('downloadStatus').textContent='Download link ready. It expires in two minutes; click the link to save the ZIP.';
}catch(e){clearDownloadLink();error(e.message);}finally{document.getElementById('download').disabled=false;}});
document.getElementById('downloadLink').addEventListener('click',()=>{setTimeout(()=>{clearDownloadLink();document.getElementById('downloadStatus').textContent='Download requested. If it did not start, prepare a fresh link.';document.getElementById('downloadHelp').hidden=false;},0);});
document.getElementById('execute').addEventListener('click',async()=>{ try { document.getElementById('execute').disabled=true; SchemaBridgeState.migration.finalJob=await(await api('/plans/'+SchemaBridgeState.migration.plan.id+'/execute',{target:connection('target'),actions:actions()})).json(); document.getElementById('jobSection').hidden=false; renderJob(); pollJob(); } catch(e){error(e.message);document.getElementById('execute').disabled=false;} });
```

Note: the last handler (`execute`) calls `renderJob`/`pollJob`, which are defined in Task 7's `execution-page.js` — that file loads *after* `review-page.js`, which is fine for function *declarations* (hoisted, and in classic scripts all top-level function declarations from every evaluated/loaded file are available globally regardless of load order for calls that happen later, at event-handler-fire time, not at script-parse time) as long as `execution-page.js` is loaded before this handler can actually fire (i.e., before the user clicks Execute) — it is, per the dependency order list.

- [ ] **Step 4: Add script tag to `index.html`**, remove the ported code from `app.js`.

- [ ] **Step 5: Run test to verify it passes**

Run: `node scripts/test-review-page-ui.mjs`
Expected: PASS.

- [ ] **Step 6: Run the other harnesses**

Run: `node scripts/test-connection-ui.mjs && node scripts/test-bulk-ui.mjs && node scripts/test-catalog-ui.mjs && node scripts/test-table-settings-ui.mjs`
Expected: all PASS (add `'review-page'` to each one's `JS_FILES` first).

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/static/js/review-page.js scripts/test-review-page-ui.mjs src/main/resources/static/index.html src/main/resources/static/app.js scripts/test-connection-ui.mjs scripts/test-bulk-ui.mjs scripts/test-catalog-ui.mjs scripts/test-table-settings-ui.mjs
git commit -m "Extract review-page module, add dedicated test, use shared statusBadge"
```

---

### Task 7: Execution page + revert panel

**Files:**
- Create: `src/main/resources/static/js/execution-page.js`
- Create: `src/main/resources/static/js/revert-panel.js`
- Modify: `index.html`, `app.js`, all `scripts/test-*-ui.mjs` `JS_FILES` arrays (add `'execution-page','revert-panel'`)

**Interfaces:**
- Consumes: `api`, `error`, `save`, `renderProgress`, `statusBadge`, `SchemaBridgeState.migration`.
- Produces: `renderJob(): void`, `pollJob(): void`, `renderRevert(): void`, `revertBusy(job)`, `jobBusy(job)`, `clearRevertReview()`.

- [ ] **Step 1: Write `js/revert-panel.js`** (ported verbatim from current `app.js` lines 8-41, state-prefixed)

```js
function revertBusy(job){return !!job?.revert && ['QUEUED','RUNNING'].includes(job.revert.state);}
function jobBusy(job){return ['QUEUED','RUNNING'].includes(job.state)||revertBusy(job);}
function clearRevertReview(){SchemaBridgeState.migration.pendingRevert=null;document.getElementById('revertReview').hidden=true;document.getElementById('acknowledgeRevert').checked=false;document.getElementById('confirmRevert').disabled=true;}
function renderRevert(){
  const finalJob = SchemaBridgeState.migration.finalJob;
  const r=finalJob?.revert;
  document.getElementById('revertSection').hidden=!finalJob||(finalJob.state!=='SUCCEEDED'&&!r);
  if(SchemaBridgeState.migration.pendingRevert&&(SchemaBridgeState.migration.pendingRevert.jobId!==finalJob?.id||revertBusy(finalJob)))clearRevertReview();
  const canReview=finalJob?.state==='SUCCEEDED'&&(!r||['FAILED','PARTIAL'].includes(r.state));
  document.getElementById('reviewRevert').hidden=!canReview;document.getElementById('reviewRevert').disabled=false;document.getElementById('cancelRevert').hidden=!revertBusy(finalJob);
  document.getElementById('revertState').textContent=r?`${r.state}: ${r.message} ${r.progress.rowsCommitted.toLocaleString()} rows removed by committed revert work.`:'Select Review revert to check this job against the target before removing anything.';
  document.getElementById('revertResults').replaceChildren();
  for(const table of r?.tables||[]){const item=document.createElement('li');item.textContent=`${table.table}: ${table.status} · ${table.rows.toLocaleString()} original rows · ${table.action==='DROP_TABLE'?'drop migration-created table':'remove transferred rows'}`;document.getElementById('revertResults').append(item);}
}
document.getElementById('reviewRevert').addEventListener('click',async()=>{
  const id=SchemaBridgeState.migration.finalJob.id;clearRevertReview();document.getElementById('reviewRevert').disabled=true;document.getElementById('revertState').textContent='Checking target data and dependencies…';
  try{
    const review=await(await api('/jobs/'+id+'/revert-preview',connection('target'))).json();
    if(SchemaBridgeState.migration.finalJob?.id!==id)return;SchemaBridgeState.migration.pendingRevert=review;
    document.getElementById('revertTarget').textContent=`Job ${review.jobId} · Target: ${review.target}`;
    document.getElementById('revertExpiry').textContent='Review expires at '+new Date(review.expiresAt).toLocaleTimeString()+'. The target is checked again when you confirm.';
    document.getElementById('revertTables').replaceChildren();
    for(const table of review.tables){const item=document.createElement('li');item.textContent=`${table.table}: ${table.action==='DROP_TABLE'?'remove transferred rows and drop table':'remove only transferred rows'} · ${table.rows.toLocaleString()} original rows · ${table.status}`;document.getElementById('revertTables').append(item);}
    document.getElementById('revertReview').hidden=false;document.getElementById('revertState').textContent='Review the removal below. Nothing has been removed.';document.getElementById('revertReview').scrollIntoView({behavior:'smooth',block:'center'});
  }catch(e){document.getElementById('revertState').textContent=e.message;error(e.message);}finally{document.getElementById('reviewRevert').disabled=false;}
});
document.getElementById('acknowledgeRevert').addEventListener('change',()=>{document.getElementById('confirmRevert').disabled=!SchemaBridgeState.migration.pendingRevert||!document.getElementById('acknowledgeRevert').checked;});
document.getElementById('dismissRevert').addEventListener('click',()=>{clearRevertReview();renderRevert();});
document.getElementById('confirmRevert').addEventListener('click',async()=>{
  const m = SchemaBridgeState.migration;
  if(!m.pendingRevert||!document.getElementById('acknowledgeRevert').checked||m.pendingRevert.jobId!==m.finalJob.id)return;
  const review=m.pendingRevert;document.getElementById('confirmRevert').disabled=true;
  try{m.finalJob=await(await api('/jobs/'+review.jobId+'/revert',{target:connection('target'),token:review.token})).json();clearRevertReview();renderJob();pollJob();}
  catch(e){clearRevertReview();document.getElementById('revertState').textContent=e.message;error(e.message);}
});
document.getElementById('cancelRevert').addEventListener('click',async()=>{try{SchemaBridgeState.migration.finalJob=await(await api('/jobs/'+SchemaBridgeState.migration.finalJob.id+'/cancel',{})).json();renderJob();}catch(e){error(e.message);}});
```

- [ ] **Step 2: Write `js/execution-page.js`** (ported verbatim from current `app.js` lines 280-315, 301-315, state-prefixed; `renderJob` calls `statusBadge` is **not** introduced here — the job rows table stays textual per the original except validation summary, matching today exactly, since the per-table execution list intentionally stays close to the "Execution details" monospace framing agreed in the spec rather than becoming a decorated badge table)

```js
const validationMethodLabel={FINGERPRINT:'Fingerprint (count + SHA-256)',ROW_COUNT:'Row count only',NONE:'No validation'};
function renderJob(){
  const finalJob = SchemaBridgeState.migration.finalJob;
  renderProgress('job',finalJob.progress);document.getElementById('cancelJob').hidden=!['RUNNING','QUEUED'].includes(finalJob.state);document.getElementById('resumeJob').hidden=!finalJob.resumable; document.getElementById('jobState').textContent=finalJob.state+' — '+finalJob.message; document.getElementById('jobRows').replaceChildren();
  for(const table of finalJob.tables){
    const tr=document.createElement('tr');[table.table,table.action,table.status,table.rows].forEach(value=>cell(tr,value));
    const details=cell(tr,'');
    const v=table.validation;
    if(v){
      const summary=document.createElement('div');
      const scope=v.method==='NONE'?(v.skippedReason||''):`${validationMethodLabel[v.method]||v.method} · ${v.rowsChecked.toLocaleString()} rows checked · ${v.durationSeconds.toFixed(2)}s`;
      summary.textContent=scope; details.append(summary);
    }
    const message=document.createElement('div'); message.className='muted'; message.textContent=table.message; details.append(message);
    document.getElementById('jobRows').append(tr);
  }
  renderRevert();
}
async function pollJob(){
  const m = SchemaBridgeState.migration;
  clearTimeout(m.polling);
  try{m.finalJob=await(await api('/jobs/'+m.finalJob.id,undefined,'GET')).json();renderJob();if(jobBusy(m.finalJob))m.polling=setTimeout(pollJob,1500);else refreshHistory();}catch(e){error(e.message);m.polling=setTimeout(pollJob,5000);}
}
document.getElementById('jobDownload').addEventListener('click',()=>save(new Blob([JSON.stringify(SchemaBridgeState.migration.finalJob,null,2)],{type:'application/json'}),'execution-report.json'));
document.getElementById('prepareData').addEventListener('click',async()=>{
  const m = SchemaBridgeState.migration;
  if(!m.lastRequest)return;try{
  const version=m.requestVersion;const previousPlan=m.plan.id;const request={...m.lastRequest,metadataOnly:false};
  const started=await(await api('/preparations',request)).json();m.preparationId=started.id;document.getElementById('preparationSection').hidden=false;document.getElementById('cancelPreparation').hidden=false;document.getElementById('prepareData').disabled=true;
  async function poll(){try{const current=await(await api('/preparations/'+started.id,undefined,'GET')).json();document.getElementById('preparationState').textContent=current.message;renderProgress('preparation',current.progress);
    if(['QUEUED','PREPARING'].includes(current.state)){m.preparationPoll=setTimeout(poll,1000);return;}
    document.getElementById('cancelPreparation').hidden=true;document.getElementById('prepareData').disabled=false;if(current.state==='READY'&&version===m.requestVersion){m.plan=current.plan;renderPlan();document.getElementById('preview').textContent=await(await api('/plans/'+m.plan.id+'/preview',undefined,'GET')).text();await api('/plans/'+previousPlan,undefined,'DELETE');}
    if(current.state==='READY'&&version!==m.requestVersion)await api('/plans/'+current.plan.id,undefined,'DELETE');
    if(current.state==='FAILED')error(current.message);
  }catch(e){document.getElementById('prepareData').disabled=false;error(e.message);}}
  await poll();
}catch(e){error(e.message);document.getElementById('prepareData').disabled=false;}});
document.getElementById('cancelPreparation').addEventListener('click',async()=>{try{const id=SchemaBridgeState.migration.preparationId;if(id)await api('/preparations/'+id+'/cancel',{});}catch(e){error(e.message);}});
document.getElementById('cancelJob').addEventListener('click',async()=>{try{SchemaBridgeState.migration.finalJob=await(await api('/jobs/'+SchemaBridgeState.migration.finalJob.id+'/cancel',{})).json();renderJob();}catch(e){error(e.message);}});
document.getElementById('resumeJob').addEventListener('click',async()=>{try{SchemaBridgeState.migration.finalJob=await(await api('/jobs/'+SchemaBridgeState.migration.finalJob.id+'/resume',connection('target'))).json();renderJob();pollJob();}catch(e){error(e.message);}});
```

Note: `refreshHistory` (called from `pollJob`'s success path) is defined in Task 8's `history-page.js`, loaded after this file — same hoisting/load-order reasoning as Task 6's note applies.

- [ ] **Step 3: Add both script tags to `index.html`**, remove the ported code from `app.js`.

- [ ] **Step 4: Add `'execution-page','revert-panel'` to every harness's `JS_FILES`, and to `test-review-page-ui.mjs`'s list (it now needs them too, since `review-page.js`'s `execute` button handler calls `renderJob`)**

- [ ] **Step 5: Run every harness**

Run: `node scripts/test-connection-ui.mjs && node scripts/test-bulk-ui.mjs && node scripts/test-catalog-ui.mjs && node scripts/test-table-settings-ui.mjs && node scripts/test-review-page-ui.mjs`
Expected: all PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/static/js/execution-page.js src/main/resources/static/js/revert-panel.js src/main/resources/static/index.html src/main/resources/static/app.js scripts/test-*-ui.mjs
git commit -m "Extract execution-page and revert-panel modules"
```

---

### Task 8: History page with the unfinished-work guard

**Files:**
- Create: `src/main/resources/static/js/history-page.js`
- Create: `scripts/test-history-guard-ui.mjs` (new)
- Modify: `index.html`, `app.js`

**Interfaces:**
- Consumes: `api`, `error`, `statusBadge`, `jobBusy`, `renderJob`, `pollJob`, `renderPlan`, `SchemaBridgeState.migration`.
- Produces: `jobHistoryCard(job): HTMLElement`, `refreshHistory(): Promise<void>`, `hasUnfinishedMigrationWork(): boolean`, `openHistoryJob(job): Promise<void>`.

This is the task that implements **Review Focus item 2**: the guard must fire not only when a plan exists with no finished job, but also when the current form inputs no longer match `lastRequest` (an analyzed-but-now-stale configuration).

- [ ] **Step 1: Write the failing test**

Create `scripts/test-history-guard-ui.mjs`:
```js
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {createRequire} from 'node:module';
const require=createRequire(new URL('../target/ui-test/package.json',import.meta.url));
const {JSDOM}=require('jsdom');
const html=await readFile(new URL('../src/main/resources/static/index.html',import.meta.url),'utf8');
const dom=new JSDOM(html,{url:'http://localhost:8098',runScripts:'outside-only'});
const {window}=dom;
const JS_FILES=['format','api','toast','state','status-badge','progress-panel','connection-form','datasource-catalog','flow-and-upload','migration-stepper','table-picker','review-page','execution-page','revert-panel','history-page'];
for (const file of JS_FILES) window.eval(await readFile(new URL(`../src/main/resources/static/js/${file}.js`,import.meta.url),'utf8'));

// Case 1: fresh session, nothing configured yet -> no unfinished work.
assert.equal(window.hasUnfinishedMigrationWork(), false, 'a fresh session has no unfinished work');

// Case 2: plan exists but no job executed yet -> unfinished work.
window.SchemaBridgeState.migration.plan = {id:'p1'};
window.SchemaBridgeState.migration.finalJob = null;
assert.equal(window.hasUnfinishedMigrationWork(), true, 'an analyzed plan with no job is unfinished work');

// Case 3: a job finished, but the form changed afterward (lastRequest no longer matches a fresh invalidate()) -> still unfinished.
window.SchemaBridgeState.migration.finalJob = {id:'j1', state:'SUCCEEDED'};
window.SchemaBridgeState.migration.lastRequest = {sourceDialect:'ORACLE'};
window.invalidate(); // simulates an input change firing after the job completed
assert.equal(window.hasUnfinishedMigrationWork(), true, 'a finished job whose configuration later changed must still count as unfinished work (Review Focus #2)');

// Case 4: a job already finished (planId 'old-plan'), then the user re-analyzed into a brand new
// plan without executing it -> still unfinished, even though a (stale) finalJob is present.
window.SchemaBridgeState.migration.finalJob = {id:'j1', state:'SUCCEEDED', planId:'old-plan'};
window.SchemaBridgeState.migration.plan = {id:'new-plan'};
assert.equal(window.hasUnfinishedMigrationWork(), true, 're-analyzing after an older finished job must still count as unfinished work, not be masked by the stale finalJob');

// Case 4b: the finalJob actually corresponds to the current plan (just executed it) -> no guard needed.
window.SchemaBridgeState.migration.plan = {id:'old-plan'};
assert.equal(window.hasUnfinishedMigrationWork(), false, 'a plan that matches its own just-executed job is not unfinished work');

// Case 5: everything is clean and matches -> opening history proceeds without a guard prompt.
window.SchemaBridgeState.migration.plan = null;
window.SchemaBridgeState.migration.finalJob = null;
window.SchemaBridgeState.migration.lastRequest = null;
assert.equal(window.hasUnfinishedMigrationWork(), false);
console.log('history-guard-ui: all assertions passed');
```

- [ ] **Step 2: Run test to verify it fails**

Run: `node scripts/test-history-guard-ui.mjs`
Expected: FAIL — `window.hasUnfinishedMigrationWork is not a function`.

- [ ] **Step 3: Write `js/history-page.js`**, porting `app.js` lines 316-348 (`jobStateLabel`, `jobHistoryCard`, `refreshHistory`) with the job-state badge now via `statusBadge`, plus the new guard functions:

```js
function jobStateLabel(state){return ({QUEUED:'Queued',RUNNING:'Running',SUCCEEDED:'Completed',FAILED:'Failed',CANCELLED:'Cancelled',RECOVERY_REQUIRED:'Needs reconciliation'})[state]||state;}
function hasUnfinishedMigrationWork(){
  const m = SchemaBridgeState.migration;
  // A plan exists that isn't the one the current finalJob was executed from — covers both
  // "analyzed but never executed" (finalJob null) and "re-analyzed after an older job already
  // finished" (finalJob stale, still referencing a previous planId).
  if (m.plan && m.plan.id !== m.finalJob?.planId) return true;
  if (m.finalJob && m.lastRequest === null && m.plan === null && m.requestVersion > 0) return true; // invalidate() ran after a finished job, with no re-analysis yet
  return false;
}
function jobHistoryCard(job){
  const card=document.createElement('article');card.className='history-card';card.setAttribute('role','listitem');
  const heading=document.createElement('div');heading.className='history-card-heading';
  const displayState=job.revert?.state||job.state;
  heading.append(statusBadge('job', displayState));
  const id=document.createElement('code');id.textContent=job.id.slice(0,8);heading.append(id);card.append(heading);
  const summary=document.createElement('p');const committed=Number(job.progress?.rowsCommitted||0);summary.className='history-summary';summary.textContent=`${job.tables.length} table${job.tables.length===1?'':'s'} · ${committed.toLocaleString()} ${job.state==='RECOVERY_REQUIRED'?'rows recorded committed':'rows committed'}`;card.append(summary);
  const detail=document.createElement('p');detail.className='history-detail';detail.textContent=job.revert?.message||job.message;card.append(detail);
  const actions=document.createElement('div');actions.className='history-actions';
  const view=document.createElement('button');view.className='secondary';view.textContent='View report / revert';view.addEventListener('click',()=>openHistoryJob(job));actions.append(view);
  if(!jobBusy(job)){
    const uncertain=job.state==='RECOVERY_REQUIRED'||['PARTIAL','RECOVERY_REQUIRED'].includes(job.revert?.state);
    const remove=document.createElement('button');remove.className=uncertain?'secondary danger':'secondary';remove.textContent=uncertain?'Review and discard evidence':'Delete saved files';
    remove.addEventListener('click',async()=>{
      const prompt=uncertain
        ?'This job\'s commit outcome is uncertain — these files are the evidence needed to reconcile the target (see RECOVERY-RUNBOOK.md). Only discard them after you have manually verified and reconciled the target database. Discard anyway?'
        :'Delete this saved migration and its exported data? Target database rows will remain.';
      if(!window.confirm(prompt))return;
      remove.disabled=true;
      const m = SchemaBridgeState.migration;
      try{await api('/plans/'+job.planId+(uncertain?'?acknowledgeUncertain=true':''),undefined,'DELETE');if(m.finalJob?.id===job.id){clearTimeout(m.polling);document.getElementById('jobSection').hidden=true;document.getElementById('revertSection').hidden=true;clearRevertReview();}if(m.plan?.id===job.planId)invalidate();await refreshHistory();}catch(e){remove.disabled=false;error(e.message);}
    });
    actions.append(remove);
  }
  card.append(actions);return card;
}
async function openHistoryJob(job){
  if (hasUnfinishedMigrationWork() && !window.confirm('Opening this saved migration will replace your current unsaved configuration. Continue, or keep editing?')) return;
  const m = SchemaBridgeState.migration;
  try{
    clearTimeout(m.polling);clearRevertReview();m.finalJob=await(await api('/jobs/'+job.id,undefined,'GET')).json();document.getElementById('jobSection').hidden=false;renderJob();m.plan=await(await api('/plans/'+job.planId,undefined,'GET')).json();renderPlan();document.getElementById('execute').disabled=true;document.getElementById('preview').textContent=await(await api('/plans/'+job.planId+'/preview',undefined,'GET')).text();if(jobBusy(m.finalJob))pollJob();m.maxStep=4;goToStep(4);
    if (typeof navigateToPage === 'function') navigateToPage('migration');
  }catch(e){error(e.message);}
}
async function refreshHistory(){
  const button=document.getElementById('refreshHistory'),status=document.getElementById('jobHistoryStatus');button.disabled=true;status.textContent='Loading saved migrations…';
  try{const jobs=await(await api('/jobs',undefined,'GET')).json();const list=document.getElementById('jobHistory');list.replaceChildren();
    if(!jobs.length){list.textContent='No saved migrations.';status.textContent='History is up to date.';return;}
    for(const job of jobs)list.append(jobHistoryCard(job));status.textContent=`${jobs.length} saved migration${jobs.length===1?'':'s'} loaded.`;
  }catch(e){status.textContent='Could not refresh migration history.';error(e.message);}
  finally{button.disabled=false;}
}
document.getElementById('refreshHistory').addEventListener('click',refreshHistory);
```

`navigateToPage` is defined in Task 11's `shell.js`; the `typeof` guard keeps this file loadable (and testable in isolation, as in Step 1's test) before `shell.js` exists.

- [ ] **Step 4: Add script tag to `index.html`**, remove the ported code from `app.js`.

- [ ] **Step 5: Run test to verify it passes**

Run: `node scripts/test-history-guard-ui.mjs`
Expected: PASS.

- [ ] **Step 6: Add `'history-page'` to every other harness's `JS_FILES` and run all of them**

Run: `node scripts/test-connection-ui.mjs && node scripts/test-bulk-ui.mjs && node scripts/test-catalog-ui.mjs && node scripts/test-table-settings-ui.mjs && node scripts/test-review-page-ui.mjs`
Expected: all PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/static/js/history-page.js scripts/test-history-guard-ui.mjs src/main/resources/static/index.html src/main/resources/static/app.js scripts/test-*-ui.mjs
git commit -m "Extract history-page module with unfinished-work guard before replacing active configuration"
```

---

### Task 9: Storage page + Settings page

**Files:**
- Create: `src/main/resources/static/js/storage-page.js`
- Create: `src/main/resources/static/js/settings-page.js`
- Modify: `index.html` (Settings page copy clarified per spec §5), `app.js`, all harnesses' `JS_FILES`

**Interfaces:**
- Consumes: `api`, `error`, `formatBytes`, `save`.
- Produces: `refreshStorage(): Promise<void>`, `settingsProfile(): object`.

- [ ] **Step 1: Write `js/storage-page.js`** (ported verbatim from current `app.js` lines 350-364)

```js
async function refreshStorage(){
  const button=document.getElementById('refreshStorage');button.disabled=true;
  try{
    const s=await(await api('/storage',undefined,'GET')).json();
    const metrics=document.getElementById('storageMetrics');metrics.replaceChildren();
    for(const [label,value] of [['Staged data',formatBytes(s.stagedBytes)],['Free disk',formatBytes(s.freeDiskBytes)],['Reclaimable on next cleanup',formatBytes(s.reclaimableBytes)],['Saved plans',String(s.plans.length)]]){
      const div=document.createElement('div');const dt=document.createElement('dt');dt.textContent=label;const dd=document.createElement('dd');dd.textContent=value;div.append(dt,dd);metrics.append(div);
    }
    const orphans=document.getElementById('storageOrphans');
    if(s.orphanedDirectories.length){orphans.hidden=false;orphans.textContent=`${s.orphanedDirectories.length} orphaned work-directory folder${s.orphanedDirectories.length===1?'':'s'} found with no matching saved plan (left over from an interrupted run). Review and remove manually if no longer needed.`;}
    else orphans.hidden=true;
  }catch(e){error(e.message);}
  finally{button.disabled=false;}
}
document.getElementById('refreshStorage').addEventListener('click',refreshStorage);
```

- [ ] **Step 2: Write the failing test for the settings-import dialect-switch guard (Review Focus item 3)**

Create `scripts/test-settings-import-ui.mjs`:
```js
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {createRequire} from 'node:module';
const require=createRequire(new URL('../target/ui-test/package.json',import.meta.url));
const {JSDOM}=require('jsdom');
const html=await readFile(new URL('../src/main/resources/static/index.html',import.meta.url),'utf8');
const dom=new JSDOM(html,{url:'http://localhost:8098',runScripts:'outside-only'});
const {window}=dom;
window.fetch = async () => ({ok:true,status:200,json:async()=>[]});
const JS_FILES=['format','api','toast','state','status-badge','progress-panel','connection-form','datasource-catalog','flow-and-upload','migration-stepper','table-picker','review-page','execution-page','revert-panel','history-page','storage-page','settings-page'];
for (const file of JS_FILES) window.eval(await readFile(new URL(`../src/main/resources/static/js/${file}.js`,import.meta.url),'utf8'));

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
```

- [ ] **Step 3: Run test to verify it fails**

Run: `node scripts/test-settings-import-ui.mjs`
Expected: FAIL — `selectedSourceTables().length` is still 1 (the naive one-line `document.getElementById('sourceDialect').value=p.sourceDialect` assignment doesn't touch `sourceTables` or `columnOptions` at all).

- [ ] **Step 4: Write `js/settings-page.js`** (ported from current `app.js` lines 365-390, with the dialect-switch guard fix built in from the start — not a bolt-on)

```js
function settingsProfile(){
  const value=id=>document.getElementById(id)?document.getElementById(id).value:undefined;
  return {version:1,sourceDialect:value('sourceDialect'),targetDialect:value('targetDialect'),targetSchema:value('targetSchema'),
    source:{host:value('sourceHost'),port:value('sourcePort'),database:value('sourceDatabase'),username:value('sourceUsername')},
    target:{host:value('targetHost'),port:value('targetPort'),database:value('targetDatabase'),username:value('targetUsername')},
    options:{maxRows:value('maxRows'),maxTableGb:value('maxTableGb'),diskReserveMb:value('diskReserveMb'),fetchSize:value('fetchSize'),batchRows:value('batchRows'),batchMb:value('batchMb'),queryTimeout:value('queryTimeout'),readTimeout:value('readTimeout'),validateData:document.getElementById('validateData')?.checked,useCopy:document.getElementById('useCopy')?.checked}};
}
document.getElementById('exportSettings').addEventListener('click',()=>{save(new Blob([JSON.stringify(settingsProfile(),null,2)],{type:'application/json'}),'schemabridge-settings.json');});
document.getElementById('importSettings').addEventListener('change',async()=>{
  const file=document.getElementById('importSettings').files[0];if(!file)return;
  try{
    const p=JSON.parse(await file.text());
    if(p.sourceDialect&&document.getElementById('sourceDialect')&&document.getElementById('sourceDialect').value!==p.sourceDialect){document.getElementById('sourceDialect').value=p.sourceDialect;document.getElementById('columnOptions').replaceChildren();fillOptions(document.getElementById('sourceTables'),[]);applyDialect('source');if(document.getElementById('tablePickerList'))renderTablePicker();}
    if(p.targetDialect&&document.getElementById('targetDialect'))document.getElementById('targetDialect').value=p.targetDialect;
    if(p.targetSchema!=null&&document.getElementById('targetSchema'))document.getElementById('targetSchema').value=p.targetSchema;
    for(const prefix of ['source','target']){const side=p[prefix];if(!side)continue;
      for(const field of ['Host','Port','Database','Username'])if(side[field.toLowerCase()]!=null&&document.getElementById(prefix+field))document.getElementById(prefix+field).value=side[field.toLowerCase()];
    }
    if(p.options)for(const [key,id] of [['maxRows','maxRows'],['maxTableGb','maxTableGb'],['diskReserveMb','diskReserveMb'],['fetchSize','fetchSize'],['batchRows','batchRows'],['batchMb','batchMb'],['queryTimeout','queryTimeout'],['readTimeout','readTimeout']])if(p.options[key]!=null&&document.getElementById(id))document.getElementById(id).value=p.options[key];
    if(p.options&&document.getElementById('validateData'))document.getElementById('validateData').checked=!!p.options.validateData;
    if(p.options&&document.getElementById('useCopy'))document.getElementById('useCopy').checked=!!p.options.useCopy;
    invalidate();
    document.getElementById('configStatus').textContent='Settings imported. Passwords were not included — enter them before connecting.';
  }catch(e){error('Could not import settings: '+e.message);}
  finally{document.getElementById('importSettings').value='';}
});
```

This mirrors the existing per-dialect reset already used by the `sourceDialect` `change` handler (Task 12's `main.js`), so an imported dialect change can't leave a stale table selection from the previous dialect/schema behind.

- [ ] **Step 5: Run test to verify it passes**

Run: `node scripts/test-settings-import-ui.mjs`
Expected: PASS.

- [ ] **Step 6: Update Settings page copy in `index.html`** (spec §5 requirement — the existing "Reusable configuration" card, line 78 of today's `index.html`) to be explicit about scope:

Replace:
```html
<section class="card"><div class="section-title"><h2>Reusable configuration</h2></div><p class="muted">Save dialects, connection host/port/database/username, target schema and transfer settings as a JSON profile — never passwords. Re-enter passwords after importing.</p>...
```
with:
```html
<section class="card"><div class="section-title"><h2>Export / import settings</h2></div><p class="muted">Download the current dialects, connection host/port/database/username, target schema and transfer settings as a local JSON file — never passwords. Nothing is stored on the server; this is not a saved-connections manager. Re-enter passwords after importing.</p>...
```

- [ ] **Step 7: Add both script tags to `index.html`**, remove the ported code from `app.js`.

- [ ] **Step 8: Add `'storage-page','settings-page'` to every harness's `JS_FILES` (including `test-settings-import-ui.mjs` itself, which already lists them) and run all of them**

Run: `node scripts/test-connection-ui.mjs && node scripts/test-bulk-ui.mjs && node scripts/test-catalog-ui.mjs && node scripts/test-table-settings-ui.mjs && node scripts/test-review-page-ui.mjs && node scripts/test-history-guard-ui.mjs && node scripts/test-settings-import-ui.mjs`
Expected: all PASS.

- [ ] **Step 9: Commit**

```bash
git add src/main/resources/static/js/storage-page.js src/main/resources/static/js/settings-page.js scripts/test-settings-import-ui.mjs src/main/resources/static/index.html src/main/resources/static/app.js scripts/test-*-ui.mjs
git commit -m "Extract storage-page and settings-page modules, clarify settings scope copy, fix import dialect-switch guard"
```

---

### Task 10: Dashboard page (new)

**Files:**
- Create: `src/main/resources/static/js/dashboard-page.js`
- Create: `scripts/test-dashboard-ui.mjs` (new)
- Modify: `index.html` (add the Dashboard page container — markup only, no removal from `app.js` since this is new code)

**Interfaces:**
- Consumes: `api`, `error`, `statusBadge`, `formatBytes`.
- Produces: `renderDashboard(): Promise<void>`.

- [ ] **Step 1: Write the failing test**

Create `scripts/test-dashboard-ui.mjs`:
```js
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {createRequire} from 'node:module';
const require=createRequire(new URL('../target/ui-test/package.json',import.meta.url));
const {JSDOM}=require('jsdom');
const html=await readFile(new URL('../src/main/resources/static/index.html',import.meta.url),'utf8');
const dom=new JSDOM(html,{url:'http://localhost:8098',runScripts:'outside-only'});
const {window}=dom;
const JS_FILES=['format','api','toast','state','status-badge','progress-panel','connection-form','datasource-catalog','flow-and-upload','migration-stepper','table-picker','review-page','execution-page','revert-panel','history-page','storage-page','settings-page','dashboard-page'];
window.fetch = async (path) => ({
  ok:true, status:200,
  json: async () => path.endsWith('/jobs') ? [
    {id:'aaaa1111', state:'SUCCEEDED', message:'Completed.', tables:[{table:'customers'}], progress:{rowsCommitted:12480}},
    {id:'bbbb2222', state:'RUNNING', message:'Loading orders.', tables:[{table:'orders'}], progress:{rowsCommitted:3102}}
  ] : path.endsWith('/storage') ? {stagedBytes:128000000, freeDiskBytes:212000000000, reclaimableBytes:32000, plans:[1,2,3], orphanedDirectories:[]} : {}
});
for (const file of JS_FILES) window.eval(await readFile(new URL(`../src/main/resources/static/js/${file}.js`,import.meta.url),'utf8'));

await window.renderDashboard();
const activityRows = window.document.querySelectorAll('#dashboardActivity tr');
assert.equal(activityRows.length, 2, 'dashboard shows recent activity rows from GET /jobs, not invented data');
assert.ok(window.document.getElementById('dashboardActivity').textContent.includes('12,480'), 'real committed-row counts must render');
assert.ok(window.document.getElementById('dashboardStorage').textContent.includes('128'), 'real storage figures must render (no fabricated metrics)');
const quickStart = window.document.querySelectorAll('#dashboardQuickStart [data-flow]');
assert.equal(quickStart.length, 4, 'exactly the 4 real flow types, no invented quick actions');
console.log('dashboard-ui: all assertions passed');
```

- [ ] **Step 2: Run test to verify it fails**

Run: `node scripts/test-dashboard-ui.mjs`
Expected: FAIL — `window.renderDashboard is not a function`, and `#dashboardActivity`/`#dashboardQuickStart`/`#dashboardStorage` don't exist in `index.html` yet.

- [ ] **Step 3: Add the Dashboard page container to `index.html`**, inside `<main>`, before the existing `<form id="configuration">` (this becomes the Migration page's content in Task 12 — for now it just needs to exist so the Dashboard markup has a sibling to toggle against; full shell wiring is Task 11):

```html
<section id="dashboardPage" class="app-page">
  <div class="section-heading"><h2>Dashboard</h2><p class="muted">Database Migration Workspace</p></div>
  <div id="dashboardQuickStart" class="dashboard-quick"></div>
  <section class="card"><div class="section-title"><h2>Recent Migration Activity</h2><button id="dashboardRefresh" type="button" class="secondary">Refresh</button></div>
    <div class="table-scroll"><table><thead><tr><th>Source</th><th>Target</th><th>Status</th><th>Rows</th></tr></thead><tbody id="dashboardActivity"></tbody></table></div>
  </section>
  <section class="card"><h2>Storage</h2><dl class="progress-metrics" id="dashboardStorage"></dl></section>
</section>
```

- [ ] **Step 4: Write `js/dashboard-page.js`**, using only data already available from `GET /jobs` and `GET /storage` — no invented fields (per the spec's explicit "no invented metrics" rule, and matching the mockup approved during brainstorming):

```js
const FLOW_QUICK_START = [
  {value:'files-download', label:'SQL → Download', hint:'Convert scripts into a review package.'},
  {value:'files-db', label:'SQL → Database', hint:'Convert scripts and run a selected plan.'},
  {value:'db-download', label:'Database → Download', hint:'Export selected tables and their data.'},
  {value:'db-db', label:'Database → Database', hint:'Move selected tables to another database.'}
];
function renderQuickStart(){
  const host = document.getElementById('dashboardQuickStart'); host.replaceChildren();
  for (const item of FLOW_QUICK_START) {
    const card = document.createElement('button'); card.type = 'button'; card.className = 'dashboard-qcard'; card.dataset.flow = item.value;
    const title = document.createElement('b'); title.textContent = item.label; const hint = document.createElement('span'); hint.textContent = item.hint;
    card.append(title, hint);
    card.addEventListener('click', () => {
      const radio = document.querySelector(`input[name="flow"][value="${item.value}"]`);
      if (radio) { radio.checked = true; radio.dispatchEvent(new Event('change', {bubbles:true})); }
      if (typeof navigateToPage === 'function') navigateToPage('migration');
    });
    host.append(card);
  }
}
async function renderDashboard(){
  renderQuickStart();
  try {
    const jobs = await (await api('/jobs', undefined, 'GET')).json();
    const body = document.getElementById('dashboardActivity'); body.replaceChildren();
    for (const job of jobs.slice(0, 8)) {
      const tr = document.createElement('tr');
      const sourceCell = document.createElement('td'); sourceCell.textContent = job.tables.map(t=>t.table).slice(0,2).join(', ') || '—';
      const targetCell = document.createElement('td'); targetCell.textContent = job.id.slice(0,8);
      const statusCell = document.createElement('td'); statusCell.append(statusBadge('job', job.revert?.state || job.state));
      const rowsCell = document.createElement('td'); rowsCell.textContent = Number(job.progress?.rowsCommitted || 0).toLocaleString();
      tr.append(sourceCell, targetCell, statusCell, rowsCell); body.append(tr);
    }
    if (!jobs.length) { body.replaceChildren(); const tr = document.createElement('tr'); const td = document.createElement('td'); td.colSpan = 4; td.className = 'muted'; td.textContent = 'No saved migrations yet.'; tr.append(td); body.append(tr); }
  } catch (e) { error(e.message); }
  try {
    const s = await (await api('/storage', undefined, 'GET')).json();
    const metrics = document.getElementById('dashboardStorage'); metrics.replaceChildren();
    for (const [label, value] of [['Staged data', formatBytes(s.stagedBytes)], ['Free disk', formatBytes(s.freeDiskBytes)], ['Reclaimable', formatBytes(s.reclaimableBytes)], ['Saved plans', String(s.plans.length)]]) {
      const div = document.createElement('div'); const dt = document.createElement('dt'); dt.textContent = label; const dd = document.createElement('dd'); dd.textContent = value; div.append(dt, dd); metrics.append(div);
    }
  } catch (e) { error(e.message); }
}
document.getElementById('dashboardRefresh').addEventListener('click', renderDashboard);
```

`navigateToPage` (Task 11) is referenced defensively via `typeof` guard, matching Task 8's pattern.

- [ ] **Step 5: Add the script tag to `index.html`**

```html
<script src="/js/dashboard-page.js"></script>
```
(placed after `settings-page.js`, before `shell.js`/`main.js` per the dependency order list).

- [ ] **Step 6: Run test to verify it passes**

Run: `node scripts/test-dashboard-ui.mjs`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/static/js/dashboard-page.js scripts/test-dashboard-ui.mjs src/main/resources/static/index.html
git commit -m "Add Dashboard page: quick-start cards, recent activity, storage summary from real data"
```

---

### Task 11: Shell (sidebar, topbar, page switching, polling lifecycle)

**Files:**
- Create: `src/main/resources/static/js/shell.js`
- Create: `scripts/test-shell-navigation-ui.mjs` (new)
- Modify: `index.html` (sidebar/topbar markup wraps all 5 page containers)

**Interfaces:**
- Consumes: `renderDashboard`, `refreshHistory`, `refreshStorage`, `SchemaBridgeState.migration`.
- Produces: `navigateToPage(pageId): void`, `currentPageId(): string`.

This is the task implementing **Review Focus item 1** (polling must stop exactly once on leaving Migration, resume exactly once and only if still active on return).

- [ ] **Step 1: Write the failing test**

Create `scripts/test-shell-navigation-ui.mjs`:
```js
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {createRequire} from 'node:module';
const require=createRequire(new URL('../target/ui-test/package.json',import.meta.url));
const {JSDOM}=require('jsdom');
const html=await readFile(new URL('../src/main/resources/static/index.html',import.meta.url),'utf8');
const dom=new JSDOM(html,{url:'http://localhost:8098',runScripts:'outside-only'});
const {window}=dom;
let fetchCount = 0;
window.fetch = async (path) => { fetchCount++; return {ok:true, status:200, json: async () => path.endsWith('/jobs/running-job-1') ? {id:'running-job-1', state:'RUNNING', message:'x', tables:[], progress:{}} : path.endsWith('/jobs') ? [] : {stagedBytes:0,freeDiskBytes:0,reclaimableBytes:0,plans:[],orphanedDirectories:[]}}; };
const JS_FILES=['format','api','toast','state','status-badge','progress-panel','connection-form','datasource-catalog','flow-and-upload','migration-stepper','table-picker','review-page','execution-page','revert-panel','history-page','storage-page','settings-page','dashboard-page','shell'];
for (const file of JS_FILES) window.eval(await readFile(new URL(`../src/main/resources/static/js/${file}.js`,import.meta.url),'utf8'));

// Simulate a job mid-flight, as if the user had just started it.
window.SchemaBridgeState.migration.finalJob = {id:'running-job-1', state:'RUNNING', message:'x', tables:[], progress:{}};
window.document.getElementById('jobSection').hidden = false;
window.renderJob(); window.pollJob();
const pollingTimerBeforeLeaving = window.SchemaBridgeState.migration.polling;
assert.ok(pollingTimerBeforeLeaving, 'pollJob() must have armed a timer');

// Leave Migration -> polling timer must be cleared (not just overwritten later).
window.navigateToPage('history');
assert.equal(window.SchemaBridgeState.migration.polling, null, 'leaving Migration must stop the UI polling timer');
assert.equal(window.currentPageId(), 'history');

// Return to Migration -> must fetch once immediately, then resume polling because the job is still RUNNING.
const fetchCountBeforeReturn = fetchCount;
await window.navigateToPage('migration');
assert.ok(fetchCount > fetchCountBeforeReturn, 'returning to Migration must fetch current status immediately, not wait for the next tick');
assert.ok(window.SchemaBridgeState.migration.polling, 'polling must resume because the job is still active');

// Now the job is finished; leaving and returning again must NOT resume polling.
window.SchemaBridgeState.migration.finalJob.state = 'SUCCEEDED';
window.navigateToPage('history');
await window.navigateToPage('migration');
assert.equal(window.SchemaBridgeState.migration.polling, null, 'a finished job must not resume polling on return');
console.log('shell-navigation-ui: all assertions passed');
```

- [ ] **Step 2: Run test to verify it fails**

Run: `node scripts/test-shell-navigation-ui.mjs`
Expected: FAIL — `window.navigateToPage is not a function`.

- [ ] **Step 3: Write `js/shell.js`**:

```js
const PAGE_IDS = ['dashboard','migration','history','storage','settings'];
let activePage = 'dashboard';
function currentPageId(){ return activePage; }
async function navigateToPage(pageId){
  if (!PAGE_IDS.includes(pageId)) return;
  const leaving = activePage;
  if (leaving === 'migration' && pageId !== 'migration') {
    clearTimeout(SchemaBridgeState.migration.polling); SchemaBridgeState.migration.polling = null;
    clearTimeout(SchemaBridgeState.migration.preparationPoll); SchemaBridgeState.migration.preparationPoll = null;
  }
  activePage = pageId;
  for (const id of PAGE_IDS) { const el = document.getElementById(id + 'Page'); if (el) el.hidden = id !== pageId; }
  document.querySelectorAll('[data-nav-page]').forEach(link => {
    const active = link.dataset.navPage === pageId;
    link.classList.toggle('active', active);
    if (active) link.setAttribute('aria-current','page'); else link.removeAttribute('aria-current');
  });
  if (pageId === 'migration' && SchemaBridgeState.migration.finalJob) {
    try {
      SchemaBridgeState.migration.finalJob = await (await api('/jobs/'+SchemaBridgeState.migration.finalJob.id, undefined, 'GET')).json();
      renderJob();
      if (jobBusy(SchemaBridgeState.migration.finalJob)) pollJob();
    } catch (e) { error(e.message); }
  }
  if (pageId === 'dashboard') await renderDashboard();
  if (pageId === 'history') await refreshHistory();
  if (pageId === 'storage') await refreshStorage();
}
document.querySelectorAll('[data-nav-page]').forEach(link => link.addEventListener('click', (event) => { event.preventDefault(); navigateToPage(link.dataset.navPage); }));
```

- [ ] **Step 4: Add the sidebar/topbar shell markup to `index.html`**, wrapping the 5 page containers (Dashboard already added in Task 10; the existing `<form id="configuration">` + `reviewPage` + `resultsPage` sections together become the content of `#migrationPage`; History/Storage/Settings cards move out of `resultsPage` into their own `#historyPage`/`#storagePage`/`#settingsPage` containers — this structural move happens in Task 12, which is explicitly scoped to that; this task only adds the shell chrome and the `migrationPage`/`historyPage`/`storagePage`/`settingsPage` **empty container ids** so `navigateToPage` has something to toggle. Add, inside `<body>`, before the existing `<header>`:

```html
<div class="app-shell">
  <nav class="app-sidebar" aria-label="Primary">
    <div class="app-brand"><span class="brand-mark">S</span><span class="brand-label">Schema Bridge</span></div>
    <a href="#" data-nav-page="dashboard" class="active" aria-current="page">Dashboard</a>
    <a href="#" data-nav-page="migration">Migration</a>
    <a href="#" data-nav-page="history">History</a>
    <a href="#" data-nav-page="storage">Storage</a>
    <a href="#" data-nav-page="settings">Settings</a>
  </nav>
  <div class="app-main">
```
and close it (`</div></div>`) at the end of `<body>`, before the closing `</body>`. Wrap the existing `<header>...</header><main>...</main>` pair inside this `.app-main` div. Add empty containers (populated structurally in Task 12):
```html
<section id="historyPage" class="app-page" hidden></section>
<section id="storagePage" class="app-page" hidden></section>
<section id="settingsPage" class="app-page" hidden></section>
```
and give the existing `<main>` element `id="migrationPage"` plus `class="app-page"` (it already contains the wizard).

- [ ] **Step 5: Add script tag to `index.html`**, after `dashboard-page.js`:
```html
<script src="/js/shell.js"></script>
```

- [ ] **Step 6: Run test to verify it passes**

Run: `node scripts/test-shell-navigation-ui.mjs`
Expected: PASS.

- [ ] **Step 7: Run every other harness** (add `'shell'` to each one's `JS_FILES` first)

Run: `node scripts/test-connection-ui.mjs && node scripts/test-bulk-ui.mjs && node scripts/test-catalog-ui.mjs && node scripts/test-table-settings-ui.mjs && node scripts/test-review-page-ui.mjs && node scripts/test-history-guard-ui.mjs && node scripts/test-settings-import-ui.mjs && node scripts/test-dashboard-ui.mjs`
Expected: all PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/resources/static/js/shell.js scripts/test-shell-navigation-ui.mjs src/main/resources/static/index.html scripts/test-*-ui.mjs
git commit -m "Add shell: sidebar navigation, page switching, polling stop/resume on navigation"
```

---

### Task 12: Final `index.html` restructuring — move History/Storage/Settings cards into their own pages

**Files:**
- Modify: `index.html` (move markup only — no new logic), `app.js` (delete, now empty except `main.js`'s content — see Step 4), `styles.css` (minor: `.app-page[hidden]` rule)

**Interfaces:** none new — this task only relocates existing DOM elements (`#storageMetrics`/`#storageOrphans`/`#refreshStorage`, `#jobHistory`/`#jobHistoryStatus`/`#refreshHistory`, the "Export / import settings" card) from inside `resultsPage` into `#storagePage`/`#historyPage`/`#settingsPage` respectively. The IDs stay identical, so every function from Tasks 8/9 that calls `document.getElementById('storageMetrics')` etc. keeps working unchanged.

- [ ] **Step 1: Cut the "Local storage" `<section>` (today's `index.html` line 115) out of `resultsPage` and paste it as the sole content of `#storagePage`**, adding a page heading:
```html
<section id="storagePage" class="app-page" hidden>
  <div class="section-heading"><h2>Storage</h2></div>
  <section class="card" aria-labelledby="storageTitle"><div class="section-title"><h2 id="storageTitle">Local storage</h2><button id="refreshStorage" class="secondary" type="button">Refresh storage</button></div><p class="muted">Staged data and reports for all saved plans, plus free disk space on this work directory's drive. A preview only — nothing is deleted here.</p><dl class="progress-metrics" id="storageMetrics"></dl><p id="storageOrphans" class="muted" hidden></p></section>
</section>
```

- [ ] **Step 2: Cut the "Saved migration history" `<section>` (today's line 116) out of `resultsPage` into `#historyPage`**:
```html
<section id="historyPage" class="app-page" hidden>
  <div class="section-heading"><h2>History</h2></div>
  <section class="card" aria-labelledby="historyTitle"><div class="section-title"><h2 id="historyTitle">Saved migration history</h2><button id="refreshHistory" class="secondary" type="button">Refresh history</button></div><p class="muted">History loads automatically. Passwords are not saved. Interrupted jobs with an uncertain commit require target reconciliation.</p><p id="jobHistoryStatus" class="history-status" role="status" aria-live="polite"></p><div id="jobHistory" class="history-list" role="list" aria-label="Saved migrations"></div></section>
</section>
```

- [ ] **Step 3: Cut the "Export / import settings" card (Task 9 renamed it) out of `configuration`'s step-2 page into `#settingsPage`**:
```html
<section id="settingsPage" class="app-page" hidden>
  <div class="section-heading"><h2>Settings</h2></div>
  <section class="card"><div class="section-title"><h2>Export / import settings</h2></div><p class="muted">Download the current dialects, connection host/port/database/username, target schema and transfer settings as a local JSON file — never passwords. Nothing is stored on the server; this is not a saved-connections manager. Re-enter passwords after importing.</p><div class="action-bar"><button type="button" id="exportSettings" class="secondary">Export settings</button><label class="upload">Import settings<input id="importSettings" type="file" accept="application/json"></label></div></section>
  <section class="card"><h2>About</h2><p class="muted">SchemaBridge · Local single-user application · <span id="buildVersion">Loading version…</span> · Credentials are kept out of reports</p></section>
</section>
```
Also delete the old `<footer>` content that duplicated the version string (today's line 137's `<footer>` keeps the banner image but drops the now-redundant version `<span>`, since it moved to the Settings page's "About" card).

- [ ] **Step 4: Confirm `app.js` is now empty except `main.js`'s bootstrap content** — everything else has been ported in Tasks 1–11. Delete `app.js` entirely and create `js/main.js` with the one remaining piece of top-level bootstrap code (today's `app.js` lines 163, 202, and the version-loader at line 492+):
```js
function loadBuildVersion(){ api('/capabilities', undefined, 'GET').then(r => r.json()).then(info => { document.getElementById('buildVersion').textContent = 'Version ' + info.version; }).catch(() => { document.getElementById('buildVersion').textContent = 'Version unavailable'; }); }
connectionForm('source'); connectionForm('target');
document.querySelectorAll('input[name="flow"]').forEach(input => input.addEventListener('change', updateFlow));
document.querySelectorAll('[data-step-nav]').forEach(button => button.addEventListener('click',()=>goToStep(Number(button.dataset.stepNav))));
document.querySelectorAll('[data-back]').forEach(button => button.addEventListener('click',()=>goToStep(Number(button.dataset.back))));
document.getElementById('flowNext').addEventListener('click',()=>{SchemaBridgeState.migration.maxStep=Math.max(SchemaBridgeState.migration.maxStep,2);goToStep(2);});
document.getElementById('reviewNext').addEventListener('click',()=>{if(!SchemaBridgeState.migration.plan)return;SchemaBridgeState.migration.maxStep=Math.max(SchemaBridgeState.migration.maxStep,4);goToStep(4);});
document.getElementById('startOver').addEventListener('click',()=>{const m=SchemaBridgeState.migration;m.plan=null;m.lastRequest=null;m.maxStep=1;document.getElementById('reportSection').hidden=true;document.getElementById('outputActions').hidden=true;document.getElementById('preparationSection').hidden=true;document.getElementById('jobSection').hidden=true;goToStep(1);});
document.getElementById('configuration').addEventListener('input', invalidate);
for (const prefix of ['source','target']) document.getElementById(prefix + 'Dialect').addEventListener('change', () => { if(prefix==='source'){document.getElementById('columnOptions').replaceChildren();fillOptions(document.getElementById('sourceTables'), []);if(document.getElementById('tablePickerList'))renderTablePicker();} applyDialect(prefix); fillOptions(document.getElementById(prefix + 'SchemaSelect'), []); if(prefix !== 'source') document.getElementById('targetSchema').value = document.getElementById('targetDialect').value === 'ORACLE' ? 'APP' : 'public'; });
for (const id of ['files','folder']) document.getElementById(id).addEventListener('change', () => loadSqlFiles(document.getElementById(id)));
document.getElementById('example').addEventListener('click', () => { document.getElementById('sourceDialect').value = 'ORACLE'; document.getElementById('targetDialect').value = 'POSTGRESQL'; document.getElementById('sql').value = "CREATE TABLE customers (\n  id NUMBER(10,0) PRIMARY KEY,\n  name VARCHAR2(100) NOT NULL,\n  joined_at DATE\n);\nINSERT INTO customers (id,name,joined_at) VALUES (1,'Anita',TIMESTAMP '2026-01-15 09:30:00');\nINSERT INTO customers (id,name,joined_at) VALUES (2,'Sam',NULL);"; invalidate(); });
document.getElementById('configuration').addEventListener('submit', async event => {
  const m = SchemaBridgeState.migration;
  event.preventDefault(); document.getElementById('analyse').disabled = true; document.getElementById('configStatus').textContent = 'Reading source and comparing target definitions…';
  try {
    if(usesDatabase()&&(!selectedSourceTables().length||selectedSourceTables().length>500))throw new Error('Select between 1 and 500 source tables before analysis.');
    const request = {options:transferOptions(),selections:usesDatabase()?tableSelections():{},metadataOnly:usesDatabase(),sourceDialect:document.getElementById('sourceDialect').value,targetDialect:document.getElementById('targetDialect').value,targetSchema:document.getElementById('targetSchema').value.trim(),sql:usesDatabase()?null:document.getElementById('sql').value,source:usesDatabase()?connection('source'):null,sourceSchema:usesDatabase()?document.getElementById('sourceSchemaSelect').value:null,tables:usesDatabase()?Array.from(document.getElementById('sourceTables').selectedOptions).map(o=>o.value):[],includeData:document.getElementById('includeData').checked,target:writesDatabase()?connection('target'):null};
    m.lastRequest=request; const version=m.requestVersion; const result = await (await api('/plans', request)).json(); if(version!==m.requestVersion){await api('/plans/'+result.id,undefined,'DELETE');return;} m.plan=result; renderPlan(); document.getElementById('preview').textContent = await (await api('/plans/' + m.plan.id + '/preview', undefined, 'GET')).text(); document.getElementById('configStatus').textContent = 'Analysis complete. Review the proposed changes.'; m.maxStep=Math.max(m.maxStep,3);goToStep(3);
  } catch(e) { error(e.message); document.getElementById('configStatus').textContent = 'Analysis failed. Check the message and try again.'; } finally { document.getElementById('analyse').disabled = false; }
});
function transferOptions(){return {maxRows:Number(document.getElementById('maxRows').value),maxTableBytes:Math.round(Number(document.getElementById('maxTableGb').value)*1e9),diskReserveBytes:Math.round(Number(document.getElementById('diskReserveMb').value)*1e6),fetchSize:Number(document.getElementById('fetchSize').value),batchRows:Number(document.getElementById('batchRows').value),batchBytes:Math.round(Number(document.getElementById('batchMb').value)*1e6),queryTimeoutSeconds:Number(document.getElementById('queryTimeout').value),readTimeoutSeconds:Number(document.getElementById('readTimeout').value),validateData:document.getElementById('validateData').checked,useCopy:document.getElementById('useCopy').checked};}
loadBuildVersion();
navigateToPage('dashboard');
```
Replace `<script src="/app.js" defer></script>` in `index.html` with `<script src="/js/main.js" defer></script>`, and delete `src/main/resources/static/app.js`.

- [ ] **Step 4: Add `.app-page[hidden]{display:none}` to `styles.css`** if not already covered by the existing global `[hidden]{display:none!important}` rule (today's `styles.css` already has this exact rule — confirm it still applies to `.app-page` elements; no change needed if so, this step is a verification, not necessarily an edit).

- [ ] **Step 5: Update every harness's `JS_FILES` to read `main.js` last** instead of a separate `app.js` reference, and re-run every single harness:

Run: `node scripts/test-connection-ui.mjs && node scripts/test-bulk-ui.mjs && node scripts/test-catalog-ui.mjs && node scripts/test-table-settings-ui.mjs && node scripts/test-review-page-ui.mjs && node scripts/test-history-guard-ui.mjs && node scripts/test-settings-import-ui.mjs && node scripts/test-dashboard-ui.mjs && node scripts/test-shell-navigation-ui.mjs`
Expected: all PASS. This is the first point where the *entire* real file list (not a test-local subset) is exercised together — pay close attention to any load-order error here (a function called before its declaring script has run), since this is exactly the kind of mistake the incremental approach in Tasks 1–11 was designed to catch earlier, but the final concatenation is the real proof.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/static/index.html src/main/resources/static/js/main.js src/main/resources/static/styles.css scripts/test-*-ui.mjs
git rm src/main/resources/static/app.js
git commit -m "Finish index.html restructuring: History/Storage/Settings promoted to their own pages, delete now-empty app.js"
```

---

### Task 13: Dark theme CSS token system

**Files:**
- Modify: `src/main/resources/static/styles.css` (full rewrite, same file — no split into multiple CSS files, to keep this change reviewable as one diff)

**Interfaces:** none (pure styling; no JS/HTML structural change — all the class names and element IDs referenced by `styles.css` already exist from Tasks 1–12).

- [ ] **Step 1: Replace the `:root` block at the top of `styles.css`** with the token system from the spec (§3):
```css
:root{
  --bg-primary:#0a0e14;--bg-secondary:#0f1420;--bg-surface:#141a24;--bg-elevated:#1a212e;
  --border:#262e3d;--border-strong:#34404f;
  --text-primary:#e4e8ef;--text-secondary:#8b94a7;--text-tertiary:#5a6378;
  --accent:#3fb6ff;--accent-muted:#1c3a52;
  --success:#3ecf8e;--warning:#e8a33d;--error:#f0555a;--info:#3fb6ff;
  --font-ui:system-ui,-apple-system,"Segoe UI",sans-serif;
  --font-mono:ui-monospace,Consolas,"SF Mono",monospace;
  font-family:var(--font-ui);color:var(--text-primary);background:var(--bg-primary);font-size:15px;line-height:1.55;
}
*{box-sizing:border-box}body{margin:0;background:var(--bg-primary)}
```

- [ ] **Step 2: Rewrite every existing color reference** in the remaining CSS (the `--green`/`--line`/light hex values like `#18352f`, `#f5f7f5`, `#087b60`, `#dce5de`, etc.) to the new tokens. This is a mechanical, exhaustive find/replace — map each old value to its semantic replacement:
  - `var(--green)` / `#087b60` → `var(--accent)`
  - `var(--line)` / `#dce5de` → `var(--border)`
  - body text colors (`#18352f`, `#233d34`, `#203b30`) → `var(--text-primary)`
  - secondary/muted text (`#667b72`, `#728378`, `#687d74`, `.muted,small{color:...}`) → `var(--text-secondary)` (per the user's explicit correction: real labels use secondary, not tertiary)
  - card/surface backgrounds (`white`, `#fff`) → `var(--bg-surface)`
  - header background → `var(--bg-secondary)`
  - `.badge.MATCH`/success colors (`#e0f3e8`/`#17613c`) → background `var(--accent-muted)`-style success tint, text `var(--success)`
  - `.badge.MISMATCH`/warning colors (`#fff0df`/`#98601d`) → `var(--warning)`
  - `.message.issue` error colors (`#fff0ed`/`#c66c5c`) → `var(--error)`
  - `#error` banner (now superseded by `.toast` — see Step 4) 

- [ ] **Step 3: Add `.status-badge` / `.status-dot` rules** for the new shared component from Task 2:
```css
.status-badge{display:inline-flex;align-items:center;gap:6px;font-size:11px;font-weight:650;padding:4px 9px;border-radius:20px;font-family:var(--font-ui)}
.status-dot{width:6px;height:6px;border-radius:50%;background:currentColor}
.status-badge.status-success{background:#11332740;color:var(--success)}
.status-badge.status-error{background:#3a171840;color:var(--error)}
.status-badge.status-warning{background:#3a2a1040;color:var(--warning)}
.status-badge.status-info{background:var(--accent-muted);color:var(--accent)}
```

- [ ] **Step 4: Replace the `#error` rule with `.toast`/`.toast-close` rules** (Task 1's new component):
```css
#toastHost{position:fixed;bottom:22px;left:50%;transform:translateX(-50%);display:flex;flex-direction:column;gap:10px;z-index:50;max-width:92vw}
.toast{display:flex;align-items:center;gap:14px;min-width:280px;max-width:700px;padding:14px 16px;border-radius:9px;font-size:13px;box-shadow:0 10px 40px #00000050;border:1px solid var(--border)}
.toast-error{background:#2a1416;color:var(--text-primary);border-color:#4a2024}
.toast-success{background:#0f2a20;border-color:#1c4433}
.toast-info{background:var(--bg-elevated)}
.toast-close{background:transparent;border:0;color:var(--text-secondary);font-size:18px;line-height:1;cursor:pointer;padding:0 2px}
.toast-close:hover{color:var(--text-primary)}
```

- [ ] **Step 5: Add sidebar/shell/collapsible-disclosure rules**:
```css
.app-shell{display:grid;grid-template-columns:220px 1fr;min-height:100vh}
.app-sidebar{background:var(--bg-secondary);border-right:1px solid var(--border);padding:16px 0;display:flex;flex-direction:column}
.app-brand{display:flex;align-items:center;gap:10px;padding:0 16px 18px;font-weight:700;font-size:14px;border-bottom:1px solid var(--border);margin-bottom:12px}
.brand-mark{width:26px;height:26px;border-radius:6px;background:var(--accent);color:var(--bg-primary);display:grid;place-items:center;font-weight:800;font-size:13px}
.app-sidebar a{display:flex;align-items:center;gap:10px;padding:9px 16px;color:var(--text-secondary);font-size:13px;font-weight:500;text-decoration:none}
.app-sidebar a.active{background:var(--accent-muted);color:var(--accent)}
.app-sidebar a:focus-visible,button:focus-visible,input:focus-visible,select:focus-visible,summary:focus-visible{outline:2px solid var(--accent);outline-offset:2px}
.mc-disclosure{border:1px solid var(--border);border-radius:8px;margin:10px 0;background:var(--bg-surface);overflow:hidden}
.mc-disclosure summary{list-style:none;cursor:pointer;padding:10px 12px;display:flex;align-items:center;justify-content:space-between;font-size:12px;font-weight:650;color:var(--text-primary)}
.mc-disclosure summary::-webkit-details-marker{display:none}
.mc-disclosure summary .chev{display:inline-flex;width:16px;transition:transform .15s;color:var(--text-secondary)}
.mc-disclosure[open] summary .chev{transform:rotate(90deg)}
.mc-disclosure summary .count{font-weight:500;color:var(--text-secondary);font-size:11px}
.mc-disclosure .mc-dbody{padding:4px 12px 12px;border-top:1px solid var(--border)}
.dashboard-quick{display:grid;grid-template-columns:repeat(4,1fr);gap:10px;margin-bottom:22px}
.dashboard-qcard{border:1px solid var(--border);background:var(--bg-surface);border-radius:10px;padding:14px;font-size:12px;text-align:left;cursor:pointer;color:var(--text-primary)}
.dashboard-qcard b{display:block;font-size:13px;margin-bottom:4px}
.dashboard-qcard span{color:var(--text-secondary)}
@media(max-width:800px){.app-shell{grid-template-columns:56px 1fr}.app-sidebar .brand-label,.app-sidebar a span:not(.ic){display:none}.app-sidebar a{justify-content:center}.dashboard-quick{grid-template-columns:repeat(2,1fr)}}
```

- [ ] **Step 6: Set monospace typography** on SQL/identifier/technical-value elements only (not on form labels/buttons), per the user's explicit instruction. Confirm/add:
```css
textarea,pre,#preview,.history-card-heading code,td code,.jobRows td:first-child{font-family:var(--font-mono)}
```

- [ ] **Step 7: Run every jsdom harness once more** (pure CSS change — these tests don't assert on computed styles, so this is a sanity check that nothing in the CSS rewrite accidentally broke an HTML structure dependency)

Run: `node scripts/test-connection-ui.mjs && node scripts/test-bulk-ui.mjs && node scripts/test-catalog-ui.mjs && node scripts/test-table-settings-ui.mjs && node scripts/test-review-page-ui.mjs && node scripts/test-history-guard-ui.mjs && node scripts/test-settings-import-ui.mjs && node scripts/test-dashboard-ui.mjs && node scripts/test-shell-navigation-ui.mjs`
Expected: all PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/resources/static/styles.css
git commit -m "Rewrite styles.css around a dark CSS custom-property token system"
```

---

### Task 14: Full verification pass

**Files:** none created; this task only runs checks and fixes anything Tasks 1–13 missed.

- [ ] **Step 1: Run the full Maven build**

Run: `mvn -B -ntp -f pom.xml verify`
Expected: BUILD SUCCESS (static resources are copied, not compiled — this mainly confirms the Java test suite is unaffected and the jar still packages).

- [ ] **Step 2: Check `scripts/smoke-test.mjs` and `scripts/smoke-edge-cases.mjs` for DOM-structure assertions that moved**

Run: `grep -n "getElementById\|querySelector" scripts/smoke-test.mjs scripts/smoke-edge-cases.mjs`
For any assertion referencing an element ID that Task 12 relocated (it shouldn't be any — all IDs were preserved, only their parent containers moved), update the assertion to match; otherwise no change needed.

- [ ] **Step 3: Start the app and smoke-test it live**

Run: `mvn -f pom.xml spring-boot:run` (background), then: `node scripts/smoke-test.mjs && node scripts/smoke-edge-cases.mjs`
Expected: both PASS against the running server.

- [ ] **Step 4: Manual verification pass** (per spec §7 and the user's explicit rollout requirements) — open the running app in a browser and walk through:
  - All 4 flow types from the Dashboard quick-start cards land in Migration at the correct starting configuration.
  - Configure sub-tabs (Source/Target/Objects/Options); LCNC datasource picker and manual entry both work.
  - Table picker: select up to 500 tables (confirm 501 is rejected), expand a table's settings, toggle Columns/Filters/Row-limit disclosures independently, add/remove multiple filters, set a row limit, rename columns/table.
  - Review: structured column differences render with correction text, row-budget line, "DML only for all matches", ordered-script statement table path (upload a SQL file flow).
  - Execution: prepare data for a metadata-only plan, download ZIP via ticket, execute a plan, watch the progress panel update, cancel a running job, resume a failed-but-resumable job.
  - Navigate to History while a job is `RUNNING` (open browser devtools Network tab): confirm polling requests stop; wait, then return to Migration and confirm one immediate fetch plus resumed polling.
  - Open a History job while Migration has an unsaved, changed configuration: confirm the replace-confirmation prompt appears; confirm it does *not* appear when there's nothing unfinished.
  - Revert review → confirm (checkbox-gated) flow; a `RECOVERY_REQUIRED` job's "Review and discard evidence" danger path.
  - Trigger two errors in a row: confirm both toasts stack and neither auto-dismisses; trigger a success toast (e.g. a completed download) and confirm it fades on its own.
  - Settings: export produces a JSON file with no password fields; import repopulates fields and resets stale table-picker state when the dialect changes; copy text is explicit about local-file-only scope.
  - Resize the browser to a narrow width: sidebar collapses to icon-only, table picker stacks, forms remain usable.
  - Browser console: zero errors across all of the above.

- [ ] **Step 5: Stop the dev server, run the full jsdom + Maven suite one final time together**

Run: `node scripts/test-connection-ui.mjs && node scripts/test-bulk-ui.mjs && node scripts/test-catalog-ui.mjs && node scripts/test-table-settings-ui.mjs && node scripts/test-review-page-ui.mjs && node scripts/test-history-guard-ui.mjs && node scripts/test-settings-import-ui.mjs && node scripts/test-dashboard-ui.mjs && node scripts/test-shell-navigation-ui.mjs && mvn -B -ntp -f pom.xml verify`
Expected: all PASS, BUILD SUCCESS.

- [ ] **Step 6: Commit** (only if Steps 1–4 required any fixes; otherwise this task produces no diff and is just a verification record)

```bash
git add -A
git commit -m "Full verification pass for the Schema Bridge UI redesign" --allow-empty
```
