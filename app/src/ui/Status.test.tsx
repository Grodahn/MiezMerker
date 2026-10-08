import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, expect, test, vi } from 'vitest';
import { assets } from '../assets';
import { Status } from './Status';
import { bluetoothSelectionMessage, errorStatus, friendlyError } from './status-messages';

afterEach(() => { cleanup(); vi.useRealTimers(); });

test('loading has named indeterminate progress, long-wait help and a terminal live update', () => {
  vi.useFakeTimers();
  const { rerender } = render(<Status kind="loading" title="Verbinde …"/>);
  expect(screen.getByRole('progressbar', { name: 'Verbinde …' }).hasAttribute('value')).toBe(false);
  expect(screen.getByRole('status').getAttribute('aria-live')).toBe('polite');
  act(() => { vi.advanceTimersByTime(15_000); });
  expect(screen.getByText(/Das dauert länger/)).toBeTruthy();
  rerender(<Status kind="error" title="Nicht erreichbar" message="Bitte Napf einschalten."/>);
  expect(screen.queryByRole('progressbar')).toBeNull();
  expect(screen.queryByText(/Das dauert länger/)).toBeNull();
  expect(screen.getByRole('alert').getAttribute('aria-atomic')).toBe('true');
});

test('scoped progress uses only real valid totals; actions remain outside live updates', () => {
  const retry = vi.fn();
  const { rerender } = render(<Status kind="loading" scope="Napf A" title="Auslesen" progress={{ value: 2, max: 5 }}
    action={{ label: 'Erneut versuchen', onClick: retry }}/>);
  expect(screen.getByRole('progressbar').getAttribute('value')).toBe('2');
  expect(screen.getByText('2 von 5')).toBeTruthy();
  const button = screen.getByRole('button', { name: 'Erneut versuchen' });
  expect(screen.getByRole('status').contains(button)).toBe(false);
  button.focus(); fireEvent.click(button); expect(retry).toHaveBeenCalledOnce();
  rerender(<Status kind="loading" title="Auslesen" progress={{ value: 10, max: 0 }}/ >);
  expect(screen.getByRole('progressbar').hasAttribute('value')).toBe(false);
});

test('status artwork is decorative, centrally replaceable and falls back', () => {
  const original = assets.status.empty;
  assets.status.empty = '/replacement.svg';
  try {
    const { container } = render(<Status kind="empty" title="Keine Daten"/>);
    const graphic = container.querySelector('img')!;
    expect(graphic.getAttribute('src')).toBe('/replacement.svg');
    expect(graphic.getAttribute('alt')).toBe('');
    expect(graphic.getAttribute('aria-hidden')).toBe('true');
    fireEvent.error(graphic);
    expect(graphic.getAttribute('src')).toBe(assets.placeholders.generic);
  } finally { assets.status.empty = original; }
});

test.each([
  ['HTTP 401', 'Bitte erneut anmelden', undefined],
  ['Offline-Credential abgelaufen', 'Offline-Berechtigung prüfen', undefined],
  ['Bluetooth-Berechtigung verweigert', 'Zugriff nicht erlaubt', undefined],
  ['Bluetooth deaktiviert', 'Bluetooth nicht verfügbar', true],
  ['Dieser Browser unterstützt kein Web Bluetooth', 'Bluetooth wird nicht unterstützt', undefined],
  ['Timeout', 'Zeitüberschreitung', true],
  ['Verbinden zeitüberschritten.', 'Zeitüberschreitung', true],
  ['Gerät nicht erreichbar', 'Verbindung nicht verfügbar', true],
])('display error %s has accurate recovery', (message, title, retry) => {
  expect(errorStatus(message)).toMatchObject({ title });
  expect(errorStatus(message).retry).toBe(retry);
});

test('chooser cancellation, empty/closed chooser and permission rejection differ', () => {
  const wrapped = Object.assign(new Error('Kein Node gefunden'), { cause: new DOMException('', 'NotFoundError') });
  expect(bluetoothSelectionMessage(wrapped)).toContain('abgebrochen oder kein passendes Gerät');
  expect(errorStatus(bluetoothSelectionMessage(wrapped)).kind).toBe('info');
  expect(bluetoothSelectionMessage(new DOMException('', 'AbortError'))).toContain('Auswahl abgebrochen');
  expect(bluetoothSelectionMessage(new DOMException('', 'NotAllowedError'))).toContain('Berechtigung verweigert');
  expect(friendlyError('Failed to fetch')).toContain('Keine Verbindung zum Server');
});
