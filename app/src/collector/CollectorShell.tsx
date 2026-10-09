import { useCallback, useEffect, useRef, useState } from 'react';
import { CollectorDatabase, requestPersistentStorage } from '../platform/offline-store';
import { getAuthState, subscribeAuth } from '../platform/auth';
import { OfflineIdentity, OfflineIdentityDatabase } from '../platform/offline-identity';
import {
  bluetoothCapability, browserDeviceId, browserDeviceLabel,
  getDevicesFallbackMessage, getDevicesSupport,
  previouslyAuthorizedDevices, requestNodeDevice,
  WebBluetoothTransport,
} from '../platform/web-bluetooth';
import type { CollectorViewState } from './collector-sync';
import { initialCollectorView } from './collector-sync';
import type { BackendSyncState } from './backend-upload';
import { ClaimError } from './claim-node';
import { provisionNode } from './provision-node';
import { assets } from '../assets';
import { Graphic } from '../ui/Graphic';
import { NodeSetupWizard } from './NodeSetupWizard';
import { BackendUploader } from './backend-upload';
import { CollectorObservationStore } from './observation-store';
import { UploadStatus } from './CollectorStatus';
import { BatchNodeList, BatchSummary } from './BatchSyncStatus';
import { resolveBatchContexts, type BatchNodeContext } from './batch-context';
import {
  dedupeBatchInputs, retryableBatchInputs, runBatchSync,
  type BatchNodeInput, type BatchNodeResult,
} from './batch-sync';
import { Status } from '../ui/Status';
import { bluetoothErrorMessage, errorStatus, statusMessages, syncHelp } from '../ui/status-messages';

