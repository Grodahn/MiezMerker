import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { fireEvent, waitFor, within } from '@testing-library/react';
import { mountSharedCare, type SharedCareApi } from '../../../backend/src/main/resources/static/shared-care.js';

const source = { organizationId: 'org-b', displayName: 'Organisation B' };
const profile = { chipId: 'CHIP', source, catDisplayName: 'B-Name' };
const visit = { source, startAt: '2026-10-01T10:00:00Z', endAt: '2026-10-01T10:01:00Z' };
let root: HTMLDivElement;
let dispose: () => void;
let api: SharedCareApi;
beforeEach(() => {
  vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(true);
  root = document.createElement('div'); document.body.append(root);
  api = { observations: vi.fn().mockResolvedValue([{ id: 'own-read', organizationId: 'org-a', chipId: 'CHIP', clockStatus: 'UNKNOWN' }]),
    resolve: vi.fn().mockResolvedValue({ items: [profile], nextOffset: null }),
    visits: vi.fn().mockResolvedValue({ items: [visit], nextOffset: null }),
    photo: vi.fn().mockResolvedValue(null) };
});
afterEach(() => { dispose?.(); root.remove(); vi.restoreAllMocks(); });
function mount(options = {}) {
  dispose = mountSharedCare(root, { organizationId: 'org-a', chipId: 'CHIP', api, ...options });
}
async function ready() { await waitFor(() => expect(root.textContent).toContain('B-Name')); }

test.each([
  ['CARE', [], false],
  ['CARE + VISITS', [visit], false],
  ['CARE + VISITS + SITE_LABEL', [{ ...visit, siteDisplayName: 'Freigegebener Garten' }], true],
])('%s renders only returned summaries', async (_scopes, visits, label) => {
  vi.mocked(api.visits).mockResolvedValue({ items: visits }); mount(); await ready();
  expect(root.textContent).toContain('Wird auch von Organisation B betreut');
  expect(root.textContent).toContain('schreibgeschützt');
  expect(root.querySelector('input,textarea,a,img')).toBeNull();
  expect(root.textContent?.includes('Freigegebener Garten')).toBe(label);
  expect(root.querySelectorAll('time')).toHaveLength(visits.length * 2);
  if (visits.length && !label) expect(root.textContent).toContain('Futterstellenname nicht freigegeben');
  if (!visits.length) expect(root.textContent).toContain('Keine freigegebenen Besuche');
  expect(api.resolve).toHaveBeenCalledWith('org-a', { ownObservationRefs: ['own-read'], limit: 5, offset: 0 }, expect.any(AbortSignal));
  // UNKNOWN observation is proof, never a fabricated foreign visit timestamp.
  expect(root.textContent).toContain('UNKNOWN-Reads');
});

test.each(['no grant', 'hidden organization', 'revoked grant'])('%s does not suggest a hidden foreign profile', async () => {
  vi.mocked(api.resolve).mockResolvedValue({ items: [] }); mount();
  await waitFor(() => expect(root.textContent).toContain('Keine freigegebene Betreuung'));
  expect(root.textContent).not.toContain('Organisation B'); expect(api.visits).not.toHaveBeenCalled();
});

test('authorized foreign photo renders read-only with provenance; denied photo keeps a neutral placeholder', async () => {
  vi.mocked(api.photo).mockResolvedValue('blob:foreign-b');
  mount(); await ready();
  const image = root.querySelector('.shared-care__source img') as HTMLImageElement | null;
  expect(image?.src).toContain('blob:foreign-b');
  expect(image?.alt).toContain('Organisation B');
  expect(api.photo).toHaveBeenCalledWith('org-a',
    { ownObservationRef: 'own-read', sourceOrganizationId: 'org-b' }, expect.any(AbortSignal));
  // The local photo is never substituted: no photo URL appears outside the
  // provenance-marked foreign card.
  expect(root.querySelectorAll('img')).toHaveLength(1);

  vi.mocked(api.photo).mockResolvedValue(null);
  fireEvent.click(within(root).getByRole('button', { name: 'Freigaben neu prüfen' }));
  await waitFor(() => expect(root.textContent).toContain('Kein freigegebenes Foto verfügbar'));
  expect(root.querySelector('img')).toBeNull();
  // A denied photo never fails the profile/visit page.
  expect(root.textContent).toContain('B-Name');
});

