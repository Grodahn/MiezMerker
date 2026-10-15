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
import { initialCollectorView, runFieldSync } from './collector-sync';
import type { BackendSyncState } from './backend-upload';
import { ClaimError } from './claim-node';
import { provisionNode } from './provision-node';
import { verifyRegistrationCandidate } from './registration-probe';
import { fetchNodeSetupState, SetupError } from './node-setup';
import { authorizationPermits } from '../platform/authorization-status';
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
import './sync.css';

// Issue #161: authoritative, organization-bound recovery for CLAIMED nodes
// without an initial deployment. The stored ids are only a resume hint; the
// backend/node state decides. Never derived from BLE names or local flags,
// never re-claims, never opens for foreign or already-assigned nodes.
function isActiveAdminFor(organizationId: string): boolean {
  const state = getAuthState();
  if (state.user && !authorizationPermits(state.user.userId, organizationId)) return false;
  return Boolean(state.user?.memberships.some(
    m => m.organizationId === organizationId && m.status === 'ACTIVE' && m.role === 'ADMIN'));
}

function rememberSetupHint(organizationId: string, nodeId: string): void {
  try {
    localStorage.setItem('miezmerker-last-setup-node', JSON.stringify({ organizationId, nodeId }));
  } catch { /* Resume hint is best-effort; backend state stays authoritative. */ }
}

function clearSetupHint(): void {
  try { localStorage.removeItem('miezmerker-last-setup-node'); } catch { /* best-effort */ }
}

