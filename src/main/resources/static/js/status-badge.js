const STATUS_LABELS = {
  NEW:'New table', MATCH:'Already exists · match', MISMATCH:'Already exists / review', UNCHECKED:'Not inspected',
  QUEUED:'Queued', RUNNING:'Running', SUCCEEDED:'Completed', FAILED:'Failed', CANCELLED:'Cancelled', RECOVERY_REQUIRED:'Needs reconciliation',
  REVERTED:'Reverted', PARTIAL:'Partially reverted', NOT_RUN:'Not run'
};
const STATUS_SEMANTIC = {
  NEW:'info', MATCH:'success', MISMATCH:'warning', UNCHECKED:'info',
  QUEUED:'info', RUNNING:'info', SUCCEEDED:'success', FAILED:'error', CANCELLED:'warning', RECOVERY_REQUIRED:'error',
  REVERTED:'success', PARTIAL:'warning', NOT_RUN:'info'
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
