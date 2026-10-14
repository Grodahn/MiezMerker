import createClient from 'openapi-fetch';
import type { paths } from './generated';

// Relative paths preserve same-origin session cookies and the local /api proxy.
export const api = createClient<paths>({ baseUrl: '', credentials: 'same-origin' });

// A business API denial is inconclusive: reconcile through the authenticated
// status endpoint rather than interpreting a generic 401/403 as revocation.
// Coalesce ordinary successful exchanges; no permanent background connection.
let refreshing = false;
let lastRefresh = 0;
api.use({
  onResponse({ request, response }) {
    const path = new URL(request.url, window.location.origin).pathname;
    if (path.startsWith('/api/v1/auth/') || path === '/api/v1/health' || path === '/api/v1/version'
        || path === '/api/v1/credentials/issuer' || (response.ok && path.startsWith('/api/v1/devices'))) return;
    const denied = response.status === 401 || response.status === 403;
    if (refreshing || (!denied && (!response.ok || Date.now() - lastRefresh < 60_000))) return;
    refreshing = true;
    lastRefresh = Date.now();
    void import('../platform/auth').then(async auth => {
      if (auth.getAuthState().user) await auth.fetchSession();
    }).catch(() => {}).finally(() => { refreshing = false; });
  },
});
