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
  else if(!selectedSourceTables().length){error('Select at least one source table to continue.');return;}
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
