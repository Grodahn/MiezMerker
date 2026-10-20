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
import { markSessionExpired } from '../platform/auth';
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

test('unmount revokes transient URLs; unsupported SVG previews are rejected', async () => {
  fetchMock.mockResolvedValue(photoResponse());
  const view = render(<CatPhoto organizationId="org-a" cat={cat({ hasPhoto: true, photoUpdatedAt: 'v1' })} onChanged={() => {}} />);
  await screen.findByAltText('Katzenfoto von Minka');
  const urls = [...created];
  expect(urls).not.toHaveLength(0);
  view.unmount();
  expect(revoked).toEqual(expect.arrayContaining(urls));

  // The client also limits previews to JPEG/PNG. Server decoding remains
  // authoritative for the bytes and independently rejects forged MIME types.
  render(<CatPhoto organizationId="org-a" cat={cat()} onChanged={() => {}} />);
  fireEvent.change(screen.getByLabelText(/Bild wählen/),
    { target: { files: [new File(['<svg/>'], 'bild.svg', { type: 'image/svg+xml' })] } });
  expect(await screen.findByRole('alert')).toBeTruthy();
  expect(screen.queryByAltText('Vorschau: neues Foto für Minka')).toBeNull();
  expect((screen.getByRole('button', { name: 'Foto speichern' }) as HTMLButtonElement).disabled).toBe(true);
});

test.each([401, 403])('upload denial %i discards private bytes and pending previews', async status => {
  vi.mocked(markSessionExpired).mockClear();
  fetchMock.mockResolvedValue(photoResponse());
  render(<CatPhoto organizationId="org-a" cat={cat({ hasPhoto: true })} onChanged={() => {}} />);
  await screen.findByAltText('Katzenfoto von Minka');
  fireEvent.change(screen.getByLabelText(/Bild wählen/),
    { target: { files: [new File(['png'], 'photo.png', { type: 'image/png' })] } });
  fetchMock.mockResolvedValueOnce({ ok: false, status });
  await act(async () => fireEvent.click(screen.getByRole('button', { name: 'Foto speichern' })));
  expect(screen.queryByAltText('Katzenfoto von Minka')).toBeNull();
  expect(screen.queryByAltText(/Vorschau/)).toBeNull();
  expect(revoked).toEqual(expect.arrayContaining(created));
  expect(markSessionExpired).toHaveBeenCalledTimes(status === 401 ? 1 : 0);
});

test('invalid replacement and cancelled selection discard the previous preview', async () => {
  render(<CatPhoto organizationId="org-a" cat={cat()} onChanged={() => {}} />);
  const input = screen.getByLabelText(/Bild wählen/);
  const valid = new File(['png'], 'photo.png', { type: 'image/png' });
  fireEvent.change(input, { target: { files: [valid] } });
  expect(screen.getByAltText(/Vorschau/)).toBeTruthy();
  fireEvent.change(input, { target: { files: [new File(['html'], 'attack.html', { type: 'text/html' })] } });
  expect(screen.queryByAltText(/Vorschau/)).toBeNull();
  expect(revoked).toContain(created[0]);
  fireEvent.change(input, { target: { files: [valid] } });
  fireEvent.change(input, { target: { files: [] } });
  expect(screen.queryByAltText(/Vorschau/)).toBeNull();
  expect(screen.queryByText(/Ausgewählt/)).toBeNull();
  expect((screen.getByRole('button', { name: 'Foto speichern' }) as HTMLButtonElement).disabled).toBe(true);
});

test('changing the cat clears its preview and ignores an upload response from the previous cat', async () => {
  let finish!: (response: unknown) => void;
  fetchMock.mockImplementation(() => new Promise(resolve => { finish = resolve; }));
  const changed = vi.fn();
  const view = render(<CatPhoto organizationId="org-a" cat={cat()} onChanged={changed} />);
  fireEvent.change(screen.getByLabelText(/Bild wählen/),
    { target: { files: [new File(['png'], 'photo.png', { type: 'image/png' })] } });
  await act(async () => fireEvent.click(screen.getByRole('button', { name: 'Foto speichern' })));
  const signal = fetchMock.mock.calls[0][1].signal as AbortSignal;
  view.rerender(<CatPhoto organizationId="org-a" cat={cat({ id: 'cat-2', name: 'Schwarzi' })} onChanged={changed} />);
  expect(signal.aborted).toBe(true);
  expect(revoked).toContain(created[0]);
  expect(screen.queryByAltText(/Vorschau/)).toBeNull();
  await act(async () => finish({ ok: true, status: 200 }));
  expect(changed).not.toHaveBeenCalled();
  expect(screen.queryByText('Foto gespeichert.')).toBeNull();
});

test('a batched organization switch clears private photos and previews and ignores late loads', async () => {
  let finish!: (response: unknown) => void;
  fetchMock.mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }))
    .mockResolvedValue(photoResponse());
  render(<CatPhoto organizationId="org-a" cat={cat({ hasPhoto: true })} onChanged={() => {}} />);
  fireEvent.change(screen.getByLabelText(/Bild wählen/),
    { target: { files: [new File(['png'], 'private.png', { type: 'image/png' })] } });
  const preview = created[0];
  await act(async () => {
    auth.activeOrganizationId = 'org-b'; listeners.forEach(fn => fn());
    auth.activeOrganizationId = 'org-a'; listeners.forEach(fn => fn());
  });
  expect(revoked).toContain(preview);
  expect(screen.queryByAltText(/Vorschau/)).toBeNull();
  await screen.findByAltText('Katzenfoto von Minka');
  const count = created.length;
  await act(async () => finish(photoResponse('stale')));
  expect(created).toHaveLength(count);
});

test.each([
  ['upload', 200], ['upload', 401], ['upload', 403], ['delete', 204],
] as const)('%s response %i prevents a late image body from restoring the old photo', async (operation, status) => {
  let finishBody!: (blob: Blob) => void;
  fetchMock.mockResolvedValueOnce({ ok: true, status: 200,
    blob: () => new Promise<Blob>(resolve => { finishBody = resolve; }) });
  render(<CatPhoto organizationId="org-a" cat={cat({ hasPhoto: true })} onChanged={() => {}} />);
  await waitFor(() => expect(finishBody).toBeTypeOf('function'));
  const loadSignal = fetchMock.mock.calls[0][1].signal as AbortSignal;
  fetchMock.mockResolvedValueOnce({ ok: status < 400, status });
  if (operation === 'upload') {
    fireEvent.change(screen.getByLabelText(/Bild wählen/),
      { target: { files: [new File(['png'], 'replacement.png', { type: 'image/png' })] } });
    await act(async () => fireEvent.click(screen.getByRole('button', { name: 'Foto speichern' })));
  } else {
    fireEvent.click(screen.getByRole('button', { name: 'Foto entfernen' }));
    await act(async () => fireEvent.click(screen.getByRole('button', { name: 'Ja, entfernen' })));
  }
  expect(loadSignal.aborted).toBe(true);
  const count = created.length;
  // Simulate a transport that still delivers the body after abort, while the
  // parent list refresh fails and therefore leaves hasPhoto unchanged.
  await act(async () => finishBody(new Blob(['old photo'], { type: 'image/jpeg' })));
  expect(created).toHaveLength(count);
  expect(screen.queryByAltText('Katzenfoto von Minka')).toBeNull();
  expect(screen.queryByAltText(/Vorschau/)).toBeNull();
});
