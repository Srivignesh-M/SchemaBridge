function settingsProfile(){
  const value=id=>document.getElementById(id)?document.getElementById(id).value:undefined;
  return {version:1,sourceDialect:value('sourceDialect'),targetDialect:value('targetDialect'),targetSchema:value('targetSchema'),
    source:{host:value('sourceHost'),port:value('sourcePort'),database:value('sourceDatabase'),username:value('sourceUsername')},
    target:{host:value('targetHost'),port:value('targetPort'),database:value('targetDatabase'),username:value('targetUsername')}};
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
    // Transfer settings are chosen automatically per migration; older files' "options" are ignored.
    invalidate();
    document.getElementById('configStatus').textContent='Settings imported. Passwords were not included — enter them before connecting.';
  }catch(e){error('Could not import settings: '+e.message);}
  finally{document.getElementById('importSettings').value='';}
});
