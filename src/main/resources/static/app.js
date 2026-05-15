/* ═══════════════════════════════════════════════════════════════
   CloudQueryX — Developer Platform
   ═══════════════════════════════════════════════════════════════ */
var token = localStorage.getItem('cqx_token');
var currentDbId = null;
var currentSection = 'overview';
var currentExplorerTab = 'memories';
var chatMemorySuggestions = [];
var lastAutoSavedContext = [];
var forceGraph = null;

var API = '';
function api(path, opts) {
  opts = opts || {};
  var headers = Object.assign({ 'Content-Type': 'application/json' }, opts.headers || {});
  if (token) headers['Authorization'] = 'Bearer ' + token;
  if (currentDbId) headers['X-Database-Id'] = currentDbId;
  return fetch(API + path, Object.assign({}, opts, { headers: headers }))
    .then(function(r) { return r.json(); })
    .catch(function(e) { return { error: e.message }; });
}

function esc(s) { var d = document.createElement('div'); d.textContent = s || ''; return d.innerHTML; }
function previewText(t, max) { t = t || ''; return t.length > (max || 120) ? t.substring(0, max || 120) + '...' : t; }

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
}

function handleSignup(e) {
  e.preventDefault();
  var email = document.getElementById('signup-email').value;
  var password = document.getElementById('signup-password').value;
  api('/api/auth/signup', { method: 'POST', body: JSON.stringify({ email: email, password: password }) })
    .then(function(res) {
      if (res.error) { document.getElementById('signup-error').textContent = res.error; return; }
      token = res.token; localStorage.setItem('cqx_token', token);
      closeAuth(); showApp(res.user, res.defaultDatabase);
    });
  return false;
}

function handleLogin(e) {
  e.preventDefault();
  var email = document.getElementById('login-email').value;
  var password = document.getElementById('login-password').value;
  api('/api/auth/login', { method: 'POST', body: JSON.stringify({ email: email, password: password }) })
    .then(function(res) {
      if (res.error) { document.getElementById('login-error').textContent = res.error; return; }
      token = res.token; localStorage.setItem('cqx_token', token);
      closeAuth(); showApp(res.user, res.defaultDatabase);
    });
  return false;
}

function logout() {
  api('/api/auth/logout', { method: 'POST' });
  token = null; currentDbId = null;
  localStorage.removeItem('cqx_token');
  document.getElementById('app-view').style.display = 'none';
  document.getElementById('landing-view').style.display = 'block';
}

function showApp(user, db) {
  document.getElementById('landing-view').style.display = 'none';
  document.getElementById('app-view').style.display = 'block';
  document.getElementById('sidebar-email').textContent = user ? user.email : '';
  if (db) openDatabase(db);
  else loadDatabases();
  checkHealth();
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
  showSection('overview');
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
    case 'explorer': loadExplorerTab(currentExplorerTab); break;
    case 'graph': loadGraphData(); break;
    case 'api-reference': initApiReference(); break;
    case 'api-keys': loadApiKeys(); break;
    case 'webhooks': loadWebhooks(); break;
    case 'settings': loadSettings(); break;
  }

  if (window.innerWidth <= 768) closeSidebar();
}

function switchExplorerTab(tab) {
  currentExplorerTab = tab;
  document.querySelectorAll('#explorer-tabs .subtab').forEach(function(t) {
    t.classList.toggle('active', t.dataset.subtab === tab);
  });
  document.querySelectorAll('.explorer-panel').forEach(function(p) { p.classList.remove('active'); });
  var panel = document.getElementById('explorer-' + tab);
  if (panel) panel.classList.add('active');
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
    api('/api/events?limit=10'),
    api('/api/databases/' + currentDbId + '/api-keys')
  ]).then(function(results) {
    var memCount = results[0].count || 0;
    var srcCount = (results[1].sources || []).length;
    var entCount = results[2].count || (results[2].entities || []).length;
    var relCount = results[3].count || (results[3].relationships || []).length;
    var events = results[4].events || [];
    var keyCount = (results[5].apiKeys || results[5] || []).length || 0;

    renderStatCards(memCount, srcCount, entCount, relCount, events.length, keyCount);
    renderActivityFeed(events);
  });
}

