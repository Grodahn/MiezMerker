import { useCallback, useState } from 'react';
import { api } from '../api/client';
import type { components } from '../api/generated';
import { Card } from '../ui/primitives';
import { LoadState, type Site, siteName } from './common';
import { requestOptions, result, useLoad } from './data';

type Item = components['schemas']['TemporalVisitItem'];
export function temporalTime(value?: string | null, timeZone?: string): string {
  if (!value || !/^[0-9]+$/.test(value)) return 'Keine belegte Zeitgrenze';
  const number = Number(value);
  const date = new Date(number);
  if (!Number.isSafeInteger(number) || number <= 0 || Number.isNaN(date.getTime())) return 'Keine darstellbare Zeitgrenze';
  return new Intl.DateTimeFormat('de-DE', { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', fractionalSecondDigits: 3, timeZoneName: 'short', timeZone }).format(date);
}
const qualityLabels: Record<string, string> = {
  VERIFIED: 'Bestätigte Zeit mit Unsicherheit', RECONSTRUCTED: 'Rekonstruiert mit Unsicherheit',
  BOUNDED: 'Nur zeitlich eingegrenzt', ESTIMATED: 'Schätzung – nicht bestätigt',
  UNKNOWN: 'Zeitpunkt unbekannt', CONFLICT: 'Zeitbelege widersprechen sich – Zeitpunkt unbekannt',
};
export function TemporalItems({ items, sites }: { items: Item[]; sites: Site[] }) {
  return <ul className="visit-list" aria-label="Zeitfenster und RFID-Beobachtungen">{items.map(row => <li key={row.id}>
    <Card className="visit-card temporal-card">
      <h3>{row.kind === 'VISIT' ? 'Besuch mit Zeitfenster' : 'Einzelne RFID-Beobachtung'}</h3>
      <p>{qualityLabels[row.quality ?? 'UNKNOWN'] ?? qualityLabels.UNKNOWN}</p>
      <dl className="visit-card__list">
        <div><dt>Frühestens</dt><dd>{temporalTime(row.earliestUtcMs)}</dd></div>
        <div><dt>Spätestens</dt><dd>{temporalTime(row.latestUtcMs)}</dd></div>
        <div><dt>Futterstelle (historisch)</dt><dd>{row.feedingSiteId ? siteName(sites, row.feedingSiteId) : 'Futterstelle nicht bestätigt'}</dd></div>
        <div><dt>RFID-Beobachtungen</dt><dd>{row.observationCount}</dd></div>
      </dl>
      <p>Eine genaue Besuchsdauer ist aus dem Zeitfenster nicht bekannt.</p>
      {row.ambiguousGap && <p>Ob diese Beobachtungen zu einem gemeinsamen Besuch gehören, ist unklar.</p>}
      <details><summary>Herkunft und relative Reihenfolge</summary>
        <ul>{row.observations?.map(read => <li key={read.time?.observationId}>
          Napf: {read.nodeId}, Boot: {read.time?.bootCounter ?? 'unbekannt'}, Folge: {read.time?.sequence}, Laufzeit (ms): {read.time?.monotonicMs ?? 'unbekannt'}
          <p>Zeitbelege: {read.time?.anchorIds?.join(', ') || 'keine'}</p>
          <p>Unsicherheitsgrundlage: {read.time?.uncertaintyPolicies?.join(', ') || 'keine'}</p>
          <p>Zuordnung: {read.time?.attributionProvenance}</p>
        </li>)}</ul>
      </details>
    </Card>
  </li>)}</ul>;
}
export function TemporalHistory({ organizationId, chipId, sites }: { organizationId: string; chipId: string; sites: Site[] }) {
  const [offset, setOffset] = useState(0);
  const history = useLoad(useCallback(async (signal: AbortSignal) => {
    const response = await api.GET('/api/v1/organizations/{organizationId}/visits/temporal', { ...requestOptions(signal),
      params: { path: { organizationId }, query: { chipId, limit: 20, offset } } });
    if (response.response.status === 503) throw new Error('Der Zeitverlauf kann derzeit nicht vollständig berechnet werden. Bitte die Verwaltung kontaktieren.');
    return result(response);
  }, [organizationId, chipId, offset]));
  return <section className="detail" aria-label="Rekonstruierter Verlauf"><h2>Zeitfenster und Beobachtungen</h2>
    <p>Ein RFID-Read ist noch kein bestätigter Besuch. Zeitfenster zeigen die belegte Unsicherheit in Ihrer lokalen Zeitzone. Reihenfolge nach Napf, Boot und Beobachtungsfolge.</p>
    <LoadState {...history}/>
    {history.data && <>
      {history.data.items?.length ? <TemporalItems items={history.data.items} sites={sites}/> : <p>Keine weiteren Beobachtungen.</p>}
      <div className="actions" aria-label="Zeitfensterseiten">
        <button type="button" disabled={!offset} onClick={() => setOffset(Math.max(0, offset - 20))}>Vorherige Beobachtungen</button>
        <span aria-live="polite">Ab Eintrag {offset + 1}</span>
        <button type="button" disabled={history.data.nextOffset == null} onClick={() => setOffset(history.data!.nextOffset!)}>Weitere Beobachtungen</button>
      </div>
      <p>{history.data.algorithmVersion} / {history.data.timeProjectionVersion}</p>
    </>}
  </section>;
}
