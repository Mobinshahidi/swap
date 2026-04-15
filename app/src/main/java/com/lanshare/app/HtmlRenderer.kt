package com.lanshare.app

import android.text.TextUtils
import java.net.URLEncoder
import java.util.Locale

object HtmlRenderer {
    private val css = """
*, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }
:root {
  --bg: #f7f6f3; --surface: #ffffff; --border: #e4e2dc;
  --text: #1a1a18; --muted: #888680; --accent: #2a6ef5;
  --accent-light: #eef2ff; --danger: #e53e3e; --success: #16a34a;
  --radius: 10px;
  --mono: ui-monospace, 'Menlo', 'Cascadia Code', monospace;
  --sans: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
}
body { background: var(--bg); color: var(--text); font-family: var(--sans);
       font-weight: 300; min-height: 100vh; padding: 2rem 1rem; }
.container { max-width: 680px; margin: 0 auto; }
header { margin-bottom: 2rem; padding-bottom: 1.25rem; border-bottom: 1px solid var(--border); }
.breadcrumb { font-family: var(--mono); font-size: 0.82rem; color: var(--muted);
  margin-bottom: 0.5rem; display: flex; align-items: center; gap: 0.3rem; flex-wrap: wrap; }
.breadcrumb a { color: var(--accent); text-decoration: none; }
.breadcrumb a:hover { text-decoration: underline; }
.breadcrumb span { color: var(--border); }
.header-row { display: flex; align-items: baseline; gap: 0.75rem; }
header h1 { font-family: var(--mono); font-size: 1.1rem; font-weight: 500; letter-spacing: -0.02em; }
header .count { font-size: 0.78rem; color: var(--muted); font-family: var(--mono); }
.upload-zone {
  border: 1.5px dashed var(--border); border-radius: var(--radius);
  padding: 2rem; text-align: center; cursor: pointer;
  transition: all 0.18s; background: var(--surface); margin-bottom: 1rem; position: relative;
}
.upload-zone:hover, .upload-zone.drag-over { border-color: var(--accent); background: var(--accent-light); }
.upload-zone input[type=file] { position: absolute; inset: 0; opacity: 0; cursor: pointer; width: 100%; height: 100%; }
.upload-icon { font-size: 1.75rem; margin-bottom: 0.5rem; display: block; }
.upload-label { font-size: 0.9rem; color: var(--muted); }
.upload-label strong { color: var(--accent); font-weight: 500; }
#progress-wrap { display: none; margin-bottom: 1rem; background: var(--surface);
  border: 1px solid var(--border); border-radius: var(--radius); padding: 1rem 1.25rem; }
#progress-label { font-size: 0.8rem; font-family: var(--mono); color: var(--muted); margin-bottom: 0.5rem; }
#progress-bar-bg { background: var(--border); border-radius: 99px; height: 4px; overflow: hidden; }
#progress-bar { height: 100%; background: var(--accent); border-radius: 99px; width: 0%; transition: width 0.15s; }
.card { background: var(--surface); border: 1px solid var(--border);
  border-radius: var(--radius); margin-bottom: 1rem; overflow: hidden; }
.card-header { padding: 0.7rem 1.25rem; font-size: 0.75rem; font-family: var(--mono);
  color: var(--muted); letter-spacing: 0.06em; text-transform: uppercase;
  border-bottom: 1px solid var(--border); cursor: pointer;
  display: flex; justify-content: space-between; align-items: center; user-select: none; }
.card-header:hover { background: var(--bg); }
.card-body { padding: 1rem 1.25rem; display: none; }
.card-body.open { display: block; }
#paste-text { width: 100%; min-height: 90px; border: 1px solid var(--border); border-radius: 6px;
  padding: 0.75rem; font-family: var(--mono); font-size: 0.82rem;
  color: var(--text); background: var(--bg); resize: vertical; outline: none; transition: border-color 0.15s; }
#paste-text:focus { border-color: var(--accent); }
.paste-row { display: flex; gap: 0.5rem; margin-top: 0.75rem; align-items: center; flex-wrap: wrap; }
.paste-row input[type=text], .paste-row input[type=password] {
  flex: 1; min-width: 100px; border: 1px solid var(--border); border-radius: 6px;
  padding: 0.5rem 0.75rem; font-family: var(--mono); font-size: 0.82rem;
  color: var(--text); background: var(--bg); outline: none; transition: border-color 0.15s; }
.paste-row input:focus { border-color: var(--accent); }
.btn { background: var(--accent); color: white; border: none; border-radius: 6px;
  padding: 0.5rem 1.1rem; font-family: var(--mono); font-size: 0.82rem;
  cursor: pointer; white-space: nowrap; transition: opacity 0.15s; }
.btn:hover { opacity: 0.85; }
.btn:disabled { opacity: 0.45; cursor: not-allowed; }
.btn-success { background: var(--success); }
.hint { font-size: 0.72rem; color: var(--muted); margin-top: 0.5rem; font-family: var(--mono); }
.files-card { background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius); overflow: hidden; }
.files-header { padding: 0.75rem 1.25rem; font-size: 0.75rem; font-family: var(--mono); color: var(--muted);
  letter-spacing: 0.06em; text-transform: uppercase; border-bottom: 1px solid var(--border);
  display: flex; justify-content: space-between; align-items: center; gap: 0.5rem; }
.files-header-left { display: flex; align-items: center; gap: 0.6rem; }
.select-toolbar { display: none; padding: 0.65rem 1.25rem;
  background: var(--accent-light); border-bottom: 1px solid var(--border);
  align-items: center; gap: 0.75rem; flex-wrap: wrap; }
.select-toolbar.visible { display: flex; }
.select-toolbar .sel-count { font-family: var(--mono); font-size: 0.8rem; color: var(--accent); flex: 1; }
.btn-zip { background: var(--success); }
#queue-panel { display: none; background: var(--surface); border: 1px solid var(--border);
  border-radius: var(--radius); margin-bottom: 1rem; overflow: hidden; }
#queue-panel.visible { display: block; }
.queue-header { padding: 0.65rem 1.25rem; font-size: 0.75rem; font-family: var(--mono);
  color: var(--muted); letter-spacing: 0.06em; text-transform: uppercase;
  border-bottom: 1px solid var(--border); display: flex; justify-content: space-between; align-items: center; }
.queue-list { padding: 0.5rem 0; }
.queue-item { padding: 0.5rem 1.25rem; display: flex; align-items: center; gap: 0.75rem;
  font-family: var(--mono); font-size: 0.8rem; border-bottom: 1px solid var(--border); }
.queue-item:last-child { border-bottom: none; }
.queue-item-name { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.queue-status { font-size: 0.75rem; white-space: nowrap; }
.queue-status.pending { color: var(--muted); }
.queue-status.active  { color: var(--accent); }
.queue-status.done    { color: var(--success); }
.queue-status.error   { color: var(--danger); }
.queue-mini-bar { height: 3px; background: var(--border); border-radius: 99px; overflow: hidden; margin-top: 3px; }
.queue-mini-fill { height: 100%; background: var(--accent); border-radius: 99px; width: 0%; transition: width 0.15s; }
table { width: 100%; border-collapse: collapse; }
tr { border-bottom: 1px solid var(--border); transition: background 0.1s; }
tr:last-child { border-bottom: none; }
tr:hover { background: var(--bg); }
tr.selected { background: var(--accent-light) !important; }
td { padding: 0.75rem 1.25rem; font-size: 0.88rem; vertical-align: middle; }
td.icon { width: 2rem; padding-right: 0; font-size: 1rem; }
td.check { width: 2rem; padding-right: 0; }
td.check input[type=checkbox] { width: 16px; height: 16px; cursor: pointer; accent-color: var(--accent); }
td.size { text-align: right; font-family: var(--mono); font-size: 0.78rem; color: var(--muted); white-space: nowrap; }
a { color: var(--text); text-decoration: none; }
a:hover { color: var(--accent); }
.locked { cursor: pointer; color: var(--text); }
.locked:hover { color: var(--accent); }
.empty { padding: 2.5rem; text-align: center; color: var(--muted); font-size: 0.88rem; font-family: var(--mono); }
#select-all-cb { width: 16px; height: 16px; cursor: pointer; accent-color: var(--accent); }
.modal-overlay { display: none; position: fixed; inset: 0;
  background: rgba(0,0,0,0.35); z-index: 100; align-items: center; justify-content: center; }
.modal-overlay.show { display: flex; }
.modal { background: var(--surface); border-radius: var(--radius);
  padding: 1.5rem; width: 90%; max-width: 360px; box-shadow: 0 8px 32px rgba(0,0,0,0.15); }
.modal h2 { font-family: var(--mono); font-size: 0.95rem; font-weight: 500; margin-bottom: 0.35rem; }
.modal p  { font-size: 0.8rem; color: var(--muted); margin-bottom: 1rem; font-family: var(--mono); }
.modal input { width: 100%; border: 1px solid var(--border); border-radius: 6px;
  padding: 0.6rem 0.75rem; font-family: var(--mono); font-size: 0.88rem;
  background: var(--bg); outline: none; margin-bottom: 0.75rem; transition: border-color 0.15s; }
.modal input:focus { border-color: var(--accent); }
.modal-error { font-size: 0.78rem; color: var(--danger); font-family: var(--mono);
  margin-bottom: 0.5rem; display: none; }
.modal-btns { display: flex; gap: 0.5rem; justify-content: flex-end; }
.btn-cancel { background: var(--border); color: var(--text); border: none; border-radius: 6px;
  padding: 0.5rem 1rem; font-family: var(--mono); font-size: 0.82rem; cursor: pointer; }
.toast { position: fixed; bottom: 1.5rem; left: 50%;
  transform: translateX(-50%) translateY(80px);
  background: var(--text); color: white; font-size: 0.82rem;
  font-family: var(--mono); padding: 0.6rem 1.2rem; border-radius: 99px;
  opacity: 0; transition: all 0.3s; pointer-events: none; white-space: nowrap; }
.toast.show { opacity: 1; transform: translateX(-50%) translateY(0); }
""".trimIndent()

