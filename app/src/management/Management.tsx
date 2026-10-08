import { useEffect, useState } from 'react';
import { flushSync } from 'react-dom';
import { Cats } from './Cats';
import { Nodes } from './Nodes';
import { useManagementContext } from './context';
import { Status } from '../ui/Status';
import { statusMessages } from '../ui/status-messages';

export const managementAreas: Record<string, string> = {
  '/nodes': 'Nodes', '/cats': 'Katzen',
};

function ScopedManagement({ path, organizationId, admin }: { path: string; organizationId: string; admin: boolean }) {
  if (path === '/nodes') return <Nodes organizationId={organizationId} admin={admin}/>;
  return <Cats organizationId={organizationId}/>;
}
export function Management({ path }: { path: string }) {
  const context = useManagementContext();
  const [online, setOnline] = useState(navigator.onLine);
  const [departed, setDeparted] = useState(false);
  useEffect(() => {
    const update = () => setOnline(navigator.onLine);
    // History can retain an entire document after another page changes its session/org.
    // Discard business state and abort work before the browser freezes this document.
    const hide = () => flushSync(() => setDeparted(true));
    const show = (event: PageTransitionEvent) => { if (event.persisted) window.location.reload(); };
    window.addEventListener('online', update); window.addEventListener('offline', update);
    window.addEventListener('pagehide', hide); window.addEventListener('pageshow', show);
    return () => {
      window.removeEventListener('online', update); window.removeEventListener('offline', update);
      window.removeEventListener('pagehide', hide); window.removeEventListener('pageshow', show);
    };
  }, []);
  return <section className="management"><h1>{managementAreas[path] ?? 'Seite nicht gefunden'}</h1>
    {departed ? <p role="status">Verwaltung wird beim Zurückkehren neu geladen.</p>
      : !managementAreas[path] ? <p>Bitte einen Bereich in der Navigation auswählen.</p>
      : !context.userId ? <p>Bitte anmelden, um die Daten Ihrer Organisation zu verwalten.</p>
      : !context.organizationId ? <p>Bitte eine aktive Organisation auswählen.</p>
      : !online ? <Status {...statusMessages.offline}><a href="/sync">Vor-Ort-Sync öffnen</a></Status>
      : <ScopedManagement key={`${context.generation}:${path}`} path={path} organizationId={context.organizationId} admin={context.admin}/>}
  </section>;
}