// Single shared MiezMerker PWA under /app. No second collector application.
// Issue #76: one user-initiated "Futterstelle auslesen" sequentially
// synchronizes all previously browser-authorized, currently reachable, and
// organization-authorized NapfNodes. Node→PWA and PWA→Backend stay
// independent; local success means safely copied from Node + acknowledged to
// Node. Backend upload is a separate retryable step.
//
// Three independent operations stay distinct:
// 1. ADMIN claiming/provisioning (existing #18/#53 contract, unchanged).
// 2. Per-browser Bluetooth permission via explicit "Weiteren Napf freigeben"
//    (requestDevice, user activation required).
// 3. Regular batch sync via getDevices() without another chooser.
export function CollectorShell(props: {
  now?: () => number;
} = {}) {
  const [storage, setStorage] = useState('Lokaler Speicher wird geöffnet …');
  const [authVersion, setAuthVersion] = useState(0);
  const [connectivityVersion, setConnectivityVersion] = useState(0);
  const [uploadContextVersion, setUploadContextVersion] = useState(0);
  const [credential, setCredential] = useState('Offline-Credential wird geprüft …');
  const [uploadView, setUploadView] = useState<{
    backendState: BackendSyncState; backendMessage: string; pendingUploads: number; uploadedCount: number;
  }>({ backendState: 'idle', backendMessage: '', pendingUploads: 0, uploadedCount: 0 });
  const [batchRunning, setBatchRunning] = useState(false);
  const [batchProgress, setBatchProgress] = useState<{ done: number; total: number; current: string; detail: string } | null>(null);
  const [batchResults, setBatchResults] = useState<BatchNodeResult[]>([]);
  const [batchNotice, setBatchNotice] = useState('');
  const [batchContexts, setBatchContexts] = useState<Map<string, BatchNodeContext>>(new Map());
  const [contextsLoading, setContextsLoading] = useState(false);
  const [devices, setDevices] = useState<Array<{ id: string; name: string; device: unknown }>>([]);
  const [devicesSupport, setDevicesSupport] = useState<'supported' | 'unsupported'>(() => getDevicesSupport());
  const [freigebenBusy, setFreigebenBusy] = useState(false);
  const [freigebenMessage, setFreigebenMessage] = useState('');
  const uploadBusy = useRef(false);
  const uploadRequested = useRef(false);
  const contextGeneration = useRef(0);
  const mounted = useRef(true);
  const credentialCheckGeneration = useRef(0);
  const [claimBusy, setClaimBusy] = useState(false);
  const [claimMessage, setClaimMessage] = useState('');
  const [claimReceipt, setClaimReceipt] = useState('');
  const [claimTarget, setClaimTarget] = useState<{ browserId: string; nodeId: string } | null>(null);
  const [setupNodeId, setSetupNodeId] = useState<string | null>(null);
  const [setupOrgId, setSetupOrgId] = useState<string | null>(null);
  const [resumeCandidate, setResumeCandidate] = useState<{ organizationId: string; nodeId: string } | null>(null);
  const batchController = useRef<AbortController | null>(null);

  const auth = getAuthState();
  const contextKey = () => `${getAuthState().user?.userId ?? ''}/${getAuthState().activeOrganizationId ?? ''}`;

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
        batchController.current?.abort(); batchController.current = null;
        setBatchRunning(false); setBatchProgress(null); setBatchResults([]); setBatchNotice('');
        setBatchContexts(new Map()); setClaimBusy(false); setClaimMessage(''); setClaimTarget(null);
        previousContext = nextContext;
      }
      setAuthVersion(v => v + 1);
    });
    return () => { mounted.current = false; contextGeneration.current++; unsubscribe(); };
  }, []);
  useEffect(() => {
    setBatchResults([]); setBatchNotice(''); setBatchContexts(new Map()); setClaimMessage(''); setClaimTarget(null);
    setBatchRunning(false); setBatchProgress(null); setClaimBusy(false); setSetupNodeId(null); setSetupOrgId(null);
    setFreigebenMessage('');
    return () => { batchController.current?.abort(); batchController.current = null; };
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

  const refreshDevices = useCallback(async (): Promise<Array<{ id: string; name: string; device: unknown }>> => {
    const support = getDevicesSupport();
    setDevicesSupport(support);
    if (bluetoothCapability() !== 'supported') {
      setDevices([]);
      return [];
    }
    try {
      const found = await previouslyAuthorizedDevices();
      const mapped = found.map((d, i) => ({
        id: browserDeviceId(d, `node-${i}`),
        name: browserDeviceLabel(d, 'Bekannter Node'),
        device: d,
      }));
      const deduped = new Map(mapped.map(m => [m.id, m]));
      const list = [...deduped.values()];
      setDevices(list);
      return list;
    } catch {
      // A failed listing must not leave a stale bowl count on screen: the
      // batch below would run against an empty snapshot while the UI still
      // claims N authorized bowls.
      setDevices([]);
      return [];
    }
  }, []);

  useEffect(() => {
    let active = true;
    if (bluetoothCapability() !== 'supported') { setDevices([]); return; }
    void refreshDevices().catch(() => { if (active) setDevices([]); });
    return () => { active = false; };
  }, [refreshDevices]);

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
    setUploadView(v => ({ ...v, backendState: 'uploading', backendMessage: 'Backend-Upload läuft …' }));
    const db = new CollectorDatabase();
    const store = new CollectorObservationStore(db);
    void (async () => {
      try {
        await store.open();
        const uploader = new BackendUploader(store);
        const result = await uploader.upload(state.activeOrganizationId as string);
        const stats = await store.organizationUploadStats(state.activeOrganizationId as string);
        if (contextStillActive()) setUploadView({ backendState: result.state, backendMessage: result.message,
          pendingUploads: stats.pending + stats.failed, uploadedCount: stats.uploaded });
      } catch (e) {
        if (contextStillActive()) setUploadView(v => ({ ...v, backendState: 'failed',
          backendMessage: `Backend-Upload fehlgeschlagen (${e instanceof Error ? e.message : 'unbekannt'}). Vor-Ort-Sync bleibt gültig.` }));
      } finally {
        uploadBusy.current = false;
        if (mounted.current && (uploadRequested.current || !contextStillActive())) setUploadContextVersion(v => v + 1);
        db.close();
      }
    })();
  }, [batchRunning]);

  useEffect(() => { if (!batchRunning) retryUpload(); }, [auth.user?.userId, auth.activeOrganizationId, connectivityVersion, batchRunning, uploadContextVersion]);

  const loadContextsFor = useCallback(async (nodeIds: string[], organizationId: string, signal: AbortSignal) => {
    if (!nodeIds.length) { setBatchContexts(new Map()); return; }
    setContextsLoading(true);
    try {
      const contexts = await resolveBatchContexts(nodeIds, organizationId, { signal });
      if (!signal.aborted) setBatchContexts(contexts);
    } catch {
      if (!signal.aborted) setBatchContexts(new Map());
    } finally {
      if (!signal.aborted) setContextsLoading(false);
    }
  }, []);

  const startBatch = useCallback(async (explicitInputs?: BatchNodeInput[]) => {
    if (batchRunning || claimBusy) return;
    const state = getAuthState();
    if (!state.user || !state.activeOrganizationId) {
      setBatchNotice('Bitte anmelden und Organisation wählen.');
      return;
    }
    if (bluetoothCapability() !== 'supported') {
      setBatchNotice('Dieser Browser unterstützt kein Web Bluetooth. Bitte aktuelles Chrome/Chromium auf Android verwenden.');
      return;
    }
    const operation = new AbortController();
    batchController.current = operation;
    setBatchRunning(true);
    setBatchNotice('');
    setBatchResults([]);
    setBatchContexts(new Map());
    setClaimMessage(''); setClaimTarget(null);
    const startedContext = contextKey();
    const stillActive = () => batchController.current === operation && !operation.signal.aborted && contextKey() === startedContext;
    const orgStillActive = () => batchController.current === operation && contextKey() === startedContext;
    const organizationId = state.activeOrganizationId;
    try {
      // Fresh permission snapshot: getDevices() may include powered-off or
      // removed devices; only actual connection attempts prove reachability.
      // Never call requestDevice() here: batch sync must not consume user
      // activation and never discovers unpermitted devices.
      let inputs = explicitInputs;
      if (!inputs) {
        const list = await refreshDevices();
        if (!orgStillActive()) return;
        inputs = dedupeBatchInputs(list.map(entry => ({
          device: entry.device, browserId: entry.id, browserLabel: entry.name,
        })));
      }
      if (!inputs.length) {
        if (orgStillActive()) {
          setBatchNotice(devicesSupport === 'unsupported'
            ? getDevicesFallbackMessage('unsupported')
            : getDevicesFallbackMessage('supported'));
        }
        return;
      }
      setBatchProgress({ done: 0, total: inputs.length, current: '', detail: 'Starte Sammel-Sync …' });
      credentialCheckGeneration.current++;
      const results = await runBatchSync(inputs, {
        signal: operation.signal,
        trustedNowS: () => Math.floor((props.now?.() ?? Date.now()) / 1000),
        createTransport: device => new WebBluetoothTransport(device as never),
        onNodeStart: (index, total, input) => {
          if (stillActive()) setBatchProgress({ done: index, total, current: input.browserLabel, detail: 'Verbinde mit Node …' });
        },
        onNodeUpdate: (index, total, input, view: CollectorViewState) => {
          if (!stillActive()) return;
          if (view.credentialState) setCredential(view.credentialState);
          setBatchProgress({ done: index, total, current: input.browserLabel,
            detail: view.nodeMessage || 'Synchronisiere …' });
        },
        onNodeDone: (index, total) => {
          if (stillActive()) setBatchProgress(previous => previous ? { ...previous, done: index + 1 } : previous);
        },
      });
      // Cancellation keeps completed nodes valid: show partial results even
      // when the user aborted. Only an organization change discards them.
      if (!orgStillActive()) return;
      setBatchResults(results);
      setBatchProgress(null);
      // Feeding-site/cat context only after confirmed local sync, and only
      // when reliably resolvable. Offline or backend failures keep the local
      // success valid with an honest "not available" note.
      const succeededNodeIds = results.filter(r => r.kind === 'success' && r.nodeId).map(r => r.nodeId as string);
      const unclaimedNodeId = results.find(r => r.kind === 'unclaimed')?.nodeId ?? null;
      if (succeededNodeIds.length) {
        await loadContextsFor(succeededNodeIds, organizationId, operation.signal);
      }
      if (unclaimedNodeId && organizationId) {
        const db = new CollectorDatabase();
        try {
          const meta = await db.nodeMeta.get(unclaimedNodeId);
          if (stillActive() && meta?.organizationId === organizationId) setClaimReceipt(meta.claimReceipt ?? '');
          else if (stillActive()) setClaimReceipt('');
        } catch {
          if (stillActive()) setClaimReceipt('');
        } finally {
          db.close();
        }
      }
      // A renewed credential during the batch updates the header; the visit's
      // newer result must not be overwritten by the stale pre-check.
      if (stillActive()) {
        const db = new OfflineIdentityDatabase();
        try {
          const cached = await new OfflineIdentity(db).credential(state.user.userId, organizationId);
          if (stillActive() && cached) setCredential('Offline-Credential gültig (offline Sync möglich).');
        } catch { /* Header stays as-is. */ } finally {
          db.close();
        }
      }
    } finally {
      if (batchController.current === operation) {
        batchController.current = null;
        setBatchRunning(false);
        setBatchProgress(null);
      }
    }
  }, [batchRunning, claimBusy, devicesSupport, refreshDevices, loadContextsFor, props]);

  const retryFailed = useCallback(() => {
    if (batchRunning || claimBusy || !batchResults.length) return;
    const previous = batchResults;
    const operation = new AbortController();
    batchController.current = operation;
    setBatchRunning(true);
    setBatchNotice('');
    const startedContext = contextKey();
    const stillActive = () => batchController.current === operation && !operation.signal.aborted && contextKey() === startedContext;
    const orgStillActive = () => batchController.current === operation && contextKey() === startedContext;
    const organizationId = getAuthState().activeOrganizationId;
    void (async () => {
      // Refresh permissions first: a revoked bowl must not be retried
      // blindly from a stale list, and a newly freed bowl is not part of
      // this retry (it joins the next full batch instead).
      const list = await refreshDevices();
      if (!orgStillActive()) return;
      const currentInputs: BatchNodeInput[] = list.map(entry => ({
        device: entry.device, browserId: entry.id, browserLabel: entry.name,
      }));
      const retryInputs = retryableBatchInputs(currentInputs, previous);
      if (!retryInputs.length) {
        setBatchNotice('Keine wiederholbaren Näpfe mehr vorhanden (Berechtigung entzogen oder bereits erfolgreich).');
        return;
      }
      setBatchProgress({ done: 0, total: retryInputs.length, current: '', detail: 'Wiederhole fehlgeschlagene Näpfe …' });
      try {
        const retried = await runBatchSync(retryInputs, {
          signal: operation.signal,
          trustedNowS: () => Math.floor((props.now?.() ?? Date.now()) / 1000),
          createTransport: device => new WebBluetoothTransport(device as never),
          onNodeStart: (index, total, input) => {
            if (stillActive()) setBatchProgress({ done: index, total, current: input.browserLabel, detail: 'Verbinde mit Node …' });
          },
          onNodeUpdate: (index, total, input, view: CollectorViewState) => {
            if (stillActive()) setBatchProgress({ done: index, total, current: input.browserLabel, detail: view.nodeMessage || 'Synchronisiere …' });
          },
        });
        if (!orgStillActive() || !organizationId) return;
        const merged = previous.map(old => retried.find(r => r.browserId === old.browserId) ?? old);
        setBatchResults(merged);
        const succeededNodeIds = merged.filter(r => r.kind === 'success' && r.nodeId).map(r => r.nodeId as string);
        if (succeededNodeIds.length) await loadContextsFor(succeededNodeIds, organizationId, operation.signal);
      } catch {
        if (orgStillActive()) setBatchNotice('Wiederholung abgebrochen. Bereits abgeschlossene Näpfe bleiben gültig.');
      } finally {
        if (batchController.current === operation) {
          batchController.current = null;
          setBatchRunning(false);
          setBatchProgress(null);
        }
      }
    })();
  }, [batchRunning, claimBusy, batchResults, refreshDevices, loadContextsFor, props]);

  const cancelBatch = useCallback(() => {
    batchController.current?.abort();
  }, []);

  // Explicit first-release flow: requestDevice() requires a user gesture, so
  // it is invoked synchronously in this click handler before any IDB,
  // permission or network await can consume transient user activation.
  // The newly permitted device joins the current list and is synced without
  // another chooser (or in the next batch while one is running).
  const releaseAdditionalBowl = useCallback(() => {
    const state = getAuthState();
    if (freigebenBusy) return;
    if (!state.user || !state.activeOrganizationId) {
      setFreigebenMessage('Bitte anmelden und Organisation wählen.');
      return;
    }
    if (bluetoothCapability() !== 'supported') {
      setFreigebenMessage('Dieser Browser unterstützt kein Web Bluetooth. Bitte aktuelles Chrome/Chromium auf Android verwenden.');
      return;
    }
    setFreigebenBusy(true);
    setFreigebenMessage('');
    // requestNodeDevice() is invoked synchronously in this click handler to
    // preserve transient user activation; no IDB/network await runs before it.
    const request = requestNodeDevice();
    void request.then(device => {
      const id = browserDeviceId(device, `node-${Date.now()}`);
      const name = browserDeviceLabel(device, 'Freigegebener Node');
      setDevices(previous => {
        if (previous.some(entry => entry.id === id)) return previous;
        return [...previous, { id, name, device }];
      });
      if (batchRunning || claimBusy) {
        setFreigebenMessage(`„${name}“ freigegeben und für den nächsten Sammel-Sync vorgemerkt.`);
        return;
      }
      setFreigebenMessage(`„${name}“ freigegeben. Starte Sync ohne weiteren Chooser …`);
      void startBatch([{ device, browserId: id, browserLabel: name }]);
    }).catch((e: unknown) => {
      setFreigebenMessage(bluetoothErrorMessage(e, 'selection'));
    }).finally(() => setFreigebenBusy(false));
  }, [freigebenBusy, batchRunning, claimBusy, startBatch]);

  const claimUnclaimedNode = useCallback((result: BatchNodeResult) => {
    const state = getAuthState();
    const organizationId = state.activeOrganizationId;
    const nodeId = result.nodeId;
    const entry = devices.find(d => d.id === result.browserId);
    if (!nodeId || !organizationId || !entry || claimBusy || batchRunning) return;
    const operation = new AbortController();
    batchController.current = operation;
    setClaimBusy(true);
    setClaimMessage('');
    setClaimTarget({ browserId: result.browserId, nodeId });
    const startedContext = contextKey();
    const stillActive = () => batchController.current === operation && !operation.signal.aborted && contextKey() === startedContext;
    const transport = new WebBluetoothTransport(entry.device as never);
    void provisionNode(transport, nodeId, organizationId, operation.signal).then(() => {
      if (!stillActive()) return;
      setClaimMessage('Napf registriert!');
      setSetupNodeId(nodeId);
      setSetupOrgId(organizationId);
      try {
        localStorage.setItem('miezmerker-last-setup-node',
          JSON.stringify({ organizationId, nodeId }));
      } catch { /* Resume hint is best-effort; backend state stays authoritative. */ }
      setResumeCandidate(null);
    }).catch((e: unknown) => {
      if (stillActive()) setClaimMessage(e instanceof ClaimError ? e.message : e instanceof Error ? e.message : 'Claiming fehlgeschlagen.');
    }).finally(() => { if (batchController.current === operation) { batchController.current = null; setClaimBusy(false); } });
  }, [devices, claimBusy, batchRunning]);

  useEffect(() => {
    let active = true;
    const db = new CollectorDatabase();
    const unclaimedNodeId = batchResults.find(r => r.kind === 'unclaimed')?.nodeId ?? null;
    setClaimReceipt(previous => unclaimedNodeId ? previous : '');
    if (!unclaimedNodeId) { db.close(); return () => { active = false; }; }
    void db.nodeMeta.get(unclaimedNodeId).then(meta => {
      if (active && meta?.organizationId === auth.activeOrganizationId) setClaimReceipt(meta.claimReceipt ?? '');
    }).catch(() => {}).finally(() => db.close());
    return () => { active = false; };
  }, [batchResults, auth.activeOrganizationId]);

  const capability = bluetoothCapability();
  const memberships = auth.user?.memberships.filter(m => m.status === 'ACTIVE') ?? [];
  const activeMembership = memberships.find(m => m.organizationId === auth.activeOrganizationId);
  const isAdmin = activeMembership?.role === 'ADMIN';
  const uploadViewState: CollectorViewState = {
    ...initialCollectorView,
    backendState: uploadView.backendState,
    backendMessage: uploadView.backendMessage,
    pendingUploads: uploadView.pendingUploads,
    uploadedCount: uploadView.uploadedCount,
  };
  const retryableCount = retryableBatchInputs(
    devices.map(entry => ({ device: entry.device, browserId: entry.id, browserLabel: entry.name })),
    batchResults,
  ).length;
  const canStart = !batchRunning && !claimBusy && !freigebenBusy && capability === 'supported' && Boolean(auth.user && auth.activeOrganizationId);

  return <section aria-label="Vor-Ort-Sync"><h1 className="title-with-icon"><Graphic src={assets.illustrations.sync} alt="" className="app-icon"/>Vor-Ort-Sync</h1>
    <Status kind={storage.includes('wird') ? 'loading' : storage.includes('nicht') ? 'error' : 'info'} title={storage}/>
    <p>{auth.user
      ? `Angemeldet als ${auth.user.email}${auth.activeOrganizationId ? ` · Organisation: ${activeMembership?.organizationName ?? auth.activeOrganizationId} (${activeMembership?.role ?? '?'})` : ' · keine Organisation gewählt'}`
      : 'Nicht angemeldet.'}</p>
    <Status kind={credential.startsWith('Offline-Credential wird geprüft') ? 'loading' : 'info'} title="Offline-Berechtigung" message={credential}/>
    {capability !== 'supported' && <Status {...errorStatus('Dieser Browser unterstützt kein Web Bluetooth. Bitte aktuelles Chrome/Chromium auf Android verwenden.')}/>}
    {!navigator.onLine && <Status {...statusMessages.offline}/>}

    <p>Ein bewusster Klick liest alle auf dieser Browserinstallation freigegebenen, aktuell erreichbaren Näpfe der aktiven Organisation nacheinander aus. Mehrere Näpfe an einer Futterstelle sind normal; Näpfe unterschiedlicher Futterstellen werden korrekt zugeordnet. Neue Näpfe brauchen je eine einmalige explizite Freigabe.</p>
    {devicesSupport === 'supported' && devices.length > 0 && <p>{devices.length === 1
      ? '1 Napf auf dieser Browserinstallation freigegeben (Erreichbarkeit wird beim Sync geprüft).'
      : `${devices.length} Näpfe auf dieser Browserinstallation freigegeben (Erreichbarkeit wird beim Sync geprüft).`} <button type="button" disabled={batchRunning} onClick={() => void refreshDevices()}>Liste aktualisieren</button></p>}
    {devicesSupport === 'supported' && devices.length === 0 && !batchRunning && !batchResults.length && <Status kind="info" title="Noch kein Napf freigegeben" message={getDevicesFallbackMessage('supported')}/>}
    {devicesSupport === 'unsupported' && capability === 'supported' && <Status kind="info" title="Automatisches Wiederfinden nicht verfügbar" message={getDevicesFallbackMessage('unsupported')}/>}

    <div className="actions">
      <button type="button" disabled={!canStart} onClick={() => void startBatch()}>
        {batchRunning ? 'Synchronisiere …' : 'Futterstelle auslesen'}
      </button>
      <button type="button" disabled={freigebenBusy || claimBusy || capability !== 'supported' || !auth.user || !auth.activeOrganizationId} onClick={releaseAdditionalBowl}>
        {freigebenBusy ? 'Freigabe läuft …' : 'Weiteren Napf freigeben'}
      </button>
      {batchRunning && <button type="button" onClick={cancelBatch}>Abbrechen</button>}
    </div>
    {freigebenMessage && <Status kind="info" title="Browser-Freigabe" message={freigebenMessage}/>}

    {batchProgress && <Status kind="loading" title={`Sammel-Sync läuft (${batchProgress.done} von ${batchProgress.total})`}
      scope="Vor-Ort-Sync" graphic="bluetooth" progress={{ value: batchProgress.done, max: batchProgress.total }}
      longWaitMessage="Das dauert länger. Bitte Näpfe eingeschaltet lassen, Smartphone näher heranhalten und Bluetooth prüfen. Die Navigation bleibt verfügbar."
      message={batchProgress.current ? `Aktuell: „${batchProgress.current}“ (Browser-Hinweis, nicht verifiziert) — ${batchProgress.detail}` : batchProgress.detail}/>}
    {batchNotice && !batchRunning && <Status kind="info" title="Sammel-Sync" message={batchNotice}/>}

    {!batchRunning && batchResults.length > 0 && <>
      <BatchSummary results={batchResults}/>
      <BatchNodeList results={batchResults} contexts={batchContexts} contextsLoading={contextsLoading}
        isAdmin={isAdmin} claimBusy={claimBusy} onClaim={claimUnclaimedNode}/>
      {retryableCount > 0 && <button type="button" disabled={!canStart} onClick={retryFailed}>
        {`Fehlgeschlagene erneut versuchen (${retryableCount})`}
      </button>}
      <button type="button" disabled={!canStart} onClick={() => void startBatch()}>
        Alle erneut auslesen
      </button>
    </>}

    {claimTarget && claimMessage && <Status kind={claimMessage === 'Napf registriert!' ? 'success' : 'error'} title="Napf registrieren" message={claimMessage}/>}
    {!claimTarget && claimMessage && <Status kind="info" title="Napf registrieren" message={claimMessage}/>}
    {claimReceipt && <p>Gespeichertes Receipt vorhanden; ein abgebrochener Claim wird sicher erneut zugestellt.</p>}

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

    <UploadStatus view={uploadViewState} retry={retryUpload} disabled={batchRunning}/>

    <details><summary>Hinweise zu Fehlerfällen</summary><ul>
      {syncHelp.map(message => <li key={message}>{message}</li>)}
    </ul></details>
  </section>;
}
