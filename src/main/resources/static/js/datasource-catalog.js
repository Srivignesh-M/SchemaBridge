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
