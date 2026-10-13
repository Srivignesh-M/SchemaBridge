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
      parts.push('-- Source file: ' + path(file).replace(/[\r\n\u2028\u2029]/g,' ') + '\n' + text.replace(/^\uFEFF/,''));
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
