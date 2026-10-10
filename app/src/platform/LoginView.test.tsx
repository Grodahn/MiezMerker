import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { assets } from '../assets';

const { login } = vi.hoisted(() => ({ login: vi.fn() }));
vi.mock('./auth', () => ({ login }));
import { LoginView } from './LoginView';

beforeEach(() => { login.mockReset(); });
afterEach(cleanup);

function fillAndSubmit() {
  fireEvent.change(screen.getByLabelText('E-Mail'), { target: { value: 'volunteer@example.org' } });
  fireEvent.change(screen.getByLabelText('Passwort'), { target: { value: 'test-password' } });
  fireEvent.submit(screen.getByRole('form', { name: 'Anmelden' }));
}

test('labels, autocomplete and branding use the central registry', () => {
  const { container } = render(<LoginView online/>);
  expect(screen.getByLabelText('E-Mail').getAttribute('autocomplete')).toBe('username');
  expect(screen.getByLabelText('Passwort').getAttribute('autocomplete')).toBe('current-password');
  expect(screen.getByRole('img', { name: 'MiezMerker' }).getAttribute('src')).toBe(assets.brand.logo);
  const background = container.querySelector('.login-background')!;
  expect(background.getAttribute('src')).toBe(assets.illustrations.loginBackground);
  expect(background.getAttribute('aria-hidden')).toBe('true');
  expect(screen.queryByRole('navigation')).toBeNull();
});

test('logo and background mapping can be replaced without login logic changes', () => {
  const logo = assets.brand.logo;
  const background = assets.illustrations.loginBackground;
  try {
    assets.brand.logo = '/replacement-logo.webp';
    assets.illustrations.loginBackground = '/replacement-background.svg';
    const { container } = render(<LoginView online/>);
    expect(screen.getByRole('img', { name: 'MiezMerker' }).getAttribute('src')).toBe('/replacement-logo.webp');
    expect(container.querySelector('.login-background')!.getAttribute('src')).toBe('/replacement-background.svg');
  } finally {
    assets.brand.logo = logo;
    assets.illustrations.loginBackground = background;
  }
});

test('form submission announces loading, blocks duplicate submits and clears password on success', async () => {
  let resolve!: () => void;
  login.mockReturnValue(new Promise<void>(done => { resolve = done; }));
  render(<LoginView online/>);
  fillAndSubmit();
  expect(login).toHaveBeenCalledWith('volunteer@example.org', 'test-password');
  expect((screen.getByRole('button') as HTMLButtonElement).disabled).toBe(true);
  await waitFor(() => expect(screen.getByRole('status').textContent).toContain('Anmeldung wird geprüft'));
  fireEvent.submit(screen.getByRole('form'));
  expect(login).toHaveBeenCalledTimes(1);
  resolve();
  await waitFor(() => expect((screen.getByLabelText('Passwort') as HTMLInputElement).value).toBe(''));
  expect((screen.getByRole('button') as HTMLButtonElement).disabled).toBe(false);
});

test.each([new Error('Login fehlgeschlagen'), new Error('SQL secret credential account disabled'), new TypeError('fetch secret')])(
  'errors are announced and associated with inputs without sensitive details: %s', async failure => {
    login.mockRejectedValue(failure);
    render(<LoginView online/>);
    fillAndSubmit();
    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toContain('Anmeldung nicht möglich');
    expect(alert.textContent).not.toContain(failure.message);
    const description = screen.getByLabelText('E-Mail').getAttribute('aria-describedby')!;
    expect(document.getElementById(description)!.contains(alert)).toBe(true);
    expect((screen.getByRole('button') as HTMLButtonElement).disabled).toBe(false);
  },
);

test('offline notice retains the established offline access guidance', async () => {
  render(<LoginView online={false}/>);
  await waitFor(() => expect(screen.getByRole('status').textContent).toContain('gültiger Offline-Berechtigung'));
});
