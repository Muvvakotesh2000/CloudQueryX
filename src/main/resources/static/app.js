/* ═══════════════════════════════════════════════════════════════
   CloudQueryX — Developer Platform
   ═══════════════════════════════════════════════════════════════ */
var token = sessionStorage.getItem('cqx_demo_token');
var currentDbId = null;
var currentSection = 'code';
var currentExplorerTab = 'memories';
var currentCodeProjectId = null;
var currentCodeFiles = [];
var chatMemorySuggestions = [];
var lastAutoSavedContext = [];
var forceGraph = null;
var demoMode = sessionStorage.getItem('cqx_demo_mode') === '1';
var demoMessageCount = Number(sessionStorage.getItem('cqx_demo_messages') || '0');
var DEMO_MESSAGE_LIMIT = 20;
var publicConfig = {};

var API = '';
function api(path, opts) {
  opts = opts || {};
  var headers = Object.assign({ 'Content-Type': 'application/json' }, opts.headers || {});
  if (token) headers['Authorization'] = 'Bearer ' + token;
  if (currentDbId) headers['X-Database-Id'] = currentDbId;
  return fetch(API + path, Object.assign({}, opts, { headers: headers }))
    .then(function(r) {
      return r.text().then(function(text) {
        var data = {};
        if (text) {
          try { data = JSON.parse(text); }
          catch (e) { data = { error: text }; }
        }
        if (!r.ok && !data.error) data.error = 'Request failed with status ' + r.status;
        data.statusCode = r.status;
        return data;
      });
    })
    .catch(function(e) { return { error: 'Network error: ' + e.message }; });
}

function esc(s) { var d = document.createElement('div'); d.textContent = s || ''; return d.innerHTML; }
function previewText(t, max) { t = t || ''; return t.length > (max || 120) ? t.substring(0, max || 120) + '...' : t; }
function emptyTableHtml(title, desc, addFn, addLabel) {
  return '<div class="empty-table"><p class="empty-table-title">' + esc(title) + '</p>' +
    '<p class="muted">' + esc(desc) + '</p>' +
    '<button class="btn-primary-sm" onclick="' + addFn + '">+ ' + esc(addLabel) + '</button></div>';
}

// ═══════════════════════════════════════════════════════════════
// TOAST
// ═══════════════════════════════════════════════════════════════
function showToast(message, type) {
  type = type || 'success';
  var container = document.getElementById('toast-container');
  var toast = document.createElement('div');
  toast.className = 'toast ' + type;
  toast.innerHTML = '<span>' + esc(message) + '</span><button onclick="this.parentElement.remove()">&times;</button>';
  container.appendChild(toast);
  setTimeout(function() { toast.classList.add('show'); }, 10);
  setTimeout(function() { toast.classList.remove('show'); setTimeout(function() { toast.remove(); }, 300); }, 4000);
}

// ═══════════════════════════════════════════════════════════════
// PUBLIC DEMO SESSION
// ═══════════════════════════════════════════════════════════════
async function startDemo() {
  showToast('Preparing a temporary demo workspace...', 'info');
  var res = await api('/api/demo/start', { method: 'POST', body: JSON.stringify({}) });
  if (res.error) {
    showToast(res.error, 'error');
    return;
  }
  token = res.token;
  demoMode = true;
  demoMessageCount = 0;
  sessionStorage.setItem('cqx_demo_token', token);
  sessionStorage.setItem('cqx_demo_mode', '1');
  sessionStorage.setItem('cqx_demo_messages', '0');
  localStorage.removeItem('cqx_token');
  showToast('Cloud coding workspace ready. Temporary data will be deleted when this session ends.');
  showApp(res.user, res.defaultDatabase);
  updateDemoMeter();
}

async function ensureDemoSession() {
  if (token && currentDbId) return true;
  showToast('Starting a fresh temporary demo...', 'info');
  var res = await api('/api/demo/start', { method: 'POST', body: JSON.stringify({}) });
  if (res.error) {
    showToast(res.error, 'error');
    return false;
  }
  token = res.token;
  demoMode = true;
  demoMessageCount = 0;
  sessionStorage.setItem('cqx_demo_token', token);
  sessionStorage.setItem('cqx_demo_mode', '1');
  sessionStorage.setItem('cqx_demo_messages', '0');
  localStorage.removeItem('cqx_token');
  showApp(res.user, res.defaultDatabase);
  updateDemoMeter();
  return true;
}

function updateDemoMeter() {
  var el = document.getElementById('demo-message-count');
  if (el) el.textContent = demoMessageCount + ' / ' + DEMO_MESSAGE_LIMIT + ' messages';
}

function cleanupDemoSession(useBeacon) {
  var demoToken = sessionStorage.getItem('cqx_demo_token');
  if (!demoToken) return;
  var payload = JSON.stringify({ token: demoToken });
  if (useBeacon && navigator.sendBeacon) {
    navigator.sendBeacon('/api/demo/end', new Blob([payload], { type: 'application/json' }));
  } else {
    fetch('/api/demo/end', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: payload,
      keepalive: true
    }).catch(function() {});
  }
  sessionStorage.removeItem('cqx_demo_token');
  sessionStorage.removeItem('cqx_demo_mode');
  sessionStorage.removeItem('cqx_demo_messages');
}

function endDemoAndReturn() {
  cleanupDemoSession(false);
  token = null;
  currentDbId = null;
  demoMode = false;
  demoMessageCount = 0;
  document.getElementById('app-view').style.display = 'none';
  document.getElementById('landing-view').style.display = 'block';
  showToast('Demo ended and temporary data was deleted.');
}

// ═══════════════════════════════════════════════════════════════
// ACTION MODAL
// ═══════════════════════════════════════════════════════════════
function showActionModal(title, html) {
  var m = document.getElementById('action-modal');
  var c = document.getElementById('action-modal-content');
  c.innerHTML = '<button class="modal-close" onclick="closeActionModal()">&times;</button><h2>' + esc(title) + '</h2>' + html;
  m.style.display = 'flex';
}
function closeActionModal() { document.getElementById('action-modal').style.display = 'none'; }

// ═══════════════════════════════════════════════════════════════
// AUTH
// ═══════════════════════════════════════════════════════════════
function showAuth(mode) {
  document.getElementById('auth-modal').style.display = 'flex';
  switchAuthTab(mode || 'login');
}
function closeAuth() { document.getElementById('auth-modal').style.display = 'none'; }
function switchAuthTab(tab) {
  document.getElementById('tab-login').classList.toggle('active', tab === 'login');
  document.getElementById('tab-signup').classList.toggle('active', tab === 'signup');
  document.getElementById('login-form').style.display = tab === 'login' ? 'block' : 'none';
  document.getElementById('signup-form').style.display = tab === 'signup' ? 'block' : 'none';
  document.getElementById('auth-title').textContent = tab === 'login' ? 'Log in to CloudQueryX' : 'Create your account';
  document.getElementById('auth-subtitle').textContent = tab === 'login' ? 'Access your context databases' : 'Get started with CloudQueryX';
  setAuthMessage('login', '');
  setAuthMessage('signup', '');
  setAuthBusy('login', false);
  setAuthBusy('signup', false);
}

function setAuthMessage(mode, message, type) {
  var el = document.getElementById(mode + '-message') || document.getElementById(mode + '-error');
  if (!el) return;
  el.textContent = message || '';
  el.className = 'form-result ' + (type || '');
}

function setAuthBusy(mode, busy) {
  var form = document.getElementById(mode + '-form');
  if (!form) return;
  var button = form.querySelector('button[type="submit"]');
  if (!button) return;
  if (!button.dataset.label) button.dataset.label = button.textContent;
  button.disabled = busy;
  button.textContent = busy ? (mode === 'login' ? 'Logging in...' : 'Creating account...') : button.dataset.label;
}

function handleSignup(e) {
  e.preventDefault();
  var email = document.getElementById('signup-email').value;
  var password = document.getElementById('signup-password').value;
  setAuthMessage('signup', 'Creating your account...', 'info');
  setAuthBusy('signup', true);
  authRequest('signup', email, password)
    .then(function(res) {
      setAuthBusy('signup', false);
      if (res.pendingVerification) {
        setAuthMessage('signup', 'Verification email sent. Confirm your email, then log in.', 'success');
        showToast('Check your email to verify your account.');
        setTimeout(function() { switchAuthTab('login'); }, 900);
        return;
      }
      if (res.error) {
        setAuthMessage('signup', res.error, 'error');
        showToast(res.error, 'error');
        return;
      }
      token = res.token; localStorage.setItem('cqx_token', token);
      setAuthMessage('signup', 'Account created. Opening your chat demo...', 'success');
      showToast('Account created. Opening chat demo.');
      setTimeout(function() { closeAuth(); showApp(res.user, res.defaultDatabase); }, 300);
    });
  return false;
}

function handleLogin(e) {
  e.preventDefault();
  var email = document.getElementById('login-email').value;
  var password = document.getElementById('login-password').value;
  setAuthMessage('login', 'Checking your account...', 'info');
  setAuthBusy('login', true);
  authRequest('login', email, password)
    .then(function(res) {
      setAuthBusy('login', false);
      if (res.error) {
        setAuthMessage('login', res.error, 'error');
        showToast(res.error, 'error');
        return;
      }
      token = res.token; localStorage.setItem('cqx_token', token);
      setAuthMessage('login', 'Logged in. Opening your chat demo...', 'success');
      showToast('Logged in. Opening chat demo.');
      setTimeout(function() { closeAuth(); showApp(res.user, res.defaultDatabase); }, 300);
    });
  return false;
}

async function loadPublicConfig() {
  var res = await api('/api/config/public');
  if (!res.error) publicConfig = res;
}

async function authRequest(mode, email, password) {
  if (publicConfig.supabaseAuthEnabled && publicConfig.supabaseUrl && publicConfig.supabaseAnonKey) {
    var endpoint = mode === 'signup'
      ? '/auth/v1/signup'
      : '/auth/v1/token?grant_type=password';
    var supabaseRes = await fetch(publicConfig.supabaseUrl.replace(/\/$/, '') + endpoint, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'apikey': publicConfig.supabaseAnonKey,
        'Authorization': 'Bearer ' + publicConfig.supabaseAnonKey
      },
      body: JSON.stringify({ email: email, password: password })
    }).then(function(r) {
      return r.text().then(function(text) {
        var data = {};
        try { data = text ? JSON.parse(text) : {}; } catch (e) { data = { error: text }; }
        if (!r.ok && !data.error) data.error = data.msg || ('Supabase auth failed with status ' + r.status);
        return data;
      });
    }).catch(function(e) { return { error: e.message }; });
    if (supabaseRes.error) return { error: supabaseRes.error_description || supabaseRes.error };
    if (mode === 'signup') {
      return { pendingVerification: true, email: email };
    }
    if (!supabaseRes.access_token) {
      return { error: 'Check your email to confirm your account, then log in.' };
    }
    token = supabaseRes.access_token;
    var me = await api('/api/auth/me');
    if (me.error) return me;
    return { token: token, user: { id: me.id, email: me.email }, defaultDatabase: me.defaultDatabase };
  }
  return api(mode === 'signup' ? '/api/auth/signup' : '/api/auth/login', {
    method: 'POST',
    body: JSON.stringify({ email: email, password: password })
  });
}

function logout() {
  if (demoMode) {
    cleanupDemoSession(false);
  } else {
    api('/api/auth/logout', { method: 'POST' });
  }
  token = null; currentDbId = null;
  localStorage.removeItem('cqx_token');
  sessionStorage.removeItem('cqx_demo_token');
  sessionStorage.removeItem('cqx_demo_mode');
  sessionStorage.removeItem('cqx_demo_messages');
  document.getElementById('app-view').style.display = 'none';
  document.getElementById('landing-view').style.display = 'block';
}

function showApp(user, db) {
  document.getElementById('landing-view').style.display = 'none';
  document.getElementById('app-view').style.display = 'block';
  document.getElementById('sidebar-email').textContent = demoMode ? 'Public demo session' : (user ? user.email : '');
  if (db) openDatabase(db);
  else loadDatabases();
  checkHealth();
  updateDemoMeter();
}

function backToLanding() {
  logout();
}

// ═══════════════════════════════════════════════════════════════
// DATABASE
// ═══════════════════════════════════════════════════════════════
function loadDatabases() {
  api('/api/databases').then(function(res) {
    var dbs = res.databases || [];
    if (dbs.length > 0) openDatabase(dbs[0]);
  });
}

function openDatabase(db) {
  currentDbId = db.id;
  document.getElementById('sidebar-db-name').textContent = db.name || 'Context Database';
  document.getElementById('no-db-view').style.display = 'none';
  document.getElementById('db-view').style.display = 'block';
  showSection('code');
}

// ═══════════════════════════════════════════════════════════════
// HEALTH
// ═══════════════════════════════════════════════════════════════
function checkHealth() {
  api('/api/health').then(function(res) {
    var dot = document.getElementById('health-dot');
    if (res.status === 'ok' || res.status === 'UP') {
      dot.className = 'health-indicator ok';
      dot.querySelector('.health-text').textContent = 'Healthy';
    } else {
      dot.className = 'health-indicator err';
      dot.querySelector('.health-text').textContent = 'Error';
    }
  });
}

