import { CollectorShell } from './collector/CollectorShell';
import { AuthPanel } from './platform/AuthPanel';
import { LoginView } from './platform/LoginView';
import { isCollectorPath, useAppGate } from './platform/gate';
import { Management, managementAreas } from './management/Management';

function AllowedShell({ path, authenticated, online }: { path: string; authenticated: boolean; online: boolean }) {
  const collector = isCollectorPath(path);
  return <><header><strong>MiezMerker</strong><nav aria-label="Bereiche">
    <a href="/sync" aria-current={collector ? 'page' : undefined}>Vor-Ort-Sync</a>
    {authenticated && Object.entries(managementAreas)
      .map(([href, label]) => <a key={href} href={href} aria-current={path === href ? 'page' : undefined}>{label}</a>)}
  </nav>{authenticated && <AuthPanel/>}</header><main>
    {!authenticated && <>
      <p role="status">Offline-Betrieb: Verwaltung ist ohne gültige Online-Sitzung gesperrt.
        Der Vor-Ort-Sync bleibt mit gültigem Offline-Credential verfügbar.</p>
      {online && <LoginView online={online}/>}
    </>}
    {collector ? <CollectorShell/> :
    <Management path={path}/>}
  </main>{authenticated && <footer>RFID → Node → BLE → PWA → Backend → abgeleitete Besuche</footer>}</>;
}

export function App() {
  const path = window.location.pathname;
  const collector = isCollectorPath(path);
  const { status, online } = useAppGate(path);
  // #31: single centralized gate. Loading renders no navigation and no
  // protected contents. Login-only renders only the login view even when the
  // URL points at a protected route. Offline-sync-only keeps only /sync
  // usable; management routes stay locked without stale data.
  if (status === 'loading') {
    return <><header><strong>MiezMerker</strong></header>
      <main><p role="status">Anmeldung wird geprüft …</p></main></>;
  }
  if (status === 'authenticated' || (status === 'offline-sync' && collector)) {
    return <AllowedShell path={path} authenticated={status === 'authenticated'} online={online}/>;
  }
  return <><header><strong>MiezMerker</strong></header>
    <main><LoginView online={online}/></main></>;
}