// Returns the first own CLAIMED node without any deployment history.
// Returns null for foreign/assigned/unclaimed/offline/backend failures:
// no invented authoritative setup state, sync stays valid.
async function findMissingInitialSetup(
  organizationId: string,
  nodeIds: string[],
  signal?: AbortSignal,
): Promise<{ organizationId: string; nodeId: string } | null> {
  if (!isActiveAdminFor(organizationId)) return null;
  const unique = [...new Set(nodeIds.filter(Boolean))];
  for (const nodeId of unique) {
    if (signal?.aborted) return null;
    try {
      const setup = await fetchNodeSetupState(organizationId, nodeId, { signal });
      if (setup.organizationId !== organizationId) continue;
      if (setup.claimed && !setup.activeDeployment && setup.deploymentCount === 0) {
        return { organizationId, nodeId: setup.nodeId };
      }
    } catch (error) {
      // No authoritative state: never invent setup, keep the local sync valid.
      // Per-node isolation (foreign, forbidden, validation, closed history)
      // skips only this node; transport/session/backend failures stop the
      // probe because the remaining nodes would fail the same way.
      if (error instanceof SetupError) {
        if (error.kind === 'foreign' || error.kind === 'forbidden-role' ||
            error.kind === 'validation' || error.kind === 'conflict-existing') continue;
        return null;
      }
      if (error instanceof DOMException && error.name === 'AbortError') return null;
      const message = error instanceof Error ? error.message : String(error ?? '');
      if (/abgebrochen/i.test(message)) return null;
      // Generic transport/backend failure: stop probing, sync stays valid.
      return null;
    }
  }
  return null;
}

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
  // Browser device objects are session-only permissions, never trusted node IDs.
  const sessionSelectedDevices = useRef<typeof devices>([]);
  const [devicesSupport, setDevicesSupport] = useState<'supported' | 'unsupported'>(() => getDevicesSupport());
  const [devicesError, setDevicesError] = useState('');
  const [freigebenBusy, setFreigebenBusy] = useState(false);
  const [freigebenMessage, setFreigebenMessage] = useState('');
  const uploadBusy = useRef(false);
  const uploadRequested = useRef(false);
  const contextGeneration = useRef(0);
  const mounted = useRef(true);
  const credentialCheckGeneration = useRef(0);
  const [registrationCandidate, setRegistrationCandidate] = useState<BatchNodeResult | null>(null);
  const registrationDialog = useRef<HTMLDialogElement>(null);
  const declineButton = useRef<HTMLButtonElement>(null);
  const addButton = useRef<HTMLButtonElement>(null);
  const [claimBusy, setClaimBusy] = useState(false);
  const [claimMessage, setClaimMessage] = useState('');
  const [claimReceipt, setClaimReceipt] = useState('');
  const [claimTarget, setClaimTarget] = useState<{ browserId: string; nodeId: string } | null>(null);
  const [setupNodeId, setSetupNodeId] = useState<string | null>(null);
  const [setupOrgId, setSetupOrgId] = useState<string | null>(null);
  const [resumeCandidate, setResumeCandidate] = useState<{ organizationId: string; nodeId: string } | null>(null);
  const batchController = useRef<AbortController | null>(null);
  const permissionRequest = useRef(false);

  const auth = getAuthState();
  const contextKey = () => {
    const state = getAuthState();
    const membership = state.user?.memberships.find(m => m.organizationId === state.activeOrganizationId);
    return `${state.user?.userId ?? ''}/${state.activeOrganizationId ?? ''}/${membership?.role ?? ''}/${membership?.status ?? ''}`;
  };

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
        setRegistrationCandidate(null); setSetupNodeId(null); setSetupOrgId(null);
        setBatchContexts(new Map()); setClaimBusy(false); setClaimMessage(''); setClaimTarget(null);
        setContextsLoading(false);
        setUploadView({ backendState: 'idle', backendMessage: '', pendingUploads: 0, uploadedCount: 0 });
        previousContext = nextContext;
      }
      setAuthVersion(v => v + 1);
    });
    return () => { mounted.current = false; contextGeneration.current++; batchController.current?.abort(); unsubscribe(); };
  }, []);
  useEffect(() => {
    setBatchResults([]); setBatchNotice(''); setBatchContexts(new Map()); setClaimMessage(''); setClaimTarget(null);
    setBatchRunning(false); setBatchProgress(null); setClaimBusy(false); setSetupNodeId(null); setSetupOrgId(null);
    setFreigebenMessage('');
    setContextsLoading(false);
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
    setCredential('Offline-Credential wird geprüft …');
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

  const refreshDevices = useCallback(async (signal?: AbortSignal): Promise<Array<{ id: string; name: string; device: unknown }>> => {
    setDevicesError('');
    const support = getDevicesSupport();
    setDevicesSupport(support);
    if (bluetoothCapability() !== 'supported') {
      setDevices([]);
      return [];
    }
    if (support === 'unsupported') {
      const selected = sessionSelectedDevices.current;
      if (mounted.current && !signal?.aborted) setDevices(selected);
      return selected;
    }
    try {
      const found = await previouslyAuthorizedDevices(signal);
      const mapped = found.map((d, i) => ({
        id: browserDeviceId(d, `node-${i}`),
        name: browserDeviceLabel(d, 'Bekannter Node'),
        device: d,
      }));
      const deduped = new Map(mapped.map(m => [m.id, m]));
      const list = [...deduped.values()];
      if (mounted.current && !signal?.aborted) setDevices(list);
      return list;
    } catch (error) {
      // A failed listing must not leave a stale bowl count on screen: the
      // batch below would run against an empty snapshot while the UI still
      // claims N authorized bowls.
      if (mounted.current && !signal?.aborted) {
        setDevices([]);
        setDevicesError(`Freigegebene Näpfe konnten nicht aufgelistet werden. ${bluetoothErrorMessage(error)}`);
      }
      throw error;
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
    const store = new CollectorObservationStore(db, state.user?.userId);
    void (async () => {
      try {
        await store.open();
        const controller = new AbortController();
        const unsubscribe = subscribeAuth(() => { if (!contextStillActive()) controller.abort(); });
        const uploader = new BackendUploader(store, {
          signal: controller.signal,
          assertActive: () => { if (controller.signal.aborted || !contextStillActive()) throw new Error('Upload nach Kontextwechsel abgebrochen.'); },
        });
        let result;
        try { result = await uploader.upload(state.activeOrganizationId as string); }
        finally { unsubscribe(); }
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
    const generation = contextGeneration.current;
    setContextsLoading(true);
    try {
      const contexts = await resolveBatchContexts(nodeIds, organizationId, { signal });
      if (mounted.current && generation === contextGeneration.current && !signal.aborted) setBatchContexts(contexts);
    } catch {
      if (mounted.current && generation === contextGeneration.current && !signal.aborted) setBatchContexts(new Map());
    } finally {
      if (mounted.current && generation === contextGeneration.current) setContextsLoading(false);
    }
  }, []);

  const startBatch = useCallback(async (explicitInputs?: BatchNodeInput[], registration = false) => {
    if (batchController.current || batchRunning || claimBusy) return;
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
    const stillActive = () => mounted.current && batchController.current === operation && !operation.signal.aborted && contextKey() === startedContext;
    const orgStillActive = () => mounted.current && batchController.current === operation && contextKey() === startedContext;
    const organizationId = state.activeOrganizationId;
    let verifiedCandidate: BatchNodeResult | null = null;
    const verifiedNodeIds = new Set<string>();
    let preflightError: unknown;
    try {
      // Fresh permission snapshot: getDevices() may include powered-off or
      // removed devices; only actual connection attempts prove reachability.
      // Never call requestDevice() here: batch sync must not consume user
      // activation and never discovers unpermitted devices.
      let inputs = explicitInputs;
      if (!inputs) {
        const list = await refreshDevices(operation.signal);
        if (!stillActive()) return;
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
      let results = await runBatchSync(inputs, {
        runSingleNode: (transport, signal, onUpdate) => runFieldSync({ createTransport: () => transport, signal, onUpdate,
          onUnclaimed: getAuthState().user?.memberships.some(m => m.organizationId === organizationId && m.status === 'ACTIVE' && m.role === 'ADMIN')
            ? async (connected, hello) => {
              setFreigebenMessage('Napf verbunden. Signierter Identitätsnachweis wird geprüft …');
              try {
                await verifyRegistrationCandidate(connected, hello.nodeId, organizationId, signal, true);
                verifiedNodeIds.add(hello.nodeId);
              } catch (error) { preflightError = error; throw error; }
            } : undefined,
        }),
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
      if (registration) results = results.map(result => result.kind === 'foreign'
        ? { ...result, foreignOrganizationName: null, message: 'Dieser Napf ist für deine Organisation nicht verfügbar.' } : result);
      setBatchResults(results);
      setBatchProgress(null);
      if (preflightError) throw preflightError;
      if (registration && results[0]?.kind === 'foreign') {
        setFreigebenMessage('Dieser Napf ist für deine Organisation nicht verfügbar.');
      }
      if (registration && results[0]?.kind === 'success') {
        setFreigebenMessage('Dieser Napf gehört bereits zu deiner Organisation.');
      }
      const activeAdmin = getAuthState().user?.memberships.some(m => m.organizationId === organizationId && m.status === 'ACTIVE' && m.role === 'ADMIN');
      const candidate = results.find(result => result.kind === 'unclaimed' && result.nodeId);
      if (candidate && !operation.signal.aborted) {
        if (!activeAdmin) setFreigebenMessage('Ein Organisationsadmin muss diesen Napf registrieren.');
        else {
          const input = inputs.find(input => input.browserId === candidate.browserId);
          if (input) {
            setFreigebenMessage('Napf verbunden. Signierter Identitätsnachweis wird geprüft …');
            if (!verifiedNodeIds.has(candidate.nodeId!)) {
              await verifyRegistrationCandidate(new WebBluetoothTransport(input.device as never), candidate.nodeId!, organizationId, operation.signal);
            }
            if (stillActive()) { setFreigebenMessage(''); verifiedCandidate = candidate; }
          }
        }
      }
      // Feeding-site/cat context only after confirmed local sync, and only
      // when reliably resolvable. Offline or backend failures keep the local
      // success valid with an honest "not available" note.
      const succeededNodeIds = results.filter(r => r.kind === 'success' && r.nodeId).map(r => r.nodeId as string);
      const unclaimedNodeId = results.find(r => r.kind === 'unclaimed')?.nodeId ?? null;
      if (succeededNodeIds.length && !operation.signal.aborted) {
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
      // Issue #161 recovery: an already CLAIMED own node without an initial
      // deployment is offered the existing setup wizard on any ordinary sync,
      // not only on the narrow registration-success path. Backend state
      // decides; probe failures keep the local sync valid and never block
      // otherwise valid data transfer.
      if (!verifiedCandidate && succeededNodeIds.length && !operation.signal.aborted) {
        try {
          const missing = await findMissingInitialSetup(organizationId, succeededNodeIds, operation.signal);
          if (missing && stillActive()) {
            setSetupNodeId(missing.nodeId);
            setSetupOrgId(missing.organizationId);
            rememberSetupHint(missing.organizationId, missing.nodeId);
            setResumeCandidate(null);
            if (!registration) {
              setBatchNotice('Napf registriert, Einrichtung offen: Futterstellen-Zuordnung kann direkt abgeschlossen werden.');
            }
          }
        } catch {
          // Setup probe is best-effort; sync/contexts stay valid.
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
    } catch (error) {
      if (orgStillActive()) {
        setFreigebenMessage(''); // Preflight is finished; do not retain its busy hint.
        setBatchNotice(bluetoothErrorMessage(error));
      }
    } finally {
      if (batchController.current === operation) {
        batchController.current = null;
        setBatchRunning(false);
        setBatchProgress(null);
        if (verifiedCandidate && !operation.signal.aborted && contextKey() === startedContext) setRegistrationCandidate(verifiedCandidate);
      }
    }
  }, [batchRunning, claimBusy, devicesSupport, refreshDevices, loadContextsFor, props]);

  const retryFailed = useCallback(() => {
    if (batchController.current || batchRunning || claimBusy || !batchResults.length) return;
    const previous = batchResults;
    const operation = new AbortController();
    batchController.current = operation;
    setBatchRunning(true);
    setBatchNotice('');
    const startedContext = contextKey();
    const stillActive = () => mounted.current && batchController.current === operation && !operation.signal.aborted && contextKey() === startedContext;
    const orgStillActive = () => mounted.current && batchController.current === operation && contextKey() === startedContext;
    const organizationId = getAuthState().activeOrganizationId;
    void (async () => {
      try {
        // Refresh permissions first: a revoked bowl must not be retried
        // blindly from a stale list, and a newly freed bowl is not part of
        // this retry (it joins the next full batch instead).
        const list = await refreshDevices(operation.signal);
        if (!stillActive()) return;
        const currentInputs: BatchNodeInput[] = list.map(entry => ({
          device: entry.device, browserId: entry.id, browserLabel: entry.name,
        }));
        const retryInputs = retryableBatchInputs(currentInputs, previous);
        if (!retryInputs.length) {
          setBatchNotice('Keine wiederholbaren Näpfe mehr vorhanden (Berechtigung entzogen oder bereits erfolgreich).');
          return;
        }
        setBatchProgress({ done: 0, total: retryInputs.length, current: '', detail: 'Wiederhole fehlgeschlagene Näpfe …' });
        credentialCheckGeneration.current++;
        const retried = await runBatchSync(retryInputs, {
          signal: operation.signal,
          trustedNowS: () => Math.floor((props.now?.() ?? Date.now()) / 1000),
          createTransport: device => new WebBluetoothTransport(device as never),
          onNodeStart: (index, total, input) => {
            if (stillActive()) setBatchProgress({ done: index, total, current: input.browserLabel, detail: 'Verbinde mit Node …' });
          },
          onNodeUpdate: (index, total, input, view: CollectorViewState) => {
            if (!stillActive()) return;
            if (view.credentialState) setCredential(view.credentialState);
            setBatchProgress({ done: index, total, current: input.browserLabel, detail: view.nodeMessage || 'Synchronisiere …' });
          },
          onNodeDone: (index) => {
            if (stillActive()) setBatchProgress(previous => previous ? { ...previous, done: index + 1 } : previous);
          },
        });
        if (!orgStillActive() || !organizationId) return;
        const merged = previous.map(old => retried.find(r => r.browserId === old.browserId) ?? old);
        setBatchResults(merged);
        const succeededNodeIds = merged.filter(r => r.kind === 'success' && r.nodeId).map(r => r.nodeId as string);
        if (succeededNodeIds.length && !operation.signal.aborted) await loadContextsFor(succeededNodeIds, organizationId, operation.signal);
        // Issue #161: retried syncs also recover CLAIMED nodes without an
        // initial deployment. Probe failures never invalidate the retry.
        if (succeededNodeIds.length && !operation.signal.aborted) {
          try {
            const missing = await findMissingInitialSetup(organizationId, succeededNodeIds, operation.signal);
            if (missing && stillActive()) {
              setSetupNodeId(missing.nodeId);
              setSetupOrgId(missing.organizationId);
              rememberSetupHint(missing.organizationId, missing.nodeId);
              setResumeCandidate(null);
              setBatchNotice('Napf registriert, Einrichtung offen: Futterstellen-Zuordnung kann direkt abgeschlossen werden.');
            }
          } catch {
            // Best-effort only.
          }
        }
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
    if (permissionRequest.current || claimBusy || registrationCandidate || setupNodeId) return;
    if (!state.user || !state.activeOrganizationId) {
      setFreigebenMessage('Bitte anmelden und Organisation wählen.');
      return;
    }
    if (bluetoothCapability() !== 'supported') {
      setFreigebenMessage('Dieser Browser unterstützt kein Web Bluetooth. Bitte aktuelles Chrome/Chromium auf Android verwenden.');
      return;
    }
    setFreigebenBusy(true);
    permissionRequest.current = true;
    const generation = contextGeneration.current;
    const stillActive = () => mounted.current && generation === contextGeneration.current;
    setFreigebenMessage('');
    // requestNodeDevice() is invoked synchronously in this click handler to
    // preserve transient user activation; no IDB/network await runs before it.
    const request = requestNodeDevice();
    void request.then(device => {
      if (!stillActive()) return;
      const id = browserDeviceId(device, `node-${Date.now()}`);
      const name = browserDeviceLabel(device, 'Freigegebener Node');
      sessionSelectedDevices.current = [...sessionSelectedDevices.current.filter(entry => entry.id !== id), { id, name, device }];
      setDevices(previous => {
        if (previous.some(entry => entry.id === id)) return previous.map(entry => entry.id === id ? { id, name, device } : entry);
        return [...previous, { id, name, device }];
      });
      if (batchController.current) {
        setFreigebenMessage('Napf für diesen Browser freigegeben und für den nächsten Auslesevorgang vorgemerkt.');
        return;
      }
      setFreigebenMessage('Napf für diesen Browser freigegeben. Auslesen startet …');
      void startBatch([{ device, browserId: id, browserLabel: name }], true);
    }).catch((e: unknown) => {
      if (stillActive()) setFreigebenMessage(bluetoothErrorMessage(e, 'selection'));
    }).finally(() => { permissionRequest.current = false; if (mounted.current) setFreigebenBusy(false); });
  }, [freigebenBusy, batchRunning, claimBusy, registrationCandidate, setupNodeId, startBatch]);

  const claimUnclaimedNode = useCallback((result: BatchNodeResult) => {
    const state = getAuthState();
    const organizationId = state.activeOrganizationId;
    const nodeId = result.nodeId;
    const entry = devices.find(d => d.id === result.browserId);
    if (!nodeId || !organizationId || !entry || batchController.current || claimBusy || batchRunning) return;
    setRegistrationCandidate(null);
    const operation = new AbortController();
    batchController.current = operation;
    setClaimBusy(true);
    setClaimMessage('');
    setClaimTarget({ browserId: result.browserId, nodeId });
    const startedContext = contextKey();
    const stillActive = () => mounted.current && batchController.current === operation && !operation.signal.aborted && contextKey() === startedContext;
    const transport = new WebBluetoothTransport(entry.device as never);
    void provisionNode(transport, nodeId, organizationId, operation.signal).then(outcome => {
      if (!stillActive()) return;
      setClaimMessage(outcome?.message ?? 'Napf registriert!');
      setBatchResults(previous => previous.filter(result => result.browserId !== entry.id));
      setFreigebenMessage('');
      setSetupNodeId(nodeId);
      setSetupOrgId(organizationId);
      rememberSetupHint(organizationId, nodeId);
      setResumeCandidate(null);
    }).catch(async (e: unknown) => {
      if (!stillActive()) return;
      const originalMessage = e instanceof ClaimError ? e.message : e instanceof Error ? e.message : 'Claiming fehlgeschlagen.';
      // Issue #161: a committed receipt with a lost BLE confirmation must not
      // end as "claim failed, claim again". Recover authoritatively: if the
      // backend already owns this CLAIMED node without a deployment, offer the
      // existing wizard instead of a second cryptographic claim.
      // Foreign nodes, missing ADMIN membership and explicit cancellations
      // never recover here. The recovery awaits before the operation cleanup
      // below so stillActive() stays valid until the authoritative check
      // completes.
      const recoverable = e instanceof ClaimError
        ? (e.kind === 'uncertain' || e.kind === 'backend')
        : true;
      if (!recoverable) {
        setClaimMessage(originalMessage);
        return;
      }
      if (operation.signal.aborted) {
        setClaimMessage(originalMessage);
        return;
      }
      try {
        const missing = await findMissingInitialSetup(organizationId, [nodeId], operation.signal);
        if (!stillActive()) return;
        if (missing) {
          setClaimMessage('Napf registriert, Einrichtung offen. Claim bleibt gültig; kein erneutes Claiming erforderlich.');
          setBatchResults(previous => previous.filter(result => result.browserId !== entry.id));
          setFreigebenMessage('');
          setSetupNodeId(missing.nodeId);
          setSetupOrgId(missing.organizationId);
          rememberSetupHint(missing.organizationId, missing.nodeId);
          setResumeCandidate(null);
        } else {
          // Distinguish already-completed setups from genuine failures
          // without inventing state: a CLAIMED node with an active
          // deployment is done, everything else keeps the original error.
          try {
            const state = await fetchNodeSetupState(organizationId, nodeId, { signal: operation.signal });
            if (!stillActive()) return;
            if (state.claimed && state.activeDeployment) {
              setClaimMessage('Napf ist bereits registriert und eingerichtet. Kein erneutes Claiming erforderlich.');
              setBatchResults(previous => previous.filter(result => result.browserId !== entry.id));
            } else {
              setClaimMessage(originalMessage);
            }
          } catch {
              if (stillActive()) setClaimMessage(originalMessage);
            }
          }
        } catch {
          if (stillActive()) setClaimMessage(originalMessage);
        }
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

  useEffect(() => {
    const dialog = registrationDialog.current;
    if (!dialog) return;
    if (registrationCandidate || setupNodeId || claimBusy) {
      if (!dialog.open) dialog.showModal();
      if (registrationCandidate) declineButton.current?.focus();
    } else if (dialog.open) { dialog.close(); addButton.current?.focus(); }
  }, [registrationCandidate, setupNodeId, claimBusy]);

  const closeRegistration = () => {
    batchController.current?.abort();
    setRegistrationCandidate(null); setSetupNodeId(null); setSetupOrgId(null);
    setClaimMessage('');
  };

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
  const storageBlocked = storage === 'Lokaler Speicher nicht verfügbar';
  const authorizationBlocked = !navigator.onLine && credential.startsWith('Kein gültiges Offline-Credential');
  const needsManualSelection = devicesSupport === 'unsupported' && devices.length === 0;
  const canStart = !needsManualSelection && !batchRunning && !claimBusy && !freigebenBusy && !registrationCandidate && !setupNodeId && !storageBlocked && !authorizationBlocked && capability === 'supported' && Boolean(auth.user && auth.activeOrganizationId);

  return <section className="sync-screen" aria-label="Vor-Ort-Sync"><h1>Vor-Ort-Sync</h1>
    {storageBlocked && <Status kind="error" title="Auslesen nicht möglich" message="Der lokale Speicher ist nicht verfügbar. Bitte die App neu öffnen und den Browserspeicher prüfen."/>}
    {authorizationBlocked && <Status kind="error" title="Berechtigung fehlt" message="Bitte einmal mit Internet anmelden, damit Sie Näpfe auch offline sicher auslesen können."/>}
    {(!auth.user || !auth.activeOrganizationId) && <Status kind="error" title="Bitte anmelden und Organisation wählen"/>}
    {capability !== 'supported' && <Status {...errorStatus('Dieser Browser unterstützt kein Web Bluetooth. Bitte aktuelles Chrome/Chromium auf Android verwenden.')}/>}

    <div className="sync-primary">
      <button className="sync-button button--primary" type="button" disabled={!canStart} onClick={() => void startBatch()}
        aria-label={batchRunning ? 'Näpfe werden ausgelesen …' : 'Näpfe auslesen'} aria-describedby="sync-help">
        <Graphic src={assets.illustrations.sync} alt="" className="app-icon"/>
        <span>{batchRunning ? 'Näpfe werden ausgelesen …' : 'Näpfe auslesen'}</span>
      </button>
      <p id="sync-help">{needsManualSelection ? 'Zuerst „Weiteren Napf hinzufügen“ wählen.' : 'Bereits freigegebene Näpfe auslesen'}</p>
      <button ref={addButton} className="sync-secondary" type="button" disabled={freigebenBusy || claimBusy || Boolean(registrationCandidate || setupNodeId) || storageBlocked || authorizationBlocked || capability !== 'supported' || !auth.user || !auth.activeOrganizationId} onClick={releaseAdditionalBowl}>
        {freigebenBusy ? 'Freigabe läuft …' : 'Weiteren Napf hinzufügen'}
      </button>
    </div>
    {devicesError && <Status kind="error" title="Geräteliste nicht verfügbar" message={devicesError}/>}
    {!devicesError && devicesSupport === 'supported' && devices.length === 0 && !batchRunning && !batchResults.length && <Status kind="info" title="Noch kein Napf freigegeben" message={getDevicesFallbackMessage('supported')}/>}
    {devicesSupport === 'unsupported' && capability === 'supported' && <Status kind="info" title="Automatisches Wiederfinden nicht verfügbar" message={getDevicesFallbackMessage('unsupported')}/>}

    {batchRunning && <button type="button" onClick={cancelBatch}>Abbrechen</button>}
    {freigebenMessage && <Status kind="info" title="Browser-Freigabe" message={freigebenMessage}/>}

    {batchProgress && <Status kind="loading" title="Näpfe werden ausgelesen …"
      scope="Vor-Ort-Sync" graphic="bluetooth" progress={{ value: batchProgress.done, max: batchProgress.total }}
      longWaitMessage="Das dauert länger. Bitte Näpfe eingeschaltet lassen, Smartphone näher heranhalten und Bluetooth prüfen. Die Navigation bleibt verfügbar."
      message={`${batchProgress.done} von ${batchProgress.total} Näpfen verarbeitet`}/>}
    {batchNotice && !batchRunning && batchNotice !== getDevicesFallbackMessage(devicesSupport) &&
      <Status kind="info" title="Auslesen nicht abgeschlossen" message={batchNotice}/>}

    {!batchRunning && batchResults.length > 0 && <>
      <BatchSummary results={batchResults}/>
      {retryableCount > 0 && <button type="button" disabled={!canStart} onClick={retryFailed}>
        {`Offene Näpfe erneut versuchen (${retryableCount})`}
      </button>}
    </>}

    {claimTarget && claimMessage && <Status kind={claimMessage === 'Napf registriert!' || claimMessage.startsWith('Napf registriert;') || claimMessage.includes('Einrichtung offen') || claimMessage.includes('bereits registriert und eingerichtet') ? 'success' : 'error'} title="Napf registrieren" message={claimMessage}/>}
    {!claimTarget && claimMessage && <Status kind="info" title="Napf registrieren" message={claimMessage}/>}

    <dialog ref={registrationDialog} className="registration-dialog" aria-label="Napf registrieren"
      onCancel={event => { event.preventDefault(); closeRegistration(); }}>
      {registrationCandidate && <>
        <h2>Neuer Napf gefunden. Registrieren?</h2>
        <p>Die Registrierung benötigt Internet und eine aktive ADMIN-Mitgliedschaft. Kein Tastendruck am Napf erforderlich.</p>
        <button type="button" onClick={() => claimUnclaimedNode(registrationCandidate)}>Napf registrieren</button>
        <button ref={declineButton} type="button" onClick={closeRegistration}>Nein</button>
      </>}
      {claimBusy && <Status kind="loading" title="Napf wird sicher registriert …"/>}
      {isAdmin && auth.activeOrganizationId && setupNodeId && setupOrgId === auth.activeOrganizationId &&
        <NodeSetupWizard key={`${setupOrgId}/${setupNodeId}`} guided organizationId={auth.activeOrganizationId} nodeId={setupNodeId} onComplete={() => {
          clearSetupHint();
          setResumeCandidate(null);
          // Refresh the feeding-site context so the completed assignment is
          // visible without another manual sync (#161).
          const doneOrg = auth.activeOrganizationId;
          const doneNode = setupNodeId;
          if (doneOrg && doneNode) {
            void loadContextsFor([doneNode], doneOrg, AbortSignal.timeout(10_000)).catch(() => {
              // Context refresh is best-effort; the deployment itself is done.
            });
          }
        }}/> }
      {!registrationCandidate && <button type="button" onClick={closeRegistration}>Einrichtung schließen</button>}
    </dialog>

    {isAdmin && auth.activeOrganizationId && !setupNodeId && resumeCandidate && <div>
      <Status kind="info" title="Einrichtung noch offen" message={`Unvollständige Napf-Einrichtung gefunden. Der Claim bleibt gültig und kann ohne erneutes kryptografisches Claiming fortgesetzt werden.`}/>
      <button type="button" onClick={() => { setSetupNodeId(resumeCandidate.nodeId); setSetupOrgId(resumeCandidate.organizationId); }}>
        Einrichtung fortsetzen
      </button>
    </div>}

    {(batchResults.length > 0 || uploadView.backendState === 'failed' || uploadView.pendingUploads > 0) &&
      <UploadStatus view={uploadViewState} retry={retryUpload} disabled={batchRunning} compact/>}

    <details className="sync-technical"><summary>Technische Infos zur Entwicklung</summary>
      <Status kind={storage.includes('wird') ? 'loading' : storage.includes('nicht') ? 'error' : 'info'} title={storage}/>
      <p>{auth.user
        ? `Angemeldet als ${auth.user.email}${auth.activeOrganizationId ? ` · Organisation: ${activeMembership?.organizationName ?? auth.activeOrganizationId} (${activeMembership?.role ?? '?'})` : ' · keine Organisation gewählt'}`
        : 'Nicht angemeldet.'}</p>
      <Status kind={credential.startsWith('Offline-Credential wird geprüft') ? 'loading' : 'info'} title="Offline-Berechtigung" message={credential}/>
      {!navigator.onLine && <Status {...statusMessages.offline}/>}
      <p>Nur bereits in diesem Browser freigegebene Näpfe werden versucht. Erreichbarkeit und Berechtigung werden beim Verbindungsversuch geprüft.</p>
      <p>{devices.length} Näpfe in diesem Browser freigegeben. <button type="button" disabled={batchRunning} onClick={() => void refreshDevices().catch(() => {})}>Liste aktualisieren</button></p>
      {batchProgress && <p>Bluetooth: {batchProgress.current ? `„${batchProgress.current}“ (unverifizierter Browser-Hinweis) — ` : ''}{batchProgress.detail}</p>}
      {claimReceipt && <p>Gespeichertes Receipt vorhanden; ein abgebrochener Claim wird sicher erneut zugestellt.</p>}
      <BatchNodeList results={batchResults} contexts={batchContexts} contextsLoading={contextsLoading}
        isAdmin={isAdmin} claimBusy={claimBusy}/>
      <UploadStatus view={uploadViewState} retry={retryUpload} disabled={batchRunning}/>
      <h2>Hinweise zu Fehlerfällen</h2><ul>{syncHelp.map(message => <li key={message}>{message}</li>)}</ul>
    </details>
  </section>;
}
