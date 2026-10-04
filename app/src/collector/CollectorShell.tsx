import { useCallback, useEffect, useRef, useState } from 'react';
import { CollectorDatabase, requestPersistentStorage } from '../platform/offline-store';
import { getAuthState, subscribeAuth } from '../platform/auth';
import { OfflineIdentity, OfflineIdentityDatabase } from '../platform/offline-identity';
import {
  bluetoothCapability, previouslyAuthorizedDevices, requestNodeDevice,
  WebBluetoothTransport, describeBluetoothError,
} from '../platform/web-bluetooth';
import type { NodeTransport } from '../platform/node-transport';
import { initialCollectorView, runFieldSync, type CollectorViewState } from './collector-sync';
import { claimNode, ClaimError } from './claim-node';
import { BackendUploader } from './backend-upload';
import { CollectorObservationStore } from './observation-store';

// Single shared MiezMerker PWA under /app. No second collector application.
// Node→PWA and PWA→Backend are independent; Fertig means safely copied from
// Node + acknowledged to Node. Backend upload is a separate retryable step.
export function CollectorShell(props: {
  createTransport?: () => NodeTransport | Promise<NodeTransport>;
  now?: () => number;
} = {}) {
  const [storage, setStorage] = useState('Lokaler Speicher wird geöffnet …');
  const [authVersion, setAuthVersion] = useState(0);
  const [connectivityVersion, setConnectivityVersion] = useState(0);
  const [credential, setCredential] = useState('Offline-Credential wird geprüft …');
  const [view, setView] = useState<CollectorViewState>(initialCollectorView);
  const [running, setRunning] = useState(false);
  const uploadBusy = useRef(false);
  const [knownDevices, setKnownDevices] = useState<Array<{ id: string; name: string; device: unknown }>>([]);
  const [claimMode, setClaimMode] = useState(false);
  const [claimBusy, setClaimBusy] = useState(false);
  const [claimMessage, setClaimMessage] = useState('');
  const [claimReceipt, setClaimReceipt] = useState('');
  const [claimForm, setClaimForm] = useState({ publicKeyX: '', publicKeyY: '', claimSignature: '', timestampMillis: '' });

  const auth = getAuthState();

  useEffect(() => {
    const database = new CollectorDatabase();
    let active = true;
    database.open()
      .then(() => requestPersistentStorage())
      .then(() => { if (active) setStorage('Lokaler Speicher bereit'); })
      .catch(() => { if (active) setStorage('Lokaler Speicher nicht verfügbar'); });
    return () => { active = false; database.close(); };
  }, []);

  useEffect(() => subscribeAuth(() => setAuthVersion(v => v + 1)), []);

  useEffect(() => {
    const onChange = () => setConnectivityVersion(v => v + 1);
    window.addEventListener('online', onChange);
    window.addEventListener('offline', onChange);
    return () => {
      window.removeEventListener('online', onChange);
      window.removeEventListener('offline', onChange);
    };
  }, []);

  useEffect(() => {
    let active = true;
    (async () => {
      const state = getAuthState();
      if (!state.user || !state.activeOrganizationId) {
        if (active) setCredential('Bitte anmelden und Organisation wählen.');
        return;
      }
      const db = new OfflineIdentityDatabase();
      try {
        const cached = await new OfflineIdentity(db).credential(state.user.userId, state.activeOrganizationId);
        if (active) {
          setCredential(cached
            ? 'Offline-Credential gültig (offline Sync möglich).'
            : navigator.onLine
              ? 'Kein gültiges Offline-Credential — wird bei Sync erneuert (Internet verfügbar).'
              : 'Kein gültiges Offline-Credential und offline. Node-Sync kann nicht starten — bitte einmal mit Internet anmelden.');
        }
      } catch {
        if (active) setCredential('Offline-Credential konnte nicht geprüft werden.');
      } finally {
        db.close();
      }
    })();
    return () => { active = false; };
  }, [authVersion, connectivityVersion]);

  useEffect(() => {
    let active = true;
    if (bluetoothCapability() !== 'supported') return;
    void previouslyAuthorizedDevices().then(devices => {
      if (!active) return;
      setKnownDevices(devices.map((d, i) => {
        const anyDevice = d as { id?: string; name?: string };
        return { id: String(anyDevice.id ?? `node-${i}`), name: String(anyDevice.name ?? 'Bekannter Node'), device: d };
      }));
    }).catch(() => {});
    return () => { active = false; };
  }, []);

  const createTransport = useCallback(async (): Promise<NodeTransport> => {
    if (props.createTransport) return props.createTransport();
    if (bluetoothCapability() !== 'supported') {
      throw new Error('Dieser Browser unterstützt kein Web Bluetooth. Bitte aktuelles Chrome/Chromium auf Android verwenden.');
    }
    const device = await requestNodeDevice();
    return new WebBluetoothTransport(device);
  }, [props]);

  const startSync = useCallback(() => {
    if (running || uploadBusy.current) return;
    setRunning(true);
    setClaimMessage('');
    setView({ ...initialCollectorView, nodeState: 'connecting', nodeMessage: 'Node auswählen …' });
    // Invoke requestDevice synchronously in the click handler, before any IDB,
    // permission or network await can consume transient user activation.
    void createTransport().then(transport => runFieldSync({
      onUpdate: setView,
      createTransport: () => transport,
      trustedNowS: () => Math.floor((props.now?.() ?? Date.now()) / 1000),
    })).catch((e: unknown) => {
      setView(v => ({ ...v, nodeState: 'failed', nodeMessage: describeBluetoothError(e) }));
    }).finally(() => setRunning(false));
  }, [running, createTransport, props]);

  const connectKnownDevice = useCallback((device: unknown) => {
    if (running || uploadBusy.current) return;
    setRunning(true);
    setClaimMessage('');
    void runFieldSync({
      onUpdate: setView,
      createTransport: async () => new WebBluetoothTransport(device as never),
      trustedNowS: () => Math.floor((props.now?.() ?? Date.now()) / 1000),
    }).catch((e: unknown) => {
      setView(v => ({ ...v, nodeState: 'failed', nodeMessage: describeBluetoothError(e) }));
    }).finally(() => setRunning(false));
  }, [running, props]);

  const retryUpload = useCallback(() => {
    const state = getAuthState();
    if (!state.activeOrganizationId || running || uploadBusy.current) return;
    uploadBusy.current = true;
    setView(v => ({ ...v, backendState: 'uploading', backendMessage: 'Backend-Upload läuft …' }));
    const db = new CollectorDatabase();
    const store = new CollectorObservationStore(db);
    void (async () => {
      try {
        await store.open();
        const uploader = new BackendUploader(store);
        const result = await uploader.upload(state.activeOrganizationId as string);
        const rows = await store.pendingUploads(state.activeOrganizationId as string);
        setView(v => ({ ...v, backendState: result.state, backendMessage: result.message,
          pendingUploads: rows.length, uploadedCount: v.uploadedCount + result.uploaded + result.duplicates }));
      } catch (e) {
        setView(v => ({ ...v, backendState: 'failed',
          backendMessage: `Backend-Upload fehlgeschlagen (${e instanceof Error ? e.message : 'unbekannt'}). Vor-Ort-Sync bleibt gültig.` }));
      } finally {
        uploadBusy.current = false;
        db.close();
      }
    })();
  }, [running]);

  useEffect(() => { retryUpload(); }, [auth.user?.userId, auth.activeOrganizationId, connectivityVersion, running]);

  const submitClaim = useCallback(() => {
    const state = getAuthState();
    const nodeId = view.owner?.nodeId;
    const organizationId = state.activeOrganizationId;
    if (!nodeId || !organizationId) return;
    setClaimBusy(true);
    setClaimMessage('');
    void claimNode(organizationId, {
      nodeId,
      publicKeyX: claimForm.publicKeyX.trim(),
      publicKeyY: claimForm.publicKeyY.trim(),
      claimSignature: claimForm.claimSignature.trim(),
      timestampMillis: Number(claimForm.timestampMillis),
    }, { claimModeConfirmed: claimMode }).then(outcome => {
      setClaimReceipt(outcome.receipt ?? '');
      setClaimMessage(`Backend-Provisionierung für ${outcome.nodeId} gespeichert. Receipt muss noch am Node angewendet werden.`);
    }).catch((e: unknown) => {
      setClaimMessage(e instanceof ClaimError ? e.message : e instanceof Error ? e.message : 'Claiming fehlgeschlagen.');
    }).finally(() => setClaimBusy(false));
  }, [view.owner, claimForm, claimMode]);

  useEffect(() => {
    let active = true;
    const db = new CollectorDatabase();
    setClaimReceipt('');
    if (view.unclaimed && view.owner?.nodeId) {
      void db.nodeMeta.get(view.owner.nodeId).then(meta => {
        if (active && meta?.organizationId === auth.activeOrganizationId) setClaimReceipt(meta.claimReceipt ?? '');
      }).catch(() => {}).finally(() => db.close());
    } else db.close();
    return () => { active = false; };
  }, [view.unclaimed, view.owner?.nodeId, auth.activeOrganizationId]);

  const capability = bluetoothCapability();
  const memberships = auth.user?.memberships.filter(m => m.status === 'ACTIVE') ?? [];
  const activeMembership = memberships.find(m => m.organizationId === auth.activeOrganizationId);
  const isAdmin = activeMembership?.role === 'ADMIN';

  return <section aria-label="Vor-Ort-Sync"><h1>Vor-Ort-Sync</h1>
    <p>{storage}</p>
    <p>{auth.user
      ? `Angemeldet als ${auth.user.email}${auth.activeOrganizationId ? ` · Organisation: ${activeMembership?.organizationName ?? auth.activeOrganizationId} (${activeMembership?.role ?? '?'})` : ' · keine Organisation gewählt'}`
      : 'Nicht angemeldet.'}</p>
    <p>{credential}</p>
    {capability !== 'supported' && <p role="alert">Dieser Browser unterstützt kein Web Bluetooth. Bitte aktuelles Chrome/Chromium auf Android verwenden.</p>}
    {!navigator.onLine && <p>Offline-Modus: Node-Sync funktioniert ohne Internet. Backend-Upload wartet auf Verbindung.</p>}

    {knownDevices.length > 0 && <div><p>Bekannte Nodes (Browser-freigegeben, optional):</p><ul>
      {knownDevices.map(d => <li key={d.id}>{d.name}
        <button type="button" disabled={running || view.backendState === 'uploading'} onClick={() => connectKnownDevice(d.device)}>Verbinden</button>
      </li>)}
    </ul><p>Explizite Auswahl bleibt jederzeit möglich.</p></div>}

    <button type="button" disabled={running || view.backendState === 'uploading' || capability !== 'supported' || !auth.user || !auth.activeOrganizationId} onClick={startSync}>
      {running ? 'Synchronisiere …' : 'Node auswählen & synchronisieren'}
    </button>

    {view.nodeState !== 'idle' && <div aria-live="polite">
      <p>Node-Sync: {nodeStateLabel(view.nodeState)}</p>
      {view.recordsReceived > 0 && <p>Übernommen: {view.recordsReceived}{view.watermark ? ` · Stand: ${view.watermark}` : ''}</p>}
      {view.nodeMessage && <p>{view.nodeMessage}</p>}
    </div>}

    {view.fertig && <p role="status"><strong>Fertig</strong> — Beobachtungen sicher vom Node übernommen und quittiert.</p>}

    {view.foreign && <p role="alert">Dieser MiezMerker gehört {view.foreign.organizationName}.
      {view.foreign.publicContact ? ` Kontakt: ${view.foreign.publicContact}` : ''} Keine Observations abgerufen.</p>}

    {view.unclaimed && <div>
      <p role="alert">Node ist UNCLAIMED.</p>
      {!isAdmin
        ? <p>Nur ADMIN kann einen UNCLAIMED Node claimen. MEMBER hat keinen Zugriff.</p>
        : <div>
          <label><input type="checkbox" checked={claimMode} onChange={e => setClaimMode(e.target.checked)} />
            Physischer Claim-Modus am Node bestätigt (Taste/Power-On-Geste).</label>
          <label>Device Public Key X<input value={claimForm.publicKeyX} onChange={e => setClaimForm({ ...claimForm, publicKeyX: e.target.value })} /></label>
          <label>Device Public Key Y<input value={claimForm.publicKeyY} onChange={e => setClaimForm({ ...claimForm, publicKeyY: e.target.value })} /></label>
          <label>Claim-Signatur (base64url)<input value={claimForm.claimSignature} onChange={e => setClaimForm({ ...claimForm, claimSignature: e.target.value })} /></label>
          <label>Timestamp (ms)<input value={claimForm.timestampMillis} onChange={e => setClaimForm({ ...claimForm, timestampMillis: e.target.value })} /></label>
          <button type="button" disabled={claimBusy || !claimMode} onClick={submitClaim}>Backend-Provisionierung anfordern (ADMIN)</button>
          <p>Die automatische Provisionierung am Node ist noch nicht verfügbar. Das Receipt muss über die Provisionierungsschnittstelle des Nodes angewendet werden, bevor Sync möglich ist.</p>
          {claimReceipt && <label>Gespeichertes Claim-Receipt<textarea readOnly value={claimReceipt} /></label>}
        </div>}
      {claimMessage && <p>{claimMessage}</p>}
    </div>}

    <div><p>Backend-Upload (separat, retrybar): {backendStateLabel(view.backendState)}</p>
      {view.backendMessage && <p>{view.backendMessage}</p>}
      {(view.backendState === 'idle' || view.backendState === 'failed' || view.backendState === 'waiting-for-network') &&
        <button type="button" disabled={running} onClick={retryUpload}>Backend-Upload erneut versuchen</button>}
      {view.pendingUploads > 0 && <p>Ausstehend: {view.pendingUploads} · Hochgeladen: {view.uploadedCount}</p>}
    </div>

    <details><summary>Hinweise zu Fehlerfällen</summary><ul>
      <li>Bluetooth unsupported: aktuellen Chrome/Chromium auf Android + HTTPS verwenden.</li>
      <li>Berechtigung verweigert: Zugriff erlauben und erneut wählen.</li>
      <li>Kein Node gefunden: Node einschalten, näher herangehen.</li>
      <li>Mehrere Nodes: Auswahl im Browser-Dialog treffen.</li>
      <li>Verbindung bricht ab: erneut versuchen — gespeicherte Records werden idempotent wiederholt, kein Datenverlust.</li>
      <li>Fremde Organisation: nur öffentliche Owner-Metadaten, keine Observations.</li>
      <li>Credential abgelaufen/fehlend: einmal mit Internet anmelden/erneuern.</li>
      <li>Speicher voll: Browser-Speicher freigeben; ohne dauerhafte Speicherung kein ACK.</li>
      <li>Backend offline/fehlerhaft: Vor-Ort-Sync bleibt Fertig; Upload später retrybar.</li>
      <li>Protokoll inkompatibel: Firmware/PWA-Version prüfen.</li>
    </ul></details>
  </section>;
}

function nodeStateLabel(state: string): string {
  switch (state) {
    case 'idle': return 'bereit';
    case 'connecting': return 'verbinde/autorisiere';
    case 'receiving': return 'empfange';
    case 'persisting': return 'speichere lokal';
    case 'acknowledging': return 'quittiere';
    case 'complete': return 'abgeschlossen';
    case 'failed': return 'fehlgeschlagen';
    default: return state;
  }
}

function backendStateLabel(state: string): string {
  switch (state) {
    case 'idle': return 'bereit';
    case 'waiting-for-network': return 'wartet auf Netzwerk';
    case 'uploading': return 'lädt hoch';
    case 'complete': return 'erfolgt/abgeschlossen';
    case 'failed': return 'fehlgeschlagen/ausstehend';
    default: return state;
  }
}
