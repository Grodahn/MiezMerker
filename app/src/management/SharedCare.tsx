import { useEffect, useRef } from 'react';
import { api } from '../api/client';
import { fetchCsrfToken } from '../platform/auth';
import { contextSignal } from './context';
import { requestOptions, result } from './data';
import { mountSharedCare, type SharedCareApi } from '../../../backend/src/main/resources/static/shared-care.js';
import '../../../backend/src/main/resources/static/shared-care.css';

const sharedApi: SharedCareApi = {
  observations: async (organizationId, chipId, signal) => result(await api.GET('/api/v1/observations', {
    ...requestOptions(signal), params: { query: { organizationId, chipId, limit: 1, offset: 0, newestFirst: true } },
  })),
  resolve: async (organizationId, body, signal) => {
    const token = await fetchCsrfToken(); signal.throwIfAborted();
    return result(await api.POST('/api/v1/organizations/{organizationId}/shared-care/resolve', {
      ...requestOptions(signal), headers: { 'X-XSRF-TOKEN': token }, params: { path: { organizationId } }, body,
    }));
  },
  visits: async (organizationId, body, signal) => {
    const token = await fetchCsrfToken(); signal.throwIfAborted();
    return result(await api.POST('/api/v1/organizations/{organizationId}/shared-care/visits', {
      ...requestOptions(signal), headers: { 'X-XSRF-TOKEN': token }, params: { path: { organizationId } }, body,
    }));
  },
  // Foreign photos stay transient: fetched as blobs (no-store), surfaced as
  // revocable object URLs, never written to any browser storage.
  photo: async (organizationId, body, signal) => {
    const token = await fetchCsrfToken(); signal.throwIfAborted();
    const response = await fetch(`/api/v1/organizations/${encodeURIComponent(organizationId)}/shared-care/photo`, {
      ...requestOptions(signal),
      credentials: 'same-origin',
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': token },
      body: JSON.stringify(body),
    });
    signal.throwIfAborted();
    if (!response.ok) return null;
    const blob = await response.blob();
    signal.throwIfAborted();
    if (!blob.type.startsWith('image/')) return null;
    return URL.createObjectURL(blob);
  },
};

export function SharedCare({ organizationId, chipId }: { organizationId: string; chipId: string }) {
  const root = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (root.current) return mountSharedCare(root.current, { organizationId, chipId, api: sharedApi, controllerFactory: contextSignal });
  }, [organizationId, chipId]);
  return <section className="detail shared-care" aria-label="Geteilte Betreuung">
    <h2>Geteilte Betreuung · nur online</h2><div ref={root} aria-live="polite"/>
  </section>;
}