// ═══════════════════════════════════════════════════════════════
// NAVIGATION
// ═══════════════════════════════════════════════════════════════
function showSection(name) {
  if (name !== 'playground' && name !== 'code' && name !== 'explorer') name = 'code';
  currentSection = name;
  document.querySelectorAll('#db-view > .section').forEach(function(s) {
    s.classList.remove('active-section');
  });
  var el = document.getElementById('section-' + name);
  if (el) el.classList.add('active-section');

  document.querySelectorAll('.sidebar-item').forEach(function(item) {
    item.classList.toggle('active', item.dataset.section === name);
  });

  switch (name) {
    case 'overview': loadOverviewData(); break;
    case 'code': loadCodeProjects(); break;
    case 'explorer': loadExplorerTab(currentExplorerTab); break;
    case 'graph': loadGraphData(); break;
    case 'api-reference': initApiReference(); break;
    case 'api-keys': loadApiKeys(); break;
    case 'webhooks': loadWebhooks(); break;
    case 'settings': loadSettings(); break;
  }

  if (window.innerWidth <= 768) closeSidebar();
}

var EXPLORER_TAB_INFO = {
  memories: 'Facts, preferences, decisions, and conversation notes the assistant can recall later.',
  sources: 'Longer text such as docs, code, logs, and notes. CloudQueryX chunks and searches this text.',
  entities: 'Named things CloudQueryX understands: people, projects, tools, services, and models.',
  relationships: 'Connections between entities, such as CloudQueryX USES Supabase.',
  events: 'Timeline records such as decisions, deployments, incidents, and changes.',
  vectors: 'Embedding records used for semantic search. Most users can ignore this unless they are testing retrieval.'
};

function switchExplorerTab(tab) {
  currentExplorerTab = tab;
  document.querySelectorAll('#explorer-tabs .subtab').forEach(function(t) {
    t.classList.toggle('active', t.dataset.subtab === tab);
  });
  document.querySelectorAll('.explorer-panel').forEach(function(p) { p.classList.remove('active'); });
  var panel = document.getElementById('explorer-' + tab);
  if (panel) panel.classList.add('active');
  var desc = document.getElementById('explorer-tab-desc');
  if (desc) desc.textContent = EXPLORER_TAB_INFO[tab] || '';
  loadExplorerTab(tab);
}

function toggleSidebar() {
  document.getElementById('sidebar').classList.toggle('open');
  document.getElementById('sidebar-overlay').classList.toggle('open');
}
function closeSidebar() {
  document.getElementById('sidebar').classList.remove('open');
  document.getElementById('sidebar-overlay').classList.remove('open');
}
function toggleMobileMenu() {
  document.getElementById('mobile-menu').classList.toggle('open');
}

// ═══════════════════════════════════════════════════════════════
// OVERVIEW
// ═══════════════════════════════════════════════════════════════
function loadOverviewData() {
  Promise.all([
    api('/api/memory'),
    api('/api/sources'),
    api('/api/semantic', { method: 'POST', body: JSON.stringify({ action: 'list_entities', limit: 500 }) }),
    api('/api/semantic', { method: 'POST', body: JSON.stringify({ action: 'list_relationships', limit: 500 }) }),
    api('/api/events?limit=10')
  ]).then(function(results) {
    var memCount = results[0].count || 0;
    var srcCount = (results[1].sources || []).length;
    var entCount = results[2].count || (results[2].entities || []).length;
    var relCount = results[3].count || (results[3].relationships || []).length;
    var events = results[4].events || [];

    renderStatCards(memCount, srcCount, entCount, relCount, events.length);
    renderActivityFeed(events);
    renderGettingStarted(memCount + srcCount);
  });
}

// ─── Getting Started checklist ──────────────────────────────────────
function renderGettingStarted(dataCount) {
  var card = document.getElementById('getting-started');
  if (localStorage.getItem('cqx_gs_dismissed')) { card.style.display = 'none'; return; }

  var steps = [
    {
      done: dataCount > 0,
      title: 'Store useful context',
      desc: 'Add a memory or source so the assistant has something real to retrieve.',
      action: 'Open Stored Context', fn: "showSection('explorer')"
    },
    {
      done: !!localStorage.getItem('cqx_playground_used'),
      title: 'Try the coding assistant',
      desc: 'Upload a file, ask a coding task, and inspect exactly which context CloudQueryX selected.',
      action: 'Open Code Workspace', fn: "showSection('code')"
    }
  ];

  var allDone = steps.every(function(s) { return s.done; });
  if (allDone) { card.style.display = 'none'; return; }

  card.style.display = '';
  document.getElementById('gs-steps').innerHTML = steps.map(function(s) {
    return '<div class="gs-step ' + (s.done ? 'done' : '') + '">' +
      '<div class="gs-step-icon">' + (s.done ? '✓' : '') + '</div>' +
      '<div class="gs-step-body">' +
        '<div class="gs-step-title">' + esc(s.title) + '</div>' +
        '<div class="gs-step-desc">' + esc(s.desc) + '</div>' +
      '</div>' +
      (s.done ? '' : '<button class="btn-sm" onclick="' + s.fn + '">' + esc(s.action) + '</button>') +
      '</div>';
  }).join('');
}

function dismissGettingStarted() {
  localStorage.setItem('cqx_gs_dismissed', '1');
  document.getElementById('getting-started').style.display = 'none';
}

function renderStatCards(mem, src, ent, rel, evt) {
  var cards = [
    { label: 'Memories', value: mem, cls: 'memories', icon: 'M' },
    { label: 'Sources', value: src, cls: 'sources', icon: 'S' },
    { label: 'Entities', value: ent, cls: 'entities', icon: 'E' },
    { label: 'Relationships', value: rel, cls: 'relationships', icon: 'R' },
    { label: 'Events', value: evt, cls: 'events', icon: 'Ev' }
  ];
  document.getElementById('overview-stats').innerHTML = cards.map(function(c) {
    return '<div class="stat-card">' +
      '<div class="stat-icon ' + c.cls + '">' + c.icon + '</div>' +
      '<div><div class="stat-value">' + c.value + '</div><div class="stat-label">' + c.label + '</div></div>' +
      '</div>';
  }).join('');
}

function renderActivityFeed(events) {
  var feed = document.getElementById('activity-feed');
  if (!events || events.length === 0) {
    feed.innerHTML = '<p class="muted">No activity yet. Start by storing some context.</p>';
    return;
  }
  feed.innerHTML = events.map(function(e) {
    var time = e.timestamp || e.createdAt || '';
    if (time) { try { time = new Date(time).toLocaleString(); } catch(x) {} }
    return '<div class="activity-item">' +
      '<div class="activity-dot"></div>' +
      '<div class="activity-text"><strong>' + esc(e.eventType || '') + '</strong> ' + esc(e.action || '') + '</div>' +
      '<div class="activity-time">' + esc(time) + '</div>' +
      '</div>';
  }).join('');
}

// ═══════════════════════════════════════════════════════════════
// PLAYGROUND (Chat)
// ═══════════════════════════════════════════════════════════════
function toggleChatSide() {
  document.getElementById('chat-side').classList.toggle('collapsed');
}

function fillChatExample(btn) {
  var input = document.getElementById('chat-input');
  input.value = btn.textContent;
  input.focus();
}

function fillScenario(kind) {
  var scenarios = {
    identity: 'My name is Kotesh Muvva. I am building CloudQueryX, a provider-neutral Context Runtime for LLM applications.',
    preference: 'Remember that I prefer concise engineering explanations with clear evidence and minimal fluff.',
    architecture: 'Explain CloudQueryX architecture and show which memories, sources, graph relationships, and events you used.',
    debug: 'Debug this deployment issue: Render is serving an old static app.js file after a new GitHub commit. What context should CloudQueryX retrieve?',
    evidence: 'Why did you say that? Show the context bundle, ranking reasons, and what new memory you stored.'
  };
  var input = document.getElementById('chat-input');
  input.value = scenarios[kind] || scenarios.architecture;
  input.focus();
}

async function sendAssistantMessage() {
  var input = document.getElementById('chat-input');
  var error = document.getElementById('chat-error');
  var message = input.value.trim();
  if (!message) return;
  var ready = await ensureDemoSession();
  if (!ready) {
    error.textContent = 'Could not start a temporary demo session. Please refresh and try again.';
    return;
  }
  if (demoMode && demoMessageCount >= DEMO_MESSAGE_LIMIT) {
    error.textContent = 'Demo limit reached. End the demo to clear the temporary data and start again.';
    showToast('Demo limit reached. Start a fresh demo to continue.', 'error');
    return;
  }
  error.textContent = '';
  input.value = '';
  localStorage.setItem('cqx_playground_used', '1');
  if (demoMode) {
    demoMessageCount += 1;
    sessionStorage.setItem('cqx_demo_messages', String(demoMessageCount));
    updateDemoMeter();
  }
  renderMiniFlow('Message');
  appendChatMessage('user', message);
  renderMiniFlow('Retrieve');
  appendChatMessage('assistant', 'Building context bundle...', true);

  var res = await api('/api/assistant/chat', {
    method: 'POST',
    body: JSON.stringify({
      message: message,
      targetModel: 'medium-context-model',
      tokenBudget: 8000,
      mode: inferChatMode(message),
      includeMemories: true,
      includeSources: true,
      includeGraph: true,
      includeEvents: true
    })
  });
  if (res.error && res.statusCode === 401) {
    sessionStorage.removeItem('cqx_demo_token');
    sessionStorage.removeItem('cqx_demo_mode');
    sessionStorage.removeItem('cqx_demo_messages');
    token = null;
    currentDbId = null;
    demoMode = false;
    demoMessageCount = 0;
    var recovered = await ensureDemoSession();
    if (recovered) {
      res = await api('/api/assistant/chat', {
        method: 'POST',
        body: JSON.stringify({
          message: message,
          targetModel: 'medium-context-model',
          tokenBudget: 8000,
          mode: inferChatMode(message),
          includeMemories: true,
          includeSources: true,
          includeGraph: true,
          includeEvents: true
        })
      });
    }
  }
  removeTypingMessage();
  if (res.error) {
    renderMiniFlow('Message');
    error.textContent = res.error;
    appendChatMessage('assistant', 'Could not complete the request: ' + res.error);
    return;
  }
  renderMiniFlow('Rank');
  renderMiniFlow('Bundle');
  renderChatContext(res.contextBundle || {}, message);
  renderMiniFlow('Answer');
  appendChatMessage('assistant', res.answer || 'No answer returned.');
  chatMemorySuggestions = normalizeMemorySuggestions(res.memorySuggestions || []);
  if (chatMemorySuggestions.length === 0) {
    chatMemorySuggestions = normalizeMemorySuggestions(suggestMemoryActions(message));
  }
  renderMiniFlow('Store');
  await autoStoreAssistantSuggestions();
  renderMiniFlow('Learn');
  if (chatMemorySuggestions.length === 0) renderAutoSavedContext();
}

function appendChatMessage(role, content, typing) {
  var thread = document.getElementById('chat-thread');
  var div = document.createElement('div');
  div.className = 'chat-message ' + role + (typing ? ' typing-message' : '');
  div.innerHTML = '<div class="chat-avatar">' + (role === 'user' ? 'You' : 'CQX') + '</div>' +
    '<div class="chat-bubble">' + formatAssistantText(content) + '</div>';
  thread.appendChild(div);
  thread.scrollTop = thread.scrollHeight;
}

function removeTypingMessage() {
  var t = document.querySelector('.typing-message');
  if (t) t.remove();
}

function formatAssistantText(text) {
  var d = document.createElement('div');
  d.textContent = text;
  return d.innerHTML.replace(/\n/g, '<br>');
}

function renderChatContext(bundle, query) {
  var box = document.getElementById('chat-context-summary');
  if (bundle.error) {
    box.innerHTML = '<div class="form-error">' + esc(bundle.error) + '</div>';
    renderContextQuality({}, []);
    renderTraceDebugger({}, query || '');
    return;
  }
  var items = bundle.items || [];
  if (items.length === 0) {
    box.innerHTML = '<p class="muted">No saved context matched. The LLM answered without CloudQueryX memory for this turn.</p>';
    renderContextQuality(bundle, items);
    renderTraceDebugger(bundle, query || '');
    return;
  }
  box.innerHTML =
    '<div class="bundle-summary compact"><span>' + items.length + ' items</span><span>' +
    (bundle.estimatedTokens || 0) + ' tokens</span><span>' + esc(bundle.freshnessStatus || 'VALID') + '</span></div>' +
    items.slice(0, 5).map(function(item) {
      return '<div class="context-chip"><strong>' + esc(item.type || item.itemType || 'CONTEXT') + '</strong>' +
        '<p>' + esc(previewText(item.content || '', 180)) + '</p><small>' + esc(item.reason || '') + '</small></div>';
    }).join('');
  renderContextQuality(bundle, items);
  renderTraceDebugger(bundle, query || '');
}

function renderContextQuality(bundle, items) {
  var el = document.getElementById('chat-quality-score');
  if (!el) return;
  items = items || [];
  var tokens = Number(bundle.estimatedTokens || 0);
  var budget = 8000;
  var types = {};
  items.forEach(function(item) { types[String(item.type || item.itemType || 'context').toLowerCase()] = true; });
  var typeCount = Object.keys(types).length;
  var coverage = Math.min(100, Math.round((items.length / 5) * 70 + typeCount * 8));
  var freshness = String(bundle.freshnessStatus || 'VALID').toUpperCase() === 'VALID' ? 92 : 62;
  var tokenEfficiency = tokens > 0 ? Math.max(35, Math.min(100, Math.round(100 - (tokens / budget) * 45))) : 55;
  var noiseRisk = items.length > 10 ? 38 : items.length > 0 ? 14 : 8;
  var score = Math.round(coverage * 0.35 + freshness * 0.25 + tokenEfficiency * 0.25 + (100 - noiseRisk) * 0.15);
  el.innerHTML =
    '<div class="quality-score"><strong>' + score + '%</strong><span>Context quality</span></div>' +
    '<div class="quality-bars">' +
      qualityBar('Coverage', coverage) +
      qualityBar('Freshness', freshness) +
      qualityBar('Token efficiency', tokenEfficiency) +
      qualityBar('Noise risk', 100 - noiseRisk) +
    '</div>';
}

