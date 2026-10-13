async function api(path, body, method = 'POST') {
  const response = await fetch('/api' + path, {method, headers: {'Content-Type':'application/json','X-Migration-Client':'migration-ui'}, ...(body === undefined ? {} : {body:JSON.stringify(body)})});
  if (!response.ok) { let message = 'Request failed (' + response.status + ')'; try { message = (await response.json()).message || message; } catch (_) {} throw new Error(message); }
  return response;
}
function save(blob,name) { const url=URL.createObjectURL(blob), link=document.createElement('a'); link.href=url;link.download=name;link.click();setTimeout(()=>URL.revokeObjectURL(url),1000); }
