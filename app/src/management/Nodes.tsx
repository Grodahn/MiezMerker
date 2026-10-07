import { useCallback, useState } from 'react';
import { api } from '../api/client';
import { currentDeployment, Feedback, LoadState, siteName, Table, time,
  type Deployment, type Node, type Site } from './common';
import { requestOptions, result, useLoad, useMutation } from './data';

function NodeDetail({ node, history, sites, close, saved }: {
  node: Node; history: Deployment[]; sites: Site[]; close: () => void; saved: () => void;
}) {
  const [firmwareVersion, setFirmware] = useState(node.firmwareVersion ?? '');
  const [protocolVersion, setProtocol] = useState(node.protocolVersion ?? '');
  const [statusNote, setNote] = useState(node.statusNote ?? '');
  const [displayName, setDisplayName] = useState(node.displayName ?? '');
  const mutation = useMutation();
  const ownHistory = history.filter(d => d.nodeId === node.nodeId)
    .sort((a, b) => Date.parse(b.validFrom ?? '') - Date.parse(a.validFrom ?? ''));
  // #50: show the human-readable bowl label primarily; node_id stays as technical detail.
  return <section className="detail" aria-label="Node-Details"><h2>{node.displayName ? <>Napf {node.displayName} </> : <>Node </>}<code>{node.nodeId}</code></h2>
    <p>Aktuelle Futterstelle: {siteName(sites, currentDeployment(history, node.nodeId)?.feedingSiteId)}</p>
    <p>Letzter bekannter Serverkontakt / Upload: {time(node.lastContactAt)}</p>
    <form className="management-form" onSubmit={event => {
      event.preventDefault();
      void mutation.run(async (headers, signal) => result(await api.PATCH('/api/v1/nodes/{nodeId}', {
        ...requestOptions(signal), headers, params: { path: { nodeId: node.nodeId! } },
        body: { firmwareVersion: firmwareVersion.trim(), protocolVersion: protocolVersion.trim(), statusNote: statusNote.trim(), displayName },
      })), saved);
    }}>
      <label>Napf-Bezeichnung (optional)<input maxLength={100} value={displayName} onChange={e => setDisplayName(e.target.value)}/></label>
      <label>Firmwareversion<input maxLength={64} value={firmwareVersion} onChange={e => setFirmware(e.target.value)}/></label>
      <label>Protokollversion<input maxLength={32} value={protocolVersion} onChange={e => setProtocol(e.target.value)}/></label>
      <label>Technische Notiz<textarea maxLength={500} value={statusNote} onChange={e => setNote(e.target.value)}/></label>
      <button disabled={mutation.busy}>Metadaten speichern</button>
    </form><Feedback {...mutation}/>
    <h3>Zeitliche Zuordnung</h3>
    <p>Die Futterstellenzuordnung wird im Admin-Backend verwaltet. Historische Reads und Besuche behalten ihre damalige Futterstelle.</p>
    <h3>Deployment-Historie</h3>
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
      {data.nodes.map(node => <tr key={node.nodeId}><td>{node.displayName ? <>{node.displayName}<small><code>{node.nodeId}</code> · {node.state}</small></> : <><code>{node.nodeId}</code><small>{node.state}</small>}</td>
        <td>{siteName(data.sites, currentDeployment(data.history, node.nodeId)?.feedingSiteId)}</td>
        <td>Firmware: {node.firmwareVersion || 'Unbekannt'}<small>Protokoll: {node.protocolVersion || 'Unbekannt'}</small></td>
        <td>{time(node.lastContactAt)}<small className="preserve-lines">{node.statusNote}</small></td>
        <td><button onClick={() => setSelected(node)}>Details</button></td></tr>)}</Table>
      : <p>Noch keine Nodes vorhanden. Ein ADMIN kann im Vor-Ort-Sync einen physischen Node claimen; die Futterstellenzuordnung erfolgt im Admin-Backend.</p>)}
    {selected && data && <NodeDetail key={selected.nodeId} node={selected} sites={data.sites} history={data.history}
      close={() => setSelected(null)} saved={() => { setSelected(null); list.reload(); }}/>}</>;
}
