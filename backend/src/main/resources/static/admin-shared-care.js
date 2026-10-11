import { mountSharedCare } from './shared-care.js';

const root = document.querySelector('[data-shared-care]');
if (root) {
  async function request(path, signal, body) {
    const options = { credentials: 'same-origin', cache: 'no-store', signal: AbortSignal.any([signal, AbortSignal.timeout(15000)]) };
    if (body) {
      const csrf = await fetch('/api/v1/auth/csrf', options);
      if (!csrf.ok) throw new Error('unavailable');
      const token = (await csrf.json()).token;
      signal.throwIfAborted();
      options.method = 'POST'; options.headers = { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': token };
      options.body = JSON.stringify(body);
    }
    const response = await fetch(path, options);
    if (!response.ok) throw new Error('unavailable');
    return response.json();
  }
  mountSharedCare(root, {
    organizationId: root.dataset.organizationId, chipId: root.dataset.chipId, pageSize: 20,
    // The Admin active organization belongs to its server session, so return
    // through the existing controller before displaying any shared projection.
    onReturn: () => window.location.reload(),
    api: {
      observations: (organizationId, chipId, signal) => request('/api/v1/observations?' + new URLSearchParams({
        organizationId, chipId, limit: '1', offset: '0', newestFirst: 'true',
      }), signal),
      resolve: (organizationId, body, signal) => request(`/api/v1/organizations/${organizationId}/shared-care/resolve`, signal, body),
      visits: (organizationId, body, signal) => request(`/api/v1/organizations/${organizationId}/shared-care/visits`, signal, body),
    },
  });
}
