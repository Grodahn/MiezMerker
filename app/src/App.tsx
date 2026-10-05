import { CollectorShell } from './collector/CollectorShell';
import { AuthPanel } from './platform/AuthPanel';
import { Management, managementAreas } from './management/Management';
import { useManagementContext } from './management/context';

export function App() {
  const context = useManagementContext();
  const path = window.location.pathname;
  const collector = path === '/' || path === '/sync';
  return <><header><strong>MiezMerker</strong><nav aria-label="Bereiche">
    <a href="/sync" aria-current={collector ? 'page' : undefined}>Vor-Ort-Sync</a>
    {Object.entries(managementAreas).filter(([href]) => href !== '/admin/members' || context.admin)
      .map(([href, label]) => <a key={href} href={href} aria-current={path === href ? 'page' : undefined}>{label}</a>)}
  </nav><AuthPanel/></header><main>{collector ? <CollectorShell/> :
    <Management path={path}/>}
  </main><footer>RFID → Node → BLE → PWA → Backend → abgeleitete Besuche</footer></>;
}
