import { useEffect, useState } from 'react';
import { CollectorDatabase } from '../platform/offline-store';

export function CollectorShell() {
  const [storage, setStorage] = useState('Lokaler Speicher wird geöffnet …');
  useEffect(() => {
    const database = new CollectorDatabase();
    let active = true;
    database.open().then(() => { if (active) setStorage('Lokaler Speicher bereit'); })
      .catch(() => { if (active) setStorage('Lokaler Speicher nicht verfügbar'); });
    return () => { active = false; database.close(); };
  }, []);
  return <section><h1>Vor-Ort-Sync</h1><p>{storage}</p>
    <p>Die App-Shell ist nach dem ersten Online-Aufruf offline verfügbar. Der Node-Sync folgt in #8.</p></section>;
}
