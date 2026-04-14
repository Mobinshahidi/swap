#!/usr/bin/env python3
"""
LAN Share — Android APK version
Kivy UI + embedded HTTP file server.
No Termux required. Works standalone on Android.
"""

import io
import json
import hashlib
import html
import mimetypes
import secrets
import socket
import threading
import webbrowser
import zipfile
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from socketserver import ThreadingMixIn
from urllib.parse import parse_qs, unquote, urlparse

from kivy.app import App
from kivy.clock import Clock
from kivy.core.window import Window
from kivy.metrics import dp
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.button import Button
from kivy.uix.label import Label
from kivy.utils import platform

# ── Platform-aware shared folder ──────────────────────────────────────────────
def get_share_dir() -> Path:
    if platform == "android":
        try:
            from android.storage import primary_external_storage_path  # type: ignore
            return Path(primary_external_storage_path()) / "LanShare"
        except Exception:
            pass
        # Fallback: use app-private files dir (always writable)
        from android import mActivity  # type: ignore
        ctx = mActivity.getApplicationContext()
        return Path(str(ctx.getExternalFilesDir(None))) / "LanShare"
    return Path.home() / "LanShare"


SHARE_DIR = get_share_dir()
PASSWD_DB = SHARE_DIR / ".passwords.json"
PORT      = 1390


# ── HTTP Server ───────────────────────────────────────────────────────────────
class ThreadingHTTPServer(ThreadingMixIn, HTTPServer):
    daemon_threads    = True
    allow_reuse_address = True


# ── Helpers ───────────────────────────────────────────────────────────────────
def get_local_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))
        return s.getsockname()[0]
    except Exception:
        return "127.0.0.1"
    finally:
        s.close()


def human_size(n):
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024:
            return f"{n:.1f} {unit}"
        n /= 1024
    return f"{n:.1f} TB"


def get_icon(name):
    ext = Path(name).suffix.lower()
    icons = {
        ".jpg": "🖼️", ".jpeg": "🖼️", ".png": "🖼️", ".gif": "🖼️",
        ".webp": "🖼️", ".heic": "🖼️",
        ".mp4": "🎬", ".mov": "🎬", ".avi": "🎬", ".mkv": "🎬",
        ".mp3": "🎵", ".wav": "🎵", ".flac": "🎵", ".m4a": "🎵",
        ".pdf": "📄", ".doc": "📝", ".docx": "📝", ".txt": "📃",
        ".zip": "🗜️", ".tar": "🗜️", ".gz": "🗜️", ".rar": "🗜️",
        ".py": "🐍", ".js": "📜", ".html": "🌐", ".css": "🎨",
    }
    return icons.get(ext, "📄")


# ── Password DB ───────────────────────────────────────────────────────────────
def hash_password(password: str, salt: str) -> str:
    return hashlib.sha256(f"{salt}{password}".encode()).hexdigest()


def load_passwd_db() -> dict:
    try:
        return json.loads(PASSWD_DB.read_text())
    except Exception:
        return {}


def save_passwd_db(db: dict):
    PASSWD_DB.write_text(json.dumps(db, indent=2))


def set_file_password(rel_path: str, password: str):
    db   = load_passwd_db()
    salt = secrets.token_hex(16)
    db[rel_path] = {"salt": salt, "hash": hash_password(password, salt)}
    save_passwd_db(db)


def check_file_password(rel_path: str, password: str) -> bool:
    db    = load_passwd_db()
    entry = db.get(rel_path)
    if not entry:
        return True
    return hash_password(password, entry["salt"]) == entry["hash"]


def is_protected(rel_path: str) -> bool:
    return rel_path in load_passwd_db()


# ── CSS ───────────────────────────────────────────────────────────────────────
CSS = """
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
    color: var(--text); background: var(--bg); resize: vertical; outline: none; }
  .paste-row { display: flex; gap: 0.5rem; margin-top: 0.75rem; align-items: center; flex-wrap: wrap; }
  .paste-row input[type=text], .paste-row input[type=password] {
    flex: 1; min-width: 100px; border: 1px solid var(--border); border-radius: 6px;
    padding: 0.5rem 0.75rem; font-family: var(--mono); font-size: 0.82rem;
    color: var(--text); background: var(--bg); outline: none; }
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
    background: var(--bg); outline: none; margin-bottom: 0.75rem; }
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
"""

