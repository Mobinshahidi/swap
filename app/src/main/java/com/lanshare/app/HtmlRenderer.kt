package com.lanshare.app

import android.text.TextUtils
import java.net.URLEncoder
import java.util.Locale

object HtmlRenderer {

private val css = """
*, *::before, *::after { box-sizing: border-box                          ; margin: 0; padding: 0; }
:root {
--bg:                                                                    #f9f1ec; --surface: #f9f1ec; --border: #e0c9bd;
--text:                                                                  #1e1e1d; --muted: #8f7364; --accent: #d57455;
--accent-deep:                                                           #a85536; --accent-light: #f0d9cd; --danger: #c1503f; --success: #4a8a63;
--input-bg:                                                              #f9f1ec;
--radius: 10px                                                           ;
--mono: ui-monospace, 'Menlo', 'Cascadia Code', monospace                ;
--sans: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif        ;
}
body { background: var(--bg)                                             ; color: var(--text); font-family: var(--sans);
font-weight: 300                                                         ; min-height: 100vh; padding: 2rem 1rem; }
.container { max-width: 680px                                            ; margin: 0 auto; }
.tools-card { background: var(--surface)                                 ; border: 1px solid var(--border); border-radius: var(--radius); margin-bottom: 1rem; overflow: hidden; }
.tools-body { padding: 0.9rem 1.25rem                                    ; }
.top-tools { display: flex                                               ; gap: 0.5rem; flex-wrap: wrap; }
.tool-buttons { display: flex                                            ; gap: 0.5rem; flex-wrap: wrap; margin-top: 0.6rem; padding-top: 0.6rem; border-top: 1px solid var(--border); }
.tool-input { flex: 1                                                    ; min-width: 170px; border: 1px solid var(--border); border-radius: 8px; padding: 0.55rem 0.75rem; font-family: var(--mono); font-size: 0.82rem; background: var(--input-bg); color: var(--text); outline: none; }
.tool-select { border: 1px solid var(--border)                           ; border-radius: 8px; padding: 0.55rem 0.65rem; font-family: var(--mono); font-size: 0.82rem; background: var(--input-bg); color: var(--text); }
header { margin-bottom: 2rem                                             ; padding-bottom: 1.25rem; border-bottom: 1px solid var(--border); }
.breadcrumb { font-family: var(--mono)                                   ; font-size: 0.82rem; color: var(--muted);
margin-bottom: 0.5rem                                                    ; display: flex; align-items: center; gap: 0.3rem; flex-wrap: wrap; }
.breadcrumb a { color: var(--accent)                                     ; text-decoration: none; }
.breadcrumb a:hover { text-decoration: underline                         ; }
.breadcrumb span { color: var(--border)                                  ; }
.header-row { display: flex                                              ; align-items: baseline; gap: 0.75rem; }
header h1 { font-family: var(--mono)                                     ; font-size: 1.1rem; font-weight: 500; letter-spacing: -0.02em; }
header .count { font-size: 0.78rem                                       ; color: var(--muted); font-family: var(--mono); }
.upload-zone {
border: 1.5px dashed var(--border)                                       ; border-radius: var(--radius);
padding: 2rem                                                            ; text-align: center; cursor: pointer;
transition: all 0.18s                                                    ; background: var(--surface); margin-bottom: 1rem; position: relative;
}
.upload-zone:hover, .upload-zone.drag-over { border-color: var(--accent) ; background: var(--accent-light); }
.upload-zone input[type=file] { position: absolute                       ; inset: 0; opacity: 0; cursor: pointer; width: 100%; height: 100%; }
.upload-icon { font-size: 1.75rem                                        ; margin-bottom: 0.5rem; display: block; }
.upload-label { font-size: 0.9rem                                        ; color: var(--muted); }
.upload-label strong { color: var(--accent)                              ; font-weight: 500; }
#progress-wrap { display: none; margin-bottom: 1rem; background: var(--surface);
border: 1px solid var(--border)                                          ; border-radius: var(--radius); padding: 1rem 1.25rem; }
#progress-label { font-size: 0.8rem; font-family: var(--mono); color: var(--muted); margin-bottom: 0.5rem; }
#progress-bar-bg { background: var(--border); border-radius: 99px; height: 4px; overflow: hidden; }
#progress-bar { height: 100%; background: var(--accent); border-radius: 99px; width: 0%; transition: width 0.15s; }
.card { background: var(--surface)                                       ; border: 1px solid var(--border);
border-radius: var(--radius)                                             ; margin-bottom: 1rem; overflow: hidden; }
.card-header { padding: 0.7rem 1.25rem                                   ; font-size: 0.75rem; font-family: var(--mono);
color: var(--muted)                                                      ; letter-spacing: 0.06em; text-transform: uppercase;
border-bottom: 1px solid var(--border)                                   ; cursor: pointer;
display: flex                                                            ; justify-content: space-between; align-items: center; user-select: none; }
.card-header:hover { background: var(--bg)                               ; }
.card-body { padding: 1rem 1.25rem                                       ; display: none; }
.card-body.open { display: block                                         ; }
#paste-text { width: 100%; min-height: 90px; border: 1px solid var(--border); border-radius: 6px;
padding: 0.75rem                                                         ; font-family: var(--mono); font-size: 0.82rem;
color: var(--text)                                                       ; background: var(--bg); resize: vertical; outline: none; transition: border-color 0.15s; }
#paste-text:focus { border-color: var(--accent); }
.paste-row { display: flex                                               ; gap: 0.5rem; margin-top: 0.75rem; align-items: center; flex-wrap: wrap; }
.paste-row input[type=text], .paste-row input[type=password] {
flex: 1                                                                  ; min-width: 100px; border: 1px solid var(--border); border-radius: 6px;
padding: 0.5rem 0.75rem                                                  ; font-family: var(--mono); font-size: 0.82rem;
color: var(--text)                                                       ; background: var(--bg); outline: none; transition: border-color 0.15s; }
.paste-row input:focus { border-color: var(--accent)                     ; }
.btn { background: var(--accent)                                         ; color: white; border: none; border-radius: 6px;
padding: 0.5rem 1.1rem                                                   ; font-family: var(--mono); font-size: 0.82rem;
cursor: pointer                                                          ; white-space: nowrap; transition: opacity 0.15s; }
.btn:hover { background: var(--accent-deep)                              ; }
.btn-success:hover, .btn-zip:hover { background: var(--success)          ; opacity: 0.85; }
.btn-danger:hover { background: var(--danger)                            ; opacity: 0.85; }
.btn:disabled { opacity: 0.45                                            ; cursor: not-allowed; }
.btn-success { background: var(--success)                                ; }
.btn-danger { background: var(--danger)                                  ; }
.hint { font-size: 0.72rem                                               ; color: var(--muted); margin-top: 0.5rem; font-family: var(--mono); }
.files-card { background: var(--surface)                                 ; border: 1px solid var(--border); border-radius: var(--radius); overflow: hidden; }
.files-header { padding: 0.75rem 1.25rem                                 ; font-size: 0.75rem; font-family: var(--mono); color: var(--muted);
letter-spacing: 0.06em                                                   ; text-transform: uppercase; border-bottom: 1px solid var(--border);
display: flex                                                            ; justify-content: space-between; align-items: center; gap: 0.5rem; }
.files-header-left { display: flex                                       ; align-items: center; gap: 0.6rem; }
.select-toolbar { display: none                                          ; padding: 0.65rem 1.25rem;
background: var(--accent-light)                                          ; border-bottom: 1px solid var(--border);
align-items: center                                                      ; gap: 0.75rem; flex-wrap: wrap; }
.select-toolbar.visible { display: flex                                  ; }
.select-toolbar .sel-count { font-family: var(--mono)                    ; font-size: 0.8rem; color: var(--accent); flex: 1; }
.btn-zip { background: var(--success)                                    ; }
#queue-panel { display: none; background: var(--surface); border: 1px solid var(--border);
border-radius: var(--radius)                                             ; margin-bottom: 1rem; overflow: hidden; }
#queue-panel.visible { display: block; }
.queue-header { padding: 0.65rem 1.25rem                                 ; font-size: 0.75rem; font-family: var(--mono);
color: var(--muted)                                                      ; letter-spacing: 0.06em; text-transform: uppercase;
border-bottom: 1px solid var(--border)                                   ; display: flex; justify-content: space-between; align-items: center; }
.queue-list { padding: 0.5rem 0                                          ; }
.queue-item { padding: 0.5rem 1.25rem                                    ; display: flex; align-items: center; gap: 0.75rem;
font-family: var(--mono)                                                 ; font-size: 0.8rem; border-bottom: 1px solid var(--border); }
.queue-item:last-child { border-bottom: none                             ; }
.queue-item-name { flex: 1                                               ; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.queue-status { font-size: 0.75rem                                       ; white-space: nowrap; }
.queue-status.pending { color: var(--muted)                              ; }
.queue-status.active { color: var(--accent)                              ; }
.queue-status.done { color: var(--success)                               ; }
.queue-status.error { color: var(--danger)                               ; }
.queue-mini-bar { height: 3px                                            ; background: var(--border); border-radius: 99px; overflow: hidden; margin-top: 3px; }
.queue-mini-fill { height: 100%                                          ; background: var(--accent); border-radius: 99px; width: 0%; transition: width 0.15s; }
table { width: 100%                                                      ; border-collapse: collapse; }
tr { border-bottom: 1px solid var(--border)                              ; transition: background 0.1s; }
tr:last-child { border-bottom: none                                      ; }
tr:hover { background: var(--bg)                                         ; }
tr.selected { background: var(--accent-light) !important                 ; }
td { padding: 0.75rem 1.25rem                                            ; font-size: 0.88rem; vertical-align: middle; }
td.icon { width: 2rem                                                    ; padding-right: 0; font-size: 1rem; }
td.check { width: 2rem                                                   ; padding-right: 0; }
td.check input[type=checkbox] { width: 16px                              ; height: 16px; cursor: pointer; accent-color: var(--accent); }
td.size { text-align: right                                              ; font-family: var(--mono); font-size: 0.78rem; color: var(--muted); white-space: nowrap; }
a { color: var(--text)                                                   ; text-decoration: none; }
a:hover { color: var(--accent)                                           ; }
.locked { cursor: pointer                                                ; color: var(--text); }
.locked:hover { color: var(--accent)                                     ; }
.empty { padding: 2.5rem                                                 ; text-align: center; color: var(--muted); font-size: 0.88rem; font-family: var(--mono); }
#select-all-cb { width: 16px; height: 16px; cursor: pointer; accent-color: var(--accent); }
.modal-overlay { display: none                                           ; position: fixed; inset: 0;
background: rgba(0,0,0,0.35)                                             ; z-index: 100; align-items: center; justify-content: center; }
.modal-overlay.show { display: flex                                      ; }
.modal { background: var(--surface)                                      ; border-radius: var(--radius);
padding: 1.5rem                                                          ; width: 90%; max-width: 360px; box-shadow: 0 8px 32px rgba(0,0,0,0.15); }
.modal h2 { font-family: var(--mono)                                     ; font-size: 0.95rem; font-weight: 500; margin-bottom: 0.35rem; }
.modal p { font-size: 0.8rem                                             ; color: var(--muted); margin-bottom: 1rem; font-family: var(--mono); }
.modal input { width: 100%                                               ; border: 1px solid var(--border); border-radius: 6px;
padding: 0.6rem 0.75rem                                                  ; font-family: var(--mono); font-size: 0.88rem;
background: var(--bg)                                                    ; outline: none; margin-bottom: 0.75rem; transition: border-color 0.15s; }
.modal input:focus { border-color: var(--accent)                         ; }
.modal-error { font-size: 0.78rem                                        ; color: var(--danger); font-family: var(--mono);
margin-bottom: 0.5rem                                                    ; display: none; }
.modal-btns { display: flex                                              ; gap: 0.5rem; justify-content: flex-end; }
.btn-cancel { background: var(--border)                                  ; color: var(--text); border: none; border-radius: 6px;
padding: 0.5rem 1rem                                                     ; font-family: var(--mono); font-size: 0.82rem; cursor: pointer; }
.toast { position: fixed                                                 ; bottom: 1.5rem; left: 50%;
transform: translateX(-50%) translateY(80px)                             ;
background: var(--text)                                                  ; color: var(--bg); font-size: 0.82rem;
font-family: var(--mono)                                                 ; padding: 0.6rem 1.2rem; border-radius: 99px;
opacity: 0                                                               ; transition: all 0.3s; pointer-events: none; white-space: nowrap; }
.toast.show { opacity: 1                                                 ; transform: translateX(-50%) translateY(0); }
.footer { display: flex                                                  ; justify-content: center; margin-top: 1.1rem; }
.github-link { display: inline-flex                                      ; color: var(--muted); }
.github-link:hover { color: var(--text)                                  ; }
.github-link svg { width: 18px                                           ; height: 18px; }
.preview-box { background: var(--surface)                                ; border-radius: var(--radius); width: 92%; max-width: 900px; max-height: 90vh; display: flex; flex-direction: column; overflow: hidden; box-shadow: 0 8px 32px rgba(0,0,0,0.2); }
.preview-head { display: flex                                            ; align-items: center; justify-content: space-between; gap: 0.75rem; padding: 0.75rem 1rem; border-bottom: 1px solid var(--border); }
.preview-title { font-family: var(--mono)                                ; font-size: 0.85rem; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.preview-actions { display: flex                                         ; gap: 0.5rem; align-items: center; flex-shrink: 0; }
.preview-body { padding: 1rem                                            ; overflow: auto; background: var(--bg); }
.preview-media { max-width: 100%                                         ; max-height: 78vh; display: block; margin: 0 auto; }
.preview-frame { width: 100%                                             ; height: 78vh; border: none; background: #fff; }
.preview-text { font-family: var(--mono)                                 ; font-size: 0.8rem; white-space: pre-wrap; word-break: break-word; color: var(--text); }
.gallery { display: none                                                 ; grid-template-columns: repeat(auto-fill, minmax(110px, 1fr)); gap: 0.5rem; padding: 1rem; }
.gallery.on { display: grid                                              ; }
.tile { display: flex                                                    ; flex-direction: column; gap: 0.25rem; text-decoration: none; color: var(--text); overflow: hidden; }
.tile img { width: 100%                                                  ; height: 110px; object-fit: cover; border-radius: 8px; background: var(--bg); border: 1px solid var(--border); }
.tile span { font-size: 0.7rem                                           ; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.tile .vid-ph { width: 100%                                              ; height: 110px; border-radius: 8px; background: var(--border); display: flex; align-items: center; justify-content: center; font-size: 2rem; }
table { table-layout: fixed                                              ; }
td:nth-child(3) { overflow: hidden                                       ; word-break: break-word; }
td.size { width: 5.2rem                                                  ; }
.theme-row { display: flex                                               ; justify-content: space-between; align-items: center; gap: 0.5rem; margin-bottom: 0.6rem; font-size: 0.85rem; }
.theme-row input[type=color] { width: 36px                               ; height: 28px; border: none; background: none; cursor: pointer; padding: 0; }
.theme-hex { width: 92px                                                 ; border: 1px solid var(--border); border-radius: 6px; padding: 0.3rem 0.5rem; font-family: var(--mono); font-size: 0.78rem; background: var(--input-bg); color: var(--text); }
@media (max-width: 600px) {
body { padding: 1rem 0.5rem                                              ; }
td { padding: 0.55rem 0.5rem                                             ; font-size: 0.82rem; }
td.size { width: 4.4rem                                                  ; font-size: 0.7rem; }
.tools-card .top-tools .btn, .tools-card .tool-buttons .btn { font-size: 0.72rem; padding: 0.45rem 0.7rem; }
}
""".trimIndent()

private val jsTemplate = """
const UPLOAD_ENDPOINT = '__UPLOAD__';
const PASTE_ENDPOINT = '__PASTE__';
const ZIP_ENDPOINT = '__ZIP__';
const DELETE_ENDPOINT = '__DELETE__';
const RENAME_ENDPOINT = '__RENAME__';
const MKDIR_ENDPOINT = '__MKDIR__';
const MOVE_ENDPOINT = '__MOVE__';
const SHARE_ENDPOINT = '__SHARE__';
const CURRENT_PATH = '__CURPATH__';

const THEME_KEY = 'swapTheme'                                                          ;
const THEME_PRESETS = {
light: { bg: '#f9f1ec', surface: '#f9f1ec', border: '#e0c9bd', text: '#1e1e1d', muted: '#8f7364', accent: '#d57455', accentDeep: '#a85536', accentLight: '#f0d9cd', inputBg: '#f9f1ec' },
dark: { bg: '#1e1e1d', surface: '#1e1e1d', border: '#474641', text: '#f7f4ef', muted: '#c3c2b7', accent: '#d57455', accentDeep: '#f0d9cd', accentLight: '#453029', inputBg: '#2a2a28' }
};
function themeHexToRgb(h) {
h = (h || '').replace('#', '')                                                          ;
if (h.length === 3) h = h.split('').map(c => c + c).join('')                            ;
if (!/^[0-9a-fA-F]{6}${'$'}/.test(h)) return null                                      ;
const n = parseInt(h, 16)                                                               ;
return { r: (n >> 16) & 255, g: (n >> 8) & 255, b: n & 255 }                            ;
}
function themeRgbToHex(r, g, b) {
const c = x => ('0' + Math.max(0, Math.min(255, Math.round(x))).toString(16)).slice(-2) ;
return '#' + c(r) + c(g) + c(b)                                                         ;
}
function themeMix(hex, amt) {
const p = themeHexToRgb(hex); if (!p) return hex                                        ;
const t = amt > 0 ? 255 : 0; const a = Math.abs(amt)                                    ;
return themeRgbToHex(p.r + (t - p.r) * a, p.g + (t - p.g) * a, p.b + (t - p.b) * a)     ;
}
function themeFromColors(accent, bg, text) {
const p = themeHexToRgb(bg) || { r: 195, g: 194, b: 183 }                               ;
const dark = (0.299 * p.r + 0.587 * p.g + 0.114 * p.b) < 128                            ;
return {
bg, text, accent,
surface: bg,
border: themeMix(bg, dark ? 0.18 : -0.25),
muted: themeMix(text, dark ? -0.3 : 0.35),
accentDeep: themeMix(accent, dark ? 0.3 : -0.25),
accentLight: themeMix(accent, dark ? -0.55 : 0.35),
inputBg: themeMix(bg, dark ? 0.06 : 0.6)
};
}
function applyThemeVars(v) {
const r = document.documentElement.style                                                ;
r.setProperty('--bg', v.bg); r.setProperty('--surface', v.surface)                      ;
r.setProperty('--border', v.border); r.setProperty('--text', v.text)                    ;
r.setProperty('--muted', v.muted); r.setProperty('--accent', v.accent)                  ;
r.setProperty('--accent-deep', v.accentDeep)                                            ;
r.setProperty('--accent-light', v.accentLight)                                          ;
if (v.inputBg) r.setProperty('--input-bg', v.inputBg)                                   ;
}
function loadTheme() {
try {
const s = JSON.parse(localStorage.getItem(THEME_KEY) || 'null')                         ;
if (!s) return                                                                          ;
if (s.mode === 'dark') applyThemeVars(THEME_PRESETS.dark)                               ;
else if (s.mode === 'custom') applyThemeVars(themeFromColors(s.accent, s.bg, s.text))   ;
} catch (e) {}
}
loadTheme()                                                                             ;

const dropZone = document.getElementById('drop-zone');
const fileInput = document.getElementById('file-input');
const progressWrap = document.getElementById('progress-wrap');
const progressBar = document.getElementById('progress-bar');
const progressLabel = document.getElementById('progress-label');
const searchInput = document.getElementById('search-input');
const sortSelect = document.getElementById('sort-select');

let fileRows = [] ;

function showToast(msg) {
const t = document.getElementById('toast');
t.textContent = msg                                ;
t.classList.add('show');
setTimeout(() => t.classList.remove('show'), 2800) ;
}

function rebuildRows() {
const q = (searchInput.value || '').trim().toLowerCase();
const sort = sortSelect.value                             ;
const tbody = document.getElementById('files-tbody');
if (!tbody) return                                        ;

const rows = [...fileRows]                                                                        ;
rows.sort((a, b) => {
if (sort === 'name_desc') return b.name.localeCompare(a.name, undefined, { sensitivity: 'base' }) ;
if (sort === 'size_asc') return a.size - b.size                                                   ;
if (sort === 'size_desc') return b.size - a.size                                                  ;
if (sort === 'date_desc') return b.modified - a.modified                                          ;
if (sort === 'date_asc') return a.modified - b.modified                                           ;
return a.name.localeCompare(b.name, undefined, { sensitivity: 'base' })                           ;
})                                                                                                ;

tbody.innerHTML = '';
let visible = 0                                                        ;
rows.forEach(({ row, name }) => {
const hit = !q || name.toLowerCase().includes(q)                       ;
if (hit) {
visible++                                                              ;
tbody.appendChild(row)                                                 ;
}
})                                                                     ;
if (!visible) {
const tr = document.createElement('tr');
tr.innerHTML = '<td colspan="4" class="empty">No matching files</td>';
tbody.appendChild(tr)                                                  ;
}
updateToolbar()                                                        ;
}

searchInput && searchInput.addEventListener('input', rebuildRows) ;
sortSelect && sortSelect.addEventListener('change', rebuildRows)  ;

// Live updates by polling a tiny snapshot token over the reused keep-alive
// connection. fetch() carries cached Basic-Auth credentials automatically and,
// unlike EventSource, a background 401 never re-opens the login dialog.
let __swapSnap = null                                                              ;
setInterval(async () => {
try {
const r = await fetch('/__snapshot' + location.pathname, { cache: 'no-store' })    ;
if (!r.ok) return                                                                  ;
const s = await r.text()                                                           ;
if (__swapSnap === null) __swapSnap = s                                            ;
else if (s !== __swapSnap) location.reload()                                        ;
} catch (e) {}
}, 4000)                                                                           ;

async function uploadFiles(files) {
const list = [...files]                                                             ;
if (!list.length) return                                                            ;
// Send every selected file in ONE multipart request. iOS Safari frees Blob
// handles between sequential XHRs, so a per-file upload loop drops images after
// the first; a single request keeps every Blob alive for the whole transfer.
const fd = new FormData()                                                           ;
for (const file of list) fd.append('file', file, file.name)                         ;
progressWrap.style.display = 'block';
progressLabel.textContent = list.length === 1
? `Uploading ${'$'}{list[0].name}...`
: `Uploading ${'$'}{list.length} files...`;
progressBar.style.width = '0%';
await new Promise((res) => {
const xhr = new XMLHttpRequest()                                                    ;
xhr.open('POST', UPLOAD_ENDPOINT)                                                   ;
xhr.upload.onprogress = (e) => {
if (e.lengthComputable) progressBar.style.width = (e.loaded / e.total * 100) + '%';
}                                                                                   ;
xhr.onload = () => res()                                                            ;
xhr.onerror = () => { showToast('Upload failed'); res()                             ; };
xhr.send(fd)                                                                        ;
})                                                                                  ;
progressWrap.style.display = 'none';
showToast('Upload complete \u2713');
setTimeout(() => location.reload(), 800)                                            ;
}

fileInput.addEventListener('change', () => uploadFiles([...fileInput.files]))         ;
const cameraInput = document.getElementById('camera-input')                           ;
cameraInput && cameraInput.addEventListener('change', () => uploadFiles([...cameraInput.files]));
dropZone.addEventListener('dragover', (e) => { e.preventDefault()                     ; dropZone.classList.add('drag-over'); });
dropZone.addEventListener('dragleave', () => dropZone.classList.remove('drag-over'));
dropZone.addEventListener('drop', (e) => {
e.preventDefault()                                                                    ; dropZone.classList.remove('drag-over');
uploadFiles([...e.dataTransfer.files])                                                ;
})                                                                                    ;

document.getElementById('text-header').addEventListener('click', () => {
const body = document.getElementById('text-body');
const chevron = document.getElementById('chevron');
body.classList.toggle('open');
chevron.textContent = body.classList.contains('open') ? '\u25b2' : '\u25bc';
})                                                                           ;

document.getElementById('save-text-btn').addEventListener('click', async () => {
const text = document.getElementById('paste-text').value.trim();
const rawName = document.getElementById('paste-name').value.trim() || 'note';
const password = document.getElementById('paste-password').value;
if (!text) { showToast('Nothing to save'); return                                ; }
const filename = rawName.endsWith('.txt') ? rawName : rawName + '.txt';
const res = await fetch(PASTE_ENDPOINT, {
method: 'POST',
headers: { 'Content-Type': 'application/json' },
body: JSON.stringify({ filename, text, password })
})                                                                               ;
if (res.ok) {
document.getElementById('paste-text').value = '';
document.getElementById('paste-name').value = '';
document.getElementById('paste-password').value = '';
const icon = password ? '\uD83D\uDD12 ' : '';
showToast(`Saved ${'$'}{icon}${'$'}{filename} \u2713`);
setTimeout(() => location.reload(), 900)                                         ;
} else { showToast('Save failed'); }
})                                                                               ;

const modal = document.getElementById('pw-modal');
const modalInput = document.getElementById('modal-pw-input');
const modalError = document.getElementById('modal-error');
const modalFilename = document.getElementById('modal-filename');
let pendingUrl = null                                            ;

function openModal(url, displayName) {
pendingUrl = url                          ;
modalFilename.textContent = displayName   ;
modalError.style.display = 'none';
modalInput.value = '';
modal.classList.add('show');
setTimeout(() => modalInput.focus(), 100) ;
}

document.getElementById('modal-cancel').addEventListener('click', () => {
modal.classList.remove('show'); pendingUrl = null                           ;
})                                                                          ;
modal.addEventListener('click', (e) => {
if (e.target === modal) { modal.classList.remove('show'); pendingUrl = null ; }
})                                                                          ;

async function submitPassword() {
const pw = modalInput.value                                                                          ;
if (!pw) { modalError.textContent = 'Enter the password'; modalError.style.display = 'block'; return ; }
const url = pendingUrl + '?pw=' + encodeURIComponent(pw)                                             ;
const res = await fetch(url, { method: 'HEAD' })                                                     ;
if (res.ok) {
modal.classList.remove('show');
window.location.href = url                                                                           ;
} else {
modalError.textContent = 'Wrong password';
modalError.style.display = 'block';
modalInput.value = '';
modalInput.focus()                                                                                   ;
}
}

document.getElementById('modal-submit').addEventListener('click', submitPassword)       ;
modalInput.addEventListener('keydown', (e) => { if (e.key === 'Enter') submitPassword() ; });
window.promptPassword = (url, name) => openModal(url, name)                             ;

const selectAllCb = document.getElementById('select-all-cb');
const toolbar = document.getElementById('select-toolbar');
const selCount = document.getElementById('sel-count');
const btnZip = document.getElementById('btn-zip');
const btnQueue = document.getElementById('btn-queue');
const btnRename = document.getElementById('btn-rename');
const btnMove = document.getElementById('btn-move');
const btnDelete = document.getElementById('btn-delete');
const btnMkdir = document.getElementById('btn-mkdir');
const btnShare = document.getElementById('btn-share');

function getCheckboxes() { return [...document.querySelectorAll('.file-cb')]; }

function getCheckedItems() {
return getCheckboxes()
.filter(cb => cb.checked)
.map(cb => ({ name: cb.dataset.name, isDir: cb.dataset.type === 'dir' })) ;
}

function getCheckedNames() { return getCheckedItems().map(i => i.name) ; }

function updateToolbar() {
const items = getCheckedItems()                                              ;
const n = items.length                                                       ;
const hasDir = items.some(i => i.isDir)                                      ;
if (n > 0) {
toolbar.classList.add('visible');
const label = n === 1 ? '1 item selected' : `${'$'}{n} items selected`       ;
selCount.textContent = label + (hasDir ? ' (folders \u2192 ZIP only)' : '');
btnZip.disabled = false                                                      ;
btnQueue.disabled = items.every(i => i.isDir)                                ;
if (btnDelete) btnDelete.disabled = false                                    ;
if (btnMove) btnMove.disabled = false                                        ;
if (btnRename) btnRename.disabled = n !== 1                                  ;
if (btnShare) btnShare.disabled = !(n === 1 && !items[0].isDir)              ;
} else {
toolbar.classList.remove('visible');
}
const all = getCheckboxes()                                                  ;
selectAllCb.indeterminate = n > 0 && n < all.length                          ;
selectAllCb.checked = all.length > 0 && n === all.length                     ;
}

function toggleRowHighlight(cb) {
const row = cb.closest('tr');
if (row) row.classList.toggle('selected', cb.checked) ;
}

document.addEventListener('change', (e) => {
if (e.target.classList.contains('file-cb')) { toggleRowHighlight(e.target) ; updateToolbar(); }
})                                                                         ;

selectAllCb && selectAllCb.addEventListener('change', () => {
getCheckboxes().forEach(cb => { cb.checked = selectAllCb.checked ; toggleRowHighlight(cb); });
updateToolbar()                                                  ;
})                                                               ;

btnZip && btnZip.addEventListener('click', () => {
const names = getCheckedNames()                           ;
if (!names.length) return                                 ;
const form = document.createElement('form');
form.method = 'POST';
form.action = ZIP_ENDPOINT                                ;
names.forEach(name => {
const inp = document.createElement('input');
inp.type = 'hidden'; inp.name = 'files'; inp.value = name ;
form.appendChild(inp)                                     ;
})                                                        ;
document.body.appendChild(form)                           ;
form.submit()                                             ;
document.body.removeChild(form)                           ;
showToast(`Zipping ${'$'}{names.length} item(s)\u2026`);
})                                                        ;

btnMkdir && btnMkdir.addEventListener('click', async () => {
const raw = prompt('New folder name:')                                    ;
if (raw === null) return                                                   ;
const name = raw.trim()                                                    ;
if (!name) return                                                          ;
try {
const res = await fetch(MKDIR_ENDPOINT, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ name }) });
if (res.ok) { showToast('Folder created ✓'); setTimeout(() => location.reload(), 500); }
else { showToast('Create failed')                                          ; }
} catch (e) { showToast('Create failed')                                    ; }
})                                                                          ;

btnRename && btnRename.addEventListener('click', async () => {
const items = getCheckedItems()                                            ;
if (items.length !== 1) return                                             ;
const oldName = items[0].name                                              ;
const raw = prompt('Rename to:', oldName)                                  ;
if (raw === null) return                                                    ;
const to = raw.trim()                                                       ;
if (!to || to === oldName) return                                          ;
try {
const res = await fetch(RENAME_ENDPOINT, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ name: oldName, to }) });
if (res.ok) { showToast('Renamed ✓'); setTimeout(() => location.reload(), 500); }
else { showToast('Rename failed')                                          ; }
} catch (e) { showToast('Rename failed')                                    ; }
})                                                                          ;

btnDelete && btnDelete.addEventListener('click', async () => {
const names = getCheckedNames()                                            ;
if (!names.length) return                                                   ;
if (!confirm(`Delete ${'$'}{names.length} item(s)? This cannot be undone.`)) return;
const body = names.map(n => 'files=' + encodeURIComponent(n)).join('&')     ;
try {
const res = await fetch(DELETE_ENDPOINT, { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body });
if (res.ok) { showToast('Deleted ✓'); setTimeout(() => location.reload(), 500); }
else { showToast('Delete failed')                                          ; }
} catch (e) { showToast('Delete failed')                                    ; }
})                                                                          ;

btnMove && btnMove.addEventListener('click', async () => {
const names = getCheckedNames()                                            ;
if (!names.length) return                                                   ;
const raw = prompt('Move to folder (path from root, blank = root):', '')   ;
if (raw === null) return                                                    ;
const dest = raw.trim()                                                     ;
try {
const res = await fetch(MOVE_ENDPOINT, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ dest, files: names }) });
if (res.ok) { const j = await res.json(); showToast('Moved ' + (j.moved || 0) + ' item(s) ✓'); setTimeout(() => location.reload(), 500); }
else { showToast('Move failed')                                            ; }
} catch (e) { showToast('Move failed')                                      ; }
})                                                                          ;

btnShare && btnShare.addEventListener('click', async () => {
const items = getCheckedItems()                                                        ;
if (items.length !== 1 || items[0].isDir) return                                       ;
const raw = prompt('Link valid for how many hours? (1-168)', '24')                     ;
if (raw === null) return                                                                ;
const hours = Math.max(1, Math.min(168, parseInt(raw, 10) || 24))                      ;
const once = confirm('One-time link? OK = single download, Cancel = reusable until it expires.');
try {
const res = await fetch(SHARE_ENDPOINT, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ name: items[0].name, ttl: hours * 3600, once }) });
if (!res.ok) { showToast('Share failed')                                               ; return; }
const j = await res.json()                                                             ;
const url = location.origin + j.path                                                   ;
copyText(url)                                                                           ;
showToast('Share link copied ✓')                                                       ;
} catch (e) { showToast('Share failed')                                                 ; }
})                                                                                      ;

const queuePanel = document.getElementById('queue-panel');

const btnSearchAll = document.getElementById('btn-search-all')                         ;
btnSearchAll && btnSearchAll.addEventListener('click', async () => {
const q = (searchInput.value || '').trim()                                            ;
if (!q) { showToast('Type something to search')                                       ; return; }
try {
const r = await fetch('/__search?q=' + encodeURIComponent(q))                         ;
if (!r.ok) { showToast('Search failed')                                               ; return; }
renderSearchResults(await r.json(), q)                                                 ;
} catch (e) { showToast('Search failed')                                               ; }
})                                                                                      ;

function formatBytes(n) {
if (!n || n <= 0) return '0 B'                                                         ;
const u = ['B','KB','MB','GB','TB']; let v = n, i = 0                                  ;
while (v >= 1024 && i < u.length - 1) { v /= 1024; i++                                 ; }
return (Math.round(v * 10) / 10) + ' ' + u[i]                                          ;
}

function renderSearchResults(items, q) {
const tbody = document.getElementById('files-tbody')                                  ;
if (!tbody) return                                                                     ;
tbody.innerHTML = ''                                                                   ;
if (!items.length) {
const tr = document.createElement('tr')                                               ;
tr.innerHTML = '<td colspan="4" class="empty">No matches for &quot;' + escHtml(q) + '&quot;</td>';
tbody.appendChild(tr); return                                                          ;
}
for (const it of items) {
const tr = document.createElement('tr')                                               ;
const url = '/' + it.path.split('/').map(encodeURIComponent).join('/')                ;
const icon = it.isDir ? '📁' : '📄'                                                    ;
const link = it.isDir
? '<a href="' + url + '">' + escHtml(it.name) + '/</a>'
: '<a href="' + url + '" class="file-link" data-name="' + escHtml(it.name) + '" download="' + escHtml(it.name) + '">' + escHtml(it.name) + '</a>';
const sub = '<div style="font-size:0.7rem;color:var(--muted);">' + escHtml(it.path) + '</div>';
tr.innerHTML = '<td class="check"></td><td class="icon">' + icon + '</td><td>' + link + sub + '</td><td class="size">' + (it.isDir ? '—' : formatBytes(it.size)) + '</td>';
tbody.appendChild(tr)                                                                  ;
}
}

btnQueue && btnQueue.addEventListener('click', () => {
const items = getCheckedItems().filter(i => !i.isDir)                                   ;
if (!items.length) { showToast('Select files (not folders) for queue download'); return ; }
startQueue(items.map(i => i.name))                                                      ;
})                                                                                      ;

async function startQueue(names) {
queuePanel.classList.add('visible');
const listEl = document.getElementById('queue-list');
listEl.innerHTML = '';
const items = names.map(name => {
const base = (CURRENT_PATH ? '/' + CURRENT_PATH.replace(/^\/|\/$/g, '') + '/' : '/');
const url = base + encodeURIComponent(name)                                                               ;
const div = document.createElement('div');
div.className = 'queue-item';
div.innerHTML = `
<span class="queue-item-name" title="${'$'}{escHtml(name)}">${'$'}{escHtml(name)}</span>
<span class="queue-status pending" id="qs-${'$'}{escId(name)}">queued</span>
<div style="width:60px">
<div class="queue-mini-bar"><div class="queue-mini-fill" id="qb-${'$'}{escId(name)}"></div></div>
</div>`                                                                                                   ;
listEl.appendChild(div)                                                                                   ;
return { name, url }                                                                                      ;
})                                                                                                        ;
document.getElementById('queue-header-text').textContent =
`Downloading ${'$'}{names.length} file${'$'}{names.length > 1 ? 's' : ''}\u2026`;
for (const item of items) {
const statusEl = document.getElementById('qs-' + escId(item.name))                                        ;
const barEl = document.getElementById('qb-' + escId(item.name))                                           ;
if (statusEl) { statusEl.className = 'queue-status active'; statusEl.textContent = 'downloading\u2026'; }
try {
await downloadWithProgress(item.url, item.name, barEl)                                                    ;
if (statusEl) { statusEl.className = 'queue-status done'; statusEl.textContent = '\u2713 done'; }
} catch (err) {
if (statusEl) { statusEl.className = 'queue-status error'; statusEl.textContent = '\u2717 error'; }
}
await new Promise(r => setTimeout(r, 300))                                                                ;
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
const xhr = new XMLHttpRequest()                                                       ;
xhr.open('GET', url, true)                                                             ;
xhr.responseType = 'blob';
xhr.onprogress = (e) => {
if (e.lengthComputable && barEl) barEl.style.width = (e.loaded / e.total * 100) + '%';
}                                                                                      ;
xhr.onload = () => {
if (xhr.status === 200) {
if (barEl) barEl.style.width = '100%';
const a = document.createElement('a');
a.href = URL.createObjectURL(xhr.response)                                             ;
a.download = filename                                                                  ;
document.body.appendChild(a)                                                           ;
a.click()                                                                              ;
document.body.removeChild(a)                                                           ;
setTimeout(() => URL.revokeObjectURL(a.href), 10000)                                   ;
resolve()                                                                              ;
} else { reject(new Error('HTTP ' + xhr.status))                                       ; }
}                                                                                      ;
xhr.onerror = () => reject(new Error('Network error'));
xhr.send()                                                                             ;
})                                                                                     ;
}

const PREVIEW_IMG = ['jpg','jpeg','png','gif','webp','bmp','svg','avif']              ;
const PREVIEW_VIDEO = ['mp4','webm','mov','m4v','ogv']                               ;
const PREVIEW_AUDIO = ['mp3','wav','ogg','oga','m4a','flac','aac']                   ;
const PREVIEW_TEXT = ['txt','md','log','json','xml','csv','js','ts','css','html','htm','py','java','kt','c','cpp','h','sh','yml','yaml','ini','conf'];
function previewKind(ext) {
if (PREVIEW_IMG.includes(ext)) return 'img'                                          ;
if (PREVIEW_VIDEO.includes(ext)) return 'video'                                      ;
if (PREVIEW_AUDIO.includes(ext)) return 'audio'                                      ;
if (PREVIEW_TEXT.includes(ext)) return 'text'                                        ;
if (ext === 'pdf') return 'pdf'                                                       ;
return null                                                                           ;
}
const previewModal = document.getElementById('preview-modal')                         ;
async function openPreview(url, name, ext) {
const inlineUrl = url + (url.includes('?') ? '&' : '?') + 'inline=1'                  ;
const bodyEl = document.getElementById('preview-body')                                ;
document.getElementById('preview-title').textContent = name                           ;
const dl = document.getElementById('preview-download')                                ;
dl.href = url; dl.setAttribute('download', name)                                       ;
bodyEl.innerHTML = ''                                                                  ;
document.getElementById('preview-copy').style.display = 'none'                         ;
const kind = previewKind(ext)                                                          ;
if (kind === 'img') {
const img = document.createElement('img'); img.src = inlineUrl; img.className = 'preview-media'; bodyEl.appendChild(img);
} else if (kind === 'video') {
const v = document.createElement('video'); v.src = inlineUrl; v.controls = true; v.className = 'preview-media'; bodyEl.appendChild(v);
} else if (kind === 'audio') {
const a = document.createElement('audio'); a.src = inlineUrl; a.controls = true; a.style.width = '100%'; bodyEl.appendChild(a);
} else if (kind === 'pdf') {
const f = document.createElement('iframe'); f.src = inlineUrl; f.className = 'preview-frame'; bodyEl.appendChild(f);
} else {
const pre = document.createElement('pre'); pre.className = 'preview-text'; pre.textContent = 'Loading…'; bodyEl.appendChild(pre);
try { const r = await fetch(inlineUrl); const t = await r.text(); pre.textContent = t; const cb = document.getElementById('preview-copy'); cb.style.display = ''; cb.onclick = () => copyText(t); } catch (e) { pre.textContent = 'Failed to load'; }
}
previewModal.classList.add('show')                                                    ;
}
function copyText(t) {
if (navigator.clipboard && navigator.clipboard.writeText) {
navigator.clipboard.writeText(t).then(() => showToast('Copied ✓')).catch(() => fallbackCopy(t));
} else { fallbackCopy(t)                                                               ; }
}
function fallbackCopy(t) {
const ta = document.createElement('textarea'); ta.value = t; ta.style.position = 'fixed'; ta.style.opacity = '0';
document.body.appendChild(ta); ta.select()                                             ;
try { document.execCommand('copy'); showToast('Copied ✓'); } catch (e) { showToast('Copy failed'); }
document.body.removeChild(ta)                                                           ;
}
function closePreview() {
previewModal.classList.remove('show')                                                 ;
document.getElementById('preview-body').innerHTML = ''                                 ;
}
document.getElementById('preview-close').addEventListener('click', closePreview)       ;
previewModal.addEventListener('click', (e) => { if (e.target === previewModal) closePreview(); });
document.addEventListener('keydown', (e) => { if (e.key === 'Escape' && previewModal.classList.contains('show')) closePreview(); });
document.addEventListener('click', (e) => {
const a = e.target.closest ? e.target.closest('a.file-link') : null                   ;
if (!a) return                                                                         ;
const name = a.dataset.name || ''                                                      ;
const dot = name.lastIndexOf('.')                                                      ;
const ext = dot >= 0 ? name.slice(dot + 1).toLowerCase() : ''                          ;
if (!previewKind(ext)) return                                                          ;
e.preventDefault()                                                                     ;
openPreview(a.getAttribute('href'), name, ext)                                         ;
})                                                                                      ;

const themeModal = document.getElementById('theme-modal')                             ;
const btnTheme = document.getElementById('btn-theme')                                 ;
function normalizeHex(h) {
h = (h || '').trim(); if (h && !h.startsWith('#')) h = '#' + h                        ;
const p = themeHexToRgb(h); if (!p) return null                                        ;
return themeRgbToHex(p.r, p.g, p.b)                                                    ;
}
function clearThemeVars() {
const r = document.documentElement.style                                                ;
['--bg', '--surface', '--border', '--text', '--muted', '--accent', '--accent-deep', '--accent-light', '--input-bg']
.forEach(k => r.removeProperty(k))                                                      ;
}
function syncThemeInputs() {
try {
const s = JSON.parse(localStorage.getItem(THEME_KEY) || 'null')                        ;
let v = THEME_PRESETS.light                                                             ;
if (s && s.mode === 'custom') v = s                                                     ;
else if (s && s.mode === 'dark') v = THEME_PRESETS.dark                                 ;
[['accent', v.accent], ['bg', v.bg], ['text', v.text]].forEach(([k, val]) => {
document.getElementById('tc-' + k).value = val                                         ;
document.getElementById('tx-' + k).value = val                                         ;
})                                                                                      ;
} catch (e) {}
}
btnTheme && btnTheme.addEventListener('click', () => { syncThemeInputs(); themeModal.classList.add('show'); });
document.getElementById('theme-close').addEventListener('click', () => themeModal.classList.remove('show'));
themeModal.addEventListener('click', (e) => { if (e.target === themeModal) themeModal.classList.remove('show'); });
['accent', 'bg', 'text'].forEach(k => {
const c = document.getElementById('tc-' + k), t = document.getElementById('tx-' + k)  ;
c.addEventListener('input', () => { t.value = c.value                                  ; });
t.addEventListener('input', () => { const n = normalizeHex(t.value); if (n) c.value = n; });
})                                                                                      ;
document.getElementById('theme-apply').addEventListener('click', () => {
const accent = normalizeHex(document.getElementById('tx-accent').value) || document.getElementById('tc-accent').value;
const bg = normalizeHex(document.getElementById('tx-bg').value) || document.getElementById('tc-bg').value;
const text = normalizeHex(document.getElementById('tx-text').value) || document.getElementById('tc-text').value;
localStorage.setItem(THEME_KEY, JSON.stringify({ mode: 'custom', accent, bg, text }))   ;
applyThemeVars(themeFromColors(accent, bg, text))                                       ;
showToast('Theme applied ✓')                                                            ;
})                                                                                      ;
document.getElementById('theme-light').addEventListener('click', () => {
// Light IS the stylesheet default — drop every override so it matches exactly.
localStorage.setItem(THEME_KEY, JSON.stringify({ mode: 'light' }))                      ;
clearThemeVars(); syncThemeInputs(); showToast('Light theme ✓')                         ;
})                                                                                      ;
document.getElementById('theme-dark').addEventListener('click', () => {
localStorage.setItem(THEME_KEY, JSON.stringify({ mode: 'dark' }))                       ;
applyThemeVars(THEME_PRESETS.dark); syncThemeInputs(); showToast('Dark theme ✓')        ;
})                                                                                      ;
document.getElementById('theme-reset').addEventListener('click', () => {
localStorage.removeItem(THEME_KEY)                                                      ;
clearThemeVars(); syncThemeInputs(); showToast('Theme reset ✓')                         ;
})                                                                                      ;

const trashModal = document.getElementById('trash-modal')                             ;
const btnTrash = document.getElementById('btn-trash')                                 ;
async function openTrash() {
const body = document.getElementById('trash-body')                                    ;
body.innerHTML = 'Loading…'                                                            ;
try {
const r = await fetch('/__trash')                                                     ;
if (!r.ok) { body.textContent = 'Failed to load'; return                              ; }
const items = await r.json()                                                          ;
if (!items.length) { body.innerHTML = '<div class="empty">Trash is empty</div>'       ; }
else {
body.innerHTML = ''                                                                   ;
const tbl = document.createElement('table'); const tb = document.createElement('tbody');
for (const it of items) {
const tr = document.createElement('tr')                                               ;
tr.innerHTML = '<td class="check"><input type="checkbox" class="trash-cb" data-name="' + escHtml(it.name) + '"></td><td class="icon">' + (it.isDir ? '📁' : '📄') + '</td><td>' + escHtml(it.name) + '<div style="font-size:0.7rem;color:var(--muted);">from ' + escHtml(it.original) + '</div></td><td class="size">' + (it.isDir ? '—' : formatBytes(it.size)) + '</td>';
tb.appendChild(tr)                                                                     ;
}
tbl.appendChild(tb); body.appendChild(tbl)                                            ;
}
} catch (e) { body.textContent = 'Failed to load'                                     ; }
trashModal.classList.add('show')                                                      ;
}
btnTrash && btnTrash.addEventListener('click', openTrash)                              ;
document.getElementById('trash-close').addEventListener('click', () => trashModal.classList.remove('show'));
trashModal.addEventListener('click', (e) => { if (e.target === trashModal) trashModal.classList.remove('show'); });
document.getElementById('trash-restore').addEventListener('click', async () => {
const names = [...document.querySelectorAll('.trash-cb')].filter(c => c.checked).map(c => c.dataset.name);
if (!names.length) { showToast('Select items to restore'); return                     ; }
const body = names.map(n => 'files=' + encodeURIComponent(n)).join('&')               ;
try {
const res = await fetch('/__restore', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body });
if (res.ok) { showToast('Restored ✓'); setTimeout(() => location.reload(), 500); } else showToast('Restore failed');
} catch (e) { showToast('Restore failed')                                             ; }
})                                                                                      ;
document.getElementById('trash-empty').addEventListener('click', async () => {
if (!confirm('Permanently delete everything in Trash?')) return                        ;
try {
const res = await fetch('/__trashempty', { method: 'POST' })                          ;
if (res.ok) { showToast('Trash emptied ✓'); openTrash(); } else showToast('Failed')   ;
} catch (e) { showToast('Failed')                                                     ; }
})                                                                                      ;

const btnGallery = document.getElementById('btn-gallery')                             ;
let galleryOn = false                                                                  ;
function buildGallery() {
const gallery = document.getElementById('gallery')                                    ;
if (!gallery) return                                                                   ;
gallery.innerHTML = ''                                                                 ;
const links = [...document.querySelectorAll('#files-tbody a.file-link')]              ;
let count = 0                                                                          ;
for (const a of links) {
const name = a.dataset.name || ''                                                      ;
const dot = name.lastIndexOf('.')                                                      ;
const ext = dot >= 0 ? name.slice(dot + 1).toLowerCase() : ''                          ;
const kind = previewKind(ext)                                                          ;
if (kind !== 'img' && kind !== 'video') continue                                       ;
count++                                                                                ;
const href = a.getAttribute('href')                                                    ;
const tile = document.createElement('a')                                               ;
tile.href = href; tile.className = 'tile file-link'; tile.dataset.name = name; tile.setAttribute('download', name);
const img = document.createElement('img')                                              ;
img.loading = 'lazy'; img.src = '/__thumb' + href                                      ;
img.onerror = () => {
// Some providers can't thumbnail videos — show a play tile instead of a blank.
const ph = document.createElement('div'); ph.className = 'vid-ph'                      ;
ph.textContent = kind === 'video' ? '▶' : '🖼'                                          ;
img.replaceWith(ph)                                                                     ;
}                                                                                       ;
const label = document.createElement('span'); label.textContent = name                ;
tile.appendChild(img); tile.appendChild(label); gallery.appendChild(tile)             ;
}
if (!count) gallery.innerHTML = '<div class="empty">No images or videos here</div>'    ;
}
btnGallery && btnGallery.addEventListener('click', () => {
galleryOn = !galleryOn                                                                  ;
const table = document.querySelector('.files-card table')                              ;
const gallery = document.getElementById('gallery')                                     ;
if (galleryOn) { buildGallery(); gallery.classList.add('on'); if (table) table.style.display = 'none'; btnGallery.textContent = '☰ List'; }
else { gallery.classList.remove('on'); if (table) table.style.display = ''; btnGallery.textContent = '▦ Gallery'; }
})                                                                                      ;

(function () {
const el = document.getElementById('stats')                                           ;
if (!el) return                                                                        ;
fetch('/__stats').then(r => r.ok ? r.json() : null).then(s => {
if (!s) return                                                                         ;
el.textContent = '↑ ' + s.uploads + ' files (' + formatBytes(s.uploadBytes) + ') · ↓ ' + s.downloads + ' files (' + formatBytes(s.downloadBytes) + ') this session';
}).catch(() => {})                                                                      ;
})()                                                                                    ;

document.addEventListener('DOMContentLoaded', () => {
fileRows = [...document.querySelectorAll('#files-tbody tr[data-name]')].map(row => ({
row,
name: row.dataset.name || '',
size: Number(row.dataset.size || '0'),
modified: Number(row.dataset.modified || '0')
}))                                                                                   ;
rebuildRows()                                                                         ;
})                                                                                    ;
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
"<table><tbody id=\"files-tbody\">$bodyRows</tbody></table>"
}

val snapshot = files.map { "${it.name}:${it.size}:${it.isDirectory}" }.joinToString("|").hashCode().toString()

val encodedCurrent = FileUtils.encodePathSegments(currentPath)
val uploadEndpoint = "/upload" + if (encodedCurrent.isBlank()) "/" else "/$encodedCurrent"
val pasteEndpoint = "/paste" + if (encodedCurrent.isBlank()) "/" else "/$encodedCurrent"
val zipEndpoint = "/zip" + if (encodedCurrent.isBlank()) "/" else "/$encodedCurrent"
val deleteEndpoint = "/delete" + if (encodedCurrent.isBlank()) "/" else "/$encodedCurrent"
val renameEndpoint = "/rename" + if (encodedCurrent.isBlank()) "/" else "/$encodedCurrent"
val mkdirEndpoint = "/mkdir" + if (encodedCurrent.isBlank()) "/" else "/$encodedCurrent"
val moveEndpoint = "/move" + if (encodedCurrent.isBlank()) "/" else "/$encodedCurrent"
val shareEndpoint = "/__share" + if (encodedCurrent.isBlank()) "/" else "/$encodedCurrent"
val js = jsTemplate
.replace("__UPLOAD__", uploadEndpoint)
.replace("__PASTE__", pasteEndpoint)
.replace("__ZIP__", zipEndpoint)
.replace("__DELETE__", deleteEndpoint)
.replace("__RENAME__", renameEndpoint)
.replace("__MKDIR__", mkdirEndpoint)
.replace("__MOVE__", moveEndpoint)
.replace("__SHARE__", shareEndpoint)
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
<p class="upload-label"><strong>Click to upload</strong> or drag &amp                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            ; drop</p>
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
<input type="text" id="paste-name" placeholder="filename (optional)">
<input type="password" id="paste-password" placeholder="password (optional)">
<button class="btn" id="save-text-btn">Save</button>
</div>
<p class="hint">Leave password blank to save without protection.</p>
</div>
</div>
<div class="tools-card">
<div class="tools-body">
<div class="top-tools">
<input class="tool-input" id="search-input" placeholder="Search files and folders" />
<button class="btn" id="btn-search-all">🌐 All folders</button>
<select class="tool-select" id="sort-select">
<option value="name_asc">Sort: Name A-Z</option>
<option value="name_desc">Sort: Name Z-A</option>
<option value="size_asc">Sort: Size small-large</option>
<option value="size_desc">Sort: Size large-small</option>
<option value="date_desc">Sort: Date newest</option>
<option value="date_asc">Sort: Date oldest</option>
</select>
</div>
<div class="tool-buttons">
<button class="btn" id="btn-mkdir">📁 New folder</button>
<button class="btn" id="btn-trash">🗑 Trash</button>
<label class="btn" style="cursor:pointer;">📷 Camera
<input type="file" id="camera-input" accept="image/*,video/*" capture="environment" style="display:none;">
</label>
<button class="btn" id="btn-theme">🎨 Theme</button>
</div>
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
<div style="display:flex;gap:0.5rem;align-items:center;">
<button class="btn" id="btn-gallery" style="padding:0.3rem 0.6rem;font-size:0.7rem;">▦ Gallery</button>
<span>Size</span>
</div>
</div>
<div class="select-toolbar" id="select-toolbar">
<span class="sel-count" id="sel-count">0 selected</span>
<button class="btn btn-zip" id="btn-zip" disabled>⬇️ Download as ZIP</button>
<button class="btn" id="btn-queue" disabled>📋 Queue download</button>
<button class="btn" id="btn-rename" disabled>✏️ Rename</button>
<button class="btn" id="btn-move" disabled>📦 Move</button>
<button class="btn" id="btn-share" disabled>🔗 Share</button>
<button class="btn btn-danger" id="btn-delete" disabled>🗑️ Delete</button>
</div>
$tableOrEmpty
<div class="gallery" id="gallery"></div>
</div>
<div class="hint" id="stats" style="text-align:center;margin-top:1rem;"></div>
<div class="footer">
<a class="github-link" href="https://github.com/Mobinshahidi/swap" target="_blank" rel="noopener" aria-label="GitHub">
<svg viewBox="0 0 24 24" role="img" aria-hidden="true">
<path fill="currentColor" d="M12,0.296c-6.63,0 -12,5.373 -12,12 0,5.303 3.438,9.8 8.205,11.387 0.6,0.111 0.82,-0.261 0.82,-0.577 0,-0.285 -0.01,-1.04 -0.015,-2.04 -3.338,0.724 -4.042,-1.61 -4.042,-1.61 -0.546,-1.387 -1.333,-1.756 -1.333,-1.756 -1.089,-0.745 0.083,-0.729 0.083,-0.729 1.205,0.084 1.84,1.236 1.84,1.236 1.07,1.834 2.809,1.304 3.495,0.997 0.108,-0.776 0.418,-1.304 0.762,-1.604 -2.665,-0.304 -5.466,-1.332 -5.466,-5.93 0,-1.31 0.469,-2.381 1.235,-3.221 -0.135,-0.303 -0.54,-1.523 0.105,-3.176 0,0 1.005,-0.322 3.3,1.23 0.96,-0.267 1.98,-0.399 3,-0.405 1.02,0.006 2.04,0.138 3,0.405 2.28,-1.552 3.285,-1.23 3.285,-1.23 0.645,1.653 0.24,2.873 0.12,3.176 0.765,0.84 1.23,1.911 1.23,3.221 0,4.61 -2.805,5.625 -5.475,5.921 0.435,0.375 0.81,1.11 0.81,2.22 0,1.606 -0.015,2.896 -0.015,3.286 0,0.315 0.21,0.69 0.825,0.57C20.565,22.092 24,17.592 24,12.296 24,5.669 18.627,0.296 12,0.296z" />
</svg>
</a>
</div>
</div>
<div class="modal-overlay" id="theme-modal">
<div class="modal">
<h2>🎨 Theme</h2>
<p>Pick a preset or set custom colors (hex).</p>
<div class="theme-row"><span>Preset</span><span style="display:flex;gap:0.5rem;">
<button class="btn" id="theme-light">Light</button>
<button class="btn" id="theme-dark">Dark</button>
</span></div>
<div class="theme-row"><span>Accent</span><span style="display:flex;gap:0.4rem;align-items:center;">
<input type="color" id="tc-accent" value="#d57455"><input type="text" class="theme-hex" id="tx-accent" value="#d57455">
</span></div>
<div class="theme-row"><span>Background</span><span style="display:flex;gap:0.4rem;align-items:center;">
<input type="color" id="tc-bg" value="#f9f1ec"><input type="text" class="theme-hex" id="tx-bg" value="#f9f1ec">
</span></div>
<div class="theme-row"><span>Text</span><span style="display:flex;gap:0.4rem;align-items:center;">
<input type="color" id="tc-text" value="#1e1e1d"><input type="text" class="theme-hex" id="tx-text" value="#1e1e1d">
</span></div>
<div class="modal-btns">
<button class="btn-cancel" id="theme-reset">Reset</button>
<button class="btn-cancel" id="theme-close">Close</button>
<button class="btn" id="theme-apply">Apply</button>
</div>
</div>
</div>
<div class="modal-overlay" id="trash-modal">
<div class="preview-box">
<div class="preview-head">
<span class="preview-title">🗑 Trash</span>
<span class="preview-actions">
<button class="btn" id="trash-restore">↩ Restore</button>
<button class="btn btn-danger" id="trash-empty">Empty</button>
<button class="btn-cancel" id="trash-close">✕</button>
</span>
</div>
<div class="preview-body" id="trash-body"></div>
</div>
</div>
<div class="modal-overlay" id="preview-modal">
<div class="preview-box">
<div class="preview-head">
<span class="preview-title" id="preview-title"></span>
<span class="preview-actions">
<button class="btn" id="preview-copy" style="display:none;">📋 Copy</button>
<a class="btn" id="preview-download">⬇️ Download</a>
<button class="btn-cancel" id="preview-close">✕</button>
</span>
</div>
<div class="preview-body" id="preview-body"></div>
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
.replace("</head>", "<meta name=\"swap-snapshot\" content=\"$snapshot\"></head>")
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
val rowNameAttr = escapeHtml(entry.name)
val rowSizeAttr = if (entry.isDirectory) "0" else entry.size.toString()
val rowModifiedAttr = entry.modifiedAt.toString()
if (entry.isDirectory) {
val url = "/$encodedPath"
rows.append("<tr data-name=\"$rowNameAttr\" data-size=\"$rowSizeAttr\" data-modified=\"$rowModifiedAttr\"><td class=\"check\"><input type=\"checkbox\" class=\"file-cb\" data-name=\"$nameHtml\" data-type=\"dir\"></td><td class=\"icon\">📁</td><td><a href=\"$url\">$nameHtml/</a></td><td class=\"size\">—</td></tr>")
} else {
val url = "/$encodedPath"
if (entry.relativePath in protectedFiles) {
rows.append("<tr data-name=\"$rowNameAttr\" data-size=\"$rowSizeAttr\" data-modified=\"$rowModifiedAttr\"><td class=\"check\"></td><td class=\"icon\">🔒</td><td><span class=\"locked\" onclick=\"promptPassword('$url','$nameHtml')\">$nameHtml</span></td><td class=\"size\">${FileUtils.formatSize(entry.size)}</td></tr>")
} else {
rows.append("<tr data-name=\"$rowNameAttr\" data-size=\"$rowSizeAttr\" data-modified=\"$rowModifiedAttr\"><td class=\"check\"><input type=\"checkbox\" class=\"file-cb\" data-name=\"$nameHtml\"></td><td class=\"icon\">${FileUtils.iconFor(entry.name)}</td><td><a href=\"$url\" class=\"file-link\" data-name=\"$rowNameAttr\" download=\"$nameHtml\">$nameHtml</a></td><td class=\"size\">${FileUtils.formatSize(entry.size)}</td></tr>")
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