function qualityBar(label, value) {
  return '<div class="quality-row"><span>' + esc(label) + '</span><div><i style="width:' + value + '%"></i></div><b>' + value + '</b></div>';
}

function renderTraceDebugger(bundle, query) {
  var el = document.getElementById('chat-trace-debugger');
  if (!el) return;
  var items = bundle.items || [];
  var counts = countBundleTypes(items);
  var tokens = bundle.estimatedTokens || 0;
  var topReason = items[0] ? (items[0].reason || 'Highest ranked context item selected.') : 'No stored context matched this turn.';
  var steps = [
    ['Input', query ? previewText(query, 90) : 'User message captured.'],
    ['Retrieve', counts.total + ' candidates selected from memory/source/graph/event context.'],
    ['Rank', topReason],
    ['Budget', tokens + ' estimated tokens placed into the context bundle.'],
    ['Handoff', 'Formatted context sent to the LLM; CloudQueryX does not own the final answer.'],
    ['Learn', 'Assistant suggestions are stored as memory, source, entity, relationship, or event records.']
  ];
  el.innerHTML =
    '<div class="trace-metrics">' +
      '<span>Memory ' + counts.memory + '</span><span>Source ' + counts.source + '</span><span>Graph ' + counts.graph + '</span><span>Event ' + counts.event + '</span>' +
    '</div>' +
    '<ol class="trace-list">' + steps.map(function(step) {
      return '<li><strong>' + esc(step[0]) + '</strong><p>' + esc(step[1]) + '</p></li>';
    }).join('') + '</ol>';
}

// ═══════════════════════════════════════════════════════════════
// CODE WORKSPACE
// ═══════════════════════════════════════════════════════════════
async function loadCodeProjects() {
  var ready = await ensureDemoSession();
  if (!ready) return;
  var box = document.getElementById('code-project-list');
  if (!box) return;
  box.innerHTML = '<p class="muted">Loading projects...</p>';
  var res = await api('/api/code/projects');
  if (res.error) {
    box.innerHTML = '<p class="form-error">' + esc(res.error) + '</p>';
    return;
  }
  var projects = res.projects || [];
  if (!currentCodeProjectId && projects.length > 0) currentCodeProjectId = projects[0].id;
  renderCodeProjects(projects);
  if (currentCodeProjectId) loadCodeFiles();
}

function renderCodeProjects(projects) {
  var box = document.getElementById('code-project-list');
  if (!box) return;
  if (!projects || projects.length === 0) {
    box.innerHTML = '<p class="muted">No cloud code projects yet.</p>';
    var emptyTitle = document.getElementById('ide-project-title');
    if (emptyTitle) emptyTitle.textContent = 'Cloud Coding Workspace';
    return;
  }
  var activeProject = projects.find(function(project) { return project.id === currentCodeProjectId; });
  var title = document.getElementById('ide-project-title');
  if (title) title.textContent = activeProject ? activeProject.name : 'Cloud Coding Workspace';
  var renameInput = document.getElementById('code-project-name');
  if (renameInput) renameInput.value = activeProject ? activeProject.name : '';
  var descInput = document.getElementById('code-project-desc');
  if (descInput) descInput.value = activeProject ? (activeProject.description || '') : '';
  box.innerHTML = projects.map(function(project) {
    var active = project.id === currentCodeProjectId ? ' active' : '';
    return '<button class="code-list-item' + active + '" onclick="selectCodeProject(\'' + esc(project.id) + '\')">' +
      '<strong>' + esc(project.name) + '</strong>' +
      '<span>' + esc(project.description || project.sourceType || 'Cloud project') + '</span>' +
      '</button>';
  }).join('');
}

function selectCodeProject(projectId) {
  currentCodeProjectId = projectId;
  setCodeUploadStatus('Opening project and loading indexed files...', 'info');
  var answer = document.getElementById('code-answer');
  if (answer) answer.innerHTML = '<p class="muted">Project opened. Ask a coding question after files are indexed.</p>';
  loadCodeProjects();
  loadCodeFiles();
}

function setCodeUploadStatus(message, type) {
  var el = document.getElementById('code-upload-status');
  if (!el) return;
  el.textContent = message || '';
  el.className = 'ide-upload-status ' + (type || '');
}

async function createCodeProject(nameOverride, descOverride, sourceTypeOverride, githubRepoUrl) {
  var ready = await ensureDemoSession();
  if (!ready) return;
  var name = (nameOverride || document.getElementById('code-project-name').value || '').trim();
  var desc = descOverride != null ? descOverride : (document.getElementById('code-project-desc').value || '').trim();
  if (!name) {
    showToast('Project name required', 'error');
    return null;
  }
  var res = await api('/api/code/projects', {
    method: 'POST',
    body: JSON.stringify({ name: name, description: desc, sourceType: sourceTypeOverride || 'upload', githubRepoUrl: githubRepoUrl || null })
  });
  if (res.error) {
    showToast(res.error, 'error');
    return null;
  }
  currentCodeProjectId = res.id;
  document.getElementById('code-project-name').value = res.name || name;
  document.getElementById('code-project-desc').value = res.description || desc || '';
  showToast('Cloud code project created');
  await loadCodeProjects();
  return res;
}

async function renameCodeProject() {
  var ready = await ensureDemoSession();
  if (!ready) return;
  if (!currentCodeProjectId) {
    showToast('Upload a folder or create a GitHub project first', 'error');
    return;
  }
  var name = (document.getElementById('code-project-name').value || '').trim();
  var desc = (document.getElementById('code-project-desc').value || '').trim();
  if (!name) {
    showToast('Project name required', 'error');
    return;
  }
  var res = await api('/api/code/projects/' + currentCodeProjectId, {
    method: 'PUT',
    body: JSON.stringify({ name: name, description: desc })
  });
  if (res.error) {
    showToast(res.error, 'error');
    return;
  }
  showToast('Project name updated');
  await loadCodeProjects();
}

function focusGitHubImport() {
  var input = document.getElementById('code-github-url');
  if (input) input.focus();
  showToast('Paste a GitHub repo URL. Full OAuth import and push require GitHub app credentials.', 'info');
}

async function createGitHubProject() {
  var url = (document.getElementById('code-github-url').value || '').trim();
  var name = repoNameFromGitHubUrl(url);
  if (!name) {
    showToast('Paste a valid GitHub repository URL', 'error');
    return;
  }
  await createCodeProject(name, 'GitHub repository: ' + url, 'github', url);
  showToast('Project created from GitHub repo name. Repo file import needs GitHub OAuth configuration.', 'info');
}

