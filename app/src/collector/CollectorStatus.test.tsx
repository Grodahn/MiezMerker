import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, expect, test, vi } from 'vitest';
import { initialCollectorView } from './collector-sync';
import { NodeStatus, UploadStatus } from './CollectorStatus';

afterEach(cleanup);
const retry = vi.fn();

test('local success requires completed ACK state and durable fertig; pending upload is independent', () => {
  const local = { ...initialCollectorView, nodeState: 'acknowledging' as const, recordsReceived: 8 };
  const { rerender } = render(<><NodeStatus view={local} retry={retry} disabled={false}/>
    <UploadStatus view={{ ...local, backendState: 'waiting-for-network', pendingUploads: 8 }} retry={retry} disabled={false}/></>);
  expect(screen.queryByText('Napf ausgelesen')).toBeNull();
  expect(screen.getByText('Upload wartet auf Verbindung')).toBeTruthy();
  rerender(<><NodeStatus view={{ ...local, nodeState: 'complete', fertig: true }} retry={retry} disabled={false}/>
    <UploadStatus view={{ ...local, backendState: 'failed', pendingUploads: 3, uploadedCount: 5,
      backendMessage: 'Failed to fetch' }} retry={retry} disabled={false}/></>);
  expect(screen.getByText('Napf ausgelesen')).toBeTruthy();
  expect(screen.getByText('Zuletzt geprüft: Ausstehend: 3 · Hochgeladen: 5')).toBeTruthy();
  expect(screen.queryByText('Daten an Server übertragen')).toBeNull();
  expect(screen.getByRole('button', { name: 'Backend-Upload erneut versuchen' })).toBeTruthy();
  expect(screen.queryByText('Failed to fetch')).toBeNull();
});

test('empty outbox is not a claimed upload, and partial upload never shows full success', () => {
  const { rerender } = render(<UploadStatus view={{ ...initialCollectorView, backendState: 'complete' }} retry={retry} disabled={false}/>);
  expect(screen.getByText('Keine ausstehenden Uploads')).toBeTruthy();
  rerender(<UploadStatus view={{ ...initialCollectorView, backendState: 'complete', uploadedCount: 5, pendingUploads: 1 }} retry={retry} disabled={false}/>);
  expect(screen.queryByText('Daten an Server übertragen')).toBeNull();
  rerender(<UploadStatus view={{ ...initialCollectorView, backendState: 'complete', uploadedCount: 5 }} retry={retry} disabled={false}/>);
  expect(screen.getByText('Daten an Server übertragen')).toBeTruthy();
});

test('session rejection directs to sign-in instead of a futile upload retry', () => {
  render(<UploadStatus view={{ ...initialCollectorView, backendState: 'failed', backendMessage: 'Backend-Upload abgelehnt (HTTP 401)' }} retry={retry} disabled={false}/>);
  expect(screen.getByText('Bitte erneut anmelden')).toBeTruthy();
  expect(screen.queryByRole('button')).toBeNull();
});

test('old upload results are explicitly snapshots while a new local read completes', () => {
  render(<UploadStatus view={{ ...initialCollectorView, nodeState: 'complete', fertig: true,
    recordsReceived: 8, backendState: 'complete', uploadedCount: 5 }} retry={retry} disabled={false}/>);
  expect(screen.getByText(/Letzter geprüfter Uploadstand/)).toBeTruthy();
  expect(screen.getByText(/Neu übernommene Daten werden separat geprüft/)).toBeTruthy();
});

test('an unchecked upload does not claim local data, zero pending records or offline operation', () => {
  const { container } = render(<UploadStatus view={initialCollectorView} retry={retry} disabled={false}/>);
  expect(screen.queryByText('Upload noch ausstehend')).toBeNull();
  expect(screen.queryByText(/Ausstehend: 0/)).toBeNull();
  expect(container.querySelector('.status-card--offline')).toBeNull();
});

test('an upload error does not claim that a failed local read succeeded', () => {
  render(<UploadStatus view={{ ...initialCollectorView, nodeState: 'failed', backendState: 'failed',
    backendMessage: 'Failed to fetch' }} retry={retry} disabled={false}/>);
  expect(screen.queryByText(/Der lokale Vor-Ort-Sync bleibt gültig/)).toBeNull();
  expect(screen.queryByText(/Ausstehend: 0/)).toBeNull();
});
