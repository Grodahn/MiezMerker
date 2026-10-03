import { createRoot } from 'react-dom/client';
import { App } from './App';
import { offlineShell } from './platform/service-worker';
import './style.css';
import { startOfflineRenewal } from './platform/offline-renewal';

offlineShell.register();
startOfflineRenewal();
createRoot(document.getElementById('root')!).render(<App/>);
