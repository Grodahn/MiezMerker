import { useCallback, useState } from 'react';
import { api } from '../api/client';
import { currentDeployment, Feedback, LoadState, localNow, siteName, Table, time,
  type Deployment, type Node, type Site } from './common';
import { requestOptions, result, useLoad, useMutation } from './data';

function NodeDetail({ organizationId, node, history, sites, close, saved }: {
  organizationId: string; node: Node; history: Deployment[]; sites: Site[]; close: () => void; saved: () => void;
}) {
  const [firmwareVersion, setFirmware] = useState(node.firmwareVersion ?? '');
  const [protocolVersion, setProtocol] = useState(node.protocolVersion ?? '');
  const [statusNote, setNote] = useState(node.statusNote ?? '');
  const [feedingSiteId, setSite] = useState('');
  const [validFrom, setFrom] = useState(localNow);
  const mutation = useMutation();
  const move = useMutation();
  const ownHistory = history.filter(d => d.nodeId === node.nodeId)
    .sort((a, b) => Date.parse(b.validFrom ?? '') - Date.parse(a.validFrom ?? ''));
  return <section className="detail" aria-label="Node-Details"><h2>Node <code>{node.nodeId}</code></h2>
    <p>Aktuelle Futterstelle: {siteName(sites, currentDeployment(history, node.nodeId)?.feedingSiteId)}</p>
    <p>Letzter bekannter Serverkontakt / Upload: {time(node.lastContactAt)}</p>
    <form className="management-form" onSubmit={event => {
      event.preventDefault();
      void mutation.run(async (headers, signal) => result(await api.PATCH('/api/v1/nodes/{nodeId}', {
        ...requestOptions(signal), headers, params: { path: { nodeId: node.nodeId! } },
        body: { firmwareVersion: firmwareVersion.trim(), protocolVersion: protocolVersion.trim(), statusNote: statusNote.trim() },
      })), saved);
    }}>
      <label>Firmwareversion<input maxLength={64} value={firmwareVersion} onChange={e => setFirmware(e.target.value)}/></label>
      <label>Protokollversion<input maxLength={32} value={protocolVersion} onChange={e => setProtocol(e.target.value)}/></label>
      <label>Technische Notiz<textarea maxLength={500} value={statusNote} onChange={e => setNote(e.target.value)}/></label>
      <button disabled={mutation.busy || move.busy}>Metadaten speichern</button>
    </form><Feedback {...mutation}/>
    <h3>Zeitliche Zuordnung / Umhängen</h3>
    <p>Eine neue Zuordnung schließt die bisher offene zum gewählten Zeitpunkt. Historische Reads und Besuche behalten ihre damalige Futterstelle.</p>
    {sites.length ? <form className="management-form" onSubmit={event => {
      event.preventDefault();
      void move.run(async (headers, signal) => result(await api.POST('/api/v1/organizations/{organizationId}/deployments/move', {
        ...requestOptions(signal), headers, params: { path: { organizationId } },
        body: { nodeId: node.nodeId!, feedingSiteId, validFrom: new Date(validFrom).toISOString() },
      })), saved, 'Zeitliche Zuordnung gespeichert.');
    }}>
      <label>Neue Futterstelle<select required value={feedingSiteId} onChange={e => setSite(e.target.value)}>
        <option value="" disabled>Futterstelle auswählen</option>
        {sites.map(site => <option key={site.id} value={site.id}>{site.name}</option>)}
      </select></label>
      <label>Gültig ab (lokale Uhrzeit)<input required type="datetime-local" step="1" value={validFrom} onChange={e => setFrom(e.target.value)}/></label>
      <button disabled={move.busy || mutation.busy}>Zuordnung speichern</button>
    </form> : <p>Noch keine Futterstellen vorhanden. Bitte einen Administrator um die Einrichtung im Admin-Backend bitten.</p>}
    <Feedback {...move}/><h3>Deployment-Historie</h3>
    {ownHistory.length ? <Table label="Deployment-Historie" headings={['Futterstelle', 'Gültig ab (einschließlich)', 'Gültig bis (ausschließlich)']}>
      {ownHistory.map(deployment => <tr key={deployment.id}><td>{siteName(sites, deployment.feedingSiteId)}</td>
        <td>{time(deployment.validFrom)}</td><td>{deployment.validUntil ? time(deployment.validUntil) : 'Offen'}</td></tr>)}
    </Table> : <p>Noch keine Zuordnung vorhanden.</p>}
    <button onClick={close}>Schließen</button>
  </section>;
}

export function Nodes({ organizationId, admin }: { organizationId: string; admin: boolean }) {
  const [selected, setSelected] = useState<Node | null>(null);
  const list = useLoad(useCallback(async (signal: AbortSignal) => {
    const options = requestOptions(signal);
    const [nodes, sites, history] = await Promise.all([
      api.GET('/api/v1/nodes', { ...options, params: { query: { organizationId } } }).then(result),
      api.GET('/api/v1/organizations/{organizationId}/feeding-sites', { ...options, params: { path: { organizationId } } }).then(result),
      api.GET('/api/v1/organizations/{organizationId}/deployments', { ...options, params: { path: { organizationId } } }).then(result),
    ]);
    return { nodes, sites, history };
  }, [organizationId]));
  const data = list.data;
  return <><p>Nodes und zeitlich gültige Futterstellenzuordnungen der aktiven Organisation.</p>
    {admin && <p>Neue Nodes werden mit dem vorhandenen <a href="/sync">Vor-Ort-Sync / ADMIN-Claiming</a> angelegt.</p>}
    <button onClick={list.reload}>Aktualisieren</button><LoadState {...list}/>
    {data && (data.nodes.length ? <Table label="Nodes" headings={['Node', 'Aktuelle Futterstelle', 'Versionen', 'Letzter Kontakt / Hinweise', 'Details']}>
      {data.nodes.map(node => <tr key={node.nodeId}><td><code>{node.nodeId}</code><small>{node.state}</small></td>
        <td>{siteName(data.sites, currentDeployment(data.history, node.nodeId)?.feedingSiteId)}</td>
        <td>Firmware: {node.firmwareVersion || 'Unbekannt'}<small>Protokoll: {node.protocolVersion || 'Unbekannt'}</small></td>
        <td>{time(node.lastContactAt)}<small className="preserve-lines">{node.statusNote}</small></td>
        <td><button onClick={() => setSelected(node)}>Details / zuordnen</button></td></tr>)}</Table>
      : <p>Noch keine Nodes vorhanden. Ein ADMIN kann im Vor-Ort-Sync einen physischen Node claimen und anschließend hier zuordnen.</p>)}
    {selected && data && <NodeDetail key={selected.nodeId} organizationId={organizationId} node={selected} sites={data.sites} history={data.history}
      close={() => setSelected(null)} saved={() => { setSelected(null); list.reload(); }}/>}</>;
}
