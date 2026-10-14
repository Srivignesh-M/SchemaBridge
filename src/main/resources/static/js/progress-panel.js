function renderProgress(prefix,p){if(!p)return;const phases={QUEUED:'Queued',PREPARING:'Preparing',EXTRACTING:'Reading source',READING_DEFINITIONS:'Reading source definitions',COMPARING_TARGET:'Comparing with target',CHECKING:'Checking target and staged data',CREATING:'Creating tables',LOADING:'Loading data',FINALIZING:'Adding indexes and constraints',SUCCEEDED:'Complete',FAILED:'Failed',CANCELLED:'Cancelled',RECOVERY_REQUIRED:'Needs reconciliation'};document.getElementById(`${prefix}Phase`).textContent=phases[p.phase]||p.phase||'Waiting';document.getElementById(`${prefix}Table`).textContent=p.table?`Table: ${p.table}`:'';document.getElementById(`${prefix}Read`).textContent=Number(p.rowsRead||0).toLocaleString();document.getElementById(`${prefix}Sent`).textContent=prefix==='preparation'?'—':Number(p.rowsSent||0).toLocaleString();document.getElementById(`${prefix}Committed`).textContent=prefix==='preparation'?'—':Number(p.rowsCommitted||0).toLocaleString();document.getElementById(`${prefix}Bytes`).textContent=formatBytes(p.bytes);document.getElementById(`${prefix}Elapsed`).textContent=formatDuration(p.elapsedSeconds);document.getElementById(`${prefix}Rate`).textContent=Number(p.rowsPerSecond)>0?`${Math.round(p.rowsPerSecond).toLocaleString()} rows/s`:'—';document.getElementById(`${prefix}Eta`).textContent=p.remainingSeconds==null?'—':formatDuration(p.remainingSeconds);}
const ANALYSIS_STEPS={READING_DEFINITIONS:'Reading source definitions',COMPARING_TARGET:'Comparing with target'};
function showAnalysis(context){renderAnalysis({state:'QUEUED',progress:{}},context);document.getElementById('cancelAnalysis').disabled=false;document.getElementById('analysisOverlay').hidden=false;document.getElementById('cancelAnalysis').focus();}
function hideAnalysis(){document.getElementById('analysisOverlay').hidden=true;}
// Steps: reading source definitions (database source only), then comparing with the target (target connection only).
function renderAnalysis(preparation,{database,target}){
  const p=preparation.progress||{},steps=(database?1:0)+(target?1:0),total=Number(p.tablesTotal||0),done=Math.min(Number(p.tablesDone||0),total);
  const step=ANALYSIS_STEPS[p.phase]?[p.phase==='COMPARING_TARGET'&&database?2:1,ANALYSIS_STEPS[p.phase]]:null;
  const determinate=Boolean(step)&&total>0,bar=document.querySelector('#analysisOverlay .analysis-bar');
  let text=step?`Step ${step[0]} of ${steps} · ${step[1]}`:preparation.state==='QUEUED'?'Waiting for another preparation to finish…':database?'Connecting to source…':'Reading SQL input…';
  if(p.cancelRequested)text='Cancelling at the next table…';
  document.getElementById('analysisStep').textContent=text;
  document.getElementById('analysisTitle').textContent=total?`Analysing ${total.toLocaleString()} table${total===1?'':'s'}`:'Analysing tables';
  bar.classList.toggle('indeterminate',!determinate);
  const percent=determinate?Math.floor(((step[0]-1)+done/total)/steps*100):null;
  document.getElementById('analysisBarFill').style.width=determinate?`${percent}%`:'';
  if(determinate)bar.setAttribute('aria-valuenow',String(percent));else bar.removeAttribute('aria-valuenow');
  document.getElementById('analysisCount').textContent=determinate?`${done.toLocaleString()} / ${total.toLocaleString()} tables`:'';
  document.getElementById('analysisPercent').textContent=determinate?`${percent}%`:'';
  document.getElementById('analysisTable').textContent=determinate&&p.table?p.table:'';
  document.getElementById('analysisElapsed').textContent=`Elapsed ${formatDuration(p.elapsedSeconds)}`;
  document.getElementById('cancelAnalysis').disabled=Boolean(p.cancelRequested);
}
