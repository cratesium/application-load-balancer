const API_BASE = '';

function getToken() {
  return sessionStorage.getItem('alb_admin_token') || '';
}

async function request(path, options = {}) {
  const token = getToken();
  const headers = {
    ...(options.headers || {}),
  };

  if (token) {
    headers['Authorization'] = `Bearer ${token}`;
  }

  if (options.body && typeof options.body === 'object') {
    headers['Content-Type'] = 'application/json';
    options.body = JSON.stringify(options.body);
  }

  const response = await fetch(`${API_BASE}${path}`, {
    ...options,
    headers,
  });

  if (response.status === 401) {
    sessionStorage.removeItem('alb_admin_token');
    window.location.href = '/console/login';
    throw new Error('Unauthorized');
  }

  if (!response.ok) {
    const errorBody = await response.json().catch(() => ({}));
    const error = new Error(errorBody.message || `HTTP ${response.status}`);
    error.status = response.status;
    error.code = errorBody.error;
    error.body = errorBody;
    throw error;
  }

  if (response.status === 204) return null;
  return response.json();
}

export const api = {
  // Status
  getStatus: () => request('/admin/status'),

  // Backends
  listBackends: () => request('/admin/backends'),
  getBackend: (id) => request(`/admin/backends/${encodeURIComponent(id)}`),
  addBackend: (data) => request('/admin/backends', { method: 'POST', body: data }),
  removeBackend: (id) => request(`/admin/backends/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  disableBackend: (id) => request(`/admin/backends/${encodeURIComponent(id)}/disable`, { method: 'POST' }),
  enableBackend: (id) => request(`/admin/backends/${encodeURIComponent(id)}/enable`, { method: 'POST' }),
  updateWeight: (id, weight) =>
    request(`/admin/backends/${encodeURIComponent(id)}/weight`, { method: 'PUT', body: { weight } }),
  resetCircuitBreaker: (id) =>
    request(`/admin/backends/${encodeURIComponent(id)}/circuit-breaker/reset`, { method: 'POST' }),

  // Algorithm
  getAlgorithm: () => request('/admin/algorithm'),
  switchAlgorithm: (algorithm) =>
    request('/admin/load-balancer/algorithm', { method: 'POST', body: { algorithm } }),

  // Routes
  listRoutes: () => request('/admin/routes'),

  // Config
  reloadConfig: () => request('/admin/config/reload', { method: 'POST' }),

  // Validate token (try fetching status)
  validateToken: (token) =>
    fetch('/admin/status', {
      headers: { Authorization: `Bearer ${token}` },
    }).then((r) => r.ok),
};