test('foreign photo URLs are revoked on reload and dispose; a late photo never restores', async () => {
  const revoke = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {});
  vi.mocked(api.photo).mockImplementation(async () => URL.createObjectURL(new Blob(['x'], { type: 'image/jpeg' })));
  mount(); await ready();
  const first = (root.querySelector('.shared-care__source img') as HTMLImageElement).src;
  expect(first.startsWith('blob:')).toBe(true);
  fireEvent.click(within(root).getByRole('button', { name: 'Freigaben neu prüfen' }));
  await waitFor(() => expect(api.photo).toHaveBeenCalledTimes(2));
  await waitFor(() => expect(revoke).toHaveBeenCalledWith(first));
  // A photo resolving after dispose is never rendered.
  let finish!: (url: string) => void;
  vi.mocked(api.photo).mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
  fireEvent.click(within(root).getByRole('button', { name: 'Freigaben neu prüfen' }));
  await waitFor(() => expect(finish).toBeTypeOf('function'));
  const undone = dispose; dispose = () => {};
  undone();
  finish('blob:stale');
  await new Promise(resolve => setTimeout(resolve, 0));
  expect(root.querySelector('img')).toBeNull();
  // Nothing is written to persistent browser storage.
  expect(localStorage.length).toBe(0);
});

test.each([[], [{ id: 'forged', organizationId: 'org-b', chipId: 'CHIP' }], [{ id: 'other-chip', organizationId: 'org-a', chipId: 'OTHER' }]].map(observations => [observations]))(
  'no valid own observation prevents shared queries (%j)', async observations => {
    vi.mocked(api.observations).mockResolvedValue(observations); mount();
    await waitFor(() => expect(root.textContent).toContain('Keine eigene gespeicherte Beobachtung'));
    expect(api.resolve).not.toHaveBeenCalled(); expect(api.visits).not.toHaveBeenCalled();
  });

test('multiple organizations keep separate identities, visit pages and provenance', async () => {
  const second = { ...profile, source: { organizationId: 'org-c', displayName: 'Organisation C' }, catDisplayName: 'C-Name' };
  vi.mocked(api.resolve).mockResolvedValue({ items: [profile, second] });
  vi.mocked(api.visits).mockImplementation(async (_org, body) => ({ items: body.sourceOrganizationId === 'org-b' ? [visit] : [] }));
  mount(); await ready();
  expect(within(root).getByRole('region', { name: 'Geteilte Betreuung von Organisation C' }).textContent).toContain('C-Name');
  expect(within(root).getByRole('region', { name: 'Geteilte Betreuung von Organisation B' }).textContent).not.toContain('C-Name');
  expect(root.querySelector('th')?.scope).toBe('col');
  expect(root.querySelectorAll('button')).not.toHaveLength(0);
});

test('every visit page rechecks CARE and clears stale names before a revoked response', async () => {
  vi.mocked(api.visits).mockResolvedValue({ items: [visit], nextOffset: 5 }); mount(); await ready();
  let finish!: (value: { items: [] }) => void;
  vi.mocked(api.resolve).mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
  fireEvent.click(within(root).getByRole('button', { name: 'Ältere Besuche' }));
  expect(root.textContent).not.toContain('B-Name');
  await waitFor(() => expect(finish).toBeTypeOf('function')); finish({ items: [] });
  await waitFor(() => expect(root.textContent).toContain('Keine freigegebene Betreuung'));
  expect(api.visits).toHaveBeenCalledTimes(1);
});

test('server nextOffset drives visits and organization pagination including empty pages', async () => {
  vi.mocked(api.visits).mockResolvedValueOnce({ items: [visit], nextOffset: 5 }).mockResolvedValue({ items: [] });
  vi.mocked(api.resolve).mockResolvedValue({ items: [profile], nextOffset: 5 });
  mount(); await ready(); fireEvent.click(within(root).getByRole('button', { name: 'Ältere Besuche' }));
  await waitFor(() => expect(root.textContent).toContain('Seite 2'));
  expect(api.visits).toHaveBeenLastCalledWith('org-a', expect.objectContaining({ offset: 5, limit: 5 }), expect.any(AbortSignal));
  fireEvent.click(within(root).getByRole('button', { name: 'Weitere Organisationen' }));
  await waitFor(() => expect(api.resolve).toHaveBeenLastCalledWith('org-a', expect.objectContaining({ offset: 5 }), expect.any(AbortSignal)));
});

