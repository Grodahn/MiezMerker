import { CollectorShell } from './collector/CollectorShell';
import { AuthPanel } from './platform/AuthPanel';

const areas: Record<string, string> = {
  '/sites': 'Futterstellen', '/nodes': 'Nodes', '/cats': 'Katzen',
  '/observations': 'Rohbeobachtungen', '/visits': 'Besuche', '/admin/members': 'Mitglieder',
};
export function App() {
  const path = window.location.pathname;
  const collector = path === '/' || path === '/sync';
  return <><header><strong>MiezMerker</strong><nav aria-label="Bereiche">
    <a href="/sync">Vor-Ort-Sync</a>
    {Object.entries(areas).map(([href, label]) => <a key={href} href={href}>{label}</a>)}
  </nav><AuthPanel/></header><main>{collector ? <CollectorShell/> :
    <section><h1>{areas[path] ?? 'Seite nicht gefunden'}</h1><p>Dieser Bereich wird in #11 ergänzt.</p></section>}
  </main><footer>RFID → Node → BLE → PWA → Backend → abgeleitete Besuche</footer></>;
}
