import { useCallback, useState } from 'react';
import { api } from '../api/client';
import { currentDeployment, Feedback, LoadState, Table, time, type Site } from './common';
import { requestOptions, result, useLoad, useMutation } from './data';
import { ObservationTable } from './records';

function SiteDetail({ organizationId, site, close, saved }: {
  organizationId: string; site: Site; close: () => void; saved: () => void;
}) {
  const [name, setName] = useState(site.name ?? '');
  const [description, setDescription] = useState(site.description ?? '');
  const [locationLabel, setLocationLabel] = useState(site.locationLabel ?? '');
  const [lat, setLat] = useState(site.locationLat?.toString() ?? '');
  const [lng, setLng] = useState(site.locationLng?.toString() ?? '');
  const [removeLocation, setRemoveLocation] = useState(false);
  const [validation, setValidation] = useState('');
  const mutation = useMutation();
  const detail = useLoad(useCallback(async (signal: AbortSignal) => {
    if (!site.id) return null;
    const options = requestOptions(signal);
    const [nodes, deployments, observations, cats] = await Promise.all([
      api.GET('/api/v1/nodes', { ...options, params: { query: { organizationId } } }).then(result),
      api.GET('/api/v1/organizations/{organizationId}/deployments', { ...options, params: { path: { organizationId } } }).then(result),
      api.GET('/api/v1/observations', { ...options, params: { query: { organizationId, feedingSiteId: site.id, limit: 10, newestFirst: true } } }).then(result),
      api.GET('/api/v1/organizations/{organizationId}/cats', { ...options, params: { path: { organizationId } } }).then(result),
    ]);
    return { nodes, deployments, observations, cats };
  }, [organizationId, site.id]));
  return <section className="detail" aria-label="Futterstellendetails"><h2>{site.id ? site.name : 'Futterstelle anlegen'}</h2>
    <form className="management-form" onSubmit={event => {
      event.preventDefault(); setValidation('');
      if (!name.trim()) { setValidation('Bitte einen Namen eingeben.'); return; }
      if (!removeLocation && Boolean(lat) !== Boolean(lng)) { setValidation('Breiten- und Längengrad bitte zusammen angeben.'); return; }
      if (!removeLocation && !lat && !lng && site.locationLat != null) {
        setValidation('Zum Entfernen bitte „Standort vollständig entfernen“ auswählen.'); return;
      }
      void mutation.run(async (headers, signal) => {
        const body = { name: name.trim(), description: description.trim(), locationLabel: locationLabel.trim(),
          ...(lat && lng ? { locationLat: Number(lat), locationLng: Number(lng) } : {}) };
        return site.id ? result(await api.PATCH('/api/v1/organizations/{organizationId}/feeding-sites/{siteId}', {
          ...requestOptions(signal), headers, params: { path: { organizationId, siteId: site.id } },
          body: { ...body, clearLocation: removeLocation } }))
          : result(await api.POST('/api/v1/organizations/{organizationId}/feeding-sites', {
            ...requestOptions(signal), headers, params: { path: { organizationId } }, body }));
      }, saved);
    }}>
      <label>Name / Bezeichnung<input required maxLength={255} value={name} onChange={e => setName(e.target.value)}/></label>
      <label>Beschreibung<textarea maxLength={2000} value={description} onChange={e => setDescription(e.target.value)}/></label>
      <label>Ort / Wegbeschreibung<input disabled={removeLocation} maxLength={255} value={locationLabel} onChange={e => setLocationLabel(e.target.value)}/></label>
      <label>Breitengrad (optional)<input disabled={removeLocation} type="number" min={-90} max={90} step="any" value={lat} onChange={e => setLat(e.target.value)}/></label>
      <label>Längengrad (optional)<input disabled={removeLocation} type="number" min={-180} max={180} step="any" value={lng} onChange={e => setLng(e.target.value)}/></label>
      {site.id && <label><input type="checkbox" checked={removeLocation} onChange={e => setRemoveLocation(e.target.checked)}/>Standort vollständig entfernen (Ort und Koordinaten)</label>}
      <div className="actions"><button disabled={mutation.busy}>Speichern</button><button type="button" onClick={close}>Schließen</button></div>
    </form>{validation && <p role="alert">{validation}</p>}<Feedback {...mutation}/>
    {site.id && <><LoadState {...detail}/>{detail.data && <>
      <h3>Aktuell zugeordnete Nodes</h3>
      {detail.data.nodes.filter(node => currentDeployment(detail.data!.deployments, node.nodeId)?.feedingSiteId === site.id).length
        ? <ul>{detail.data.nodes.filter(node => currentDeployment(detail.data!.deployments, node.nodeId)?.feedingSiteId === site.id)
          .map(node => <li key={node.nodeId}><code>{node.nodeId}</code>Letzter Serverkontakt: {time(node.lastContactAt)}</li>)}</ul>
        : <p>Keine aktuell zugeordneten Nodes. Zuordnungen unter <a href="/nodes">Nodes</a> pflegen.</p>}
      <h3>Letzte Aktivität / Rohbeobachtungen</h3>
      <p>Die zehn zuletzt am Server eingegangenen Reads dieser historischen Futterstelle.</p>
      <ObservationTable rows={detail.data.observations} sites={[site]} cats={detail.data.cats}/>
    </>}</>}
  </section>;
}

export function Sites({ organizationId }: { organizationId: string }) {
  const [selected, setSelected] = useState<Site | null>(null);
  const list = useLoad(useCallback(async (signal: AbortSignal) => result(await api.GET(
    '/api/v1/organizations/{organizationId}/feeding-sites', { ...requestOptions(signal), params: { path: { organizationId } } })), [organizationId]));
  return <><p>Futterstellen der aktiven Organisation.</p><div className="actions"><button onClick={() => setSelected({})}>Futterstelle anlegen</button>
    <button onClick={list.reload}>Aktualisieren</button></div><LoadState {...list}/>
    {list.data && (list.data.length ? <Table label="Futterstellen" headings={['Name', 'Ort / Beschreibung', 'Details']}>
      {list.data.map(site => <tr key={site.id}><td>{site.name}</td><td>{site.locationLabel || 'Kein Ort hinterlegt'}
        <small className="preserve-lines">{site.description}</small></td><td><button onClick={() => setSelected(site)}>Details / bearbeiten</button></td></tr>)}</Table>
      : <p>Noch keine Futterstellen vorhanden. Legen Sie die erste Futterstelle an und ordnen Sie ihr anschließend einen Node zu.</p>)}
    {selected && <SiteDetail key={selected.id ?? 'new'} organizationId={organizationId} site={selected} close={() => setSelected(null)}
      saved={() => { setSelected(null); list.reload(); }}/>}</>;
}
