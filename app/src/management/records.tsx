import { useCallback, useState, type FormEvent } from 'react';
import { api } from '../api/client';
import { requestOptions, result, useLoad, useMutation } from './data';
import { catName, duration, millis, siteName, Table, time, LoadState, Feedback,
  type Cat, type Observation, type Site, type Visit } from './common';

export function ObservationTable({ rows, sites, cats, registerChip }: {
  rows: Observation[]; sites: Site[]; cats: Cat[]; registerChip?: (chip: string) => void;
}) {
  if (!rows.length) return <p>Keine Rohbeobachtungen vorhanden.</p>;
  return <Table label="Rohbeobachtungen" headings={['Sichtung / Empfang', 'Katze / Chip', 'Futterstelle', 'Node / Technik']}>
    {rows.map(row => <tr key={row.id}><td>
      <span className={row.clockStatus === 'UNKNOWN' ? 'warning' : ''}>
        {row.clockStatus === 'UNKNOWN' ? 'Uhrzeit unbekannt'
          : !['KNOWN', 'SYNCED', 'RTC_ONLY'].includes(row.clockStatus ?? '') ? 'Clock-Status fehlt / ungültig'
          : millis(row.observedAtMillis)}</span>
      <small>Serverempfang: {time(row.receivedAt)}</small>
      <small>Clock: {row.clockStatus ?? 'Unbekannt'}{row.clockStatus === 'RTC_ONLY' ? ' (nur RTC)' : ''}</small>
      {row.clockStatus === 'UNKNOWN' && <small>Keine verlässliche Dauer / zeitliche Futterstellenzuordnung.</small>}
    </td><td>{catName(cats, row.chipId)}<code>{row.chipId}</code>
      {registerChip && row.chipId && !cats.some(cat => cat.chipId === row.chipId) &&
        <button onClick={() => registerChip(row.chipId!)}>Katze dazu anlegen</button>}
    </td><td>{siteName(sites, row.feedingSiteId)}<small>Zuordnung bei Erfassung</small></td>
      <td><code>{row.nodeId}</code><details><summary>Technische Daten</summary>
        <dl><dt>Sequence</dt><dd>{row.sequence}</dd><dt>RTC-Rohwert (ms)</dt><dd>{row.observedAtMillis ?? 'Fehlt'}</dd>
          <dt>Monoton (ms)</dt><dd>{row.monotonicMs ?? 'Unbekannt'}</dd>
          <dt>Boot-Counter</dt><dd>{row.bootCounter ?? 'Unbekannt'}</dd>
          <dt>Incarnation</dt><dd>{row.incarnation ?? 'Unbekannt'}</dd>
          <dt>Deployment</dt><dd>{row.deploymentId ?? 'Nicht zugeordnet'}</dd></dl>
      </details></td></tr>)}
  </Table>;
}
export function VisitTable({ rows, sites, cats }: { rows: Visit[]; sites: Site[]; cats: Cat[] }) {
  if (!rows.length) return <p>Keine abgeleiteten Besuche vorhanden.</p>;
  return <Table label="Abgeleitete Besuche" headings={['Beginn / Ende', 'Dauer / Reads', 'Futterstelle', 'Katze / Chip', 'Ableitung']}>
    {rows.map(row => <tr key={row.id}><td>{millis(row.startAtMillis)}<small>bis {millis(row.endAtMillis)}</small></td>
      <td>{duration(row)}<small>{row.observationCount} Reads</small></td>
      <td>{siteName(sites, row.feedingSiteId)}</td><td>{catName(cats, row.chipId)}<code>{row.chipId}</code></td>
      <td>{row.algorithmVersion}<small>Gap: {row.gapSeconds} s</small><details><summary>Herkunft</summary>
        <dl><dt>Erste Rohbeobachtung</dt><dd>{row.firstObservationId}</dd>
          <dt>Letzte Rohbeobachtung</dt><dd>{row.lastObservationId}</dd></dl></details></td></tr>)}
  </Table>;
}

