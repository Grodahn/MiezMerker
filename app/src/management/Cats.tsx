import { useCallback, useState } from 'react';
import { api } from '../api/client';
import { Feedback, LoadState, millis, siteName, Table, time, type Cat, type Site } from './common';
import { requestOptions, result, useLoad, useMutation } from './data';
import { VisitTable } from './records';

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
  return <section className="detail"><h2>Bekannte Besuche</h2><LoadState {...visits}/>
    {visits.data && <><VisitTable rows={visits.data} sites={sites} cats={cats}/>
      <div className="actions"><button disabled={!offset} onClick={() => setOffset(offset - 20)}>Frühere Seite</button>
        <span>Seite {offset / 20 + 1}</span><button disabled={visits.data.length !== 20} onClick={() => setOffset(offset + 20)}>Nächste Seite</button></div></>}
  </section>;
}

export function Cats({ organizationId }: { organizationId: string }) {
  const [selected, setSelected] = useState<Cat | null>(null);
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
  const chips = data ? [...new Set([...data.cats.map(cat => cat.chipId!), ...data.activity.map(a => a.chipId!)])].sort() : [];
  return <><p>Bekannte Katzen und beobachtete Chips. Ein unbekannter Chip ist kein Fehler.</p>
    <div className="actions"><button onClick={() => setSelected({})}>Katze anlegen</button><button onClick={list.reload}>Aktualisieren</button></div>
    <LoadState {...list}/>
    {data && (chips.length ? <Table label="Katzen und Chips" headings={['Katze / Chip', 'Letzte Sichtung', 'Bekannte Futterstellen', 'Pflege']}>
      {chips.map(chip => {
        const cat = data.cats.find(cat => cat.chipId === chip);
        const activity = data.activity.find(a => a.chipId === chip);
        return <tr key={chip}><td>{cat?.name || (cat ? 'Ohne Namen' : 'Unbekannte Katze')}<code>{chip}</code>
          <small>{cat?.status}</small><small className="preserve-lines">{cat?.notes}</small></td>
          <td>{millis(activity?.lastSeenAtMillis)}
            {activity && <><small>Serverempfang: {time(activity.lastReceivedAt)}</small>
              {!!activity.uncertainClockCount && <small className="warning">{activity.uncertainClockCount} Read(s) ohne verlässliche Uhrzeit</small>}</>}
          </td><td>{activity?.feedingSiteIds?.length ? activity.feedingSiteIds.map(id => siteName(data.sites, id)).join(', ') : 'Keine bekannte Zuordnung'}</td>
          <td><button onClick={() => setSelected(cat ?? { chipId: chip })}>{cat ? 'Details / bearbeiten' : 'Katze dazu anlegen'}</button></td></tr>;
      })}</Table> : <p>Noch keine Katzen oder Chips vorhanden. Nach einem Node-Sync erscheinen neue Chips hier.</p>)}
    {selected && <CatEditor key={selected.id ?? selected.chipId ?? 'new'} organizationId={organizationId} initial={selected}
      onClose={() => setSelected(null)} onSaved={() => { setSelected(null); list.reload(); }}/>} 
    {selected?.chipId && data && <CatVisits key={selected.chipId} organizationId={organizationId}
      chipId={selected.chipId} cats={data.cats} sites={data.sites}/>}
  </>;
}
