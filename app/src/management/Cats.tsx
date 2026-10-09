import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { assets } from '../assets';
import { Graphic } from '../ui/Graphic';
import { Status } from '../ui/Status';
import { statusMessages } from '../ui/status-messages';
import { Card } from '../ui/primitives';
import { api } from '../api/client';
import { Feedback, LoadState, millis, siteName, time, type Activity, type Cat, type Site } from './common';
import { requestOptions, result, useLoad, useMutation } from './data';
import { VisitTable } from './VisitTable';
import './cats.css';

export function CatEditor({ organizationId, initial, onSaved, onClose }: {
  organizationId: string; initial: Cat; onSaved: () => void; onClose: () => void;
}) {
  const [chipId, setChipId] = useState(initial.chipId ?? '');
  const [name, setName] = useState(initial.name ?? '');
  const [status, setStatus] = useState(initial.status ?? '');
  const [notes, setNotes] = useState(initial.notes ?? '');
  const mutation = useMutation();
  return <section className="detail" aria-label="Katze pflegen"><h2>{initial.id ? 'Katze bearbeiten' : 'Katze anlegen'}</h2>
    <form className="management-form" onSubmit={event => {
      event.preventDefault();
      if (!chipId.trim()) return;
      void mutation.run(async (headers, signal) => {
        const body = { chipId: chipId.trim(), name: name.trim(), status: status.trim(), notes: notes.trim() };
        return initial.id
          ? result(await api.PATCH('/api/v1/organizations/{organizationId}/cats/{catId}', {
            ...requestOptions(signal), headers, params: { path: { organizationId, catId: initial.id } }, body }))
          : result(await api.POST('/api/v1/organizations/{organizationId}/cats', {
            ...requestOptions(signal), headers, params: { path: { organizationId } }, body }));
      }, onSaved);
    }}>
      <label>Chip-ID<input required maxLength={64} value={chipId} onChange={e => setChipId(e.target.value)}/></label>
      <label>Name (optional)<input maxLength={255} value={name} onChange={e => setName(e.target.value)}/></label>
      <label>Status (optional)<input maxLength={64} value={status} onChange={e => setStatus(e.target.value)}/></label>
      <label>Notiz (optional)<textarea maxLength={2000} value={notes} onChange={e => setNotes(e.target.value)}/></label>
      <div className="actions"><button disabled={mutation.busy}>Speichern</button>
        <button type="button" onClick={onClose}>Schließen</button></div>
    </form><Feedback {...mutation}/></section>;
}

function CatVisits({ organizationId, chipId, cats, sites }: {
  organizationId: string; chipId: string; cats: Cat[]; sites: Site[];
}) {
  const [offset, setOffset] = useState(0);
  const visits = useLoad(useCallback(async (signal: AbortSignal) => result(await api.GET(
    '/api/v1/organizations/{organizationId}/visits', { ...requestOptions(signal),
      params: { path: { organizationId }, query: { chipId, limit: 20, offset } } })), [organizationId, chipId, offset]));
  return <section className="detail cat-visits" aria-label="Besuchsverlauf"><h2>Bekannte Besuche</h2>
    <p className="cat-visits__hint">Besuche behalten ihre historische Futterstelle. Der Serverempfang ist keine Sichtungszeit.</p>
    <LoadState {...visits}/>
    {visits.data && <>{offset > 0 && visits.data.length === 0
      ? <Status kind="empty" title="Keine weiteren Besuche." message="Die bisherigen Besuche sind auf der vorigen Seite verfügbar."/>
      : <VisitTable rows={visits.data} sites={sites} cats={cats}/>}
      <div className="actions cat-visits__pager"><button disabled={!offset} onClick={() => setOffset(offset - 20)}>Vorherige Seite</button>
        <span aria-live="polite">Seite {offset / 20 + 1}</span><button disabled={visits.data.length !== 20} onClick={() => setOffset(offset + 20)}>Nächste Seite</button></div></>}
  </section>;
}

