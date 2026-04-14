/**
 * app.js — Resulyzer frontend logic
 *
 * Responsibilities:
 *   1. Load available roles from /roles on page start
 *   2. Handle drag-and-drop and click-to-browse file selection
 *   3. Show a file preview list with per-file remove buttons
 *   4. POST to /analyze-full when the user clicks "Analyze Resumes"
 *   5. Render rich result cards for each file
 */

// ── State ──────────────────────────────────────────────────────────────────
let selectedFiles = []; // FileList items tracked manually

// ── DOM refs ───────────────────────────────────────────────────────────────
const dropzone     = document.getElementById('dropzone');
const fileInput    = document.getElementById('file-input');
const fileList     = document.getElementById('file-list');
const analyzeBtn   = document.getElementById('analyze-btn');
const btnText      = document.getElementById('btn-text');
const btnSpinner   = document.getElementById('btn-spinner');
const resultsSection = document.getElementById('results-section');
const resultsGrid    = document.getElementById('results-grid');

// ── Bootstrap ──────────────────────────────────────────────────────────────
document.addEventListener('DOMContentLoaded', () => {
  setupDropzone();
});

// ── Drop zone wiring ───────────────────────────────────────────────────────
function setupDropzone() {
  // Click to open file dialog
  dropzone.addEventListener('click', () => fileInput.click());
  dropzone.addEventListener('keydown', e => {
    if (e.key === 'Enter' || e.key === ' ') fileInput.click();
  });

  // Drag events
  dropzone.addEventListener('dragover', e => {
    e.preventDefault();
    dropzone.classList.add('drag-over');
  });

  ['dragleave', 'dragend'].forEach(evt =>
    dropzone.addEventListener(evt, () => dropzone.classList.remove('drag-over'))
  );

  dropzone.addEventListener('drop', e => {
    e.preventDefault();
    dropzone.classList.remove('drag-over');
    addFiles([...e.dataTransfer.files]);
  });

  // File picker dialog
  fileInput.addEventListener('change', () => {
    addFiles([...fileInput.files]);
    fileInput.value = ''; // reset so the same file can be re-added
  });
}

// ── File management ────────────────────────────────────────────────────────
function addFiles(newFiles) {
  const allowed = ['.pdf', '.docx', '.txt'];

  newFiles.forEach(file => {
    const ext = '.' + file.name.split('.').pop().toLowerCase();
    if (!allowed.includes(ext)) {
      showToast(`⚠️ "${file.name}" is not a supported format.`);
      return;
    }
    // Avoid duplicates
    if (!selectedFiles.find(f => f.name === file.name && f.size === file.size)) {
      selectedFiles.push(file);
    }
  });

  renderFileList();
  syncAnalyzeButton();
}

function removeFile(index) {
  selectedFiles.splice(index, 1);
  renderFileList();
  syncAnalyzeButton();
}

function renderFileList() {
  fileList.innerHTML = '';

  if (selectedFiles.length === 0) {
    fileList.hidden = true;
    return;
  }

  fileList.hidden = false;
  selectedFiles.forEach((file, i) => {
    const li = document.createElement('li');
    li.className = 'file-item';
    li.innerHTML = `
      <span class="file-item-name">
        <span>${fileIcon(file.name)}</span>
        <span>${escHtml(file.name)}</span>
        <span style="color:var(--text-muted);font-size:0.75rem">(${formatBytes(file.size)})</span>
      </span>
      <button class="file-item-remove" aria-label="Remove ${escHtml(file.name)}" data-index="${i}">✕</button>
    `;
    li.querySelector('.file-item-remove').addEventListener('click', () => removeFile(i));
    fileList.appendChild(li);
  });
}

function syncAnalyzeButton() {
  analyzeBtn.disabled = selectedFiles.length === 0;
}

// ── Analyze ────────────────────────────────────────────────────────────────
analyzeBtn.addEventListener('click', async () => {
  if (selectedFiles.length === 0) return;

  setLoading(true);
  resultsSection.hidden = true;
  resultsGrid.innerHTML = '';

  const formData = new FormData();
  selectedFiles.forEach(file => formData.append('files', file));

  try {
    const res  = await fetch('/analyze-full', { method: 'POST', body: formData });
    const data = await res.json();

    if (!res.ok) {
      throw new Error(data.error || `Server error ${res.status}`);
    }

    renderResults(data.results || []);
    resultsSection.hidden = false;
    resultsSection.scrollIntoView({ behavior: 'smooth', block: 'start' });
  } catch (err) {
    resultsGrid.innerHTML = `<div class="error-msg">❌ ${escHtml(err.message)}</div>`;
    resultsSection.hidden = false;
  } finally {
    setLoading(false);
  }
});

