'use strict';

let authMode = 'login';
let currentUser = null;
let tasks = [];
let currentFilter = 'all';
let searchQuery = '';
let sortBy = 'newest';

const $ = (id) => document.getElementById(id);

// --- Appels API vers AuthServer.java ---

async function api(path, options = {}) {
  const token = localStorage.getItem('sp-token');
  const headers = { 'Content-Type': 'application/json', ...(options.headers || {}) };
  if (token) headers.Authorization = 'Bearer ' + token;

  const res = await fetch(path, { ...options, headers });
  const data = await res.json().catch(() => ({}));
  if (res.status === 401 && currentUser) {
    // Session expirée côté serveur
    logoutLocal();
    throw new Error(data.error || 'Session expirée.');
  }
  if (!res.ok) throw new Error(data.error || 'Erreur serveur');
  return data;
}

// --- Authentification ---

document.querySelectorAll('.auth-tab').forEach((tab) => {
  tab.addEventListener('click', () => {
    document.querySelectorAll('.auth-tab').forEach((t) => t.classList.remove('active'));
    tab.classList.add('active');
    authMode = tab.dataset.mode;
    $('authSubmit').textContent = authMode === 'login' ? 'Se connecter' : 'Créer mon compte';
    $('authError').hidden = true;
  });
});

$('authForm').addEventListener('submit', async (e) => {
  e.preventDefault();
  const errorEl = $('authError');
  errorEl.hidden = true;

  try {
    const endpoint = authMode === 'login' ? '/api/login' : '/api/register';
    const data = await api(endpoint, {
      method: 'POST',
      body: JSON.stringify({
        username: $('authUser').value.trim(),
        password: $('authPass').value,
      }),
    });
    localStorage.setItem('sp-token', data.token);
    await enterApp(data.username);
  } catch (err) {
    errorEl.textContent = err.message;
    errorEl.hidden = false;
  }
});

$('logoutBtn').addEventListener('click', async () => {
  await api('/api/logout', { method: 'POST' }).catch(() => {});
  logoutLocal();
});

function logoutLocal() {
  localStorage.removeItem('sp-token');
  currentUser = null;
  tasks = [];
  $('appView').hidden = true;
  $('authView').hidden = false;
  $('authForm').reset();
}

async function checkSession() {
  if (!localStorage.getItem('sp-token')) return;
  try {
    const data = await api('/api/me');
    await enterApp(data.username);
  } catch {
    localStorage.removeItem('sp-token');
  }
}