function renderStatCards(mem, src, ent, rel, evt, keys) {
  var cards = [
    { label: 'Memories', value: mem, cls: 'memories', icon: 'M' },
    { label: 'Sources', value: src, cls: 'sources', icon: 'S' },
    { label: 'Entities', value: ent, cls: 'entities', icon: 'E' },
    { label: 'Relationships', value: rel, cls: 'relationships', icon: 'R' },
    { label: 'Events', value: evt, cls: 'events', icon: 'Ev' },
    { label: 'API Keys', value: keys, cls: 'apikeys', icon: 'K' }
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

async function sendAssistantMessage() {
  var input = document.getElementById('chat-input');
  var error = document.getElementById('chat-error');
  var message = input.value.trim();
  if (!message) return;
  error.textContent = '';
  input.value = '';
  appendChatMessage('user', message);
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
  removeTypingMessage();
  if (res.error) {
    error.textContent = res.error;
    appendChatMessage('assistant', 'Could not complete the request: ' + res.error);
    return;
  }
  renderChatContext(res.contextBundle || {});
  appendChatMessage('assistant', res.answer || 'No answer returned.');
  chatMemorySuggestions = normalizeMemorySuggestions(res.memorySuggestions || []);
  if (chatMemorySuggestions.length === 0) {
    chatMemorySuggestions = normalizeMemorySuggestions(suggestMemoryActions(message));
  }
  await autoStoreAssistantSuggestions();
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

function renderChatContext(bundle) {
  var box = document.getElementById('chat-context-summary');
  if (bundle.error) { box.innerHTML = '<div class="form-error">' + esc(bundle.error) + '</div>'; return; }
  var items = bundle.items || [];
  if (items.length === 0) { box.innerHTML = '<p class="muted">No stored context matched this query.</p>'; return; }
  box.innerHTML =
    '<div class="bundle-summary compact"><span>' + items.length + ' items</span><span>' +
    (bundle.estimatedTokens || 0) + ' tokens</span><span>' + esc(bundle.freshnessStatus || 'VALID') + '</span></div>' +
    items.slice(0, 5).map(function(item) {
      return '<div class="context-chip"><strong>' + esc(item.type || item.itemType || 'CONTEXT') + '</strong>' +
        '<p>' + esc(previewText(item.content || '', 180)) + '</p><small>' + esc(item.reason || '') + '</small></div>';
    }).join('');
}

function normalizeMemorySuggestions(items) {
  return (items || [])
    .filter(function(item) { return item && (item.action || 'store') !== 'none'; })
    .map(function(item) {
      return {
        type: String(item.type || 'memory').toLowerCase(),
        memoryType: item.memoryType || 'CONVERSATION',
        sourceType: item.sourceType || 'note',
        entityType: item.entityType || 'CONCEPT',
        title: suggestionTitle(item),
        content: item.content || '',
        name: item.name || item.sourceEntity || item.targetEntity || '',
        sourceEntity: item.sourceEntity || '',
        targetEntity: item.targetEntity || '',
        relationshipType: item.relationshipType || 'RELATED_TO',
        importance: typeof item.importance === 'number' ? item.importance : 0.75,
        confidence: typeof item.confidence === 'number' ? item.confidence : 0.8,
        reason: item.reason || 'Suggested by the model.'
      };
    });
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
    res = await api('/api/memory', { method: 'POST', body: JSON.stringify({ action: 'store', content: suggestion.content, type: suggestion.memoryType || 'CONVERSATION', importance: suggestion.importance || 0.8 }) });
  } else if (suggestion.type === 'source') {
    res = await api('/api/sources', { method: 'POST', body: JSON.stringify({ sourceType: suggestion.sourceType || 'note', sourceName: suggestion.name || 'Auto-saved context', content: suggestion.content, metadata: { origin: 'assistant', confidence: suggestion.confidence || 0.8 } }) });
  } else if (suggestion.type === 'entity') {
    res = await api('/api/semantic', { method: 'POST', body: JSON.stringify({ action: 'add_entity', entityType: suggestion.entityType || 'CONCEPT', name: suggestion.name || previewText(suggestion.content, 80), description: suggestion.content }) });
  } else if (suggestion.type === 'relationship') {
    res = await saveSuggestedRelationship(suggestion);
  } else if (suggestion.type === 'event') {
    res = await api('/api/events', { method: 'POST', body: JSON.stringify({ eventType: suggestion.eventType || 'ASSISTANT_MEMORY', action: suggestion.content }) });
  }
  return res || { error: 'Unsupported type: ' + suggestion.type };
}

async function saveSuggestedRelationship(suggestion) {
  var leftName = suggestion.sourceEntity || 'User';
  var rightName = suggestion.targetEntity || previewText(suggestion.content, 80) || 'Context';
  var leftType = suggestion.sourceEntityType || suggestion.entityType || 'PERSON';
  var rightType = suggestion.targetEntityType || suggestion.entityType || 'PERSON';
  var left = await api('/api/semantic', { method: 'POST', body: JSON.stringify({ action: 'add_entity', entityType: leftType, name: leftName, description: suggestion.content || leftName }) });
  if (left.error) return left;
  var right = await api('/api/semantic', { method: 'POST', body: JSON.stringify({ action: 'add_entity', entityType: rightType, name: rightName, description: suggestion.content || rightName }) });
  if (right.error) return right;
  return api('/api/semantic', { method: 'POST', body: JSON.stringify({ action: 'add_relationship', sourceEntityId: left.id, targetEntityId: right.id, relationshipType: suggestion.relationshipType || 'RELATED_TO' }) });
}

function suggestMemoryActions(message) {
  var text = message.trim();
  var suggestions = [];
  if (/\b(my name is|i am|i'm|i work|i build|my project|i use|i used|i made|i created|i wrote|i develop|i code in)\b/i.test(text))
    suggestions.push({ type: 'memory', memoryType: 'FACT', title: 'Save fact', content: text, importance: 0.85, reason: 'Stable personal or project fact.' });
  if (/\b(i prefer|i like|i want|i hate|i don't like|my favorite)\b/i.test(text))
    suggestions.push({ type: 'memory', memoryType: 'PREFERENCE', title: 'Save preference', content: text, importance: 0.8, reason: 'Preference for personalization.' });
  var relPatterns = text.match(/(\w+)(?:'s|s')?\s+(brother|sister|mother|father|son|daughter|cousin|spouse|friend|wife|husband|uncle|aunt)\s+(?:is\s+)?(\w+)/gi);
  if (relPatterns) {
    var seenEntities = {};
    relPatterns.forEach(function(match) {
      var parts = match.match(/(\w+)(?:'s|s')?\s+(brother|sister|mother|father|son|daughter|cousin|spouse|friend|wife|husband|uncle|aunt)\s+(?:is\s+)?(\w+)/i);
      if (parts) {
        var left = parts[1], rel = parts[2].toUpperCase() + '_OF', right = parts[3];
        if (!seenEntities[left]) { suggestions.push({ type: 'entity', entityType: 'PERSON', name: left, content: left, title: 'Save entity: ' + left, reason: 'Person mentioned.' }); seenEntities[left] = true; }
        if (!seenEntities[right]) { suggestions.push({ type: 'entity', entityType: 'PERSON', name: right, content: right, title: 'Save entity: ' + right, reason: 'Person mentioned.' }); seenEntities[right] = true; }
        suggestions.push({ type: 'relationship', sourceEntity: left, targetEntity: right, relationshipType: rel, content: match, title: 'Save relationship', reason: left + ' ' + rel.replace(/_/g, ' ').toLowerCase() + ' ' + right });
      }
    });
  }
  if (/\b(decided|decision|changed|started|launched|fixed|broke|deployed|migrated|released|shipped)\b/i.test(text))
    suggestions.push({ type: 'event', eventType: 'USER_TIMELINE', title: 'Save event', content: text, reason: 'Timeline event.' });
  if (text.length > 280 || /\b(code|log|document|architecture|config|readme|error|stack trace|exception)\b/i.test(text))
    suggestions.push({ type: 'source', sourceType: inferSourceType(text), title: 'Store as source', content: text, reason: 'Technical text.' });
  if (suggestions.length === 0 && text.length > 20)
    suggestions.push({ type: 'memory', memoryType: 'CONVERSATION', title: 'Save note', content: text, reason: 'Conversational context.' });
  return suggestions.slice(0, 10);
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
  return 'note';
}

// ═══════════════════════════════════════════════════════════════
// DATA EXPLORER
// ═══════════════════════════════════════════════════════════════
function loadExplorerTab(tab) {
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
  if (items.length === 0) { container.innerHTML = '<div class="empty-table">No memories stored yet.</div>'; return; }
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
  if (items.length === 0) { container.innerHTML = '<div class="empty-table">No sources stored yet.</div>'; return; }
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
  if (items.length === 0) { container.innerHTML = '<div class="empty-table">No entities stored yet.</div>'; return; }
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
  if (items.length === 0) { container.innerHTML = '<div class="empty-table">No relationships stored yet.</div>'; return; }
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
  if (items.length === 0) { container.innerHTML = '<div class="empty-table">No events logged yet.</div>'; return; }
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
document.addEventListener('DOMContentLoaded', function() {
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
      if (res.error || !res.user) { logout(); return; }
      showApp(res.user, res.defaultDatabase);
    });
  }
});
