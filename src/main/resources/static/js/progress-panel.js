function renderProgress(prefix,p){if(!p)return;const phases={QUEUED:'Queued',PREPARING:'Preparing',EXTRACTING:'Reading source',RECONNECTING:'Reconnecting to source',READING_DEFINITIONS:'Reading source definitions',COMPARING_TARGET:'Comparing with target',CHECKING:'Checking target and staged data',CREATING:'Creating tables',LOADING:'Loading data',COPYING:'Streaming COPY',FINGERPRINTING:'Verifying rows and saving revert receipt',COMMITTING:'Committing table',VALIDATING_BASELINE:'Checking target before loading',VALIDATING:'Verifying loaded data',FINALIZING:'Adding indexes and constraints',SUCCEEDED:'Complete',FAILED:'Failed',CANCELLED:'Cancelled',RECOVERY_REQUIRED:'Needs reconciliation'};document.getElementById(`${prefix}Phase`).textContent=phases[p.phase]||p.phase||'Waiting';document.getElementById(`${prefix}Table`).textContent=p.table?`Table: ${p.table}`:'';document.getElementById(`${prefix}Read`).textContent=Number(p.rowsRead||0).toLocaleString();document.getElementById(`${prefix}Sent`).textContent=prefix==='preparation'?'—':Number(p.rowsSent||0).toLocaleString();document.getElementById(`${prefix}Committed`).textContent=prefix==='preparation'?'—':Number(p.rowsCommitted||0).toLocaleString();document.getElementById(`${prefix}Bytes`).textContent=formatBytes(p.bytes);document.getElementById(`${prefix}Elapsed`).textContent=formatDuration(p.elapsedSeconds);document.getElementById(`${prefix}Rate`).textContent=Number(p.rowsPerSecond)>0?`${Math.round(p.rowsPerSecond).toLocaleString()} rows/s`:'—';document.getElementById(`${prefix}Eta`).textContent=p.remainingSeconds==null?'—':formatDuration(p.remainingSeconds);renderTableProgress(prefix,p);renderTransferDetails(prefix,p);}
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
// "Table N of total" for whichever pass is running: reading source, creating, loading or finalizing tables.
function renderTableProgress(prefix,p){
  const anchor=document.getElementById(`${prefix}Table`)?.closest('.progress-current');if(!anchor)return;
  let box=document.getElementById(`${prefix}TableProgress`);
  if(!box){
    box=document.createElement('div');box.id=`${prefix}TableProgress`;box.className='table-progress';
    const bar=document.createElement('div');bar.className='table-progress-bar';bar.setAttribute('role','progressbar');bar.setAttribute('aria-valuemin','0');bar.setAttribute('aria-valuemax','100');bar.setAttribute('aria-label','Tables completed');
    bar.append(document.createElement('div'));const count=document.createElement('span');count.className='table-progress-count';box.append(bar,count);anchor.after(box);
  }
  const total=Number(p.tablesTotal||0),done=Math.min(Number(p.tablesDone||0),total);
  box.hidden=!total;if(!total)return;
  const running=['EXTRACTING','RECONNECTING','READING_DEFINITIONS','COMPARING_TARGET','CREATING','LOADING','COPYING','FINGERPRINTING','COMMITTING','VALIDATING_BASELINE','VALIDATING','FINALIZING'].includes(p.phase)&&done<total;
  const percent=Math.floor(done/total*100),bar=box.querySelector('.table-progress-bar');
  bar.firstElementChild.style.width=`${percent}%`;bar.setAttribute('aria-valuenow',String(percent));
  // Parallel readers work on several tables at once: show the range in progress.
  const active=Math.max(1,Math.min(Number(p.tablesActive||1),total-done));
  const current=active>1?`Tables ${(done+1).toLocaleString()}–${(done+active).toLocaleString()} of ${total.toLocaleString()}`:`Table ${(done+1).toLocaleString()} of ${total.toLocaleString()}`;
  box.querySelector('.table-progress-count').textContent=running?`${current} · ${percent}% of tables done`:`${done.toLocaleString()} of ${total.toLocaleString()} tables`;
}

// Byte throughput remains live while COPY row totals are waiting for the table to finish.
function renderTransferDetails(prefix,p){
  const anchor=document.getElementById(`${prefix}Table`)?.closest('.progress-current');if(!anchor)return;
  let details=document.getElementById(`${prefix}TransferDetails`);
  if(!details){details=document.createElement('p');details.id=`${prefix}TransferDetails`;details.className='muted';(document.getElementById(`${prefix}TableProgress`)||anchor).after(details);}
  const labels={COPYING:'COPY',FINGERPRINTING:'Receipt verification',COMMITTING:'Commit'};
  const timings=Object.entries(p.phaseSeconds||{}).filter(([phase])=>labels[phase]);
  details.hidden=!timings.length&&!labels[p.phase];
  details.textContent=timings.map(([phase,seconds])=>`${labels[phase]}: ${formatDuration(seconds)}`).join(' \u00b7 ');
  if(labels[p.phase]){
    document.getElementById(`${prefix}Eta`).textContent='\u2014';
    document.getElementById(`${prefix}Rate`).textContent=p.phase==='COPYING'&&p.bytesPerSecond!=null?`${formatBytes(p.bytesPerSecond)}/s`:'\u2014';
    if(p.phase==='COPYING')details.textContent+=' \u00b7 Row totals update when the table finishes; table percentage is not data percentage.';
  }
}
