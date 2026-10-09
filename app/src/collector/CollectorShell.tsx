import { useCallback, useEffect, useRef, useState } from 'react';
import { CollectorDatabase, requestPersistentStorage } from '../platform/offline-store';
import { getAuthState, subscribeAuth } from '../platform/auth';
import { OfflineIdentity, OfflineIdentityDatabase } from '../platform/offline-identity';
import {
  bluetoothCapability, previouslyAuthorizedDevices, requestNodeDevice,
  WebBluetoothTransport,
} from '../platform/web-bluetooth';
import type { NodeTransport } from '../platform/node-transport';
import { initialCollectorView, runFieldSync, type CollectorViewState } from './collector-sync';
import { ClaimError } from './claim-node';
import { provisionNode } from './provision-node';
import { assets } from '../assets';
import { Graphic } from '../ui/Graphic';
import { NodeSetupWizard } from './NodeSetupWizard';
import { BackendUploader } from './backend-upload';
import { CollectorObservationStore } from './observation-store';
import { NodeStatus, UploadStatus } from './CollectorStatus';
import { Status } from '../ui/Status';
import { bluetoothErrorMessage, errorStatus, statusMessages, syncHelp } from '../ui/status-messages';

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
  const contextGeneration = useRef(0);
  const mounted = useRef(true);
  const credentialCheckGeneration = useRef(0);
  const [knownDevices, setKnownDevices] = useState<Array<{ id: string; name: string; device: unknown }>>([]);
  const [claimBusy, setClaimBusy] = useState(false);
  const [claimMessage, setClaimMessage] = useState('');
  const [claimReceipt, setClaimReceipt] = useState('');
  const [setupNodeId, setSetupNodeId] = useState<string | null>(null);
  const [setupOrgId, setSetupOrgId] = useState<string | null>(null);
  const [resumeCandidate, setResumeCandidate] = useState<{ organizationId: string; nodeId: string } | null>(null);
  const selectedTransport = useRef<NodeTransport | null>(null);
  const nodeOperation = useRef<AbortController | null>(null);

  const auth = getAuthState();
  const contextKey = () => `${getAuthState().user?.userId ?? ''}/${getAuthState().activeOrganizationId ?? ''}`;
  const updateNodeView = useCallback((next: CollectorViewState) => {
    if (next.credentialState) {
      // A pre-renewal IDB lookup must not overwrite the visit's newer result.
      credentialCheckGeneration.current++;
      setCredential(next.credentialState);
    }
    setView(previous => ({
      ...next, backendState: previous.backendState, backendMessage: previous.backendMessage,
      pendingUploads: previous.pendingUploads, uploadedCount: previous.uploadedCount,
    }));
  }, []);

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
    mounted.current = true;
    let previousContext = contextKey();
    const unsubscribe = subscribeAuth(() => {
      const nextContext = contextKey();
      if (nextContext !== previousContext) {
        contextGeneration.current++;
        nodeOperation.current?.abort(); nodeOperation.current = null;
        selectedTransport.current = null;
        setRunning(false); setClaimBusy(false); setClaimMessage(''); setView({ ...initialCollectorView });
        previousContext = nextContext;
      }
      setAuthVersion(v => v + 1);
    });
    return () => { mounted.current = false; contextGeneration.current++; unsubscribe(); };
  }, []);
  useEffect(() => {
    setView({ ...initialCollectorView }); setClaimMessage(''); selectedTransport.current = null;
    setRunning(false); setClaimBusy(false); setSetupNodeId(null); setSetupOrgId(null);
    return () => { nodeOperation.current?.abort(); nodeOperation.current = null; };
  }, [auth.user?.userId, auth.activeOrganizationId]);

  // Resume hint for Scenario A/E: a claim confirmed before the app closed or
  // reloaded leaves backend state behind. The stored ids only hint at the
  // candidate; the wizard refetches authoritative state and never re-claims.
  useEffect(() => {
    try {
      const raw = localStorage.getItem('miezmerker-last-setup-node');
      if (!raw) { setResumeCandidate(null); return; }
      const parsed = JSON.parse(raw) as { organizationId?: string; nodeId?: string };
      if (parsed?.organizationId === auth.activeOrganizationId && typeof parsed?.nodeId === 'string'
        && parsed.nodeId !== setupNodeId) {
        setResumeCandidate({ organizationId: parsed.organizationId, nodeId: parsed.nodeId });
      } else {
        setResumeCandidate(null);
      }
    } catch { setResumeCandidate(null); }
  }, [auth.activeOrganizationId, setupNodeId]);

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
    const generation = ++credentialCheckGeneration.current;
    (async () => {
      const state = getAuthState();
      if (!state.user || !state.activeOrganizationId) {
        if (active) setCredential('Bitte anmelden und Organisation wählen.');
        return;
      }
      const db = new OfflineIdentityDatabase();
      try {
        const cached = await new OfflineIdentity(db).credential(state.user.userId, state.activeOrganizationId);
        if (active && generation === credentialCheckGeneration.current) {
          setCredential(cached
            ? 'Offline-Credential gültig (offline Sync möglich).'
            : navigator.onLine
              ? 'Kein gültiges Offline-Credential — wird bei Sync erneuert (Internet verfügbar).'
              : 'Kein gültiges Offline-Credential und offline. Node-Sync kann nicht starten — bitte einmal mit Internet anmelden.');
        }
      } catch {
        if (active && generation === credentialCheckGeneration.current) setCredential('Offline-Credential konnte nicht geprüft werden.');
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
    let selecting = true;
    void createTransport().then(transport => {
      selecting = false;
      if (!stillActive()) return;
      selectedTransport.current = transport;
      return runFieldSync({
        onUpdate: next => { if (stillActive()) updateNodeView(next); },
        signal: operation.signal,
        createTransport: () => transport,
        trustedNowS: () => Math.floor((props.now?.() ?? Date.now()) / 1000),
      });
    }).catch((e: unknown) => {
      if (stillActive()) setView(v => ({ ...v, nodeState: 'failed', nodeMessage: bluetoothErrorMessage(e, selecting ? 'selection' : 'operation') }));
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
      if (stillActive()) setView(v => ({ ...v, nodeState: 'failed', nodeMessage: bluetoothErrorMessage(e) }));
    }).finally(() => { if (nodeOperation.current === operation) { nodeOperation.current = null; setRunning(false); } });
  }, [running, claimBusy, props]);

  const retryUpload = useCallback(() => {
    const state = getAuthState();
    if (!state.activeOrganizationId) return;
    if (uploadBusy.current) { uploadRequested.current = true; return; }
    uploadRequested.current = false;
    const generation = contextGeneration.current;
    const contextStillActive = () => mounted.current && contextGeneration.current === generation &&
      getAuthState().user?.userId === state.user?.userId &&
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
        if (mounted.current && (uploadRequested.current || !contextStillActive())) setUploadContextVersion(v => v + 1);
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
      // Claim and business setup stay separate phases: the cryptographic
      // claim is confirmed here; name + initial FeedingSite follow in the
      // setup wizard and remain resumable after interruptions.
      setClaimMessage('Napf registriert!');
      setSetupNodeId(nodeId);
      setSetupOrgId(organizationId);
      try {
        localStorage.setItem('miezmerker-last-setup-node',
          JSON.stringify({ organizationId, nodeId }));
      } catch { /* Resume hint is best-effort; backend state stays authoritative. */ }
      setResumeCandidate(null);
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

  return <section aria-label="Vor-Ort-Sync"><h1 className="title-with-icon"><Graphic src={assets.illustrations.sync} alt="" className="app-icon"/>Vor-Ort-Sync</h1>
    <Status kind={storage.includes('wird') ? 'loading' : storage.includes('nicht') ? 'error' : 'info'} title={storage}/>
    <p>{auth.user
      ? `Angemeldet als ${auth.user.email}${auth.activeOrganizationId ? ` · Organisation: ${activeMembership?.organizationName ?? auth.activeOrganizationId} (${activeMembership?.role ?? '?'})` : ' · keine Organisation gewählt'}`
      : 'Nicht angemeldet.'}</p>
    <Status kind={credential.startsWith('Offline-Credential wird geprüft') ? 'loading' : 'info'} title="Offline-Berechtigung" message={credential}/>
    {capability !== 'supported' && <Status {...errorStatus('Dieser Browser unterstützt kein Web Bluetooth. Bitte aktuelles Chrome/Chromium auf Android verwenden.')}/>}
    {!navigator.onLine && <Status {...statusMessages.offline}/>}

    {knownDevices.length > 0 && <div><p>Bekannte Nodes (Browser-freigegeben, optional):</p><ul>
      {knownDevices.map(d => <li key={d.id}>{d.name}
        <button type="button" disabled={running || claimBusy} onClick={() => connectKnownDevice(d.device)}>Verbinden</button>
      </li>)}
    </ul><p>Explizite Auswahl bleibt jederzeit möglich.</p></div>}

    <button type="button" disabled={running || claimBusy || capability !== 'supported' || !auth.user || !auth.activeOrganizationId} onClick={startSync}>
      {running ? 'Synchronisiere …' : 'Node auswählen & synchronisieren'}
    </button>

    <NodeStatus view={view} retry={startSync} disabled={running || claimBusy || capability !== 'supported' || !auth.user || !auth.activeOrganizationId}/>

    {view.foreign && <Status kind="error" title="Napf gehört einer anderen Organisation" message={`Dieser MiezMerker gehört ${view.foreign.organizationName}.${view.foreign.publicContact ? ` Kontakt: ${view.foreign.publicContact}` : ''} Keine Observations abgerufen.`}/>}

    {view.unclaimed && <div>
      <Status kind="info" title="Node ist UNCLAIMED." message="Dieser Napf ist noch nicht registriert. Die Einrichtung benötigt einen ADMIN."/>
      {!isAdmin
        ? <p>Nur ADMIN kann einen UNCLAIMED Node claimen. MEMBER hat keinen Zugriff.</p>
        : <div>
          <p>Physischen Claim-Modus am Node aktivieren (Taste/Power-On-Geste), dann claimen. Der Node liefert den signierten Nachweis selbst.</p>
          <button type="button" disabled={claimBusy || running} onClick={submitClaim}>Node claimen (ADMIN)</button>
          {claimReceipt && <p>Gespeichertes Receipt vorhanden; ein abgebrochener Claim wird sicher erneut zugestellt.</p>}
        </div>}
      {claimMessage && <Status {...errorStatus(claimMessage)} scope="Napf registrieren"/>}
    </div>}
    {!view.unclaimed && claimMessage && <Status kind="info" title="Napf registrieren" message={claimMessage}/>}

    {isAdmin && auth.activeOrganizationId && setupNodeId && setupOrgId === auth.activeOrganizationId && <div>
      <NodeSetupWizard organizationId={auth.activeOrganizationId} nodeId={setupNodeId} onComplete={() => {
        try { localStorage.removeItem('miezmerker-last-setup-node'); } catch { /* best-effort */ }
        setResumeCandidate(null);
      }} />
      <button type="button" onClick={() => { setSetupNodeId(null); setSetupOrgId(null); }}>Einrichtung schließen</button>
    </div>}

    {isAdmin && auth.activeOrganizationId && !setupNodeId && resumeCandidate && <div>
      <Status kind="info" title="Einrichtung noch offen" message={`Unvollständige Napf-Einrichtung gefunden (${resumeCandidate.nodeId}). Der Claim bleibt gültig und kann ohne erneutes kryptografisches Claiming fortgesetzt werden.`}/>
      <button type="button" onClick={() => { setSetupNodeId(resumeCandidate.nodeId); setSetupOrgId(resumeCandidate.organizationId); }}>
        Einrichtung fortsetzen
      </button>
    </div>}

    {isAdmin && auth.activeOrganizationId && !setupNodeId && !view.unclaimed && !view.foreign && view.owner?.nodeId && <div>
      <p>Bereits registrierter Node erkannt (<code>{view.owner.nodeId}</code>). Falls Name oder Futterstelle fehlen, kann die Einrichtung ohne erneutes Claiming geprüft werden.</p>
      <button type="button" onClick={() => { setSetupNodeId(view.owner!.nodeId); setSetupOrgId(auth.activeOrganizationId); }}>
        Einrichtung prüfen/fortsetzen
      </button>
    </div>}

    <UploadStatus view={view} retry={retryUpload} disabled={running}/>

    <details><summary>Hinweise zu Fehlerfällen</summary><ul>
      {syncHelp.map(message => <li key={message}>{message}</li>)}
    </ul></details>
  </section>;
}
