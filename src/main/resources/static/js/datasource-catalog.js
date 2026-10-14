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
function setCatalogStatus(text){for(const prefix of ['source','target'])document.getElementById(prefix+'CatalogStatus').textContent=text;}
// silent: the automatic load when a connection form appears. It reports problems in the status line
// instead of an error toast, because manual entry stays available either way.
async function loadCatalog({silent=false}={}) {
  const m = SchemaBridgeState.migration;
  if (m.catalogLoading) return;
  m.catalogLoading = true;
  for(const prefix of ['source','target']) document.getElementById(prefix+'CatalogRefresh').disabled = true;
  if(silent)setCatalogStatus('Loading LCNC datasources…');
  try {
    const result = await (await api('/datasources',undefined,'GET')).json();
    m.catalogLoaded = true;
    if (!result.configured) { setCatalogStatus('LCNC catalogue is not configured on this computer. Use manual entry.'); if(!silent)error('LCNC catalogue is not configured. Manual entry remains available.'); return; }
    for(const prefix of ['source','target']) {
      const select=document.getElementById(prefix+'Catalog'), selected=select.value; select.replaceChildren(new Option('Manual entry',''));
      for(const item of result.datasources) { const option=new Option(`${item.name} (#${item.id})${item.supported?' — '+item.dialect:' — '+item.finding}`,String(item.id)); option.disabled=!item.supported; option.title=item.description || ''; select.add(option); }
      select.value=selected;
      if (!select.value && m.catalogSelection[prefix]) setCatalogMode(prefix,null);
      document.getElementById(prefix+'CatalogStatus').textContent = `${result.datasources.length} LCNC datasource${result.datasources.length===1?'':'s'} available. Choose one to connect automatically, or keep manual entry.`;
    }
  } catch(e) { setCatalogStatus('Could not load LCNC datasources. Use manual entry, or refresh to try again.'); if(!silent)error(e.message); }
  finally { m.catalogLoading=false; for(const prefix of ['source','target']) document.getElementById(prefix+'CatalogRefresh').disabled = document.getElementById(prefix+'Connection').hidden; }
}
// Loads the catalogue once per session, as soon as a database connection form is on screen.
function ensureCatalogLoaded(){
  const m = SchemaBridgeState.migration;
  if(m.catalogLoaded||m.catalogLoading||currentPageId()!=='migration')return;
  if(document.getElementById('sourceConnection').hidden&&document.getElementById('targetConnection').hidden)return;
  loadCatalog({silent:true});
}
