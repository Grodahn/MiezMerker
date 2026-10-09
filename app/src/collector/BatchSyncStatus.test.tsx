import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, expect, test } from 'vitest';
import { BatchNodeList, BatchSummary } from './BatchSyncStatus';
import type { BatchNodeResult } from './batch-sync';

afterEach(cleanup);

function ok(id: string, browserId = id): BatchNodeResult {
  return {
    browserId, browserLabel: `Browser ${browserId}`, kind: 'success', nodeId: id,
    ownerNodeId: id, recordsReceived: 2, watermark: '5', message: 'Fertig.',
    foreignOrganizationName: null, unclaimed: false, durationMs: 10,
  };
}

test('summary counts synchronized bowls honestly without inventing reachability', () => {
  const { unmount } = render(<BatchSummary results={[ok('n1', 'a'), ok('n2', 'b')]}/>);
  expect(screen.getByText('2 von 2 freigegebenen Näpfen ausgelesen.')).toBeTruthy();
  unmount();
  render(<BatchSummary results={[{ ...ok('n1', 'a'), kind: 'unreachable', nodeId: null, message: 'nicht erreichbar' }]}/>);
  expect(screen.getByText('0 von 1 freigegebenen Näpfen ausgelesen.')).toBeTruthy();
});

test('successful bowls show trusted bowl/site context, never the BLE label as name', () => {
  const contexts = new Map([['node-1', {
    nodeId: 'node-1', displayName: 'Der Grüne', siteId: 'site-a', siteName: 'Garten',
    activity: [{ chipId: 'CHIP-1', catId: null, catName: null, lastReliableSightingAt: null, lastReceivedAt: '2026-10-01T00:00:00Z', visitCount: 0 }],
    contextAvailable: true, contextNote: '',
  }]]);
  render(<BatchNodeList results={[ok('node-1', 'browser-x')]} contexts={contexts as never}/>);
  expect(screen.getByText(/Napf Der Grüne ausgelesen/)).toBeTruthy();
  expect(screen.getByText('Garten')).toBeTruthy();
  expect(screen.queryByText(/Browser browser-x ausgelesen/)).toBeNull();
  expect(screen.getByText(/CHIP-1/)).toBeTruthy();
});

test('unassigned bowls are honest and offline contexts are marked unavailable', () => {
  const contexts = new Map([
    ['node-1', { nodeId: 'node-1', displayName: null, siteId: null, siteName: null, activity: null, contextAvailable: true, contextNote: 'Noch keiner Futterstelle zugeordnet.' }],
    ['node-2', { nodeId: 'node-2', displayName: 'B', siteId: null, siteName: null, activity: null, contextAvailable: false, contextNote: 'Futterstellenkontext offline nicht verfügbar. Lokale Übernahme bleibt gültig.' }],
  ]);
  render(<BatchNodeList results={[ok('node-1', 'a'), ok('node-2', 'b')]} contexts={contexts as never}/>);
  expect(screen.getByText('Noch keiner Futterstelle zugeordnet.')).toBeTruthy();
  expect(screen.getByText(/offline nicht verfügbar/)).toBeTruthy();
});

test('untrusted browser labels are never shown as verified bowl names for failures', () => {
  render(<BatchNodeList results={[{
    browserId: 'b1', browserLabel: 'My Bowl', kind: 'unreachable', nodeId: null,
    ownerNodeId: null, recordsReceived: 0, watermark: null, message: 'nicht erreichbar',
    foreignOrganizationName: null, unclaimed: false, durationMs: 1,
  }]} contexts={new Map()}/>);
  expect(screen.getByText('Napf nicht erreichbar')).toBeTruthy();
  expect(screen.queryByText('Napf My Bowl ausgelesen')).toBeNull();
});
