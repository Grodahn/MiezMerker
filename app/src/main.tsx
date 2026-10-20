import { startSyncEventRetention } from './platform/sync-event-retention';
import { createRoot } from 'react-dom/client';
import { App } from './App';
import { offlineShell } from './platform/service-worker';
import './style.css';
import { startOfflineRenewal } from './platform/offline-renewal';

startSyncEventRetention();
offlineShell.register();
startOfflineRenewal();
createRoot(document.getElementById('root')!).render(<App/>);
