import { useEffect, useSyncExternalStore } from 'react';
import { CollectorShell } from './collector/CollectorShell';
import { LoginView } from './platform/LoginView';
import { isCollectorPath, useAppGate } from './platform/gate';
import { Management, managementAreas } from './management/Management';
import { ApplicationShell } from './ui/ApplicationShell';
import { documentNavigation } from './ui/navigation';
import { Home } from './home/Home';
import { FeedingSitesEntry } from './ui/TransitionalPages';
import { Status } from './ui/Status';

const subscribePath = (update: () => void) => {
  window.addEventListener('popstate', update);
  return () => window.removeEventListener('popstate', update);
};
function AllowedShell({ path, authenticated, online }: { path: string; authenticated: boolean; online: boolean }) {
  return <ApplicationShell path={path} authenticated={authenticated}>
    <div hidden={authenticated}>{!authenticated && <>
      <Status kind="offline" title="Offline-Betrieb" message="Verwaltung ist ohne gültige Online-Sitzung gesperrt. Der Vor-Ort-Sync bleibt mit gültigem Offline-Credential verfügbar."/>
      {online && <LoginView online={online}/>}
    </>}</div>
    {isCollectorPath(path) ? <CollectorShell/> : path === '/' ? <Home/> : path === '/feeding-sites' ? <FeedingSitesEntry/>
      : <Management path={path}/>}
  </ApplicationShell>;
}
export function App() {
  // Document links preserve native history, refresh/deep links and fresh
  // verification on entry. The #31 gate remains the sole client auth boundary.
  const path = useSyncExternalStore(subscribePath, () => window.location.pathname);
  const { status, online, offlineSyncAvailable } = useAppGate(path);
  const offlineEntry = path === '/' && !online && offlineSyncAvailable;
  useEffect(() => {
    if (offlineEntry) documentNavigation.replace('/sync');
  }, [offlineEntry]);
  useEffect(() => {
    const title = status === 'authenticated'
      ? ({ '/': 'Home', '/sync': 'Sync', '/feeding-sites': 'Futterstellen', ...managementAreas }[path] ?? 'Seite nicht gefunden')
      : status === 'offline-sync' ? 'Offline-Sync' : 'Anmeldung';
    document.title = `${title} · MiezMerker`;
  }, [path, status]);
  if (status === 'loading' || offlineEntry) {
    return <ApplicationShell path={path} authenticated={false}><Status kind="loading" title={offlineEntry ? 'Offline-Sync wird geöffnet …' : 'Anmeldung wird geprüft …'}/></ApplicationShell>;
  }
  if (status === 'authenticated' || (status === 'offline-sync' && isCollectorPath(path))) {
    return <AllowedShell path={path} authenticated={status === 'authenticated'} online={online}/>;
  }
  return <ApplicationShell path={path} authenticated={false}><LoginView online={online}/></ApplicationShell>;
}
