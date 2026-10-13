let toastSeq = 0;
function toastContainer() {
  let host = document.getElementById('toastHost');
  if (!host) { host = document.createElement('div'); host.id = 'toastHost'; host.setAttribute('aria-live','polite'); document.body.append(host); }
  return host;
}
function toast(message, options = {}) {
  const type = options.type || 'error';
  const host = toastContainer();
  const existing = Array.from(host.children).find(node => node.dataset.toastType === type && node.dataset.toastMessage === message);
  if (existing) {
    const count = (Number(existing.dataset.toastCount) || 1) + 1;
    existing.dataset.toastCount = String(count);
    existing.querySelector('[data-toast-text]').textContent = `${message} (×${count})`;
    return { id: existing.id, dismiss: () => existing.remove() };
  }
  const id = 'toast-' + (++toastSeq);
  const el = document.createElement('div');
  el.id = id; el.className = 'toast toast-' + type; el.dataset.toastType = type; el.dataset.toastMessage = message; el.dataset.toastCount = '1';
  if (type === 'error') el.setAttribute('role','alert'); else el.setAttribute('role','status');
  const text = document.createElement('span'); text.setAttribute('data-toast-text',''); text.textContent = message; el.append(text);
  const close = document.createElement('button'); close.type = 'button'; close.className = 'toast-close'; close.setAttribute('data-toast-close',''); close.setAttribute('aria-label','Dismiss'); close.textContent = '×';
  const dismiss = () => { el.remove(); };
  close.addEventListener('click', dismiss);
  el.append(close);
  toastContainer().append(el);
  if (type !== 'error') { el.setAttribute('data-toast-autofade',''); setTimeout(dismiss, 7000); }
  return { id, dismiss };
}
// Backward-compatible alias: existing call sites currently say error(message).
function error(message) { return toast(message, {type:'error'}); }