export function catDisplayTitle(cat?: Cat): string {
  const name = cat?.name?.trim();
  if (name) return name;
  return cat ? 'Ohne Namen' : 'Unbekannter Chip';
}

function lastSeenValue(activity?: Activity): bigint | null {
  if (!activity?.lastSeenAtMillis) return null;
  try {
    const raw = BigInt(activity.lastSeenAtMillis);
    if (raw <= 0n || raw >= 9224318016000000n) return null;
    return raw;
  } catch { return null; }
}

function feedingSiteLabels(sites: Site[], ids?: string[]): string[] {
  if (!ids?.length) return [];
  return ids.map(id => siteName(sites, id));
}

type CatFilter = 'all' | 'known' | 'unknown';

function CatDetail({ chipId, cat, activity, sites, onClose }: {
  chipId: string; cat?: Cat; activity?: Activity; sites: Site[]; onClose: () => void;
}) {
  const title = cat ? catDisplayTitle(cat) : 'Unbekannter Chip';
  const seen = lastSeenValue(activity);
  const siteLabels = feedingSiteLabels(sites, activity?.feedingSiteIds);
  const unknownClock = !!activity && !activity.lastSeenAtMillis && !!activity.uncertainClockCount;
  return <section className="detail cat-detail" aria-label="Katzendetails">
    <div className="cat-detail__header">
      <Graphic src={assets.placeholders.cat} alt="" className="avatar cat-detail__avatar" />
      <div className="cat-detail__titles">
        <h2 className="cat-detail__title">{title}</h2>
        <p className="cat-detail__chip">Chip-ID: <code>{chipId}</code></p>
      </div>
    </div>
    {!cat && <p>Zu diesem Chip ist noch keine Katze angelegt. Ein unbekannter Chip ist kein Fehler.</p>}
    <dl className="cat-detail__list">
      {cat && <>
        <div><dt>Name</dt><dd>{cat.name?.trim() || 'Ohne Namen'}</dd></div>
        <div><dt>Status</dt><dd>{cat.status?.trim() || '—'}</dd></div>
        <div><dt>Notizen</dt><dd className="preserve-lines">{cat.notes?.trim() || '—'}</dd></div>
      </>}
      <div><dt>Chip-ID (technisch)</dt><dd><code>{chipId}</code></dd></div>
      <div><dt>Letzte verlässliche Sichtung</dt><dd>
        {activity ? (unknownClock
          ? <Status {...statusMessages.unknownClock} />
          : <>{seen !== null ? millis(activity.lastSeenAtMillis) : 'Noch keine verlässliche Sichtung'}</>) : 'Unbekannt'}
      </dd></div>
      {activity && <>
        <div><dt>Serverempfang (keine Sichtung)</dt><dd>{time(activity.lastReceivedAt)}</dd></div>
        {!!activity.uncertainClockCount && !unknownClock && <div><dt>Hinweis zur Uhrzeit</dt>
          <dd className="warning">{activity.uncertainClockCount} Read(s) ohne verlässliche Uhrzeit</dd></div>}
      </>}
      <div><dt>Bekannte Futterstellen (Verlauf)</dt>
        <dd>{siteLabels.length ? siteLabels.join(', ') : 'Keine bekannte Zuordnung'}</dd></div>
    </dl>
    <div className="actions"><button type="button" onClick={onClose}>Zurück zur Liste</button></div>
  </section>;
}

