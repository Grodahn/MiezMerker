import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';

const { get, post } = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }));
vi.mock('../api/client', () => ({ api: { GET: get, POST: post } }));
import { AuthPanel } from './AuthPanel';
import { fetchSession, getAuthState } from './auth';

beforeEach(async () => {
  get.mockReset(); post.mockReset();
  get.mockResolvedValue({ error: {}, response: { status: 401 } });
  await fetchSession();
});
afterEach(cleanup);

test('user can log in, choose an organization and log out', async () => {
  render(<AuthPanel/>);
  await waitFor(() => expect(get).toHaveBeenCalledWith('/api/v1/auth/session'));
  get.mockResolvedValue({ data: { token: 'csrf' } });
  post.mockResolvedValueOnce({ data: { userId: 'u1', email: 'admin@example.org', memberships: [
    { membershipId: 'm1', organizationId: 'o1', organizationName: 'Org A', role: 'ADMIN', status: 'ACTIVE' },
    { membershipId: 'm2', organizationId: 'o2', organizationName: 'Org B', role: 'MEMBER', status: 'ACTIVE' },
    { membershipId: 'm3', organizationId: 'o3', organizationName: 'Pending', role: 'MEMBER', status: 'PENDING' },
  ] } });
  fireEvent.change(screen.getByLabelText('E-Mail'), { target: { value: 'admin@example.org' } });
  fireEvent.change(screen.getByLabelText('Passwort'), { target: { value: 'supersecret-password' } });
  fireEvent.click(screen.getByRole('button', { name: 'Anmelden' }));
  expect(await screen.findByText('Angemeldet als admin@example.org')).toBeTruthy();
  expect(screen.queryByRole('option', { name: /Pending/ })).toBeNull();
  fireEvent.change(screen.getByLabelText('Aktive Organisation'), { target: { value: 'o2' } });
  expect(getAuthState().activeOrganizationId).toBe('o2');
  post.mockResolvedValueOnce({ response: { ok: true, status: 200 } });
  fireEvent.click(screen.getByRole('button', { name: 'Abmelden' }));
  expect(await screen.findByRole('button', { name: 'Anmelden' })).toBeTruthy();
  expect(getAuthState().activeOrganizationId).toBeNull();
});

test('failed logout displays an error and keeps the account visible', async () => {
  get.mockResolvedValue({ data: { userId: 'u1', email: 'a@example.org', memberships: [] } });
  render(<AuthPanel/>);
  await screen.findByText('Angemeldet als a@example.org');
  get.mockResolvedValue({ data: { token: 'csrf' } });
  post.mockResolvedValue({ error: {}, response: { status: 403 } });
  fireEvent.click(screen.getByRole('button', { name: 'Abmelden' }));
  expect(await screen.findByRole('alert')).toHaveProperty('textContent', 'Abmelden fehlgeschlagen');
  expect(screen.getByText('Angemeldet als a@example.org')).toBeTruthy();
});

test('session display name is shown with email fallback', async () => {
  get.mockResolvedValue({ data: { userId: 'u1', email: 'ada@example.org', displayName: 'Ada Lovelace', memberships: [] } });
  render(<AuthPanel/>);
  expect(await screen.findByText('Angemeldet als Ada Lovelace')).toBeTruthy();
});