    private val jsTemplate = """
const UPLOAD_ENDPOINT = '__UPLOAD__';
const PASTE_ENDPOINT  = '__PASTE__';
const ZIP_ENDPOINT    = '__ZIP__';
const CURRENT_PATH    = '__CURPATH__';

const dropZone      = document.getElementById('drop-zone');
const fileInput     = document.getElementById('file-input');
const progressWrap  = document.getElementById('progress-wrap');
const progressBar   = document.getElementById('progress-bar');
const progressLabel = document.getElementById('progress-label');

function showToast(msg) {
  const t = document.getElementById('toast');
  t.textContent = msg;
  t.classList.add('show');
  setTimeout(() => t.classList.remove('show'), 2800);
}

async function uploadFiles(files) {
  for (const file of files) {
    const fd = new FormData();
    fd.append('file', file);
    progressWrap.style.display = 'block';
    progressLabel.textContent = `Uploading ${'$'}{file.name}...`;
    progressBar.style.width = '0%';
    await new Promise((res) => {
      const xhr = new XMLHttpRequest();
      xhr.open('POST', UPLOAD_ENDPOINT);
      xhr.upload.onprogress = (e) => {
        if (e.lengthComputable) progressBar.style.width = (e.loaded / e.total * 100) + '%';
      };
      xhr.onload  = () => res();
      xhr.onerror = () => { showToast('Upload failed'); res(); };
      xhr.send(fd);
    });
  }
  progressWrap.style.display = 'none';
  showToast('Upload complete \u2713');
  setTimeout(() => location.reload(), 800);
}

fileInput.addEventListener('change', () => uploadFiles([...fileInput.files]));
dropZone.addEventListener('dragover',  (e) => { e.preventDefault(); dropZone.classList.add('drag-over'); });
dropZone.addEventListener('dragleave', ()  => dropZone.classList.remove('drag-over'));
dropZone.addEventListener('drop', (e) => {
  e.preventDefault(); dropZone.classList.remove('drag-over');
  uploadFiles([...e.dataTransfer.files]);
});

document.getElementById('text-header').addEventListener('click', () => {
  const body    = document.getElementById('text-body');
  const chevron = document.getElementById('chevron');
  body.classList.toggle('open');
  chevron.textContent = body.classList.contains('open') ? '\u25b2' : '\u25bc';
});

document.getElementById('save-text-btn').addEventListener('click', async () => {
  const text     = document.getElementById('paste-text').value.trim();
  const rawName  = document.getElementById('paste-name').value.trim() || 'note';
  const password = document.getElementById('paste-password').value;
  if (!text) { showToast('Nothing to save'); return; }
  const filename = rawName.endsWith('.txt') ? rawName : rawName + '.txt';
  const res = await fetch(PASTE_ENDPOINT, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ filename, text, password })
  });
  if (res.ok) {
    document.getElementById('paste-text').value     = '';
    document.getElementById('paste-name').value     = '';
    document.getElementById('paste-password').value = '';
    const icon = password ? '\uD83D\uDD12 ' : '';
    showToast(`Saved ${'$'}{icon}${'$'}{filename} \u2713`);
    setTimeout(() => location.reload(), 900);
  } else { showToast('Save failed'); }
});

const modal         = document.getElementById('pw-modal');
const modalInput    = document.getElementById('modal-pw-input');
const modalError    = document.getElementById('modal-error');
const modalFilename = document.getElementById('modal-filename');
let   pendingUrl    = null;

function openModal(url, displayName) {
  pendingUrl = url;
  modalFilename.textContent = displayName;
  modalError.style.display  = 'none';
  modalInput.value          = '';
  modal.classList.add('show');
  setTimeout(() => modalInput.focus(), 100);
}

document.getElementById('modal-cancel').addEventListener('click', () => {
  modal.classList.remove('show'); pendingUrl = null;
});
modal.addEventListener('click', (e) => {
  if (e.target === modal) { modal.classList.remove('show'); pendingUrl = null; }
});

async function submitPassword() {
  const pw = modalInput.value;
  if (!pw) { modalError.textContent = 'Enter the password'; modalError.style.display = 'block'; return; }
  const url = pendingUrl + '?pw=' + encodeURIComponent(pw);
  const res  = await fetch(url, { method: 'HEAD' });
  if (res.ok) {
    modal.classList.remove('show');
    window.location.href = url;
  } else {
    modalError.textContent = 'Wrong password';
    modalError.style.display = 'block';
    modalInput.value = '';
    modalInput.focus();
  }
}

document.getElementById('modal-submit').addEventListener('click', submitPassword);
modalInput.addEventListener('keydown', (e) => { if (e.key === 'Enter') submitPassword(); });
window.promptPassword = (url, name) => openModal(url, name);

const selectAllCb = document.getElementById('select-all-cb');
const toolbar     = document.getElementById('select-toolbar');
const selCount    = document.getElementById('sel-count');
const btnZip      = document.getElementById('btn-zip');
const btnQueue    = document.getElementById('btn-queue');

function getCheckboxes() { return [...document.querySelectorAll('.file-cb')]; }

function getCheckedItems() {
  return getCheckboxes()
    .filter(cb => cb.checked)
    .map(cb => ({ name: cb.dataset.name, isDir: cb.dataset.type === 'dir' }));
}

function getCheckedNames() { return getCheckedItems().map(i => i.name); }

function updateToolbar() {
  const items  = getCheckedItems();
  const n      = items.length;
  const hasDir = items.some(i => i.isDir);
  if (n > 0) {
    toolbar.classList.add('visible');
    const label = n === 1 ? '1 item selected' : `${'$'}{n} items selected`;
    selCount.textContent = label + (hasDir ? ' (folders \u2192 ZIP only)' : '');
    btnZip.disabled   = false;
    btnQueue.disabled = items.every(i => i.isDir);
  } else {
    toolbar.classList.remove('visible');
  }
  const all = getCheckboxes();
  selectAllCb.indeterminate = n > 0 && n < all.length;
  selectAllCb.checked = all.length > 0 && n === all.length;
}

function toggleRowHighlight(cb) {
  const row = cb.closest('tr');
  if (row) row.classList.toggle('selected', cb.checked);
}

document.addEventListener('change', (e) => {
  if (e.target.classList.contains('file-cb')) { toggleRowHighlight(e.target); updateToolbar(); }
});

selectAllCb && selectAllCb.addEventListener('change', () => {
  getCheckboxes().forEach(cb => { cb.checked = selectAllCb.checked; toggleRowHighlight(cb); });
  updateToolbar();
});

btnZip && btnZip.addEventListener('click', () => {
  const names = getCheckedNames();
  if (!names.length) return;
  const form = document.createElement('form');
  form.method = 'POST';
  form.action = ZIP_ENDPOINT;
  names.forEach(name => {
    const inp = document.createElement('input');
    inp.type = 'hidden'; inp.name = 'files'; inp.value = name;
    form.appendChild(inp);
  });
  document.body.appendChild(form);
  form.submit();
  document.body.removeChild(form);
  showToast(`Zipping ${'$'}{names.length} item(s)\u2026`);
});

const queuePanel = document.getElementById('queue-panel');

btnQueue && btnQueue.addEventListener('click', () => {
  const items = getCheckedItems().filter(i => !i.isDir);
  if (!items.length) { showToast('Select files (not folders) for queue download'); return; }
  startQueue(items.map(i => i.name));
});

async function startQueue(names) {
  queuePanel.classList.add('visible');
  const listEl = document.getElementById('queue-list');
  listEl.innerHTML = '';
  const items = names.map(name => {
    const base = (CURRENT_PATH ? '/' + CURRENT_PATH.replace(/^\/|\/$/g, '') + '/' : '/');
    const url  = base + encodeURIComponent(name);
    const div  = document.createElement('div');
    div.className = 'queue-item';
    div.innerHTML = `
      <span class="queue-item-name" title="${'$'}{escHtml(name)}">${'$'}{escHtml(name)}</span>
      <span class="queue-status pending" id="qs-${'$'}{escId(name)}">queued</span>
      <div style="width:60px">
        <div class="queue-mini-bar"><div class="queue-mini-fill" id="qb-${'$'}{escId(name)}"></div></div>
      </div>`;
    listEl.appendChild(div);
    return { name, url };
  });
  document.getElementById('queue-header-text').textContent =
    `Downloading ${'$'}{names.length} file${'$'}{names.length > 1 ? 's' : ''}\u2026`;
  for (const item of items) {
    const statusEl = document.getElementById('qs-' + escId(item.name));
    const barEl    = document.getElementById('qb-' + escId(item.name));
    if (statusEl) { statusEl.className = 'queue-status active'; statusEl.textContent = 'downloading\u2026'; }
    try {
      await downloadWithProgress(item.url, item.name, barEl);
      if (statusEl) { statusEl.className = 'queue-status done'; statusEl.textContent = '\u2713 done'; }
    } catch (err) {
      if (statusEl) { statusEl.className = 'queue-status error'; statusEl.textContent = '\u2717 error'; }
    }
    await new Promise(r => setTimeout(r, 300));
  }
  document.getElementById('queue-header-text').textContent = 'Downloads complete \u2713';
  showToast(`Downloaded ${'$'}{names.length} file(s) \u2713`);
}

function escHtml(s) {
  return s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
}
function escId(s) { return s.replace(/[^a-zA-Z0-9]/g, '_'); }

function downloadWithProgress(url, filename, barEl) {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open('GET', url, true);
    xhr.responseType = 'blob';
    xhr.onprogress = (e) => {
      if (e.lengthComputable && barEl) barEl.style.width = (e.loaded / e.total * 100) + '%';
    };
    xhr.onload = () => {
      if (xhr.status === 200) {
        if (barEl) barEl.style.width = '100%';
        const a = document.createElement('a');
        a.href = URL.createObjectURL(xhr.response);
        a.download = filename;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        setTimeout(() => URL.revokeObjectURL(a.href), 10000);
        resolve();
      } else { reject(new Error('HTTP ' + xhr.status)); }
    };
    xhr.onerror = () => reject(new Error('Network error'));
    xhr.send();
  });
}
""".trimIndent()