export function Cats({ organizationId }: { organizationId: string }) {
  const [selected, setSelected] = useState<Cat | null>(null);
  const selectedPanel = useRef<HTMLDivElement>(null);
  const selectionOrigin = useRef<HTMLButtonElement | null>(null);
  const focusSearchOnReload = useRef(false);
  const searchElement = useCallback((element: HTMLInputElement | null) => {
    if (element && focusSearchOnReload.current) {
      element.focus(); focusSearchOnReload.current = false;
    }
  }, []);
  useEffect(() => {
    if (selected) selectedPanel.current?.focus();
    else if (selectionOrigin.current) {
      if (selectionOrigin.current.isConnected) selectionOrigin.current.focus();
      else document.getElementById('cat-search')?.focus();
      selectionOrigin.current = null;
    }
  }, [selected]);
  const [query, setQuery] = useState('');
  const [filter, setFilter] = useState<CatFilter>('all');
  const list = useLoad(useCallback(async (signal: AbortSignal) => {
    const options = requestOptions(signal);
    const [cats, activity, sites] = await Promise.all([
      api.GET('/api/v1/organizations/{organizationId}/cats', { ...options, params: { path: { organizationId } } }).then(result),
      api.GET('/api/v1/organizations/{organizationId}/chip-activity', { ...options, params: { path: { organizationId } } }).then(result),
      api.GET('/api/v1/organizations/{organizationId}/feeding-sites', { ...options, params: { path: { organizationId } } }).then(result),
    ]);
    return { cats, activity, sites };
  }, [organizationId]));
  const data = list.data;

  const entries = useMemo(() => {
    if (!data) return { entries: [], total: 0 };
    const chips = [...new Set([...data.cats.map(cat => cat.chipId!), ...data.activity.map(a => a.chipId!)])];
    const normalizedQuery = query.trim().toLowerCase();
    const filtered = chips
      .map(chip => ({
        chip,
        cat: data.cats.find(candidate => candidate.chipId === chip),
        activity: data.activity.find(candidate => candidate.chipId === chip),
      }))
      // Client-side search/filter over the already loaded cats + chip-activity.
      // No observation history is loaded for sorting; ordering reuses the
      // reliable last-seen millis already present in chip-activity.
      .filter(entry => (filter === 'known' ? !!entry.cat : filter === 'unknown' ? !entry.cat : true))
      .filter(entry => {
        if (!normalizedQuery) return true;
        const name = entry.cat?.name?.toLowerCase() ?? '';
        const status = entry.cat?.status?.toLowerCase() ?? '';
        return name.includes(normalizedQuery) || entry.chip.toLowerCase().includes(normalizedQuery) || status.includes(normalizedQuery);
      })
      .sort((left, right) => {
        const leftSeen = lastSeenValue(left.activity);
        const rightSeen = lastSeenValue(right.activity);
        if (leftSeen !== null && rightSeen !== null && leftSeen !== rightSeen) {
          return leftSeen > rightSeen ? -1 : 1;
        }
        if ((leftSeen === null) !== (rightSeen === null)) return leftSeen === null ? 1 : -1;
        const titleCompare = catDisplayTitle(left.cat).localeCompare(catDisplayTitle(right.cat), 'de');
        if (titleCompare !== 0) return titleCompare;
        return left.chip.localeCompare(right.chip, 'de');
      });
    return { entries: filtered, total: chips.length };
  }, [data, query, filter]);

  const totalCount = entries.total;
  const visibleEntries = entries.entries;
  const selectedEntry = selected?.chipId && data
    ? { cat: data.cats.find(candidate => candidate.chipId === selected.chipId), activity: data.activity.find(candidate => candidate.chipId === selected.chipId) }
    : undefined;

  return <div className="cats-page">
    <p>Bekannte Katzen und beobachtete Chips. Ein unbekannter Chip ist kein Fehler.</p>
    <div className="actions"><button type="button" onClick={event => {
      selectionOrigin.current = event.currentTarget; setSelected({});
    }}>Katze anlegen</button><button type="button" onClick={list.reload}>Aktualisieren</button></div>
    <LoadState {...list}/>
    <div hidden={Boolean(selected)}>{data && <>
      <div className="cat-toolbar">
        <div className="field cat-search">
          <label htmlFor="cat-search">Suche nach Name oder Chip-ID</label>
          <input ref={searchElement} id="cat-search" type="search" autoComplete="off" placeholder="Name oder Chip-ID suchen"
            value={query} onChange={event => setQuery(event.target.value)} />
        </div>
        <div className="cat-filter" role="group" aria-label="Katzen und Chips filtern">
          <button type="button" aria-pressed={filter === 'all'} onClick={() => setFilter('all')}>Alle</button>
          <button type="button" aria-pressed={filter === 'known'} onClick={() => setFilter('known')}>Bekannte Katzen</button>
          <button type="button" aria-pressed={filter === 'unknown'} onClick={() => setFilter('unknown')}>Unbekannte Chips</button>
        </div>
        <p className="cat-count" aria-live="polite">{visibleEntries.length} von {totalCount} {totalCount === 1 ? 'Eintrag' : 'Einträgen'}</p>
      </div>
      {visibleEntries.length ? <ul className="cat-list" aria-label="Katzen und Chips">
        {visibleEntries.map(({ chip, cat, activity }) => {
          const title = catDisplayTitle(cat);
          const siteLabels = feedingSiteLabels(data.sites, activity?.feedingSiteIds);
          const unknownClock = !!activity && !activity.lastSeenAtMillis && !!activity.uncertainClockCount;
          return <li key={chip}>
            <Card className="cat-card">
              <div className="cat-card__main">
                <Graphic src={assets.placeholders.cat} alt="" className="avatar cat-card__avatar" />
                <div className="cat-card__body">
                  <h2 className="cat-card__title">{title}</h2>
                  <p className="cat-card__chip">Chip-ID: <code>{chip}</code></p>
                  {cat?.status?.trim() && <p className="cat-card__status">Status: {cat.status.trim()}</p>}
                  <p className="cat-card__seen">
                    <span className="cat-card__label">Letzte Sichtung: </span>
                    {activity ? (unknownClock ? 'Unbekannt' : millis(activity.lastSeenAtMillis)) : 'Unbekannt'}
                  </p>
                  {activity && <p className="cat-card__received"><small>Serverempfang: {time(activity.lastReceivedAt)}</small></p>}
                  {!!activity?.uncertainClockCount && <p className="cat-card__uncertain"><small className="warning">{activity.uncertainClockCount} Read(s) ohne verlässliche Uhrzeit</small></p>}
                  <p className="cat-card__sites"><small>{siteLabels.length ? `Bekannte Futterstellen: ${siteLabels.join(', ')}` : 'Keine bekannte Zuordnung'}</small></p>
                </div>
              </div>
              {unknownClock && <Status {...statusMessages.unknownClock} />}
              <div className="cat-card__actions">
                <button type="button" onClick={event => {
                  selectionOrigin.current = event.currentTarget; setSelected(cat ?? { chipId: chip });
                }}>{cat ? 'Details / bearbeiten' : 'Katze dazu anlegen'}</button>
              </div>
            </Card>
          </li>;
        })}
      </ul> : (totalCount
        ? <Status kind="empty" title="Keine Treffer für diese Suche." graphic="cat"
          message="Bitte Suchbegriff oder Filter anpassen. Unbekannte Chips bleiben ohne Katze erhalten." />
        : <Status {...statusMessages.emptyCats} graphic="cat"/>)}
    </>}</div>
    <div ref={selectedPanel} tabIndex={-1} aria-label="Ausgewählte Katze" hidden={!selected}>
    {selected?.chipId && data && <CatDetail
      key={`detail-${selected.id ?? selected.chipId}`}
      chipId={selected.chipId}
      cat={selectedEntry?.cat}
      activity={selectedEntry?.activity}
      sites={data.sites}
      onClose={() => setSelected(null)} />}
    {selected && <CatEditor key={`editor-${selected.id ?? selected.chipId ?? 'new'}`} organizationId={organizationId} initial={selected}
      onClose={() => setSelected(null)} onSaved={() => {
        selectionOrigin.current = null; focusSearchOnReload.current = true;
        setSelected(null); list.reload();
      }} />}
    {selected?.chipId && data && <CatVisits key={`visits-${selected.chipId}`} organizationId={organizationId}
      chipId={selected.chipId} cats={data.cats} sites={data.sites}/>}
    </div>
  </div>;
}
