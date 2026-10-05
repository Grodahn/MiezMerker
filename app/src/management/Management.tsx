import { useCallback, useEffect, useState } from 'react';
import { api } from '../api/client';
import { Cats, CatEditor } from './Cats';
import { Sites } from './Sites';
import { Nodes } from './Nodes';
import { Records } from './records';
import { LoadState, Table } from './common';
import { requestOptions, result, useLoad } from './data';
import { useManagementContext } from './context';

export const managementAreas: Record<string, string> = {
  '/sites': 'Futterstellen', '/nodes': 'Nodes', '/cats': 'Katzen',
  '/observations': 'Rohbeobachtungen', '/visits': 'Besuche', '/admin/members': 'Mitglieder',
};

function Members({ organizationId }: { organizationId: string }) {
  const members = useLoad(useCallback(async (signal: AbortSignal) => result(await api.GET(
    '/api/v1/organizations/{organizationId}/members', { ...requestOptions(signal), params: { path: { organizationId } } })), [organizationId]));
  return <><p>ADMIN: Mitglieder der aktiven Organisation. Neue Benutzer werden in der Versuchsphase über den dokumentierten Bootstrap-/Adminmechanismus verwaltet.</p>
    <LoadState {...members}/>{members.data && (members.data.length ? <Table label="Mitglieder" headings={['E-Mail', 'Rolle', 'Status']}>
      {members.data.map(member => <tr key={member.membershipId}><td>{member.email}</td><td>{member.role}</td><td>{member.status}</td></tr>)}
    </Table> : <p>Keine Mitglieder vorhanden.</p>)}</>;
}
function ScopedManagement({ path, organizationId, admin }: { path: string; organizationId: string; admin: boolean }) {
  const [chip, setChip] = useState<string | null>(null);
  const [revision, setRevision] = useState(0);
  if (path === '/admin/members') return admin ? <Members organizationId={organizationId}/> : <p role="alert">Dieser Bereich ist ADMIN vorbehalten.</p>;
  if (path === '/sites') return <Sites organizationId={organizationId}/>;
  if (path === '/nodes') return <Nodes organizationId={organizationId} admin={admin}/>;
  if (path === '/cats') return <Cats organizationId={organizationId}/>;
  return <>{chip && <CatEditor key={chip} organizationId={organizationId} initial={{ chipId: chip }}
    onClose={() => setChip(null)} onSaved={() => { setChip(null); setRevision(value => value + 1); }}/>} 
    <Records revision={revision} organizationId={organizationId} visits={path === '/visits'} admin={admin} registerChip={setChip}/></>;
}
export function Management({ path }: { path: string }) {
  const context = useManagementContext();
  const [online, setOnline] = useState(navigator.onLine);
  useEffect(() => {
    const update = () => setOnline(navigator.onLine);
    window.addEventListener('online', update); window.addEventListener('offline', update);
    return () => { window.removeEventListener('online', update); window.removeEventListener('offline', update); };
  }, []);
  return <section className="management"><h1>{managementAreas[path] ?? 'Seite nicht gefunden'}</h1>
    {!managementAreas[path] ? <p>Bitte einen Bereich in der Navigation auswählen.</p>
      : !context.userId ? <p>Bitte anmelden, um die Daten Ihrer Organisation zu verwalten.</p>
      : !context.organizationId ? <p>Bitte eine aktive Organisation auswählen.</p>
      : !online ? <p role="status">Verwaltung benötigt eine Serververbindung. Der <a href="/sync">Vor-Ort-Sync</a> ist weiterhin offline verfügbar.</p>
      : <ScopedManagement key={`${context.generation}:${path}`} path={path} organizationId={context.organizationId} admin={context.admin}/>}
  </section>;
}