    fun renderPage(files: List<FileEntry>, currentPath: String, protectedFiles: Set<String>): String {
        val folderTitle = if (currentPath.isBlank()) "Swap" else currentPath.substringAfterLast('/')
        val breadcrumb = buildBreadcrumb(currentPath)
        val rows = buildRows(files, currentPath, protectedFiles)
        val selectable = files.count { it.isDirectory || it.relativePath !in protectedFiles }
        val selectAllCb = if (selectable > 0) "<input type=\"checkbox\" id=\"select-all-cb\" title=\"Select all\">" else ""
        val tableOrEmpty = if (files.isEmpty() && currentPath.isBlank()) {
            "<div class=\"empty\">Empty folder</div>"
        } else {
            val bodyRows = if (rows.isBlank()) "" else rows
            "<table>$bodyRows</table>"
        }

        val encodedCurrent = FileUtils.encodePathSegments(currentPath)
        val uploadEndpoint = "/upload" + if (encodedCurrent.isBlank()) "/" else "/$encodedCurrent"
        val pasteEndpoint = "/paste" + if (encodedCurrent.isBlank()) "/" else "/$encodedCurrent"
        val zipEndpoint = "/zip" + if (encodedCurrent.isBlank()) "/" else "/$encodedCurrent"
        val js = jsTemplate
            .replace("__UPLOAD__", uploadEndpoint)
            .replace("__PASTE__", pasteEndpoint)
            .replace("__ZIP__", zipEndpoint)
            .replace("__CURPATH__", encodedCurrent)

        return """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>${escapeHtml(folderTitle)}</title>
<style>$css</style>
</head>
<body>
<div class="container">
  <header>
    <div class="breadcrumb">$breadcrumb</div>
    <div class="header-row">
      <h1>📂 ${escapeHtml(folderTitle)}</h1>
      <span class="count">${files.size} items</span>
    </div>
  </header>
  <div class="upload-zone" id="drop-zone">
    <input type="file" id="file-input" multiple>
    <span class="upload-icon">⬆️</span>
    <p class="upload-label"><strong>Click to upload</strong> or drag &amp; drop</p>
    <p class="upload-label" style="margin-top:0.25rem;font-size:0.75rem;">Saves to this folder</p>
  </div>
  <div id="progress-wrap">
    <div id="progress-label">Uploading…</div>
    <div id="progress-bar-bg"><div id="progress-bar"></div></div>
  </div>
  <div class="card">
    <div class="card-header" id="text-header">
      <span>📋 Paste text</span><span id="chevron">▼</span>
    </div>
    <div class="card-body" id="text-body">
      <textarea id="paste-text" placeholder="Paste anything — a link, note, code snippet…"></textarea>
      <div class="paste-row">
        <input type="text"     id="paste-name"     placeholder="filename (optional)">
        <input type="password" id="paste-password" placeholder="password (optional)">
        <button class="btn" id="save-text-btn">Save</button>
      </div>
      <p class="hint">Leave password blank to save without protection.</p>
    </div>
  </div>
  <div id="queue-panel">
    <div class="queue-header">
      <span id="queue-header-text">Download queue</span>
      <span style="cursor:pointer;font-size:0.9rem;" onclick="document.getElementById('queue-panel').classList.remove('visible')">✕</span>
    </div>
    <div class="queue-list" id="queue-list"></div>
  </div>
  <div class="files-card">
    <div class="files-header">
      <div class="files-header-left">$selectAllCb<span>Name</span></div>
      <span>Size</span>
    </div>
    <div class="select-toolbar" id="select-toolbar">
      <span class="sel-count" id="sel-count">0 selected</span>
      <button class="btn btn-zip" id="btn-zip" disabled>⬇️ Download as ZIP</button>
      <button class="btn" id="btn-queue" disabled>📋 Queue download</button>
    </div>
    $tableOrEmpty
  </div>
</div>
<div class="modal-overlay" id="pw-modal">
  <div class="modal">
    <h2>🔒 Protected file</h2>
    <p id="modal-filename"></p>
    <input type="password" id="modal-pw-input" placeholder="Enter password">
    <div class="modal-error" id="modal-error"></div>
    <div class="modal-btns">
      <button class="btn-cancel" id="modal-cancel">Cancel</button>
      <button class="btn" id="modal-submit">Download</button>
    </div>
  </div>
</div>
<div class="toast" id="toast"></div>
<script>$js</script>
</body>
</html>
""".trimIndent()
    }

