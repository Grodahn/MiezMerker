import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
const { auth, listeners } = vi.hoisted(() => ({
  auth: { user: { userId: 'user', memberships: [
    { organizationId: 'org-a', status: 'ACTIVE', role: 'MEMBER' },
  ] }, activeOrganizationId: 'org-a', sessionVerified: true },
  listeners: new Set<() => void>(),
}));
vi.mock('../platform/auth', () => ({ getAuthState: () => auth,
  subscribeAuth: (fn: () => void) => { listeners.add(fn); return () => listeners.delete(fn); },
  fetchCsrfToken: async () => 'csrf', markSessionExpired: vi.fn(),
}));
vi.mock('../assets', () => ({ assets: { placeholders: { cat: 'placeholder-cat.svg' } } }));
import { CatPhoto } from './CatPhoto';
import type { Cat } from './common';

const cat = (overrides: Partial<Cat> = {}): Cat => ({
  id: 'cat-1', organizationId: 'org-a', chipId: 'CHIP', name: 'Minka',
  hasPhoto: false, photoUpdatedAt: null, ...overrides,
} as Cat);

let fetchMock: ReturnType<typeof vi.fn>;
let created: string[];
let revoked: string[];
beforeEach(() => {
  created = []; revoked = [];
  vi.spyOn(URL, 'createObjectURL').mockImplementation(() => {
    const url = `blob:photo-${created.length}`;
    created.push(url);
    return url;
  });
  vi.spyOn(URL, 'revokeObjectURL').mockImplementation(url => { revoked.push(url); });
  fetchMock = vi.fn();
  vi.stubGlobal('fetch', fetchMock);
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals(); });

function photoResponse(bytes = 'jpeg-bytes') {
  return { ok: true, status: 200, blob: async () => new Blob([bytes], { type: 'image/jpeg' }) };
}

test('placeholder without photo; image with accessible alt when a photo exists', async () => {
  render(<CatPhoto organizationId="org-a" cat={cat()} onChanged={() => {}} />);
  expect(screen.getByAltText(/Platzhalter/)).toBeTruthy();
  expect(fetchMock).not.toHaveBeenCalled();
  cleanup();

  fetchMock.mockResolvedValue(photoResponse());
  render(<CatPhoto organizationId="org-a" cat={cat({ hasPhoto: true, photoUpdatedAt: 'v1' })} onChanged={() => {}} />);
  const image = await screen.findByAltText('Katzenfoto von Minka') as HTMLImageElement;
  expect(image.src).toContain('blob:photo-');
  expect(fetchMock).toHaveBeenCalledWith(expect.stringContaining('/api/v1/organizations/org-a/cats/cat-1/photo'),
    expect.objectContaining({ cache: 'no-store', credentials: 'same-origin' }));
});

test('select, preview, save and replace: preview clears only after a confirmed save', async () => {
  fetchMock.mockResolvedValue(photoResponse());
  let changed = 0;
  render(<CatPhoto organizationId="org-a" cat={cat({ hasPhoto: true, photoUpdatedAt: 'v1' })} onChanged={() => { changed += 1; }} />);
  await screen.findByAltText('Katzenfoto von Minka');

  const file = new File(['new-bytes'], 'neu.png', { type: 'image/png' });
  fireEvent.change(screen.getByLabelText(/Bild wählen/), { target: { files: [file] } });
  expect(await screen.findByAltText('Vorschau: neues Foto für Minka')).toBeTruthy();

  fetchMock.mockResolvedValueOnce({ ok: true, status: 200, json: async () => ({ hasPhoto: true }) });
  await act(async () => fireEvent.click(screen.getByRole('button', { name: 'Foto speichern' })));
  const put = fetchMock.mock.calls.at(-1)!;
  expect(put[0]).toContain('/api/v1/organizations/org-a/cats/cat-1/photo');
  expect(put[1]).toMatchObject({ method: 'PUT', headers: { 'X-XSRF-TOKEN': 'csrf' } });
  expect(put[1].body).toBeInstanceOf(FormData);
  expect(changed).toBe(1);
  // The preview object URL is revoked once the save is confirmed.
  expect(revoked.length).toBeGreaterThanOrEqual(1);
});

test('failed upload keeps the current photo and reports an error', async () => {
  fetchMock.mockResolvedValue(photoResponse());
  let changed = 0;
  render(<CatPhoto organizationId="org-a" cat={cat({ hasPhoto: true, photoUpdatedAt: 'v1' })} onChanged={() => { changed += 1; }} />);
  await screen.findByAltText('Katzenfoto von Minka');

  fireEvent.change(screen.getByLabelText(/Bild wählen/),
    { target: { files: [new File(['x'], 'neu.png', { type: 'image/png' })] } });
  await screen.findByAltText('Vorschau: neues Foto für Minka');
  fetchMock.mockResolvedValueOnce({ ok: false, status: 400 });
  await act(async () => fireEvent.click(screen.getByRole('button', { name: 'Foto speichern' })));
  expect(await screen.findByRole('alert')).toBeTruthy();
  expect(changed).toBe(0);
  // The current photo is untouched; the preview stays for a retry.
  expect(screen.getByAltText('Vorschau: neues Foto für Minka')).toBeTruthy();
});

test('remove asks for confirmation, deletes, and notifies', async () => {
  fetchMock.mockResolvedValue(photoResponse());
  let changed = 0;
  render(<CatPhoto organizationId="org-a" cat={cat({ hasPhoto: true, photoUpdatedAt: 'v1' })} onChanged={() => { changed += 1; }} />);
  await screen.findByAltText('Katzenfoto von Minka');

  fireEvent.click(screen.getByRole('button', { name: 'Foto entfernen' }));
  expect(screen.getByText('Wirklich entfernen?')).toBeTruthy();
  expect(fetchMock).toHaveBeenCalledTimes(1);
  fetchMock.mockResolvedValueOnce({ ok: true, status: 204 });
  await act(async () => fireEvent.click(screen.getByRole('button', { name: 'Ja, entfernen' })));
  const deletion = fetchMock.mock.calls.at(-1)!;
  expect(deletion[1]).toMatchObject({ method: 'DELETE', headers: { 'X-XSRF-TOKEN': 'csrf' } });
  expect(changed).toBe(1);
  expect(await screen.findByRole('status')).toBeTruthy();
});

test('unmount revokes transient URLs; server-side validation stays authoritative for SVG', async () => {
  fetchMock.mockResolvedValue(photoResponse());
  const view = render(<CatPhoto organizationId="org-a" cat={cat({ hasPhoto: true, photoUpdatedAt: 'v1' })} onChanged={() => {}} />);
  await screen.findByAltText('Katzenfoto von Minka');
  const urls = [...created];
  expect(urls).not.toHaveLength(0);
  view.unmount();
  expect(revoked).toEqual(expect.arrayContaining(urls));

  // image/svg+xml passes the client file picker (image/*); the backend
  // allowlist (JPEG/PNG, decoded content) rejects it and the UI reports it.
  render(<CatPhoto organizationId="org-a" cat={cat()} onChanged={() => {}} />);
  fireEvent.change(screen.getByLabelText(/Bild wählen/),
    { target: { files: [new File(['<svg/>'], 'bild.svg', { type: 'image/svg+xml' })] } });
  await screen.findByAltText('Vorschau: neues Foto für Minka');
  fetchMock.mockResolvedValueOnce({ ok: false, status: 400 });
  await act(async () => fireEvent.click(screen.getByRole('button', { name: 'Foto speichern' })));
  expect(await screen.findByRole('alert')).toBeTruthy();
});