function repoNameFromGitHubUrl(url) {
  var value = String(url || '').trim().replace(/\.git$/i, '');
  var match = value.match(/github\.com[/:]([^/]+)\/([^/#?]+)/i);
  return match ? match[2] : '';
}

async function loadCodeFiles() {
  var box = document.getElementById('code-file-list');
  if (!box || !currentCodeProjectId) return;
  box.innerHTML = '<p class="muted">Loading files...</p>';
  var res = await api('/api/code/projects/' + currentCodeProjectId + '/files');
  if (res.error) {
    box.innerHTML = '<p class="form-error">' + esc(res.error) + '</p>';
    return;
  }
  var files = res.files || [];
  currentCodeFiles = files;
  if (files.length === 0) {
    box.innerHTML = '<p class="muted">No files indexed yet.</p>';
    var editor = document.getElementById('code-file-content');
    if (editor) editor.value = '';
    setCodeUploadStatus('Project opened. No indexed files yet.', 'info');
    return;
  }
  box.innerHTML = renderCodeFileTree(files);
  var firstKey = files[0] && files[0].s3Key ? files[0].s3Key : '';
  setCodeUploadStatus('Project opened with ' + files.length + ' indexed files.' + (firstKey ? ' S3 prefix: ' + firstKey.split('/files/')[0] + '/files/' : ''), 'success');
}

function focusCodeFilePath(path) {
  var input = document.getElementById('code-file-path');
  if (input) input.value = path || '';
  showToast('File path focused. Stored content is already indexed as context.', 'info');
}

function renderCodeFileTree(files) {
  var root = {};
  files.forEach(function(file) {
    var parts = String(file.path || 'untitled.txt').split('/').filter(Boolean);
    var node = root;
    parts.forEach(function(part, idx) {
      node.children = node.children || {};
      node.children[part] = node.children[part] || { name: part, children: {}, file: null };
      node = node.children[part];
      if (idx === parts.length - 1) node.file = file;
    });
  });
  return renderTreeNodes(root.children || {}, 0);
}

function renderTreeNodes(children, depth) {
  return Object.keys(children).sort(function(a, b) {
    var na = children[a], nb = children[b];
    if (!!na.file !== !!nb.file) return na.file ? 1 : -1;
    return a.localeCompare(b);
  }).map(function(name) {
    var node = children[name];
    if (node.file) {
      var file = node.file;
      return '<div class="ide-file-node" style="padding-left:' + (depth * 14 + 8) + 'px" onclick="focusCodeFilePath(\'' + esc(file.path) + '\')">' +
        '<span class="file-icon">' + esc(fileIcon(file.path)) + '</span>' +
        '<strong>' + esc(name) + '</strong>' +
        '<small>' + esc(file.language || 'text') + ' · v' + esc(String(file.version || 1)) + ' · ' + esc(formatBytes(file.sizeBytes || 0)) + '</small>' +
      '</div>';
    }
    return '<div class="ide-folder-node" style="padding-left:' + (depth * 14 + 8) + 'px"><span class="file-icon">▾</span><strong>' + esc(name) + '</strong></div>' +
      renderTreeNodes(node.children || {}, depth + 1);
  }).join('');
}

function fileIcon(path) {
  var lang = inferCodeLanguage(path);
  if (lang === 'java') return 'J';
  if (lang === 'javascript' || lang === 'typescript') return 'JS';
  if (lang === 'html') return '<>';
  if (lang === 'css') return '#';
  if (lang === 'json') return '{}';
  if (lang === 'markdown') return 'MD';
  if (lang === 'sql') return 'DB';
  return '•';
}

function isSkippableUpload(file) {
  var path = String(file.webkitRelativePath || file.name || '').replace(/\\/g, '/');
  var wrapped = '/' + path.toLowerCase();
  if (!path || wrapped.includes('/.git/') || wrapped.includes('/node_modules/') || wrapped.includes('/build/') || wrapped.includes('/dist/') || wrapped.includes('/target/') || wrapped.includes('/__pycache__/') || wrapped.includes('/.pytest_cache/') || wrapped.includes('/.next/') || wrapped.includes('/.gradle/')) return true;
  if (file.size > 1024 * 1024) return true;
  var lower = path.toLowerCase();
  return /\.(png|jpg|jpeg|gif|webp|ico|pdf|zip|jar|class|pyc|pyo|exe|dll|so|dylib|mp4|mov|mp3|wav|woff|woff2|ttf)$/i.test(lower);
}

async function uploadLocalProjectFiles(fileList) {
  var ready = await ensureDemoSession();
  if (!ready) return;
  var files = Array.prototype.slice.call(fileList || []).filter(function(file) { return !isSkippableUpload(file); });
  if (!files.length) {
    showToast('No supported text files found in that upload', 'error');
    setCodeUploadStatus('No supported text files found. Binary files and large dependency folders are skipped.', 'error');
    return;
  }
  var folderName = folderNameFromUpload(files) || 'Uploaded Project';
  setCodeUploadStatus('Creating project from folder "' + folderName + '"...', 'info');
  var project = await createCodeProject(folderName, 'Uploaded local folder: ' + folderName, 'folder', null);
  if (!project) return;
  var maxFiles = Math.min(files.length, 80);
  showToast('Created "' + folderName + '". Uploading ' + maxFiles + ' files.', 'info');
  var saved = 0;
  var failed = 0;
  var firstError = '';
  for (var i = 0; i < maxFiles; i++) {
    var file = files[i];
    var path = file.webkitRelativePath || file.name;
    setCodeUploadStatus('Uploading ' + (i + 1) + ' / ' + maxFiles + ': ' + path, 'info');
    try {
      var res = await uploadProjectFileWithPresignedUrl(path, file, inferCodeLanguage(path));
      if (!res.error) {
        saved++;
      } else {
        failed++;
        if (!firstError) firstError = res.error;
        break;
      }
    } catch (e) {
      failed++;
      if (!firstError) firstError = e.message || String(e);
      console.warn('Skipped upload', path, e);
      break;
    }
  }
  if (saved > 0) {
    setCodeUploadStatus('Uploaded ' + saved + ' files' + (failed ? '; ' + failed + ' failed. First error: ' + firstError : '') + '.', failed ? 'warn' : 'success');
    showToast('Uploaded and indexed ' + saved + ' files');
  } else {
    setCodeUploadStatus('Upload failed. First error: ' + (firstError || 'No files were saved.'), 'error');
    showToast('Upload failed: ' + (firstError || 'No files were saved.'), 'error');
  }
  var input = document.getElementById('code-folder-upload');
  if (input) input.value = '';
  loadCodeFiles();
  if (currentExplorerTab === 'sources') loadSourceTable();
}

async function uploadProjectFileWithPresignedUrl(path, fileOrBlob, language) {
  var contentType = contentTypeForUpload(language);
  var presign = await api('/api/code/projects/' + currentCodeProjectId + '/files/presign', {
    method: 'POST',
    body: JSON.stringify({ path: path, language: language, contentType: contentType, sizeBytes: fileOrBlob.size || 0 })
  });
  if (presign.error) return presign;

  var putRes;
  try {
    putRes = await fetch(presign.uploadUrl, {
      method: 'PUT',
      headers: { 'Content-Type': presign.contentType || contentType },
      body: fileOrBlob
    });
  } catch (e) {
    return uploadProjectFileThroughBackend(path, fileOrBlob, language,
      'Direct S3 upload blocked by browser/CORS; retried through backend.');
  }
  if (!putRes.ok) {
    return uploadProjectFileThroughBackend(path, fileOrBlob, language,
      'Direct S3 upload failed with status ' + putRes.status + '; retried through backend.');
  }

  return api('/api/code/projects/' + currentCodeProjectId + '/files/complete', {
    method: 'POST',
    body: JSON.stringify({ path: path, language: language, s3Key: presign.s3Key, sizeBytes: fileOrBlob.size || 0 })
  });
}

async function uploadProjectFileThroughBackend(path, fileOrBlob, language, note) {
  setCodeUploadStatus((note || 'Using backend upload fallback') + ' ' + path, 'warn');
  var content = await fileOrBlob.text();
  var res = await api('/api/code/projects/' + currentCodeProjectId + '/files', {
    method: 'POST',
    body: JSON.stringify({ path: path, content: content, language: language })
  });
  if (res.error && note) {
    res.error = note + ' Backend fallback failed: ' + res.error;
  }
  return res;
}

function contentTypeForUpload(language) {
  if (language === 'json') return 'application/json; charset=utf-8';
  if (language === 'html') return 'text/html; charset=utf-8';
  if (language === 'css') return 'text/css; charset=utf-8';
  if (language === 'javascript') return 'text/javascript; charset=utf-8';
  if (language === 'markdown') return 'text/markdown; charset=utf-8';
  return 'text/plain; charset=utf-8';
}

function folderNameFromUpload(files) {
  for (var i = 0; i < files.length; i++) {
    var path = files[i].webkitRelativePath || files[i].name || '';
    var parts = path.replace(/\\/g, '/').split('/').filter(Boolean);
    if (parts.length > 1) return parts[0];
  }
  return '';
}

async function saveCodeFile() {
  var ready = await ensureDemoSession();
  if (!ready) return;
  if (!currentCodeProjectId) {
    showToast('Create a code project first', 'error');
    return;
  }
  var path = document.getElementById('code-file-path').value.trim();
  var content = document.getElementById('code-file-content').value;
  if (!path || !content.trim()) {
    showToast('File path and content are required', 'error');
    return;
  }
  showToast('Saving file to cloud storage and indexing source...', 'info');
  var language = inferCodeLanguage(path);
  var res = await uploadProjectFileWithPresignedUrl(path, new Blob([content], { type: contentTypeForUpload(language) }), language);
  if (res.error) {
    showToast(res.error, 'error');
    return;
  }
  showToast('File saved and indexed');
  loadCodeFiles();
  if (currentExplorerTab === 'sources') loadSourceTable();
}

async function askCodeAssistant() {
  var ready = await ensureDemoSession();
  if (!ready) return;
  if (!currentCodeProjectId) {
    showToast('Create a code project first', 'error');
    return;
  }
  var task = document.getElementById('code-task').value.trim();
  var answer = document.getElementById('code-answer');
  if (!task) {
    showToast('Coding task required', 'error');
    return;
  }
  answer.innerHTML = '<p class="muted">Retrieving code context and building a bundle...</p>';
  var res = await api('/api/code/projects/' + currentCodeProjectId + '/ask', {
    method: 'POST',
    body: JSON.stringify({ task: task, tokenBudget: 12000 })
  });
  if (res.error) {
    answer.innerHTML = '<p class="form-error">' + esc(res.error) + '</p>';
    return;
  }
  renderCodeAnswer(res, task);
  chatMemorySuggestions = normalizeMemorySuggestions(res.memorySuggestions || []);
  await autoStoreAssistantSuggestions();
}

function renderCodeAnswer(res, task) {
  var answer = document.getElementById('code-answer');
  var bundle = res.contextBundle || {};
  var items = bundle.items || [];
  answer.innerHTML =
    '<div class="code-answer-block">' +
      '<h4>Answer</h4>' +
      '<div class="bundle-summary"><span>' + esc(res.model || 'model') + '</span><span>review first</span><span>no files changed</span></div>' +
      '<p>' + formatAssistantText(res.answer || 'No answer returned.') + '</p>' +
    '</div>' +
    '<div class="code-answer-block">' +
      '<h4>Context used</h4>' +
      '<div class="bundle-summary"><span>' + items.length + ' items</span><span>' + (bundle.estimatedTokens || 0) + ' tokens</span><span>' + esc(bundle.freshnessStatus || 'VALID') + '</span></div>' +
      (items.length ? items.slice(0, 8).map(function(item) {
        return '<div class="context-chip light"><strong>' + esc(item.type || item.itemType || 'CONTEXT') + '</strong>' +
          '<p>' + esc(previewText(item.content || '', 240)) + '</p><small>' + esc(item.reason || '') + '</small></div>';
      }).join('') : '<p class="muted">No indexed code context matched this task.</p>') +
    '</div>';
}

function inferCodeLanguage(path) {
  var lower = String(path || '').toLowerCase();
  if (lower.endsWith('.java')) return 'java';
  if (lower.endsWith('.js') || lower.endsWith('.jsx')) return 'javascript';
  if (lower.endsWith('.ts') || lower.endsWith('.tsx')) return 'typescript';
  if (lower.endsWith('.py')) return 'python';
  if (lower.endsWith('.sql')) return 'sql';
  if (lower.endsWith('.json')) return 'json';
  if (lower.endsWith('.yml') || lower.endsWith('.yaml')) return 'yaml';
  if (lower.endsWith('.md')) return 'markdown';
  if (lower.endsWith('.html')) return 'html';
  if (lower.endsWith('.css')) return 'css';
  if (lower.endsWith('.log')) return 'log';
  return 'text';
}

function formatBytes(bytes) {
  bytes = Number(bytes || 0);
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1024 * 1024) return Math.round(bytes / 1024) + ' KB';
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
}

function renderMiniFlow(activeLabel) {
  var el = document.getElementById('chat-flow-visual');
  if (!el) return;
  var labels = ['Message', 'Retrieve', 'Rank', 'Bundle', 'Answer', 'Store', 'Learn'];
  var activeIndex = Math.max(0, labels.indexOf(activeLabel || 'Message'));
  el.innerHTML = '<div class="mini-flow">' + labels.map(function(label, idx) {
    var cls = idx < activeIndex ? 'done' : (idx === activeIndex ? 'active' : '');
    return '<span class="' + cls + '">' + esc(label) + '</span>';
  }).join('') + '</div>';
}

function countBundleTypes(items) {
  var counts = { total: items.length, memory: 0, source: 0, graph: 0, event: 0 };
  items.forEach(function(item) {
    var type = String(item.type || item.itemType || '').toLowerCase();
    if (type.includes('memory')) counts.memory += 1;
    else if (type.includes('source') || type.includes('chunk')) counts.source += 1;
    else if (type.includes('entity') || type.includes('relationship') || type.includes('graph')) counts.graph += 1;
    else if (type.includes('event')) counts.event += 1;
  });
  return counts;
}

function normalizeMemorySuggestions(items) {
  var normalized = (items || [])
    .filter(function(item) { return item && (item.action || 'store') !== 'none'; })
    .map(function(item) {
      var type = normalizeSuggestionType(item.type);
      var content = cleanSuggestionContent(item.content || item.description || item.summary || item.name || '');
      var sourceEntity = cleanEntityName(item.sourceEntity || item.fromEntity || item.subject || '');
      var targetEntity = cleanEntityName(item.targetEntity || item.toEntity || item.object || '');
      var name = cleanEntityName(item.name || item.sourceName || sourceEntity || targetEntity || previewText(content, 80));
      return {
        type: type,
        memoryType: normalizeMemoryType(item.memoryType),
        sourceType: normalizeSourceType(item.sourceType, content),
        entityType: normalizeEntityType(item.entityType || inferEntityType(name, content)),
        sourceEntityType: normalizeEntityType(item.sourceEntityType || inferEntityType(sourceEntity, content)),
        targetEntityType: normalizeEntityType(item.targetEntityType || inferEntityType(targetEntity, content)),
        eventType: normalizeEventType(item.eventType, content),
        title: suggestionTitle(Object.assign({}, item, { type: type })),
        content: content,
        name: name,
        sourceName: item.sourceName || name || 'Assistant saved source',
        sourceEntity: sourceEntity,
        targetEntity: targetEntity,
        relationshipType: normalizeRelationshipType(item.relationshipType, content),
        importance: typeof item.importance === 'number' ? item.importance : 0.75,
        confidence: typeof item.confidence === 'number' ? item.confidence : 0.8,
        reason: item.reason || 'Suggested by the model.'
      };
    })
    .filter(function(item) {
      if (item.type === 'relationship') return item.sourceEntity && item.targetEntity;
      if (item.type === 'entity') return item.name;
      return item.content || item.name;
    });
  return uniqueSuggestions(normalized).slice(0, 18);
}

function suggestionTitle(item) {
  var type = String(item.type || 'memory').toLowerCase();
  if (type === 'source') return 'Store source';
  if (type === 'entity') return 'Save entity';
  if (type === 'relationship') return 'Save relationship';
  if (type === 'event') return 'Save event';
  return 'Save ' + (item.memoryType || 'memory').toLowerCase();
}

async function autoStoreAssistantSuggestions() {
  lastAutoSavedContext = [];
  var pending = chatMemorySuggestions.slice();
  for (var i = 0; i < pending.length; i++) {
    var result = await storeAssistantSuggestion(pending[i]);
    lastAutoSavedContext.push(Object.assign({}, pending[i], { saved: !result || !result.error, error: (result && result.error) || '' }));
  }
  chatMemorySuggestions = lastAutoSavedContext;
  renderAutoSavedContext();
}

function renderAutoSavedContext() {
  var div = document.getElementById('chat-memory-suggestions');
  if (lastAutoSavedContext.length === 0) {
    div.innerHTML = '<p class="muted">No new context stored from this turn.</p>';
    return;
  }
  div.innerHTML = lastAutoSavedContext.map(function(item) {
    return '<div class="suggestion-card">' +
      '<div class="suggestion-title">' + esc(item.title) + ' <span>' + esc(item.type) + '</span></div>' +
      '<div class="content">' + esc(previewText(item.content || item.name || '', 200)) + '</div>' +
      '<div class="reason">' + esc(item.error ? 'Failed: ' + item.error : item.reason || 'Stored automatically') + '</div>' +
      '</div>';
  }).join('');
}

async function storeAssistantSuggestion(suggestion) {
  var res;
  if (suggestion.type === 'memory') {
    res = await api('/api/memory', { method: 'POST', body: JSON.stringify({
      action: 'store',
      content: suggestion.content,
      type: normalizeMemoryType(suggestion.memoryType),
      importance: suggestion.importance || 0.8
    }) });
  } else if (suggestion.type === 'source') {
    res = await api('/api/sources', { method: 'POST', body: JSON.stringify({
      sourceType: normalizeSourceType(suggestion.sourceType, suggestion.content),
      sourceName: suggestion.sourceName || suggestion.name || 'Assistant saved context',
      content: suggestion.content,
      metadata: { origin: 'assistant', confidence: suggestion.confidence || 0.8, reason: suggestion.reason || '' }
    }) });
  } else if (suggestion.type === 'entity') {
    res = await api('/api/semantic', { method: 'POST', body: JSON.stringify({
      action: 'add_entity',
      entityType: normalizeEntityType(suggestion.entityType || inferEntityType(suggestion.name, suggestion.content)),
      name: suggestion.name || previewText(suggestion.content, 80),
      description: suggestion.content || suggestion.name,
      confidence: suggestion.confidence || 0.8,
      source: 'assistant'
    }) });
  } else if (suggestion.type === 'relationship') {
    res = await saveSuggestedRelationship(suggestion);
  } else if (suggestion.type === 'event') {
    res = await api('/api/events', { method: 'POST', body: JSON.stringify({
      eventType: normalizeEventType(suggestion.eventType, suggestion.content),
      action: suggestion.content
    }) });
  }
  return res || { error: 'Unsupported type: ' + suggestion.type };
}

async function saveSuggestedRelationship(suggestion) {
  var leftName = cleanEntityName(suggestion.sourceEntity || 'User');
  var rightName = cleanEntityName(suggestion.targetEntity || previewText(suggestion.content, 80) || 'Context');
  var leftType = normalizeEntityType(suggestion.sourceEntityType || inferEntityType(leftName, suggestion.content));
  var rightType = normalizeEntityType(suggestion.targetEntityType || inferEntityType(rightName, suggestion.content));
  var left = await api('/api/semantic', { method: 'POST', body: JSON.stringify({ action: 'add_entity', entityType: leftType, name: leftName, description: suggestion.content || leftName }) });
  if (left.error) return left;
  var right = await api('/api/semantic', { method: 'POST', body: JSON.stringify({ action: 'add_entity', entityType: rightType, name: rightName, description: suggestion.content || rightName }) });
  if (right.error) return right;
  return api('/api/semantic', { method: 'POST', body: JSON.stringify({
    action: 'add_relationship',
    sourceEntityId: left.id,
    targetEntityId: right.id,
    relationshipType: normalizeRelationshipType(suggestion.relationshipType, suggestion.content),
    confidence: suggestion.confidence || 0.8,
    source: 'assistant'
  }) });
}

function suggestMemoryActions(message) {
  var text = message.trim();
  var suggestions = [];
  var seenEntities = {};
  function addEntity(name, entityType, content, reason) {
    name = cleanEntityName(name);
    if (!name || seenEntities[name.toLowerCase()]) return;
    seenEntities[name.toLowerCase()] = true;
    suggestions.push({ type: 'entity', entityType: normalizeEntityType(entityType || inferEntityType(name, content)), name: name, content: content || name, title: 'Save entity: ' + name, reason: reason || 'Named thing mentioned.' });
  }
  function addRelationship(source, target, relType, content, reason) {
    source = cleanEntityName(source); target = cleanEntityName(target);
    if (!source || !target) return;
    suggestions.push({
      type: 'relationship',
      sourceEntity: source,
      targetEntity: target,
      sourceEntityType: normalizeEntityType(inferEntityType(source, content)),
      targetEntityType: normalizeEntityType(inferEntityType(target, content)),
      relationshipType: normalizeRelationshipType(relType, content),
      content: content || (source + ' ' + relType + ' ' + target),
      title: 'Save relationship',
      reason: reason || 'Relationship mentioned.'
    });
  }
  if (/\b(my name is|i am|i'm|i work|i build|my project|i use|i used|i made|i created|i wrote|i develop|i code in)\b/i.test(text))
    suggestions.push({ type: 'memory', memoryType: 'FACT', title: 'Save fact', content: text, importance: 0.85, reason: 'Stable personal or project fact.' });
  if (/\b(i prefer|i like|i want|i hate|i don't like|my favorite)\b/i.test(text))
    suggestions.push({ type: 'memory', memoryType: 'PREFERENCE', title: 'Save preference', content: text, importance: 0.8, reason: 'Preference for personalization.' });
  extractKnownEntities(text).forEach(function(entity) { addEntity(entity.name, entity.type, entity.description, entity.reason); });
  extractKnownRelationships(text).forEach(function(rel) {
    addEntity(rel.source, inferEntityType(rel.source, text), rel.source + ' mentioned in conversation.', 'Relationship endpoint.');
    addEntity(rel.target, inferEntityType(rel.target, text), rel.target + ' mentioned in conversation.', 'Relationship endpoint.');
    addRelationship(rel.source, rel.target, rel.type, rel.content || text, rel.reason);
  });
  var relPatterns = text.match(/(\w+)(?:'s|s')?\s+(brother|sister|mother|father|son|daughter|cousin|spouse|friend|wife|husband|uncle|aunt)\s+(?:is\s+)?(\w+)/gi);
  if (relPatterns) {
    relPatterns.forEach(function(match) {
      var parts = match.match(/(\w+)(?:'s|s')?\s+(brother|sister|mother|father|son|daughter|cousin|spouse|friend|wife|husband|uncle|aunt)\s+(?:is\s+)?(\w+)/i);
      if (parts) {
        var left = parts[1], rel = parts[2].toUpperCase() + '_OF', right = parts[3];
        addEntity(left, 'PERSON', left, 'Person mentioned.');
        addEntity(right, 'PERSON', right, 'Person mentioned.');
        addRelationship(left, right, rel, match, left + ' ' + rel.replace(/_/g, ' ').toLowerCase() + ' ' + right);
      }
    });
  }
  if (/\b(decided|decision|changed|started|launched|fixed|broke|deployed|migrated|released|shipped)\b/i.test(text))
    suggestions.push({ type: 'event', eventType: 'USER_TIMELINE', title: 'Save event', content: text, reason: 'Timeline event.' });
  if (text.length > 280 || /\b(code|log|document|architecture|config|readme|error|stack trace|exception)\b/i.test(text))
    suggestions.push({ type: 'source', sourceType: inferSourceType(text), title: 'Store as source', content: text, reason: 'Technical text.' });
  if (suggestions.length === 0 && text.length > 20)
    suggestions.push({ type: 'memory', memoryType: 'CONVERSATION', title: 'Save note', content: text, reason: 'Conversational context.' });
  return uniqueSuggestions(normalizeMemorySuggestions(suggestions)).slice(0, 18);
}

function inferChatMode(message) {
  var lower = message.toLowerCase();
  if (lower.includes('bug') || lower.includes('error') || lower.includes('failing')) return 'debugging';
  if (lower.includes('code') || lower.includes('build') || lower.includes('implement')) return 'coding';
  if (lower.includes('plan') || lower.includes('architecture') || lower.includes('design')) return 'planning';
  return 'general';
}

function inferSourceType(text) {
  var lower = text.toLowerCase();
  if (lower.includes('function') || lower.includes('class') || lower.includes('import')) return 'code';
  if (lower.includes('error') || lower.includes('exception') || lower.includes('stack')) return 'log';
  if (lower.includes('# ') || lower.includes('## ')) return 'markdown';
  if (lower.includes('database_url') || lower.includes('api_key') || lower.includes('config')) return 'config';
  if (lower.includes('architecture') || lower.includes('workflow') || lower.includes('design')) return 'document';
  return 'note';
}

function normalizeSuggestionType(type) {
  var value = String(type || 'memory').toLowerCase().trim();
  if (['memory', 'source', 'entity', 'relationship', 'event'].includes(value)) return value;
  if (value.includes('graph') || value.includes('edge')) return 'relationship';
  if (value.includes('node')) return 'entity';
  if (value.includes('document') || value.includes('chunk')) return 'source';
  return 'memory';
}

function normalizeMemoryType(type) {
  var value = String(type || 'CONVERSATION').toUpperCase().replace(/[^A-Z_]/g, '');
  var allowed = ['FACT', 'PREFERENCE', 'DECISION', 'CONVERSATION', 'FEEDBACK', 'WORKING', 'SEMANTIC', 'EPISODIC', 'PROCEDURAL'];
  return allowed.includes(value) ? value : 'CONVERSATION';
}

function normalizeSourceType(type, content) {
  var value = String(type || '').toLowerCase().replace(/[^a-z_]/g, '');
  var allowed = ['document', 'code', 'log', 'conversation', 'note', 'markdown', 'config'];
  return allowed.includes(value) ? value : inferSourceType(content || '');
}

function normalizeEntityType(type) {
  var value = String(type || 'CONCEPT').toUpperCase().replace(/[^A-Z_]/g, '');
  var allowed = ['PERSON', 'PROJECT', 'CONCEPT', 'SERVICE', 'DATABASE', 'MODEL'];
  if (value === 'TOOL' || value === 'FRAMEWORK' || value === 'LANGUAGE' || value === 'API') return 'SERVICE';
  return allowed.includes(value) ? value : 'CONCEPT';
}

function normalizeRelationshipType(type, content) {
  var value = String(type || '').toUpperCase().replace(/[^A-Z_]/g, '');
  var allowed = ['BROTHER_OF', 'SISTER_OF', 'MOTHER_OF', 'FATHER_OF', 'SON_OF', 'DAUGHTER_OF', 'COUSIN_OF', 'SPOUSE_OF', 'FRIEND_OF', 'WORKS_AT', 'USES', 'BUILT_WITH', 'CREATED_BY', 'OWNS', 'MANAGES', 'DEPENDS_ON', 'COMBINES', 'STORES_IN', 'RETRIEVES_FROM', 'RANKS_WITH', 'FORMATS_FOR', 'RELATED_TO'];
  if (allowed.includes(value)) return value;
  var lower = String(content || '').toLowerCase();
  if (lower.includes('built with') || lower.includes('using java') || lower.includes('uses java')) return 'BUILT_WITH';
  if (lower.includes('uses') || lower.includes('use ')) return 'USES';
  if (lower.includes('depends')) return 'DEPENDS_ON';
  if (lower.includes('stores')) return 'STORES_IN';
  if (lower.includes('retrieves')) return 'RETRIEVES_FROM';
  return 'RELATED_TO';
}

function normalizeEventType(type, content) {
  var value = String(type || '').toUpperCase().replace(/[^A-Z_]/g, '');
  var allowed = ['USER_TIMELINE', 'PROJECT_MILESTONE', 'DEPLOYMENT', 'INCIDENT', 'ASSISTANT_MEMORY'];
  if (allowed.includes(value)) return value;
  var lower = String(content || '').toLowerCase();
  if (lower.includes('deployed') || lower.includes('released') || lower.includes('launched')) return 'DEPLOYMENT';
  if (lower.includes('broke') || lower.includes('incident') || lower.includes('failed')) return 'INCIDENT';
  if (lower.includes('decided') || lower.includes('changed') || lower.includes('started')) return 'PROJECT_MILESTONE';
  return 'USER_TIMELINE';
}

function cleanSuggestionContent(value) {
  return String(value || '').replace(/\s+/g, ' ').trim();
}

function cleanEntityName(value) {
  return String(value || '').replace(/^[\s"'`]+|[\s"'`.,:;!?]+$/g, '').replace(/\s+/g, ' ').trim();
}

function inferEntityType(name, content) {
  var value = (String(name || '') + ' ' + String(content || '')).toLowerCase();
  if (!name) return 'CONCEPT';
  if (/cloudqueryx|project|runtime|engine|platform/.test(value) && /cloudqueryx|project/.test(value)) return 'PROJECT';
  if (/postgres|pgvector|supabase|database|rds|neon|mysql|redis/.test(value)) return 'DATABASE';
  if (/openai|claude|gemini|llm|model|assistant|gpt/.test(value)) return 'MODEL';
  if (/api|server|service|runtime|engine|store|graph|frontend|backend|java|react|docker|render|vercel/.test(value)) return 'SERVICE';
  if (/^[A-Z][a-z]+(?:\s+[A-Z][a-z]+){1,3}$/.test(String(name || ''))) return 'PERSON';
  return 'CONCEPT';
}

function extractKnownEntities(text) {
  var lower = text.toLowerCase();
  var specs = [
    ['CloudQueryX', 'PROJECT', 'Provider-neutral context runtime project.'],
    ['Context Runtime', 'SERVICE', 'Service layer that plans and builds LLM context.'],
    ['Memory Engine', 'SERVICE', 'Subsystem that stores and recalls durable memories.'],
    ['Source Store', 'SERVICE', 'Subsystem that stores and chunks source text.'],
    ['Knowledge Graph', 'SERVICE', 'Graph layer for entities and relationships.'],
    ['Event Store', 'SERVICE', 'Timeline layer for changes, actions, and incidents.'],
    ['Java API Server', 'SERVICE', 'Backend API layer for CloudQueryX.'],
    ['Website UI', 'SERVICE', 'Browser interface for the CloudQueryX demo.'],
    ['Supabase PostgreSQL', 'DATABASE', 'Persistent database backend.'],
    ['PostgreSQL', 'DATABASE', 'Relational database backend.'],
    ['pgvector', 'DATABASE', 'PostgreSQL vector search extension.'],
    ['OpenAI', 'MODEL', 'LLM provider used by the demo assistant.'],
    ['LLM', 'MODEL', 'Language model that consumes CloudQueryX context bundles.']
  ];
  return specs.filter(function(spec) { return lower.includes(spec[0].toLowerCase()); })
    .map(function(spec) { return { name: spec[0], type: spec[1], description: spec[2], reason: 'Named architecture component mentioned.' }; });
}

function extractKnownRelationships(text) {
  var lower = text.toLowerCase();
  var rels = [];
  function has(a, b) { return lower.includes(a.toLowerCase()) && lower.includes(b.toLowerCase()); }
  if (has('CloudQueryX', 'Supabase')) rels.push({ source: 'CloudQueryX', target: 'Supabase PostgreSQL', type: 'USES', reason: 'Architecture storage relationship.', content: text });
  if (has('CloudQueryX', 'PostgreSQL')) rels.push({ source: 'CloudQueryX', target: 'PostgreSQL', type: 'USES', reason: 'Database usage relationship.', content: text });
  if (has('CloudQueryX', 'pgvector')) rels.push({ source: 'CloudQueryX', target: 'pgvector', type: 'USES', reason: 'Vector retrieval relationship.', content: text });
  if (has('Context Runtime', 'Memory Engine')) rels.push({ source: 'Context Runtime', target: 'Memory Engine', type: 'COMBINES', reason: 'Runtime combines memory recall.', content: text });
  if (has('Context Runtime', 'Source')) rels.push({ source: 'Context Runtime', target: 'Source Store', type: 'COMBINES', reason: 'Runtime combines source chunks.', content: text });
  if (has('Context Runtime', 'Knowledge Graph') || has('Context Runtime', 'graph')) rels.push({ source: 'Context Runtime', target: 'Knowledge Graph', type: 'COMBINES', reason: 'Runtime combines graph context.', content: text });
  if (has('Context Runtime', 'Event')) rels.push({ source: 'Context Runtime', target: 'Event Store', type: 'COMBINES', reason: 'Runtime combines event freshness.', content: text });
  if (has('Java API Server', 'Context Runtime')) rels.push({ source: 'Java API Server', target: 'Context Runtime', type: 'USES', reason: 'API calls runtime services.', content: text });
  if (has('Website UI', 'Java API Server')) rels.push({ source: 'Website UI', target: 'Java API Server', type: 'USES', reason: 'Frontend sends API requests.', content: text });
  if (has('CloudQueryX', 'OpenAI')) rels.push({ source: 'CloudQueryX', target: 'OpenAI', type: 'FORMATS_FOR', reason: 'Demo formats context for the LLM.', content: text });
  return rels;
}

function uniqueSuggestions(items) {
  var seen = {};
  return (items || []).filter(function(item) {
    var key = [item.type, item.memoryType || '', item.entityType || '', item.name || '', item.sourceEntity || '', item.targetEntity || '', item.relationshipType || '', item.content || ''].join('|').toLowerCase();
    if (seen[key]) return false;
    seen[key] = true;
    return true;
  });
}

// ═══════════════════════════════════════════════════════════════
// DATA EXPLORER
// ═══════════════════════════════════════════════════════════════
function loadExplorerTab(tab) {
  var desc = document.getElementById('explorer-tab-desc');
  if (desc) desc.textContent = EXPLORER_TAB_INFO[tab] || '';
  switch (tab) {
    case 'memories': loadMemoryTable(); break;
    case 'sources': loadSourceTable(); break;
    case 'entities': loadEntityTable(); break;
    case 'relationships': loadRelationshipTable(); break;
    case 'events': loadEventTable(); break;
  }
}

async function loadMemoryTable() {
  var res = await api('/api/memory', { method: 'POST', body: JSON.stringify({ action: 'context', maxRecords: 200 }) });
  var items = res.context || [];
  var container = document.getElementById('memory-table-container');
  if (items.length === 0) { container.innerHTML = emptyTableHtml('No memories stored yet.', 'Memories are scored facts like preferences, decisions, or conversation history.', 'showAddMemoryModal()', 'Add your first memory'); return; }
  container.innerHTML = '<table class="data-table" id="memory-table"><thead><tr>' +
    '<th>Type</th><th>Content</th><th>Importance</th><th>Scope</th><th>ID</th>' +
    '</tr></thead><tbody>' +
    items.map(function(m) {
      var t = (m.type || m.memoryType || '').toLowerCase();
      return '<tr data-type="' + esc(m.type || '') + '" data-content="' + esc((m.content || '').toLowerCase()) + '">' +
        '<td><span class="type-badge ' + t + '">' + esc(m.type || m.memoryType || '') + '</span></td>' +
        '<td class="content-cell" title="' + esc(m.content || '') + '">' + esc(previewText(m.content, 100)) + '</td>' +
        '<td>' + (m.importance != null ? m.importance : '') + '</td>' +
        '<td>' + esc(m.scope || 'user') + '</td>' +
        '<td class="id-cell">' + esc((m.id || '').substring(0, 8)) + '</td>' +
        '</tr>';
    }).join('') +
    '</tbody></table>';
}

function filterMemoryTable() {
  var search = (document.getElementById('memory-search').value || '').toLowerCase();
  var typeFilter = document.getElementById('memory-type-filter').value;
  var rows = document.querySelectorAll('#memory-table tbody tr');
  rows.forEach(function(row) {
    var matchType = !typeFilter || row.dataset.type === typeFilter;
    var matchSearch = !search || (row.dataset.content || '').includes(search);
    row.style.display = matchType && matchSearch ? '' : 'none';
  });
}

async function loadSourceTable() {
  var res = await api('/api/sources');
  var items = res.sources || [];
  var container = document.getElementById('source-table-container');
  if (items.length === 0) { container.innerHTML = emptyTableHtml('No sources stored yet.', 'Sources are documents, code, or logs that get chunked and embedded automatically.', 'showAddSourceModal()', 'Add your first source'); return; }
  container.innerHTML = '<table class="data-table"><thead><tr>' +
    '<th>Type</th><th>Name</th><th>Version</th><th>Status</th><th>ID</th>' +
    '</tr></thead><tbody>' +
    items.map(function(s) {
      var t = (s.sourceType || '').toLowerCase();
      return '<tr>' +
        '<td><span class="type-badge ' + t + '">' + esc(s.sourceType || '') + '</span></td>' +
        '<td>' + esc(s.sourceName || '') + '</td>' +
        '<td>' + (s.version || 1) + '</td>' +
        '<td>' + esc(s.status || 'ACTIVE') + '</td>' +
        '<td class="id-cell">' + esc((s.id || '').substring(0, 8)) + '</td>' +
        '</tr>';
    }).join('') +
    '</tbody></table>';
}

async function loadEntityTable() {
  var res = await api('/api/semantic', { method: 'POST', body: JSON.stringify({ action: 'list_entities', limit: 200 }) });
  var items = res.entities || [];
  var container = document.getElementById('entity-table-container');
  if (items.length === 0) { container.innerHTML = emptyTableHtml('No entities stored yet.', 'Entities are knowledge-graph nodes — people, projects, tools, or concepts.', 'showAddEntityModal()', 'Add your first entity'); return; }
  container.innerHTML = '<table class="data-table"><thead><tr>' +
    '<th>Type</th><th>Name</th><th>Description</th><th>Confidence</th><th>ID</th>' +
    '</tr></thead><tbody>' +
    items.map(function(e) {
      var t = (e.entityType || '').toLowerCase();
      return '<tr>' +
        '<td><span class="type-badge ' + t + '">' + esc(e.entityType || '') + '</span></td>' +
        '<td><strong>' + esc(e.name || '') + '</strong></td>' +
        '<td class="content-cell">' + esc(previewText(e.description, 80)) + '</td>' +
        '<td>' + (e.confidence != null ? e.confidence : '') + '</td>' +
        '<td class="id-cell">' + esc((e.id || '').substring(0, 8)) + '</td>' +
        '</tr>';
    }).join('') +
    '</tbody></table>';
}

async function loadRelationshipTable() {
  var res = await api('/api/semantic', { method: 'POST', body: JSON.stringify({ action: 'list_relationships', limit: 200 }) });
  var items = res.relationships || [];
  var container = document.getElementById('relationship-table-container');
  if (items.length === 0) { container.innerHTML = emptyTableHtml('No relationships stored yet.', 'Relationships are weighted edges connecting two entities, e.g. "Alice" —WORKS_ON→ "CloudQueryX".', 'showAddRelationshipModal()', 'Add your first relationship'); return; }
  container.innerHTML = '<table class="data-table"><thead><tr>' +
    '<th>Type</th><th>Source Entity</th><th>Target Entity</th><th>Weight</th><th>ID</th>' +
    '</tr></thead><tbody>' +
    items.map(function(r) {
      return '<tr>' +
        '<td><span class="type-badge concept">' + esc(r.relationshipType || '') + '</span></td>' +
        '<td class="id-cell">' + esc((r.sourceEntityId || '').substring(0, 8)) + '</td>' +
        '<td class="id-cell">' + esc((r.targetEntityId || '').substring(0, 8)) + '</td>' +
        '<td>' + (r.weight != null ? r.weight : '') + '</td>' +
        '<td class="id-cell">' + esc((r.id || '').substring(0, 8)) + '</td>' +
        '</tr>';
    }).join('') +
    '</tbody></table>';
}

async function loadEventTable() {
  var res = await api('/api/events?limit=100');
  var items = res.events || [];
  var container = document.getElementById('event-table-container');
  if (items.length === 0) { container.innerHTML = emptyTableHtml('No events logged yet.', 'Events are timeline entries for things that happened — deployments, incidents, user actions.', 'showAddEventModal()', 'Log your first event'); return; }
  container.innerHTML = '<table class="data-table"><thead><tr>' +
    '<th>Event Type</th><th>Action</th><th>Time</th><th>ID</th>' +
    '</tr></thead><tbody>' +
    items.map(function(e) {
      var time = e.timestamp || e.createdAt || '';
      if (time) { try { time = new Date(time).toLocaleString(); } catch(x) {} }
      return '<tr>' +
        '<td><strong>' + esc(e.eventType || '') + '</strong></td>' +
        '<td class="content-cell">' + esc(previewText(e.action, 100)) + '</td>' +
        '<td>' + esc(time) + '</td>' +
        '<td class="id-cell">' + esc((e.id || '').substring(0, 8)) + '</td>' +
        '</tr>';
    }).join('') +
    '</tbody></table>';
}

async function searchVectors() {
  var query = document.getElementById('vector-search-query').value.trim();
  if (!query) return;
  var topK = parseInt(document.getElementById('vector-topk').value) || 10;
  var ns = document.getElementById('vector-namespace').value || 'default';
  var res = await api('/api/context/retrieve', {
    method: 'POST',
    body: JSON.stringify({ query: query, topK: topK, includeMemories: false, includeSources: false, includeGraph: false, includeEvents: false })
  });
  var container = document.getElementById('vector-results');
  var items = res.results || [];
  if (items.length === 0) { container.innerHTML = '<div class="empty-table">No vector matches found.</div>'; return; }
  container.innerHTML = '<table class="data-table"><thead><tr><th>Score</th><th>Content</th><th>Type</th></tr></thead><tbody>' +
    items.map(function(v) {
      return '<tr><td>' + (v.score != null ? v.score.toFixed(4) : '') + '</td>' +
        '<td class="content-cell">' + esc(previewText(v.content, 120)) + '</td>' +
        '<td>' + esc(v.type || '') + '</td></tr>';
    }).join('') + '</tbody></table>';
}

// ─── Add modals ─────────────────────────────────────────────
function showAddMemoryModal() {
  showActionModal('Add Memory',
    '<label>Type</label><select id="modal-mem-type"><option value="FACT">Fact</option><option value="PREFERENCE">Preference</option><option value="DECISION">Decision</option><option value="CONVERSATION">Conversation</option><option value="FEEDBACK">Feedback</option></select>' +
    '<label>Content</label><textarea id="modal-mem-content" rows="3" placeholder="Memory content..."></textarea>' +
    '<label>Importance</label><input type="number" id="modal-mem-importance" step="0.1" min="0" max="1" value="0.8">' +
    '<button class="btn-primary full" onclick="doAddMemory()">Store Memory</button>');
}
async function doAddMemory() {
  var res = await api('/api/memory', { method: 'POST', body: JSON.stringify({
    action: 'store',
    type: document.getElementById('modal-mem-type').value,
    content: document.getElementById('modal-mem-content').value,
    importance: parseFloat(document.getElementById('modal-mem-importance').value) || 0.8
  })});
  closeActionModal();
  if (res.error) { showToast(res.error, 'error'); return; }
  showToast('Memory stored'); loadMemoryTable();
}

function showAddSourceModal() {
  showActionModal('Add Source',
    '<label>Type</label><select id="modal-src-type"><option value="document">Document</option><option value="code">Code</option><option value="log">Log</option><option value="note">Note</option><option value="markdown">Markdown</option><option value="config">Config</option></select>' +
    '<label>Name</label><input type="text" id="modal-src-name" placeholder="deploy-log.md, architecture...">' +
    '<label>Content</label><textarea id="modal-src-content" rows="6" placeholder="Paste documents, code, logs..."></textarea>' +
    '<button class="btn-primary full" onclick="doAddSource()">Store Source</button>');
}
async function doAddSource() {
  var res = await api('/api/sources', { method: 'POST', body: JSON.stringify({
    sourceType: document.getElementById('modal-src-type').value,
    sourceName: document.getElementById('modal-src-name').value,
    content: document.getElementById('modal-src-content').value
  })});
  closeActionModal();
  if (res.error) { showToast(res.error, 'error'); return; }
  showToast('Source stored'); loadSourceTable();
}

function showAddEntityModal() {
  showActionModal('Add Entity',
    '<label>Type</label><select id="modal-ent-type"><option value="PERSON">Person</option><option value="PROJECT">Project</option><option value="CONCEPT">Concept</option><option value="SERVICE">Service</option><option value="DATABASE">Database</option><option value="MODEL">Model</option></select>' +
    '<label>Name</label><input type="text" id="modal-ent-name" placeholder="Entity name">' +
    '<label>Description</label><input type="text" id="modal-ent-desc" placeholder="Short description">' +
    '<button class="btn-primary full" onclick="doAddEntity()">Add Entity</button>');
}
async function doAddEntity() {
  var res = await api('/api/semantic', { method: 'POST', body: JSON.stringify({
    action: 'add_entity',
    entityType: document.getElementById('modal-ent-type').value,
    name: document.getElementById('modal-ent-name').value,
    description: document.getElementById('modal-ent-desc').value
  })});
  closeActionModal();
  if (res.error) { showToast(res.error, 'error'); return; }
  showToast('Entity added'); loadEntityTable(); if (currentSection === 'graph') loadGraphData();
}

function showAddRelationshipModal() {
  showActionModal('Add Relationship',
    '<label>Source Entity ID</label><input type="text" id="modal-rel-source" placeholder="Source entity ID">' +
    '<label>Target Entity ID</label><input type="text" id="modal-rel-target" placeholder="Target entity ID">' +
    '<label>Relationship Type</label><input type="text" id="modal-rel-type" placeholder="KNOWS, USES, CREATED_BY...">' +
    '<button class="btn-primary full" onclick="doAddRelationship()">Add Relationship</button>');
}
async function doAddRelationship() {
  var res = await api('/api/semantic', { method: 'POST', body: JSON.stringify({
    action: 'add_relationship',
    sourceEntityId: document.getElementById('modal-rel-source').value,
    targetEntityId: document.getElementById('modal-rel-target').value,
    relationshipType: document.getElementById('modal-rel-type').value
  })});
  closeActionModal();
  if (res.error) { showToast(res.error, 'error'); return; }
  showToast('Relationship added'); loadRelationshipTable(); if (currentSection === 'graph') loadGraphData();
}

function showAddEventModal() {
  showActionModal('Log Event',
    '<label>Event Type</label><input type="text" id="modal-evt-type" placeholder="USER_TIMELINE, DEPLOYMENT...">' +
    '<label>Action</label><input type="text" id="modal-evt-action" placeholder="Action description">' +
    '<button class="btn-primary full" onclick="doAddEvent()">Log Event</button>');
}
async function doAddEvent() {
  var res = await api('/api/events', { method: 'POST', body: JSON.stringify({
    eventType: document.getElementById('modal-evt-type').value,
    action: document.getElementById('modal-evt-action').value
  })});
  closeActionModal();
  if (res.error) { showToast(res.error, 'error'); return; }
  showToast('Event logged'); loadEventTable();
}

// ═══════════════════════════════════════════════════════════════
// KNOWLEDGE GRAPH VISUALIZATION
// ═══════════════════════════════════════════════════════════════
var graphNodes = [];
var graphEdges = [];
var entityColors = {
  PERSON: '#3b82f6', PROJECT: '#22c55e', CONCEPT: '#f59e0b',
  SERVICE: '#ec4899', DATABASE: '#06b6d4', MODEL: '#8b5cf6'
};

async function loadGraphData() {
  var entRes = await api('/api/semantic', { method: 'POST', body: JSON.stringify({ action: 'list_entities', limit: 200 }) });
  var relRes = await api('/api/semantic', { method: 'POST', body: JSON.stringify({ action: 'list_relationships', limit: 500 }) });
  var entities = entRes.entities || [];
  var relationships = relRes.relationships || [];

  graphNodes = entities.map(function(e) {
    return { id: e.id, label: e.name || '?', type: e.entityType || 'CONCEPT', desc: e.description || '', x: 0, y: 0, vx: 0, vy: 0 };
  });
  graphEdges = relationships.map(function(r) {
    return { source: r.sourceEntityId, target: r.targetEntityId, type: r.relationshipType || '' };
  });

  renderGraphLegend(entities);
  initForceGraph();
}

function renderGraphLegend(entities) {
  var types = {};
  entities.forEach(function(e) { types[e.entityType || 'CONCEPT'] = true; });
  document.getElementById('graph-legend').innerHTML = Object.keys(types).map(function(t) {
    return '<div class="graph-legend-item"><div class="graph-legend-dot" style="background:' + (entityColors[t] || '#6b7280') + '"></div>' + esc(t) + '</div>';
  }).join('');
}

function initForceGraph() {
  var canvas = document.getElementById('graph-canvas');
  var rect = canvas.parentElement.getBoundingClientRect();
  canvas.width = rect.width || 900;
  canvas.height = 500;
  var ctx = canvas.getContext('2d');
  var cx = canvas.width / 2, cy = canvas.height / 2;

  graphNodes.forEach(function(n) {
    n.x = cx + (Math.random() - 0.5) * 400;
    n.y = cy + (Math.random() - 0.5) * 300;
    n.vx = 0; n.vy = 0;
  });

  var nodeMap = {};
  graphNodes.forEach(function(n) { nodeMap[n.id] = n; });

  var dragging = null, hovered = null, selected = null;
  var offsetX = 0, offsetY = 0;
  var running = true;
  var frameCount = 0;

  function simulate() {
    var repulsion = 600, attraction = 0.008, damping = 0.92, idealLen = 140;
    for (var i = 0; i < graphNodes.length; i++) {
      for (var j = i + 1; j < graphNodes.length; j++) {
        var a = graphNodes[i], b = graphNodes[j];
        var dx = b.x - a.x, dy = b.y - a.y;
        var dist = Math.max(Math.sqrt(dx * dx + dy * dy), 1);
        var f = repulsion / (dist * dist);
        var fx = (dx / dist) * f, fy = (dy / dist) * f;
        a.vx -= fx; a.vy -= fy; b.vx += fx; b.vy += fy;
      }
    }
    graphEdges.forEach(function(e) {
      var s = nodeMap[e.source], t = nodeMap[e.target];
      if (!s || !t) return;
      var dx = t.x - s.x, dy = t.y - s.y;
      var dist = Math.max(Math.sqrt(dx * dx + dy * dy), 1);
      var f = (dist - idealLen) * attraction;
      var fx = (dx / dist) * f, fy = (dy / dist) * f;
      s.vx += fx; s.vy += fy; t.vx -= fx; t.vy -= fy;
    });
    graphNodes.forEach(function(n) {
      if (n === dragging) return;
      n.vx *= damping; n.vy *= damping;
      n.x += n.vx; n.y += n.vy;
      n.x = Math.max(20, Math.min(canvas.width - 20, n.x));
      n.y = Math.max(20, Math.min(canvas.height - 20, n.y));
    });
  }

  function draw() {
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    graphEdges.forEach(function(e) {
      var s = nodeMap[e.source], t = nodeMap[e.target];
      if (!s || !t) return;
      ctx.beginPath(); ctx.moveTo(s.x, s.y); ctx.lineTo(t.x, t.y);
      ctx.strokeStyle = '#94a3b8'; ctx.lineWidth = 1.2; ctx.stroke();
      var mx = (s.x + t.x) / 2, my = (s.y + t.y) / 2;
      if (e.type) {
        ctx.fillStyle = '#64748b'; ctx.font = '9px Inter, sans-serif'; ctx.textAlign = 'center';
        ctx.fillText(e.type, mx, my - 4);
      }
    });
    graphNodes.forEach(function(n) {
      var r = n === selected ? 18 : (n === hovered ? 16 : 14);
      ctx.beginPath(); ctx.arc(n.x, n.y, r, 0, Math.PI * 2);
      ctx.fillStyle = entityColors[n.type] || '#6b7280';
      if (n === hovered || n === selected) ctx.fillStyle = shadeColor(entityColors[n.type] || '#6b7280', 20);
      ctx.fill();
      ctx.strokeStyle = '#fff'; ctx.lineWidth = 2; ctx.stroke();
      ctx.fillStyle = '#0f172a'; ctx.font = '11px Inter, sans-serif'; ctx.textAlign = 'center';
      ctx.fillText(n.label, n.x, n.y + r + 14);
    });
  }

  function animate() {
    if (!running) return;
    frameCount++;
    if (frameCount < 300) simulate();
    draw();
    requestAnimationFrame(animate);
  }

  function getNode(x, y) {
    for (var i = graphNodes.length - 1; i >= 0; i--) {
      var n = graphNodes[i];
      var dx = x - n.x, dy = y - n.y;
      if (dx * dx + dy * dy < 18 * 18) return n;
    }
    return null;
  }

  canvas.onmousedown = function(ev) {
    var rect = canvas.getBoundingClientRect();
    var x = ev.clientX - rect.left, y = ev.clientY - rect.top;
    var node = getNode(x, y);
    if (node) { dragging = node; offsetX = x - node.x; offsetY = y - node.y; frameCount = 0; }
  };
  canvas.onmousemove = function(ev) {
    var rect = canvas.getBoundingClientRect();
    var x = ev.clientX - rect.left, y = ev.clientY - rect.top;
    if (dragging) { dragging.x = x - offsetX; dragging.y = y - offsetY; if (frameCount >= 300) { frameCount = 0; animate(); } return; }
    hovered = getNode(x, y);
    canvas.style.cursor = hovered ? 'pointer' : 'grab';
  };
  canvas.onmouseup = function() { dragging = null; };

  canvas.onclick = function(ev) {
    var rect = canvas.getBoundingClientRect();
    var x = ev.clientX - rect.left, y = ev.clientY - rect.top;
    var node = getNode(x, y);
    if (node) { selected = node; showGraphDetail(node); }
    else { selected = null; closeGraphDetail(); }
  };

  if (forceGraph) running = false;
  forceGraph = { stop: function() { running = false; } };
  running = true; frameCount = 0;
  if (graphNodes.length === 0) {
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    ctx.fillStyle = '#94a3b8'; ctx.font = '14px Inter, sans-serif'; ctx.textAlign = 'center';
    ctx.fillText('No entities yet. Add some to see the graph.', canvas.width / 2, canvas.height / 2);
    return;
  }
  animate();
}

function shadeColor(color, percent) {
  var num = parseInt(color.replace('#', ''), 16);
  var r = Math.min(255, (num >> 16) + percent);
  var g = Math.min(255, ((num >> 8) & 0x00FF) + percent);
  var b = Math.min(255, (num & 0x0000FF) + percent);
  return '#' + (0x1000000 + r * 0x10000 + g * 0x100 + b).toString(16).slice(1);
}

function showGraphDetail(node) {
  var panel = document.getElementById('graph-detail');
  var connected = [];
  graphEdges.forEach(function(e) {
    if (e.source === node.id || e.target === node.id) {
      var otherId = e.source === node.id ? e.target : e.source;
      var other = graphNodes.find(function(n) { return n.id === otherId; });
      if (other) connected.push({ node: other, type: e.type, direction: e.source === node.id ? 'outgoing' : 'incoming' });
    }
  });

  document.getElementById('graph-detail-content').innerHTML =
    '<h3>' + esc(node.label) + '</h3>' +
    '<div class="graph-detail-field"><label>Type</label><p><span class="type-badge ' + node.type.toLowerCase() + '">' + esc(node.type) + '</span></p></div>' +
    '<div class="graph-detail-field"><label>Description</label><p>' + esc(node.desc || 'No description') + '</p></div>' +
    '<div class="graph-detail-field"><label>ID</label><p class="mono" style="font-size:11px;color:var(--muted)">' + esc(node.id) + '</p></div>' +
    (connected.length > 0 ? '<h4 style="margin-top:16px;margin-bottom:8px">Connections (' + connected.length + ')</h4><ul class="graph-rel-list">' +
      connected.map(function(c) {
        var arrow = c.direction === 'outgoing' ? ' &rarr; ' : ' &larr; ';
        return '<li class="graph-rel-item"><span class="type-badge concept">' + esc(c.type) + '</span>' + arrow + '<strong>' + esc(c.node.label) + '</strong></li>';
      }).join('') + '</ul>' : '<p class="muted" style="margin-top:12px">No connections.</p>');

  panel.classList.add('open');
}

function closeGraphDetail() {
  document.getElementById('graph-detail').classList.remove('open');
}

// ═══════════════════════════════════════════════════════════════
// API REFERENCE
// ═══════════════════════════════════════════════════════════════
var API_ENDPOINTS = [
  {
    id: 'store', method: 'POST', path: '/api/v1/store',
    desc: 'Store a memory, source, entity, relationship, or event in your context database.',
    params: [
      { name: 'type', type: 'select', options: ['memory', 'source', 'entity', 'relationship', 'event'], required: true, desc: 'What kind of context to store' },
      { name: 'content', type: 'text', required: true, desc: 'The content or description to store' },
      { name: 'memoryType', type: 'select', options: ['FACT', 'PREFERENCE', 'DECISION', 'CONVERSATION', 'FEEDBACK'], required: false, desc: 'Memory subtype (when type=memory)' },
      { name: 'importance', type: 'number', required: false, desc: 'Importance score 0.0-1.0', default: '0.8' },
      { name: 'name', type: 'text', required: false, desc: 'Entity or source name' },
      { name: 'entityType', type: 'select', options: ['PERSON', 'PROJECT', 'CONCEPT', 'SERVICE', 'DATABASE', 'MODEL'], required: false, desc: 'Entity subtype (when type=entity)' }
    ],
    example: { type: 'memory', memoryType: 'FACT', content: 'User prefers dark mode', importance: 0.9 }
  },
  {
    id: 'recall', method: 'POST', path: '/api/v1/recall',
    desc: 'Quick semantic search across memories and sources.',
    params: [
      { name: 'query', type: 'text', required: true, desc: 'Natural language search query' },
      { name: 'topK', type: 'number', required: false, desc: 'Max results to return', default: '5' }
    ],
    example: { query: 'What does the user prefer?', topK: 5 }
  },
  {
    id: 'retrieve', method: 'POST', path: '/api/v1/retrieve',
    desc: 'Full multi-signal retrieval across all context types with RRF fusion.',
    params: [
      { name: 'query', type: 'text', required: true, desc: 'Natural language search query' },
      { name: 'topK', type: 'number', required: false, desc: 'Max results to return', default: '10' },
      { name: 'includeMemories', type: 'bool', required: false, desc: 'Search memories', default: 'true' },
      { name: 'includeSources', type: 'bool', required: false, desc: 'Search sources', default: 'true' },
      { name: 'includeGraph', type: 'bool', required: false, desc: 'Search knowledge graph', default: 'true' },
      { name: 'includeEvents', type: 'bool', required: false, desc: 'Include behavioral events', default: 'true' }
    ],
    example: { query: 'What database technologies does the team use?', topK: 10, includeMemories: true, includeSources: true, includeGraph: true }
  },
  {
    id: 'build', method: 'POST', path: '/api/v1/context/build',
    desc: 'Build a token-budgeted context bundle optimized for an LLM call.',
    params: [
      { name: 'query', type: 'text', required: true, desc: 'What the LLM needs context for' },
      { name: 'tokenBudget', type: 'number', required: false, desc: 'Max tokens for the context bundle', default: '4000' },
      { name: 'modelProfile', type: 'select', options: ['small-context-model', 'medium-context-model', 'large-context-model'], required: false, desc: 'Target model size profile' },
      { name: 'mode', type: 'select', options: ['general', 'debugging', 'planning', 'coding'], required: false, desc: 'Context retrieval mode' },
      { name: 'includeMemories', type: 'bool', required: false, desc: 'Include memories', default: 'true' },
      { name: 'includeSources', type: 'bool', required: false, desc: 'Include sources', default: 'true' }
    ],
    example: { query: 'Explain the current architecture', tokenBudget: 4000, modelProfile: 'medium-context-model', includeMemories: true, includeSources: true }
  }
];

var currentEndpoint = 'store';
var currentLang = 'curl';

function initApiReference() {
  selectEndpoint('store');
}

function selectEndpoint(id) {
  currentEndpoint = id;
  document.querySelectorAll('.api-endpoint-item').forEach(function(item) {
    item.classList.toggle('active', item.dataset.endpoint === id);
  });
  var ep = API_ENDPOINTS.find(function(e) { return e.id === id; });
  if (!ep) return;

  var paramRows = ep.params.map(function(p) {
    var input = '';
    if (p.type === 'select') {
      input = '<select id="param-' + p.name + '"><option value="">—</option>' +
        p.options.map(function(o) { return '<option value="' + o + '">' + o + '</option>'; }).join('') + '</select>';
    } else if (p.type === 'bool') {
      input = '<select id="param-' + p.name + '"><option value="true">true</option><option value="false">false</option></select>';
    } else if (p.type === 'number') {
      input = '<input type="number" id="param-' + p.name + '" value="' + (p.default || '') + '">';
    } else {
      input = '<input type="text" id="param-' + p.name + '" placeholder="' + esc(p.desc) + '">';
    }
    return '<tr><td><strong>' + esc(p.name) + '</strong>' + (p.required ? ' <span class="param-required">required</span>' : '') + '</td>' +
      '<td>' + esc(p.desc) + '</td><td>' + input + '</td></tr>';
  }).join('');

  document.getElementById('api-detail-content').innerHTML =
    '<h3><span class="method ' + ep.method.toLowerCase() + '">' + ep.method + '</span> ' + esc(ep.path) + '</h3>' +
    '<p>' + esc(ep.desc) + '</p>' +
    '<h4 style="margin-bottom:8px">Parameters</h4>' +
    '<table class="param-table"><thead><tr><th>Name</th><th>Description</th><th>Value</th></tr></thead><tbody>' + paramRows + '</tbody></table>' +
    '<h4 style="margin-bottom:8px">Request Preview</h4>' +
    '<div class="code-block"><div class="code-block-header">' +
      '<button class="lang-tab active" onclick="switchApiLang(\'curl\')">curl</button>' +
      '<button class="lang-tab" onclick="switchApiLang(\'python\')">Python</button>' +
      '<button class="lang-tab" onclick="switchApiLang(\'javascript\')">JavaScript</button>' +
      '<button class="copy-btn" onclick="copyRequestCode()">Copy</button>' +
    '</div><pre class="code-block-body" id="api-code-preview"></pre></div>' +
    '<div class="api-execute-row"><button class="btn-primary" onclick="executeApiRequest()">Execute Request</button>' +
    '<span class="muted">Uses your session token for authentication</span></div>' +
    '<h4 style="margin-bottom:8px">Response</h4>' +
    '<pre class="response-viewer" id="api-response">// Click Execute to see the response</pre>';

  updateApiCodePreview();
  ep.params.forEach(function(p) {
    var el = document.getElementById('param-' + p.name);
    if (el) el.addEventListener('input', updateApiCodePreview);
    if (el) el.addEventListener('change', updateApiCodePreview);
  });
}

function getParamValues() {
  var ep = API_ENDPOINTS.find(function(e) { return e.id === currentEndpoint; });
  if (!ep) return {};
  var body = {};
  ep.params.forEach(function(p) {
    var el = document.getElementById('param-' + p.name);
    if (!el || !el.value) return;
    var val = el.value;
    if (p.type === 'number') val = parseFloat(val);
    if (p.type === 'bool') val = val === 'true';
    body[p.name] = val;
  });
  return body;
}

function updateApiCodePreview() {
  var ep = API_ENDPOINTS.find(function(e) { return e.id === currentEndpoint; });
  if (!ep) return;
  var body = getParamValues();
  if (Object.keys(body).length === 0) body = ep.example;
  var pre = document.getElementById('api-code-preview');
  if (currentLang === 'curl') pre.textContent = generateCurl(ep, body);
  else if (currentLang === 'python') pre.textContent = generatePython(ep, body);
  else pre.textContent = generateJavaScript(ep, body);
}

function switchApiLang(lang) {
  currentLang = lang;
  document.querySelectorAll('.code-block-header .lang-tab').forEach(function(t) {
    t.classList.toggle('active', t.textContent.toLowerCase().includes(lang));
  });
  updateApiCodePreview();
}

function generateCurl(ep, body) {
  return 'curl -X ' + ep.method + ' \\\n' +
    '  ' + window.location.origin + ep.path + ' \\\n' +
    '  -H "Authorization: Bearer YOUR_API_KEY" \\\n' +
    '  -H "Content-Type: application/json" \\\n' +
    "  -d '" + JSON.stringify(body, null, 2) + "'";
}

function generatePython(ep, body) {
  return 'import requests\n\nresponse = requests.post(\n' +
    '    "' + window.location.origin + ep.path + '",\n' +
    '    headers={"Authorization": "Bearer YOUR_API_KEY"},\n' +
    '    json=' + JSON.stringify(body, null, 4) + '\n)\n\nprint(response.json())';
}

function generateJavaScript(ep, body) {
  return 'const response = await fetch("' + window.location.origin + ep.path + '", {\n' +
    '  method: "' + ep.method + '",\n' +
    '  headers: {\n' +
    '    "Authorization": "Bearer YOUR_API_KEY",\n' +
    '    "Content-Type": "application/json"\n  },\n' +
    '  body: JSON.stringify(' + JSON.stringify(body, null, 4) + ')\n});\n\nconst data = await response.json();\nconsole.log(data);';
}

function copyRequestCode() {
  var pre = document.getElementById('api-code-preview');
  navigator.clipboard.writeText(pre.textContent).then(function() { showToast('Copied to clipboard'); });
}

async function executeApiRequest() {
  var ep = API_ENDPOINTS.find(function(e) { return e.id === currentEndpoint; });
  if (!ep) return;
  var body = getParamValues();
  if (Object.keys(body).length === 0) body = ep.example;
  var viewer = document.getElementById('api-response');
  viewer.textContent = '// Executing...';
  var res = await api(ep.path, { method: ep.method, body: JSON.stringify(body) });
  viewer.textContent = JSON.stringify(res, null, 2);
}

// ═══════════════════════════════════════════════════════════════
// API KEYS
// ═══════════════════════════════════════════════════════════════
async function loadApiKeys() {
  var res = await api('/api/databases/' + currentDbId + '/api-keys');
  var keys = res.apiKeys || res || [];
  var container = document.getElementById('api-key-list');
  if (!Array.isArray(keys) || keys.length === 0) {
    container.innerHTML = '<div class="empty-table">No API keys yet. Create one to start using the API.</div>';
    return;
  }
  container.innerHTML = keys.map(function(k) {
    var status = k.status || 'ACTIVE';
    var lastUsed = k.lastUsedAt ? new Date(k.lastUsedAt).toLocaleDateString() : 'Never';
    return '<div class="key-card">' +
      '<span class="key-prefix">' + esc(k.keyPrefix || '') + '...</span>' +
      '<span class="key-name">' + esc(k.name || 'Unnamed') + '</span>' +
      '<span class="key-meta">' + esc(status) + ' &middot; Last used: ' + esc(lastUsed) + '</span>' +
      (status === 'ACTIVE' ? '<button class="btn-danger" onclick="revokeApiKey(\'' + k.id + '\')">Revoke</button>' : '') +
      '</div>';
  }).join('');
}

function showCreateKeyModal() {
  showActionModal('Create API Key',
    '<label>Key Name</label><input type="text" id="modal-key-name" placeholder="production-agent, local-dev...">' +
    '<button class="btn-primary full" onclick="doCreateApiKey()">Generate Key</button>');
}

async function doCreateApiKey() {
  var name = document.getElementById('modal-key-name').value || 'Unnamed key';
  var res = await api('/api/databases/' + currentDbId + '/api-keys', {
    method: 'POST', body: JSON.stringify({ name: name })
  });
  closeActionModal();
  if (res.error) { showToast(res.error, 'error'); return; }
  if (res.rawKey) {
    document.getElementById('api-key-reveal').innerHTML =
      '<div class="key-reveal" style="margin-top:16px"><strong>Your new API key (copy now — shown once):</strong><br><br>' + esc(res.rawKey) + '</div>';
  }
  showToast('API key created');
  loadApiKeys();
}

async function revokeApiKey(keyId) {
  if (!confirm('Revoke this API key? This cannot be undone.')) return;
  await api('/api/databases/' + currentDbId + '/api-keys/' + keyId, { method: 'DELETE' });
  showToast('Key revoked');
  loadApiKeys();
}

// ═══════════════════════════════════════════════════════════════
// WEBHOOKS
// ═══════════════════════════════════════════════════════════════
async function loadWebhooks() {
  var res = await api('/api/webhooks');
  var hooks = res.webhooks || [];
  var container = document.getElementById('webhook-list');
  if (hooks.length === 0) {
    container.innerHTML = '<div class="empty-table">No webhooks configured yet.</div>';
    return;
  }
  container.innerHTML = hooks.map(function(h) {
    return '<div class="webhook-card">' +
      '<span class="webhook-url">' + esc(h.url || '') + '</span>' +
      '<div class="webhook-events">' + (h.events || []).map(function(e) {
        return '<span class="webhook-event-tag">' + esc(e) + '</span>';
      }).join('') + '</div>' +
      '<button class="webhook-toggle' + (h.active ? ' active' : '') + '" onclick="toggleWebhook(\'' + h.id + '\', ' + !h.active + ')"></button>' +
      '<button class="btn-danger" onclick="deleteWebhook(\'' + h.id + '\')">Delete</button>' +
      '</div>';
  }).join('');
}

function showCreateWebhookModal() {
  showActionModal('Create Webhook',
    '<label>URL</label><input type="text" id="modal-wh-url" placeholder="https://your-server.com/webhook">' +
    '<label>Events (comma-separated)</label><input type="text" id="modal-wh-events" placeholder="context.stored, context.forgotten" value="context.stored, context.forgotten">' +
    '<label>Secret (optional)</label><input type="text" id="modal-wh-secret" placeholder="Webhook secret for signature verification">' +
    '<button class="btn-primary full" onclick="doCreateWebhook()">Create Webhook</button>');
}

async function doCreateWebhook() {
  var url = document.getElementById('modal-wh-url').value;
  var events = document.getElementById('modal-wh-events').value.split(',').map(function(s) { return s.trim(); }).filter(Boolean);
  var secret = document.getElementById('modal-wh-secret').value || undefined;
  var res = await api('/api/webhooks', { method: 'POST', body: JSON.stringify({ url: url, events: events, secret: secret }) });
  closeActionModal();
  if (res.error) { showToast(res.error, 'error'); return; }
  showToast('Webhook created');
  loadWebhooks();
}

async function toggleWebhook(id, active) {
  await api('/api/webhooks', { method: 'POST', body: JSON.stringify({ action: 'toggle', id: id, active: active }) });
  loadWebhooks();
}

async function deleteWebhook(id) {
  if (!confirm('Delete this webhook?')) return;
  await api('/api/webhooks', { method: 'POST', body: JSON.stringify({ action: 'delete', id: id }) });
  showToast('Webhook deleted');
  loadWebhooks();
}

// ═══════════════════════════════════════════════════════════════
// SETTINGS
// ═══════════════════════════════════════════════════════════════
async function loadSettings() {
  var health = await api('/api/health');
  var dbInfo = document.getElementById('settings-db-info');
  dbInfo.innerHTML =
    '<div class="setting-item"><span class="setting-label">Database ID</span><span class="setting-value">' + esc(currentDbId || '') + '</span></div>' +
    '<div class="setting-item"><span class="setting-label">Status</span><span class="setting-value">' + esc(health.database || 'Unknown') + '</span></div>';

  var healthInfo = document.getElementById('settings-health-info');
  var features = health.features || [];
  healthInfo.innerHTML =
    '<div class="setting-item"><span class="setting-label">Server Status</span><span class="setting-value">' + esc(health.status || '') + '</span></div>' +
    '<div class="setting-item"><span class="setting-label">Embedding Provider</span><span class="setting-value">' + esc(health.embedding || 'none') + '</span></div>' +
    (features.length > 0 ? '<div class="setting-item"><span class="setting-label">Features</span><span class="setting-value">' + features.map(esc).join(', ') + '</span></div>' : '');
}

function confirmDeleteAllData() {
  if (!confirm('This will delete ALL data in your context database. This cannot be undone. Are you sure?')) return;
  showToast('Data deletion is not available in the demo', 'error');
}

// ═══════════════════════════════════════════════════════════════
// INIT
// ═══════════════════════════════════════════════════════════════
document.addEventListener('DOMContentLoaded', async function() {
  await loadPublicConfig();

  document.querySelectorAll('.sidebar-item[data-section]').forEach(function(item) {
    item.addEventListener('click', function(e) {
      e.preventDefault();
      showSection(item.dataset.section);
    });
  });

  document.querySelectorAll('#explorer-tabs .subtab[data-subtab]').forEach(function(tab) {
    tab.addEventListener('click', function() {
      switchExplorerTab(tab.dataset.subtab);
    });
  });

  var chatInput = document.getElementById('chat-input');
  if (chatInput) {
    chatInput.addEventListener('keydown', function(e) {
      if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); sendAssistantMessage(); }
    });
  }

  if (token) {
    api('/api/auth/me').then(function(res) {
      if (res.error || !res.email) {
        showToast(res.error || 'Demo session expired. Start a new demo.', 'error');
        logout();
        return;
      }
      showApp(res, res.defaultDatabase);
    });
  }

  window.addEventListener('beforeunload', function() {
    if (demoMode) cleanupDemoSession(true);
  });
});
