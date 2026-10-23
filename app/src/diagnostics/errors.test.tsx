import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, expect, test } from 'vitest';
import { Status } from '../ui/Status';
import { errorStatus, bluetoothErrorMessage } from '../ui/status-messages';
import { ClaimError } from '../collector/claim-node';
import { SyncFailedError } from '../collector/sync-engine';
import { errorCodes, withCode, firmwareErrorCode, apiErrorCode, safeFailure } from './errors';

afterEach(cleanup);

test('every catalog code is unique; translated wording does not select codes', () => {
  const codes = Object.values(errorCodes);
  expect(new Set(codes).size).toBe(codes.length);
  for (const text of ['Fehlertext', 'Different text', 'Identitätsprüfung umformuliert']) {
    expect(new ClaimError('trusted-time', text, errorCodes.CLAIM_IDENTITY).message).toContain('[MM-CLAIM-004]');
  }
  expect(new ClaimError('trusted-time', 'Text').message).toContain(errorCodes.CLOCK_POLICY);
  expect(new SyncFailedError('unauthorized', 'Text').message).toContain(errorCodes.AUTH_CREDENTIAL);
});

test('critical claim code survives readable adapters and is shown exactly once', () => {
  const message = withCode('Kein gültiger Identitätsnachweis. Sicherheitszeit-Policy prüfen.', errorCodes.CLAIM_IDENTITY);
  render(<Status {...errorStatus(message)}/>);
  expect(screen.getByRole('alert').textContent).toContain('Kein gültiger Identitätsnachweis');
  expect(screen.getByRole('alert').textContent?.match(/MM-CLAIM-004/g)).toHaveLength(1);
});

test('fallback covers uncoded errors; success and normal offline waiting are not errors', () => {
  const { rerender } = render(<Status kind="error" title="Vorgang fehlgeschlagen"/>);
  expect(screen.getByRole('alert').textContent).toContain(errorCodes.UI_UNKNOWN);
  rerender(<Status kind="success" title="Gespeichert"/>);
  expect(screen.queryByText(/MM-/)).toBeNull();
  rerender(<Status kind="offline" title="Upload wartet auf Internet"/>);
  expect(screen.queryByText(/MM-/)).toBeNull();
});

test('unknown external error content and arbitrary server codes are not trusted', () => {
  const raw = new Error('secret token; stack; chip-123; tenant-private');
  expect(safeFailure(raw, 'Nicht verfügbar.')).not.toContain('secret');
  expect(errorStatus(safeFailure(raw, 'Nicht verfügbar.')).message).not.toContain('secret');
  expect(bluetoothErrorMessage(raw)).not.toContain('secret');
  expect(apiErrorCode(403, { code: 'SECRET' })).toBe(errorCodes.HTTP_FORBIDDEN);
  expect(apiErrorCode(409, { code: errorCodes.ADMIN_CONFLICT })).toBe(errorCodes.ADMIN_CONFLICT);
  expect(apiErrorCode(502, '<html>stack</html>')).toBe(errorCodes.HTTP_UNKNOWN);
});

test('existing firmware statuses map without changing the wire contract', () => {
  expect(firmwareErrorCode(0)).toBeUndefined();
  for (let value = 1; value <= 15; value++) expect(firmwareErrorCode(value)).toMatch(/^MM-/);
  expect(firmwareErrorCode(2)).toBe(firmwareErrorCode(3));
  expect(firmwareErrorCode(7)).toBe(firmwareErrorCode(3));
  expect(firmwareErrorCode(9)).not.toBe(firmwareErrorCode(10));
  expect(firmwareErrorCode(12)).not.toBe(firmwareErrorCode(13));
  expect(firmwareErrorCode(255)).toBe(errorCodes.BLE_PROTOCOL);
});