    private fun buildBreadcrumb(currentPath: String): String {
        if (currentPath.isBlank()) {
            return "<a href=\"/\">Swap</a>"
        }
        val segments = currentPath.split('/').filter { it.isNotBlank() }
        val out = mutableListOf<String>()
        out.add("<a href=\"/\">Swap</a>")
        var accum = ""
        for (seg in segments) {
            accum = if (accum.isBlank()) seg else "$accum/$seg"
            out.add("<span>/</span>")
            out.add("<a href=\"/${encodeSegments(accum)}\">${escapeHtml(seg)}</a>")
        }
        return out.joinToString("")
    }

    private fun buildRows(files: List<FileEntry>, currentPath: String, protectedFiles: Set<String>): String {
        val rows = StringBuilder()
        if (currentPath.isNotBlank()) {
            val parent = currentPath.substringBeforeLast('/', "")
            val parentUrl = if (parent.isBlank()) "/" else "/${encodeSegments(parent)}"
            rows.append("<tr><td class=\"check\"></td><td class=\"icon\">⬆️</td><td><a href=\"$parentUrl\">..</a></td><td></td></tr>")
        }
        for (entry in files.sortedWith(compareBy<FileEntry> { !it.isDirectory }.thenBy { it.name.lowercase(Locale.getDefault()) })) {
            val nameHtml = escapeHtml(entry.name)
            val encodedPath = encodeSegments(entry.relativePath)
            if (entry.isDirectory) {
                val url = "/$encodedPath"
                rows.append("<tr><td class=\"check\"><input type=\"checkbox\" class=\"file-cb\" data-name=\"$nameHtml\" data-type=\"dir\"></td><td class=\"icon\">📁</td><td><a href=\"$url\">$nameHtml/</a></td><td class=\"size\">—</td></tr>")
            } else {
                val url = "/$encodedPath"
                if (entry.relativePath in protectedFiles) {
                    rows.append("<tr><td class=\"check\"></td><td class=\"icon\">🔒</td><td><span class=\"locked\" onclick=\"promptPassword('$url','$nameHtml')\">$nameHtml</span></td><td class=\"size\">${FileUtils.formatSize(entry.size)}</td></tr>")
                } else {
                    rows.append("<tr><td class=\"check\"><input type=\"checkbox\" class=\"file-cb\" data-name=\"$nameHtml\"></td><td class=\"icon\">${FileUtils.iconFor(entry.name)}</td><td><a href=\"$url\" download=\"$nameHtml\">$nameHtml</a></td><td class=\"size\">${FileUtils.formatSize(entry.size)}</td></tr>")
                }
            }
        }
        return rows.toString()
    }

    private fun encodeSegments(path: String): String {
        return path.split('/').filter { it.isNotBlank() }.joinToString("/") {
            URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }
    }

    private fun escapeHtml(input: String): String = TextUtils.htmlEncode(input)
}