// ── Render results ─────────────────────────────────────────────────────────
function renderResults(results) {
  results.forEach(result => {
    const card = document.createElement('div');
    card.className = 'result-card' + (result.error ? ' has-error' : '');
    card.innerHTML = buildResultCardHTML(result);
    resultsGrid.appendChild(card);
  });
}

function buildResultCardHTML(r) {
  if (r.error) {
    return `
      <div class="result-filename">📄 ${escHtml(r.filename)}</div>
      <div class="error-msg">❌ ${escHtml(r.error)}</div>
    `;
  }

  // Score badge
  let scoreBadge = '';
  if (r.selection_score !== null && r.selection_score !== undefined) {
    const cls = r.selection_score >= 70 ? 'high' : r.selection_score >= 40 ? 'medium' : 'low';
    scoreBadge = `
      <div style="display:flex;align-items:center;gap:1rem;margin-bottom:1.25rem">
        <div class="score-badge ${cls}">${r.selection_score}%</div>
        <div>
          <div style="font-weight:700;font-size:1rem">Selection Score</div>
          <div style="color:var(--text-muted);font-size:0.82rem">Against target role</div>
        </div>
      </div>
    `;
  }

  // Confidence bars (top 4 only)
  const topConf = Object.entries(r.confidence || {})
    .sort((a, b) => b[1] - a[1])
    .slice(0, 4);

  const confBars = topConf.map(([role, pct]) => `
    <div class="confidence-bar-row">
      <span class="confidence-bar-label" title="${escHtml(role)}">${escHtml(role)}</span>
      <div class="confidence-bar-track">
        <div class="confidence-bar-fill" style="width:${pct}%"></div>
      </div>
      <span class="confidence-bar-pct">${pct}%</span>
    </div>
  `).join('');

  const missing = (r.missing_skills || []).map(s =>
    `<span class="skill-chip missing" style="margin-right: 0.3rem; margin-bottom: 0.3rem">✗ ${escHtml(s)}</span>`
  ).join('');

  const skillsBlock = missing ? `
    <div class="skills-block" style="margin-top:1.25rem">
      <div class="skills-block-title">Missing Skills For Predicted Role</div>
      <div class="skill-chips">${missing}</div>
    </div>
  ` : '';

  return `
    <div class="result-filename">📄 ${escHtml(r.filename)}</div>
    <div class="result-row">
      <span class="result-label">Predicted Role</span>
      <span class="result-value">${escHtml(r.predicted_role)}</span>
    </div>
    ${confBars ? `
      <div style="margin-top:1rem">
        <div class="skills-block-title">Confidence Breakdown</div>
        <div class="confidence-bar-wrap">${confBars}</div>
      </div>
    ` : ''}
    ${skillsBlock}
  `;
}

// ── UI helpers ─────────────────────────────────────────────────────────────
function setLoading(loading) {
  analyzeBtn.disabled = loading;
  btnText.hidden      = loading;
  btnSpinner.hidden   = !loading;
}

function showToast(message) {
  const toast = document.createElement('div');
  toast.className = 'error-msg';
  toast.style.cssText = 'position:fixed;bottom:1.5rem;left:50%;transform:translateX(-50%);z-index:9999;animation:slide-in 0.3s ease;min-width:240px;text-align:center';
  toast.textContent = message;
  document.body.appendChild(toast);
  setTimeout(() => toast.remove(), 3500);
}

function escHtml(str) {
  const div = document.createElement('div');
  div.appendChild(document.createTextNode(String(str)));
  return div.innerHTML;
}

function fileIcon(filename) {
  const ext = filename.split('.').pop().toLowerCase();
  return ext === 'pdf' ? '📕' : ext === 'docx' ? '📘' : '📄';
}

function formatBytes(bytes) {
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
  return (bytes / 1024 / 1024).toFixed(1) + ' MB';
}