# ── JS ────────────────────────────────────────────────────────────────────────
JS = r"""
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
      progressLabel.textContent = `Uploading ${file.name}...`;
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
      showToast(`Saved ${icon}${filename} \u2713`);
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
      const label = n === 1 ? '1 item selected' : `${n} items selected`;
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
    showToast(`Zipping ${names.length} item(s)\u2026`);
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
        <span class="queue-item-name" title="${escHtml(name)}">${escHtml(name)}</span>
        <span class="queue-status pending" id="qs-${escId(name)}">queued</span>
        <div style="width:60px">
          <div class="queue-mini-bar"><div class="queue-mini-fill" id="qb-${escId(name)}"></div></div>
        </div>`;
      listEl.appendChild(div);
      return { name, url };
    });
    document.getElementById('queue-header-text').textContent =
      `Downloading ${names.length} file${names.length > 1 ? 's' : ''}\u2026`;
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
    showToast(`Downloaded ${names.length} file(s) \u2713`);
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
"""

# ── HTML renderer ─────────────────────────────────────────────────────────────
def render_page(files, current_path="", protected_set=None):
    if protected_set is None:
        protected_set = set()

    from urllib.parse import quote as urlquote

    parts = [p for p in current_path.split("/") if p]

    breadcrumb = '<a href="/">LanShare</a>'
    for i, part in enumerate(parts):
        href = "/" + "/".join(urlquote(p, safe="") for p in parts[:i + 1]) + "/"
        breadcrumb += f' <span>/</span> <a href="{html.escape(href)}">{html.escape(part)}</a>'

    back_row = ""
    if parts:
        parent = "/" + "/".join(urlquote(p, safe="") for p in parts[:-1])
        if parent != "/":
            parent += "/"
        back_row = (
            f'<tr><td class="check"></td><td class="icon">⬆️</td>'
            f'<td><a href="{html.escape(parent)}">..</a></td><td></td></tr>'
        )

    base            = ("/" + current_path.strip("/") + "/").replace("//", "/")
    upload_endpoint = "/upload" + base
    paste_endpoint  = "/paste"  + base
    zip_endpoint    = "/zip"    + base

    file_count_downloadable = sum(
        1 for f in files
        if not ((current_path.strip("/") + "/" + f["name"]).strip("/") in protected_set
                and not f["is_dir"])
    )

    rows = back_row
    for f in files:
        ne      = html.escape(f["name"])
        ne_url  = urlquote(f["name"], safe="")
        size    = f["size"]
        rel_key = (current_path.strip("/") + "/" + f["name"]).strip("/")
        locked  = rel_key in protected_set

        if f["is_dir"]:
            cb_id = f"cb-{html.escape(f['name'])}"
            rows += (
                f'<tr>'
                f'<td class="check"><input type="checkbox" class="file-cb" id="{cb_id}"'
                f' data-name="{ne}" data-type="dir"></td>'
                f'<td class="icon">📁</td>'
                f'<td><a href="{base}{ne_url}/">{ne}/</a></td>'
                f'<td class="size">—</td></tr>'
            )
        elif locked:
            file_url = base + ne_url
            rows += (
                f'<tr>'
                f'<td class="check"></td>'
                f'<td class="icon">🔒</td>'
                f'<td><span class="locked" onclick="promptPassword(\'{file_url}\',\'{ne}\')">{ne}</span></td>'
                f'<td class="size">{size}</td></tr>'
            )
        else:
            icon  = get_icon(f["name"])
            cb_id = f"cb-{html.escape(f['name'])}"
            rows += (
                f'<tr>'
                f'<td class="check"><input type="checkbox" class="file-cb" id="{cb_id}"'
                f' data-name="{ne}"></td>'
                f'<td class="icon">{icon}</td>'
                f'<td><a href="{base}{ne_url}" download="{ne}">{ne}</a></td>'
                f'<td class="size">{size}</td></tr>'
            )

    folder_title = parts[-1] if parts else "LanShare"
    file_count   = len(files)
    has_content  = bool(files) or bool(back_row)

    safe_curpath = current_path.strip("/")
    js = (JS
          .replace("'__UPLOAD__'", f"'{upload_endpoint}'")
          .replace("'__PASTE__'",  f"'{paste_endpoint}'")
          .replace("'__ZIP__'",    f"'{zip_endpoint}'")
          .replace("'__CURPATH__'",f"'{safe_curpath}'"))

    select_all_cb = (
        '<input type="checkbox" id="select-all-cb" title="Select all">'
        if file_count_downloadable else ""
    )

    return f"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>{html.escape(folder_title)}</title>
