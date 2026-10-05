import { CollectorShell } from './collector/CollectorShell';
import { AuthPanel } from './platform/AuthPanel';
import { LoginView } from './platform/LoginView';
import { isCollectorPath, useAppGate } from './platform/gate';
import { Management, managementAreas } from './management/Management';
import { useManagementContext } from './management/context';

function AuthenticatedShell({ path }: { path: string }) {
  const context = useManagementContext();
  const collector = isCollectorPath(path);
  return <><header><strong>MiezMerker</strong><nav aria-label="Bereiche">
    <a href="/sync" aria-current={collector ? 'page' : undefined}>Vor-Ort-Sync</a>
    {Object.entries(managementAreas).filter(([href]) => href !== '/admin/members' || context.admin)
      .map(([href, label]) => <a key={href} href={href} aria-current={path === href ? 'page' : undefined}>{label}</a>)}
  </nav><AuthPanel/></header><main>{collector ? <CollectorShell/> :
    <Management path={path}/>}
  </main><footer>RFID → Node → BLE → PWA → Backend → abgeleitete Besuche</footer></>;
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
  if (status === 'authenticated') return <AuthenticatedShell path={path}/>;
  if (status === 'offline-sync' && collector) {
    return <><header><strong>MiezMerker</strong><nav aria-label="Bereiche">
      <a href="/sync" aria-current="page">Vor-Ort-Sync</a>
    </nav></header><main>
      <p role="status">Offline-Betrieb: Verwaltung ist ohne gültige Online-Sitzung gesperrt.
        Der Vor-Ort-Sync bleibt mit gültigem Offline-Credential verfügbar.</p>
      <CollectorShell/>
    </main></>;
  }
  return <><header><strong>MiezMerker</strong></header>
    <main><LoginView online={online}/></main></>;
}
