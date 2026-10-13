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
