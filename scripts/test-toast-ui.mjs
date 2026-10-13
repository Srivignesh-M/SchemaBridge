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

// Review finding #6: identical error messages repeating (e.g. a poll-retry failure firing every
// few seconds) must not grow the stack forever — they should bump a count on the existing toast.
for (let i = 0; i < 5; i++) window.toast('A second failure', {type:'error'});
assert.equal(window.document.querySelectorAll('[data-toast-type="error"]').length, 1, 'identical repeated errors must dedupe, not pile up');
assert.match(window.document.querySelector('[data-toast-type="error"]').textContent, /×6/, 'the deduped toast should show how many times it has recurred');
// A genuinely different error must still stack as its own toast (Review Focus #5 must still hold).
window.toast('A third, different failure', {type:'error'});
assert.equal(window.document.querySelectorAll('[data-toast-type="error"]').length, 2, 'a different error message must still stack separately');

console.log('toast-ui: all assertions passed');
