import { createRoot } from 'react-dom/client';
import { App } from './App';
import { offlineShell } from './platform/service-worker';
import './style.css';

offlineShell.register();
createRoot(document.getElementById('root')!).render(<App/>);
