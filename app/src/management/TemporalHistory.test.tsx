import { cleanup, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, test, vi } from 'vitest';
import { TemporalHistory, TemporalItems, temporalTime } from './TemporalHistory';
import { api } from '../api/client';
vi.mock('../api/client', () => ({ api: { GET: vi.fn() } }));
import type { components } from '../api/generated';
afterEach(cleanup);
type Item = components['schemas']['TemporalVisitItem'];
const item = (fields: Partial<Item>): Item => ({ id: 'read', kind: 'OBSERVATION', chipId: 'CHIP', quality: 'UNKNOWN',
  earliestUtcMs: null, latestUtcMs: null, feedingSiteId: null, observationCount: 1, ambiguousGap: false, observations: [], ...fields });
describe('temporal presentation', () => {
  test('unknown and conflicting time do not fabricate dates or receipt times', () => {
    render(<TemporalItems sites={[]} items={[item({}), item({ id: 'conflict', quality: 'CONFLICT' })]}/>);
    expect(screen.getByText('Zeitpunkt unbekannt')).toBeTruthy();
    expect(screen.getByText('Zeitbelege widersprechen sich – Zeitpunkt unbekannt')).toBeTruthy();
    expect(screen.getAllByText('Futterstelle nicht bestätigt')).toHaveLength(2);
    expect(screen.getAllByText('Keine belegte Zeitgrenze')).toHaveLength(4);
    expect(screen.queryByText(/1970/)).toBeNull();
  });
  test('reconstruction, bounded observations and ambiguous gaps remain distinct', () => {
    render(<TemporalItems sites={[]} items={[
      item({ id: 'visit', kind: 'VISIT', quality: 'RECONSTRUCTED', earliestUtcMs: '1791748800000', latestUtcMs: '1791748801000', observationCount: 2 }),
      item({ id: 'bounded', quality: 'BOUNDED', earliestUtcMs: '1791748800000', latestUtcMs: '1791748900000', ambiguousGap: true }),
    ]}/>);
    const list = screen.getByRole('list', { name: 'Zeitfenster und RFID-Beobachtungen' });
    expect(within(list).getByRole('heading', { name: 'Besuch mit Zeitfenster' })).toBeTruthy();
    expect(within(list).getByRole('heading', { name: 'Einzelne RFID-Beobachtung' })).toBeTruthy();
    expect(screen.getByText('Nur zeitlich eingegrenzt')).toBeTruthy();
    expect(screen.getByText(/gemeinsamen Besuch gehören/)).toBeTruthy();
    expect(screen.getAllByText(/genaue Besuchsdauer/)).toHaveLength(2);
    expect(screen.getAllByText('Herkunft und relative Reihenfolge')).toHaveLength(2);
  });
  test('capacity failure is accessible and never looks like an empty history', async () => {
    vi.mocked(api.GET).mockResolvedValue({ response: new Response('{}', { status: 503 }) } as never);
    render(<TemporalHistory organizationId="org" chipId="CHIP" sites={[]}/>);
    expect(await screen.findByText(/Zeitverlauf kann derzeit nicht vollständig berechnet werden/)).toBeTruthy();
    expect(screen.queryByText('Keine weiteren Beobachtungen.')).toBeNull();
  });
  test('UTC is formatted with explicit user timezone and no epoch placeholder', () => {
    expect(temporalTime('1791748800000', 'Europe/Berlin')).toContain('22:00:00');
    expect(temporalTime('1791748800000', 'UTC')).toContain('20:00:00');
    expect(temporalTime('1791748800400', 'UTC')).toContain('20:00:00,400');
    for (const value of ['0', '-1', 'invalid', '9224318016000000', '9007199254740993']) expect(temporalTime(value)).not.toContain('1970');
    expect(temporalTime(null)).toBe('Keine belegte Zeitgrenze');
  });
});
