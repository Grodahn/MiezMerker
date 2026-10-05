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
import { ClaimError } from './claim-node';
import { provisionNode } from './provision-node';
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
  const [uploadContextVersion, setUploadContextVersion] = useState(0);
  const [credential, setCredential] = useState('Offline-Credential wird geprüft …');
  const [view, setView] = useState<CollectorViewState>(initialCollectorView);
  const [running, setRunning] = useState(false);
  const uploadBusy = useRef(false);
  const uploadRequested = useRef(false);
  const [knownDevices, setKnownDevices] = useState<Array<{ id: string; name: string; device: unknown }>>([]);
  const [claimBusy, setClaimBusy] = useState(false);
  const [claimMessage, setClaimMessage] = useState('');
  const [claimReceipt, setClaimReceipt] = useState('');
  const selectedTransport = useRef<NodeTransport | null>(null);
  const nodeOperation = useRef<AbortController | null>(null);

  const auth = getAuthState();
  const contextKey = () => `${getAuthState().user?.userId ?? ''}/${getAuthState().activeOrganizationId ?? ''}`;
  const updateNodeView = useCallback((next: CollectorViewState) => setView(previous => ({
    ...next, backendState: previous.backendState, backendMessage: previous.backendMessage,
    pendingUploads: previous.pendingUploads, uploadedCount: previous.uploadedCount,
  })), []);

  useEffect(() => {
    const database = new CollectorDatabase();
    let active = true;
    database.open()
      .then(() => requestPersistentStorage())
      .then(() => { if (active) setStorage('Lokaler Speicher bereit'); })
      .catch(() => { if (active) setStorage('Lokaler Speicher nicht verfügbar'); });
    return () => { active = false; database.close(); };
  }, []);

  useEffect(() => {
    let previousContext = contextKey();
    return subscribeAuth(() => {
      const nextContext = contextKey();
      if (nextContext !== previousContext) {
        nodeOperation.current?.abort(); nodeOperation.current = null;
        selectedTransport.current = null;
        setRunning(false); setClaimBusy(false); setClaimMessage(''); setView({ ...initialCollectorView });
        previousContext = nextContext;
      }
      setAuthVersion(v => v + 1);
    });
  }, []);
  useEffect(() => {
    setView({ ...initialCollectorView }); setClaimMessage(''); selectedTransport.current = null;
    setRunning(false); setClaimBusy(false);
    return () => { nodeOperation.current?.abort(); nodeOperation.current = null; };
  }, [auth.user?.userId, auth.activeOrganizationId]);

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
    if (running || claimBusy) return;
    const operation = new AbortController();
    nodeOperation.current = operation;
    setRunning(true);
    setClaimMessage('');
    const startedContext = contextKey();
    const stillActive = () => nodeOperation.current === operation && !operation.signal.aborted && contextKey() === startedContext;
    setView(previous => ({ ...initialCollectorView, backendState: previous.backendState,
      backendMessage: previous.backendMessage, pendingUploads: previous.pendingUploads,
      uploadedCount: previous.uploadedCount, nodeState: 'connecting', nodeMessage: 'Node auswählen …' }));
    // Invoke requestDevice synchronously in the click handler, before any IDB,
    // permission or network await can consume transient user activation.
    void createTransport().then(transport => {
      if (!stillActive()) return;
      selectedTransport.current = transport;
      return runFieldSync({
        onUpdate: next => { if (stillActive()) updateNodeView(next); },
        signal: operation.signal,
        createTransport: () => transport,
        trustedNowS: () => Math.floor((props.now?.() ?? Date.now()) / 1000),
      });
    }).catch((e: unknown) => {
      if (stillActive()) setView(v => ({ ...v, nodeState: 'failed', nodeMessage: describeBluetoothError(e) }));
    }).finally(() => { if (nodeOperation.current === operation) { nodeOperation.current = null; setRunning(false); } });
  }, [running, claimBusy, createTransport, props]);

  const connectKnownDevice = useCallback((device: unknown) => {
    if (running || claimBusy) return;
    const operation = new AbortController();
    nodeOperation.current = operation;
    setRunning(true);
    setClaimMessage('');
    const startedContext = contextKey();
    const stillActive = () => nodeOperation.current === operation && !operation.signal.aborted && contextKey() === startedContext;
    const transport = new WebBluetoothTransport(device as never);
    selectedTransport.current = transport;
    void runFieldSync({
      onUpdate: next => { if (stillActive()) updateNodeView(next); },
      signal: operation.signal,
      createTransport: () => transport,
      trustedNowS: () => Math.floor((props.now?.() ?? Date.now()) / 1000),
    }).catch((e: unknown) => {
      if (stillActive()) setView(v => ({ ...v, nodeState: 'failed', nodeMessage: describeBluetoothError(e) }));
    }).finally(() => { if (nodeOperation.current === operation) { nodeOperation.current = null; setRunning(false); } });
  }, [running, claimBusy, props]);

  const retryUpload = useCallback(() => {
    const state = getAuthState();
    if (!state.activeOrganizationId) return;
    if (uploadBusy.current) { uploadRequested.current = true; return; }
    uploadRequested.current = false;
    const contextStillActive = () => getAuthState().user?.userId === state.user?.userId &&
      getAuthState().activeOrganizationId === state.activeOrganizationId;
    uploadBusy.current = true;
    setView(v => ({ ...v, backendState: 'uploading', backendMessage: 'Backend-Upload läuft …' }));
    const db = new CollectorDatabase();
    const store = new CollectorObservationStore(db);
    void (async () => {
      try {
        await store.open();
        const uploader = new BackendUploader(store);
        const result = await uploader.upload(state.activeOrganizationId as string);
        const stats = await store.organizationUploadStats(state.activeOrganizationId as string);
        if (contextStillActive()) setView(v => ({ ...v, backendState: result.state, backendMessage: result.message,
          pendingUploads: stats.pending + stats.failed, uploadedCount: stats.uploaded }));
      } catch (e) {
        if (contextStillActive()) setView(v => ({ ...v, backendState: 'failed',
          backendMessage: `Backend-Upload fehlgeschlagen (${e instanceof Error ? e.message : 'unbekannt'}). Vor-Ort-Sync bleibt gültig.` }));
      } finally {
        uploadBusy.current = false;
        if (uploadRequested.current || !contextStillActive()) setUploadContextVersion(v => v + 1);
        db.close();
      }
    })();
  }, [running]);

  useEffect(() => { if (!running) retryUpload(); }, [auth.user?.userId, auth.activeOrganizationId, connectivityVersion, running, uploadContextVersion]);

  const submitClaim = useCallback(() => {
    const state = getAuthState();
    const nodeId = view.owner?.nodeId;
    const organizationId = state.activeOrganizationId;
    const transport = selectedTransport.current;
    if (!nodeId || !organizationId || !transport || claimBusy || running) return;
    const operation = new AbortController();
    nodeOperation.current = operation;
    setClaimBusy(true);
    setClaimMessage('');
    const startedContext = contextKey();
    const stillActive = () => nodeOperation.current === operation && !operation.signal.aborted && contextKey() === startedContext;
    void provisionNode(transport, nodeId, organizationId, operation.signal).then(() => {
      if (!stillActive()) return;
      setClaimMessage(`Node ${nodeId} geclaimt und am Gerät bestätigt. Sync kann jetzt starten.`);
      setView(v => ({ ...v, unclaimed: false, nodeState: 'idle', nodeMessage: '' }));
    }).catch((e: unknown) => {
      if (stillActive()) setClaimMessage(e instanceof ClaimError ? e.message : e instanceof Error ? e.message : 'Claiming fehlgeschlagen.');
    }).finally(() => { if (nodeOperation.current === operation) { nodeOperation.current = null; setClaimBusy(false); } });
  }, [view.owner, claimBusy, running]);

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
        <button type="button" disabled={running || claimBusy} onClick={() => connectKnownDevice(d.device)}>Verbinden</button>
      </li>)}
    </ul><p>Explizite Auswahl bleibt jederzeit möglich.</p></div>}

    <button type="button" disabled={running || claimBusy || capability !== 'supported' || !auth.user || !auth.activeOrganizationId} onClick={startSync}>
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
          <p>Physischen Claim-Modus am Node aktivieren (Taste/Power-On-Geste), dann claimen. Der Node liefert den signierten Nachweis selbst.</p>
          <button type="button" disabled={claimBusy || running} onClick={submitClaim}>Node claimen (ADMIN)</button>
          {claimReceipt && <p>Gespeichertes Receipt vorhanden; ein abgebrochener Claim wird sicher erneut zugestellt.</p>}
        </div>}
      {claimMessage && <p>{claimMessage}</p>}
    </div>}
    {!view.unclaimed && claimMessage && <p role="status">{claimMessage}</p>}

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