<style>{CSS}</style>
</head>
<body>
<div class="container">
  <header>
    <div class="breadcrumb">{breadcrumb}</div>
    <div class="header-row">
      <h1>📂 {html.escape(folder_title)}</h1>
      <span class="count">{file_count} items</span>
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
      <span style="cursor:pointer;font-size:0.9rem;"
            onclick="document.getElementById('queue-panel').classList.remove('visible')">✕</span>
    </div>
    <div class="queue-list" id="queue-list"></div>
  </div>
  <div class="files-card">
    <div class="files-header">
      <div class="files-header-left">{select_all_cb}<span>Name</span></div>
      <span>Size</span>
    </div>
    <div class="select-toolbar" id="select-toolbar">
      <span class="sel-count" id="sel-count">0 selected</span>
      <button class="btn btn-zip" id="btn-zip" disabled>⬇️ Download as ZIP</button>
      <button class="btn" id="btn-queue" disabled>📋 Queue download</button>
    </div>
    {"<table>" + rows + "</table>" if has_content else '<div class="empty">Empty folder</div>'}
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
<script>{js}</script>
</body>
</html>"""


# ── Request Handler ────────────────────────────────────────────────────────────
class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        pass  # silence request logs on Android

    def resolve_path(self, url_path):
        rel    = unquote(url_path).lstrip("/")
        target = (SHARE_DIR / rel).resolve()
        try:
            target.relative_to(SHARE_DIR.resolve())
            return target
        except ValueError:
            return None

    def rel_key(self, file_path: Path) -> str:
        try:
            return str(file_path.resolve().relative_to(SHARE_DIR.resolve()))
        except ValueError:
            return ""

    def do_GET(self):
        parsed   = urlparse(self.path)
        url_path = parsed.path
        params   = parse_qs(parsed.query)
        target   = self.resolve_path(url_path)

        if target is None:
            self.send_error(403, "Forbidden"); return

        if target.is_dir():
            self.serve_dir(target)
        elif target.is_file():
            key = self.rel_key(target)
            if is_protected(key):
                pw = params.get("pw", [None])[0]
                if not pw or not check_file_password(key, pw):
                    if self.command == "HEAD":
                        self.send_response(401); self.end_headers()
                    else:
                        self.send_error(401, "Wrong password")
                    return
            self.serve_file(target)
        else:
            self.send_error(404, "Not found")

    def do_HEAD(self):
        self.do_GET()

    def serve_dir(self, directory):
        items = []
        db    = load_passwd_db()
        try:
            for entry in sorted(directory.iterdir(),
                                 key=lambda e: (e.is_file(), e.name.lower())):
                if entry.name == ".passwords.json":
                    continue
                items.append({
                    "name":   entry.name,
                    "size":   human_size(entry.stat().st_size) if entry.is_file() else "",
                    "is_dir": entry.is_dir(),
                })
        except Exception:
            items = []

        try:
            rel = str(directory.resolve().relative_to(SHARE_DIR.resolve()))
            if rel == ".": rel = ""
        except ValueError:
            rel = ""

        body = render_page(items, rel, protected_set=set(db.keys())).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        try:    self.wfile.write(body)
        except BrokenPipeError: pass

    def serve_file(self, path):
        from urllib.parse import quote as urlquote
        mime, _ = mimetypes.guess_type(str(path))
        mime     = mime or "application/octet-stream"
        ascii_name  = path.name.encode("ascii", "replace").decode("ascii")
        utf8_name   = urlquote(path.name, safe="")
        disposition = f"attachment; filename=\"{ascii_name}\"; filename*=UTF-8''{utf8_name}"
        self.send_response(200)
        self.send_header("Content-Type", mime)
        self.send_header("Content-Length", str(path.stat().st_size))
        self.send_header("Content-Disposition", disposition)
        self.end_headers()
        if self.command == "HEAD": return
        try:
            with open(path, "rb") as f:
                while chunk := f.read(65536):
                    self.wfile.write(chunk)
        except BrokenPipeError: pass

    def do_POST(self):
        from urllib.parse import quote as urlquote
        url_path = urlparse(self.path).path

        # ── ZIP ──────────────────────────────────────────────────────────────
        if url_path.startswith("/zip"):
            rel        = unquote(url_path[4:]).strip("/")
            target_dir = (SHARE_DIR / rel).resolve() if rel else SHARE_DIR.resolve()
            try:    target_dir.relative_to(SHARE_DIR.resolve())
            except ValueError:
                self.send_error(403, "Forbidden"); return

            length = int(self.headers.get("Content-Length", 0))
            body   = self.rfile.read(length).decode("utf-8", errors="replace")
            from urllib.parse import parse_qs as pqs
            names  = pqs(body).get("files", [])
            if not names:
                self.send_error(400, "No files specified"); return

            buf   = io.BytesIO()
            added = 0
            with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as zf:
                for name in names:
                    safe  = Path(name).name
                    fpath = (target_dir / safe).resolve()
                    try:    fpath.relative_to(SHARE_DIR.resolve())
                    except ValueError: continue
                    if fpath.is_file():
                        if is_protected(self.rel_key(fpath)): continue
                        zf.write(fpath, safe); added += 1
                    elif fpath.is_dir():
                        for child in sorted(fpath.rglob("*")):
                            if not child.is_file(): continue
                            if child.name == ".passwords.json": continue
                            if is_protected(self.rel_key(child)): continue
                            zf.write(child, safe + "/" + str(child.relative_to(fpath)))
                            added += 1

            if added == 0:
                self.send_error(404, "No downloadable files found"); return

            zip_bytes = buf.getvalue()
            zip_name  = (names[0].rstrip("/") + ".zip") if len(names) == 1 else "files.zip"
            ascii_z   = zip_name.encode("ascii", "replace").decode("ascii")
            utf8_z    = urlquote(zip_name, safe="")
            zip_disp  = f"attachment; filename=\"{ascii_z}\"; filename*=UTF-8''{utf8_z}"

            self.send_response(200)
            self.send_header("Content-Type", "application/zip")
            self.send_header("Content-Length", str(len(zip_bytes)))
            self.send_header("Content-Disposition", zip_disp)
            self.end_headers()
            try:    self.wfile.write(zip_bytes)
            except BrokenPipeError: pass
            return

        # ── Paste text ───────────────────────────────────────────────────────
        if url_path.startswith("/paste"):
            rel        = unquote(url_path[6:]).strip("/")
            target_dir = (SHARE_DIR / rel).resolve() if rel else SHARE_DIR.resolve()
            try:    target_dir.relative_to(SHARE_DIR.resolve())
            except ValueError:
                self.send_error(403, "Forbidden"); return

            length   = int(self.headers.get("Content-Length", 0))
            payload  = json.loads(self.rfile.read(length))
            text     = payload.get("text", "")
            name     = Path(payload.get("filename", "note.txt")).name
            password = payload.get("password", "").strip()
            if not name.endswith(".txt"): name += ".txt"

            target_dir.mkdir(parents=True, exist_ok=True)
            dest    = target_dir / name
            counter = 1
            stem    = Path(name).stem
            while dest.exists():
                dest = target_dir / f"{stem}_{counter}.txt"; counter += 1
            dest.write_text(text, encoding="utf-8")

            if password:
                set_file_password(self.rel_key(dest), password)

            resp = json.dumps({"saved": dest.name}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(resp)))
            self.end_headers()
            self.wfile.write(resp)
            return

        # ── Upload ───────────────────────────────────────────────────────────
        if url_path.startswith("/upload"):
            rel        = unquote(url_path[7:]).strip("/")
            upload_dir = (SHARE_DIR / rel).resolve() if rel else SHARE_DIR.resolve()
            try:    upload_dir.relative_to(SHARE_DIR.resolve())
            except ValueError:
                self.send_error(403, "Forbidden"); return

            upload_dir.mkdir(parents=True, exist_ok=True)
            content_type = self.headers.get("Content-Type", "")
            if "multipart/form-data" not in content_type:
                self.send_error(400, "Expected multipart/form-data"); return

            boundary = None
            for part in content_type.split(";"):
                part = part.strip()
                if part.startswith("boundary="):
                    boundary = part[9:].strip().encode(); break
            if not boundary:
                self.send_error(400, "No boundary"); return

            length = int(self.headers.get("Content-Length", 0))
            body   = self.rfile.read(length)
            parts  = body.split(b"--" + boundary)

            saved = 0
            for part in parts:
                if b"Content-Disposition" not in part: continue
                if b"\r\n\r\n" not in part: continue
                headers_raw, content = part.split(b"\r\n\r\n", 1)
                content     = content.rstrip(b"\r\n--")
                headers_str = headers_raw.decode(errors="replace")
                filename    = None
                for line in headers_str.split("\r\n"):
                    if "filename=" in line:
                        filename = line.split("filename=")[-1].strip().strip('"'); break
                if not filename or not content: continue
                filename = Path(filename).name
                dest     = upload_dir / filename
                counter  = 1
                stem, suffix = Path(filename).stem, Path(filename).suffix
                while dest.exists():
                    dest = upload_dir / f"{stem}_{counter}{suffix}"; counter += 1
                dest.write_bytes(content)
                saved += 1

            resp = json.dumps({"saved": saved}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(resp)))
            self.end_headers()
            self.wfile.write(resp)
            return

        self.send_error(404)


# ── Kivy App ──────────────────────────────────────────────────────────────────
class LanShareApp(App):
    def __init__(self, **kwargs):
        super().__init__(**kwargs)
        self.server     = None
        self.server_url = ""

    def build(self):
        self.title = "LAN Share"
        Window.clearcolor = (0.97, 0.96, 0.95, 1)

        root = BoxLayout(orientation="vertical", padding=dp(24), spacing=dp(16))

        # Title
        root.add_widget(Label(
            text="📂 LAN Share",
            font_size="26sp",
            bold=True,
            color=(0.1, 0.1, 0.1, 1),
            size_hint=(1, None),
            height=dp(48),
            halign="center",
        ))

        # URL display
        self.url_label = Label(
            text="Starting…",
            font_size="22sp",
            bold=True,
            color=(0.16, 0.43, 0.96, 1),
            size_hint=(1, None),
            height=dp(60),
            halign="center",
            valign="middle",
        )
        self.url_label.bind(size=lambda w, _: setattr(w, "text_size", w.size))
        root.add_widget(self.url_label)

        # Hint
        self.hint_label = Label(
            text="Open this address in any browser on the same Wi-Fi",
            font_size="14sp",
            color=(0.53, 0.53, 0.5, 1),
            size_hint=(1, None),
            height=dp(32),
            halign="center",
        )
        root.add_widget(self.hint_label)

        # Open in browser button
        self.open_btn = Button(
            text="🌐  Open in Browser",
            size_hint=(1, None),
            height=dp(56),
            background_normal="",
            background_color=(0.16, 0.43, 0.96, 1),
            color=(1, 1, 1, 1),
            font_size="16sp",
            disabled=True,
        )
        self.open_btn.bind(on_press=self.open_in_browser)
        root.add_widget(self.open_btn)

        # Folder path
        self.path_label = Label(
            text=f"Shared folder:\n{SHARE_DIR}",
            font_size="12sp",
            color=(0.4, 0.4, 0.4, 1),
            size_hint=(1, None),
            height=dp(52),
            halign="center",
            valign="top",
        )
        self.path_label.bind(size=lambda w, _: setattr(w, "text_size", w.size))
        root.add_widget(self.path_label)

        return root

    def on_start(self):
        if platform == "android":
            self._request_permissions()
        else:
            Clock.schedule_once(lambda dt: self._start_server(), 0.1)

    def _request_permissions(self):
        try:
            from android.permissions import (  # type: ignore
                Permission, check_permission, request_permissions
            )
            needed = [
                Permission.READ_EXTERNAL_STORAGE,
                Permission.WRITE_EXTERNAL_STORAGE,
            ]
            if all(check_permission(p) for p in needed):
                Clock.schedule_once(lambda dt: self._start_server(), 0.1)
                return

            def _cb(permissions, grants):
                Clock.schedule_once(lambda dt: self._start_server(), 0.1)

            request_permissions(needed, _cb)
        except Exception as e:
            self.url_label.text = f"Permission error:\n{e}"

    def _start_server(self):
        if self.server is not None:
            return
        try:
            SHARE_DIR.mkdir(parents=True, exist_ok=True)
        except Exception as e:
            self.url_label.text = f"Cannot create folder:\n{e}"
            return

        ip  = get_local_ip()
        self.server_url = f"http://{ip}:{PORT}"

        try:
            self.server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
        except Exception as e:
            self.url_label.text = f"Server error:\n{e}"
            return

        threading.Thread(target=self.server.serve_forever, daemon=True).start()

        self.url_label.text    = self.server_url
        self.path_label.text   = f"Shared folder:\n{SHARE_DIR}"
        self.open_btn.disabled = False

    def open_in_browser(self, *_):
        if not self.server_url:
            return
        if platform == "android":
            try:
                from jnius import autoclass  # type: ignore
                Intent          = autoclass("android.content.Intent")
                Uri             = autoclass("android.net.Uri")
                PythonActivity  = autoclass("org.kivy.android.PythonActivity")
                intent = Intent(Intent.ACTION_VIEW, Uri.parse(self.server_url))
                PythonActivity.mActivity.startActivity(intent)
                return
            except Exception:
                pass
        webbrowser.open(self.server_url)

    def on_stop(self):
        if self.server:
            self.server.shutdown()
            self.server.server_close()


if __name__ == "__main__":
    LanShareApp().run()