type Filters = { feedingSiteId: string; nodeId: string; chipId: string; from: string; to: string };
const empty: Filters = { feedingSiteId: '', nodeId: '', chipId: '', from: '', to: '' };
const pageSize = 50;
export function Records({ organizationId, visits, admin, registerChip, revision }: {
  organizationId: string; visits: boolean; admin: boolean; registerChip: (chip: string) => void; revision: number;
}) {
  const [draft, setDraft] = useState(empty);
  const [filters, setFilters] = useState(empty);
  const [offset, setOffset] = useState(0);
  const [filterError, setFilterError] = useState('');
  const mutation = useMutation();
  const lookups = useLoad(useCallback(async (signal: AbortSignal) => {
    const options = requestOptions(signal);
    const [sites, cats, nodes] = await Promise.all([
      api.GET('/api/v1/organizations/{organizationId}/feeding-sites', { ...options, params: { path: { organizationId } } }).then(result),
      api.GET('/api/v1/organizations/{organizationId}/cats', { ...options, params: { path: { organizationId } } }).then(result),
      api.GET('/api/v1/nodes', { ...options, params: { query: { organizationId } } }).then(result),
    ]);
    return { sites, cats, nodes };
  }, [organizationId, revision]));
  const records = useLoad(useCallback(async (signal: AbortSignal) => {
    const query = { feedingSiteId: filters.feedingSiteId || undefined, chipId: filters.chipId || undefined,
      fromMillis: filters.from ? Date.parse(filters.from) : undefined,
      toMillis: filters.to ? Date.parse(filters.to) : undefined, limit: pageSize, offset };
    return visits
      ? { visits: result(await api.GET('/api/v1/organizations/{organizationId}/visits', {
        ...requestOptions(signal), params: { path: { organizationId }, query } })), observations: [] }
      : { observations: result(await api.GET('/api/v1/observations', { ...requestOptions(signal),
        params: { query: { ...query, organizationId, nodeId: filters.nodeId || undefined, newestFirst: true } } })), visits: [] };
  }, [organizationId, visits, filters, offset]));

  function apply(event: FormEvent) {
    event.preventDefault();
    if (draft.from && draft.to && Date.parse(draft.from) >= Date.parse(draft.to)) {
      setFilterError('Das Ende muss nach dem Beginn liegen.'); return;
    }
    setFilterError(''); setOffset(0); setFilters({ ...draft, chipId: draft.chipId.trim() });
  }
  const data = lookups.data;
  const count = visits ? records.data?.visits.length : records.data?.observations.length;
  return <>
    <p>{visits ? 'Besuche werden im Backend aus Rohbeobachtungen abgeleitet. Rohdaten bleiben erhalten.'
      : 'Unveränderte einzelne RFID-Reads. Unbekannte Chips können später einer Katze zugeordnet werden.'}</p>
    <LoadState {...lookups}/>
    {data && <form className="management-form filters" onSubmit={apply}>
      <label>Futterstelle<select value={draft.feedingSiteId} onChange={e => setDraft({ ...draft, feedingSiteId: e.target.value })}>
        <option value="">Alle Futterstellen</option>{data.sites.map(site => <option key={site.id} value={site.id}>{site.name}</option>)}</select></label>
      {!visits && <label>Node<select value={draft.nodeId} onChange={e => setDraft({ ...draft, nodeId: e.target.value })}>
        <option value="">Alle Nodes</option>{data.nodes.map(node => <option key={node.nodeId} value={node.nodeId}>{node.nodeId}</option>)}</select></label>}
      <label>Chip-ID<input value={draft.chipId} maxLength={64} onChange={e => setDraft({ ...draft, chipId: e.target.value })}/></label>
      <label>Von (einschließlich)<input type="datetime-local" value={draft.from} onChange={e => setDraft({ ...draft, from: e.target.value })}/></label>
      <label>Bis (ausschließlich)<input type="datetime-local" value={draft.to} onChange={e => setDraft({ ...draft, to: e.target.value })}/></label>
      <div className="actions"><button type="submit">Filter anwenden</button><button type="button" onClick={() => {
        setDraft(empty); setFilters(empty); setOffset(0); setFilterError('');
      }}>Zurücksetzen</button></div>
      <small>Lokale Uhrzeit. {visits ? 'Zeitraum filtert den Besuchsbeginn.' : 'Zeitfilter beziehen sich auf den RTC-Rohwert; Reads ohne Uhrzeit erscheinen ohne Zeitfilter.'}</small>
    </form>}
    {filterError && <p role="alert">{filterError}</p>}
    {visits && admin && <div className="actions"><button disabled={mutation.busy} onClick={() => void mutation.run(async (headers, signal) => {
      const response = result(await api.POST('/api/v1/organizations/{organizationId}/visits/recompute', {
        ...requestOptions(signal), headers, params: { path: { organizationId } }, body: {} }));
      return response;
    }, () => { setOffset(0); records.reload(); }, 'Besuche wurden aus Rohdaten neu berechnet.')}>Besuche neu berechnen</button>
      <small>ADMIN: Backend-Standardparameter, jeweils nur diese Organisation.</small></div>}
    <Feedback {...mutation}/><LoadState {...records}/>
    {records.data && data && <>{visits
      ? <VisitTable rows={records.data.visits} sites={data.sites} cats={data.cats}/>
      : <ObservationTable rows={records.data.observations} sites={data.sites} cats={data.cats} registerChip={registerChip}/>}
      <div className="actions pagination" aria-label="Seitennavigation">
        <button disabled={offset === 0 || records.loading} onClick={() => setOffset(offset - pageSize)}>Zurück</button>
        <span>Seite {offset / pageSize + 1} · {count} Einträge</span>
        <button disabled={count !== pageSize || records.loading} onClick={() => setOffset(offset + pageSize)}>Weiter</button>
        <button onClick={records.reload}>Aktualisieren</button>
      </div></>}
  </>;
}