test.each(['offline', 'blur', 'pagehide', 'submit'])('%s clears all foreign DOM and ignores a late request', async event => {
  mount(event === 'submit' ? { onReturn: vi.fn() } : {}); await ready();
  let finish!: (value: { items: typeof profile[] }) => void;
  vi.mocked(api.resolve).mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
  fireEvent.click(within(root).getByRole('button', { name: 'Freigaben neu prüfen' }));
  await waitFor(() => expect(finish).toBeTypeOf('function'));
  (event === 'submit' ? document : window).dispatchEvent(new Event(event));
  finish({ items: [profile] }); await new Promise(resolve => setTimeout(resolve, 0));
  expect(root.textContent).not.toContain('B-Name'); expect(root.querySelector('time')).toBeNull();
});

test('a field-PWA local form submission leaves the shared panel usable', async () => {
  mount(); await ready();
  document.dispatchEvent(new Event('submit'));
  expect(root.textContent).toContain('B-Name');
  expect(within(root).getByRole('button', { name: 'Freigaben neu prüfen' })).toBeTruthy();
});

test('Admin focus and visibility return events request only one context reload', async () => {
  const onReturn = vi.fn(); mount({ onReturn }); await ready();
  window.dispatchEvent(new Event('blur'));
  document.dispatchEvent(new Event('visibilitychange'));
  window.dispatchEvent(new Event('focus'));
  expect(onReturn).toHaveBeenCalledTimes(1);
  expect(root.textContent).not.toContain('B-Name');
});

test('revoked care restores keyboard focus to an enabled refresh control', async () => {
  vi.mocked(api.visits).mockResolvedValue({ items: [visit], nextOffset: 5 }); mount(); await ready();
  vi.mocked(api.resolve).mockResolvedValueOnce({ items: [] });
  const older = within(root).getByRole('button', { name: 'Ältere Besuche' });
  older.focus(); fireEvent.click(older);
  await waitFor(() => expect(root.textContent).toContain('Keine freigegebene Betreuung'));
  expect(document.activeElement).toBe(within(root).getByRole('button', { name: 'Freigaben neu prüfen' }));
});

test('a failed page restores keyboard focus to its retry control', async () => {
  mount(); await ready(); vi.mocked(api.resolve).mockRejectedValueOnce(new Error('unavailable'));
  const refresh = within(root).getByRole('button', { name: 'Freigaben neu prüfen' });
  refresh.focus(); fireEvent.click(refresh);
  await waitFor(() => expect(root.textContent).toContain('Berechtigung prüfen'));
  expect(document.activeElement).toBe(within(root).getByRole('button', { name: 'Erneut prüfen' }));
});

test('offline initially performs no request; reconnect reauthorizes', async () => {
  vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false); mount();
  expect(root.textContent).toContain('nur online'); expect(api.observations).not.toHaveBeenCalled();
  vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(true); window.dispatchEvent(new Event('online')); await ready();
});

test('Admin returns through current server organization and uses larger pages', async () => {
  const onReturn = vi.fn(); mount({ pageSize: 20, onReturn }); await ready();
  window.dispatchEvent(new Event('blur')); window.dispatchEvent(new Event('focus'));
  expect(root.textContent).not.toContain('B-Name'); expect(onReturn).toHaveBeenCalled();
  expect(api.visits).toHaveBeenCalledWith('org-a', expect.objectContaining({ limit: 20 }), expect.any(AbortSignal));
});

test('safe text, absent timestamps, and error messages never disclose foreign details', async () => {
  vi.mocked(api.resolve).mockResolvedValue({ items: [{ ...profile, catDisplayName: '<img src=x onerror=alert(1)>' }] });
  vi.mocked(api.visits).mockResolvedValue({ items: [{ source, startAt: undefined, endAt: 'invalid' }] });
  mount(); await waitFor(() => expect(root.textContent).toContain('<img'));
  expect(root.querySelector('img')).toBeNull(); expect(root.querySelector('time')).toBeNull();
  vi.mocked(api.resolve).mockRejectedValue(new Error('secret site and foreign-id'));
  fireEvent.click(within(root).getByRole('button', { name: 'Freigaben neu prüfen' }));
  await waitFor(() => expect(root.textContent).toContain('Berechtigung prüfen'));
  expect(root.textContent).not.toContain('secret'); expect(root.textContent).not.toContain('<img');
});