async function enterApp(username) {
  currentUser = username;
  $('userName').textContent = username;
  $('authView').hidden = true;
  $('appView').hidden = false;

  const now = new Date();
  $('currentDate').textContent = now.toLocaleDateString('fr-FR', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric', });$('dateInput').value = now.toISOString().split('T')[0];

  tasks = await api('/api/tasks');
  renderAll();
}

// --- Synchronisation des tâches avec le serveur ---

async function saveTasks() {
  try {
    await api('/api/tasks', { method: 'PUT', body: JSON.stringify(tasks) });
  } catch (err) {
    alert(err.message);
  }
}

// --- Ajout, recherche, tri, édition et suppression ---

$('taskForm').addEventListener('submit', async (e) => {
  e.preventDefault();
  const text = $('taskInput').value.trim();
  if (!text) return;

  tasks.unshift({
    id: Date.now(),
    text,
    subject: $('subjectSelect').value || 'Autre',
    priority: $('prioritySelect').value,
    date: $('dateInput').value || null,
    completed: false,
  });

  renderAll();
  await saveTasks();
  $('taskForm').reset();$('dateInput').value = new Date().toISOString().split('T')[0];
  $('prioritySelect').value = 'medium';$('taskInput').focus();
});

$('searchInput').addEventListener('input', (e) => {
  searchQuery = e.target.value.trim().toLowerCase();
  renderTasks();
});

$('sortSelect').addEventListener('change', (e) => {
  sortBy = e.target.value;
  renderTasks();
});

document.querySelectorAll('.filter-btn').forEach((btn) => {
  btn.addEventListener('click', () => {
    document.querySelectorAll('.filter-btn').forEach((b) => b.classList.remove('active'));
    btn.classList.add('active');
    currentFilter = btn.dataset.filter;
    renderTasks();
  });
});

$('tasksContainer').addEventListener('click', async (e) => {
  const delBtn = e.target.closest('[data-delete]');
  if (delBtn) {
    if (!confirm('Supprimer définitivement cette tâche ?')) return;
    const id = Number(delBtn.dataset.delete);
    tasks = tasks.filter((t) => t.id !== id);
    renderAll();
    await saveTasks();
    return;
  }

  const editBtn = e.target.closest('[data-edit]');
  if (editBtn) {
    const task = tasks.find((t) => t.id === Number(editBtn.dataset.edit));
    if (!task) return;
    $('editId').value = task.id;
    $('editText').value = task.text;
    $('editSubject').value = task.subject || 'Autre';
    $('editPriority').value = task.priority;
    $('editDate').value = task.date || '';
    $('editModal').showModal();
  }
});

$('editCancel').addEventListener('click', () =>$('editModal').close());

$('editForm').addEventListener('submit', async (e) => {
  e.preventDefault();
  const id = Number($('editId').value);
  const task = tasks.find((t) => t.id === id);
  if (task) {
    task.text = $('editText').value.trim();
    task.subject = $('editSubject').value;
    task.priority = $('editPriority').value;
    task.date = $('editDate').value || null;
    renderAll();
    await saveTasks();
  }
  $('editModal').close();
});

$('tasksContainer').addEventListener('change', async (e) => {
  if (!e.target.matches('[data-toggle]')) return;
  const task = tasks.find((t) => t.id === Number(e.target.dataset.toggle));
  if (task) {
    task.completed = e.target.checked;
    renderAll();
    await saveTasks();
  }
});

// --- Rendu ---

function renderAll() {
  renderTasks();
  updateProgress();
  updateFilterCounts();
}

function getVisibleTasks() {
  const priorityRank = { high: 0, medium: 1, low: 2 };

  return tasks
    .filter((t) => {
      if (currentFilter === 'active' && t.completed) return false;
      if (currentFilter === 'completed' && !t.completed) return false;
      if (currentFilter === 'high' && (t.priority !== 'high' || t.completed)) return false;
      if (searchQuery) {
        const hay = `${t.text} ${t.subject}`.toLowerCase();
        if (!hay.includes(searchQuery)) return false;
      }
      return true;
    })
    .sort((a, b) => {
      if (sortBy === 'priority') return priorityRank[a.priority] - priorityRank[b.priority];
      if (sortBy === 'date') return (a.date || '9999-12-31').localeCompare(b.date || '9999-12-31');
      return b.id - a.id;
    });
}

function renderTasks() {
  const list = getVisibleTasks();
  if (!list.length) {
    $('tasksContainer').innerHTML = `
      <div class="empty-state">
        <i class="fas fa-clipboard-list"></i>
        <p>Aucune tâche à afficher.</p>
      </div>`;
    return;
  }

  const labels = { high: 'Haute', medium: 'Moyenne', low: 'Basse' };
  const today = new Date(new Date().toDateString());

  $('tasksContainer').innerHTML = list.map((t) => {
    const overdue = t.date && !t.completed && new Date(t.date + 'T00:00:00') < today;
    return `
      <div class="task-card priority-${t.priority} ${t.completed ? 'completed' : ''}">
        <div class="checkbox-wrapper">
          <input type="checkbox" id="t-${t.id}" data-toggle="${t.id}" ${t.completed ? 'checked disabled' : ''}>
          <label class="custom-checkbox" for="t-${t.id}"><i class="fas fa-check"></i></label>
        </div>
        <div class="task-content">
          <div class="task-text">${escapeHTML(t.text)}</div>
          <div class="task-meta">
            <span class="task-subject">${escapeHTML(t.subject)}</span>
            <span class="priority-badge ${t.priority}">${labels[t.priority]}</span>
            ${t.date ? `<span class="task-date ${overdue ? 'overdue' : ''}">
              <i class="far fa-calendar-alt"></i> ${formatDate(t.date)}${overdue ? ' (en retard)' : ''}
            </span>` : ''}
          </div>
        </div>
        <div class="task-actions">
          ${!t.completed ? `
          <button class="btn-icon btn-edit" data-edit="${t.id}" title="Modifier">
            <i class="fas fa-pen"></i>
          </button>` : ''}
          <button class="btn-icon btn-delete" data-delete="${t.id}" title="Supprimer">
            <i class="fas fa-trash-alt"></i>
          </button>
        </div>
      </div>`;
  }).join('');
}

function updateProgress() {
  const total = tasks.length;
  const done = tasks.filter((t) => t.completed).length;
  const pct = total ? Math.round((done / total) * 100) : 0;
  $('progressFill').style.width = pct + '%';
  $('progressPercentage').textContent = pct + '\%';$('completedCount').textContent = `${done} terminée(s)`;
  $('remainingCount').textContent = `${total - done} restante(s)`;
}

function updateFilterCounts() {
  $('countAll').textContent = tasks.length;
  $('countActive').textContent = tasks.filter((t) => !t.completed).length;
  $('countCompleted').textContent = tasks.filter((t) => t.completed).length;
}

// Thème sombre
$('themeToggle').addEventListener('click', () => {
  const isDark = document.documentElement.classList.toggle('dark');
  localStorage.setItem('sp-theme', isDark ? 'dark' : 'light');
});

function formatDate(dateStr) {
  return new Date(dateStr + 'T00:00:00').toLocaleDateString('fr-FR', { day: 'numeric', month: 'short' });
}

function escapeHTML(str) {
  const div = document.createElement('div');
  div.textContent = str;
  return div.innerHTML;
}

checkSession();