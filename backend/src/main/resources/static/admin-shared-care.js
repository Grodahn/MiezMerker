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
      // Transient blob URLs only: no-store, never any browser storage. The
      // presenter revokes them on reload, context loss and dispose.
      photo: async (organizationId, body, signal) => {
        const csrf = await fetch('/api/v1/auth/csrf', { credentials: 'same-origin', cache: 'no-store', signal: AbortSignal.any([signal, AbortSignal.timeout(15000)]) });
        if (!csrf.ok) return null;
        const token = (await csrf.json()).token;
        signal.throwIfAborted();
        const response = await fetch(`/api/v1/organizations/${organizationId}/shared-care/photo`, {
          credentials: 'same-origin', cache: 'no-store', signal: AbortSignal.any([signal, AbortSignal.timeout(15000)]),
          method: 'POST', headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': token },
          body: JSON.stringify(body),
        });
        signal.throwIfAborted();
        if (!response.ok) return null;
        const blob = await response.blob();
        signal.throwIfAborted();
        if (!blob.type.startsWith('image/')) return null;
        return URL.createObjectURL(blob);
      },
    },
  });
}
