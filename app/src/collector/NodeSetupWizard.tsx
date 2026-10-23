import { errorCodes, safeFailure } from '../diagnostics/errors';
import { useEffect, useRef, useState } from 'react';
import { Status } from '../ui/Status';
import { errorStatus, statusMessages } from '../ui/status-messages';
import {
  ensureInitialDeployment, fetchNodeSetupState, isSetupComplete, listFeedingSiteOptions,
  saveBowlName, SetupError, type DeploymentView, type FeedingSiteView,
} from './node-setup';

// Simple functional setup UI for #53 (visual refinement belongs to #67).
// Claim success and business setup stay visibly separate; no generic success
// is shown before the relevant backend confirmation arrives.
export function NodeSetupWizard(props: { organizationId: string; nodeId: string; onComplete?: () => void; guided?: boolean }) {
  const { organizationId, nodeId } = props;
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');
  const [claimed, setClaimed] = useState(false);
  const [savedName, setSavedName] = useState<string | null>(null);
  const [nameInput, setNameInput] = useState('');
  const [nameBusy, setNameBusy] = useState(false);
  const [nameMessage, setNameMessage] = useState('');
  const [nameError, setNameError] = useState('');
  const [sites, setSites] = useState<FeedingSiteView[]>([]);
  const [sitesError, setSitesError] = useState('');
  const [selectedSite, setSelectedSite] = useState('');
  const [deployment, setDeployment] = useState<DeploymentView | null>(null);
  const [deployBusy, setDeployBusy] = useState(false);
  const [deployMessage, setDeployMessage] = useState('');
  const [deployError, setDeployError] = useState('');
  const [complete, setComplete] = useState(false);

  const revision = useRef(0);
  const reload = async () => {
    const current = ++revision.current;
    setLoading(true);
    setLoadError('');
    setNameMessage('');
    setNameError('');
    setDeployMessage('');
    setDeployError('');
    setSitesError('');
    // The node state is authoritative and required; the site list is needed
    // only for selection. A site-list failure must not hide an otherwise
    // resumable name/deployment state (offline name saving stays possible).
    const state = await fetchNodeSetupState(organizationId, nodeId).catch((e: unknown) => {
      if (current !== revision.current) return null;
      setLoadError(e instanceof SetupError ? e.message : safeFailure(e, 'Laden fehlgeschlagen.'));
      return null;
    });
    if (current !== revision.current) return;
    if (!state) {
      setLoading(false);
      return;
    }
    setClaimed(state.claimed);
    setSavedName(state.displayName);
    // Preserve an already saved name across interruptions (Scenario B):
    // prefill it so a retry never discards it.
    setNameInput(state.displayName ?? '');
    setDeployment(state.activeDeployment);
    setComplete(isSetupComplete(state));
    if (isSetupComplete(state)) props.onComplete?.();
    try {
      const options = await listFeedingSiteOptions(organizationId);
      if (current !== revision.current) return;
      setSites(options);
      setSitesError('');
      if (!state.activeDeployment && !selectedSite && options.length > 0) {
        setSelectedSite(options[0]?.id ?? '');
      }
    } catch (e) {
      if (current !== revision.current) return;
      setSites([]);
      setSitesError(e instanceof SetupError ? e.message : safeFailure(e, 'Futterstellen konnten nicht geladen werden.'));
    } finally {
      if (current === revision.current) setLoading(false);
    }
  };

  useEffect(() => {
    setLoading(true);
    setLoadError('');
    setNameMessage('');
    setNameError('');
    setDeployMessage('');
    setDeployError('');
    setSitesError('');
    setComplete(false);
    setSavedName(null);
    setNameInput('');
    setDeployment(null);
    setSites([]);
    setSelectedSite('');
    void reload();
    return () => { revision.current++; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [organizationId, nodeId]);

  const submitName = async () => {
    if (nameBusy || deployBusy) return;
    const current = revision.current;
    setNameBusy(true);
    setNameError('');
    setNameMessage('');
    try {
      const saved = await saveBowlName(organizationId, nodeId, nameInput);
      if (current !== revision.current) return;
      setSavedName(saved);
      setNameInput(saved);
      setNameMessage(`Napf-Name gespeichert: ${saved}`);
      // Best-effort refresh: the save above is already confirmed, so a
      // refresh failure must not mask it as an error.
      try {
        const state = await fetchNodeSetupState(organizationId, nodeId);
        if (current !== revision.current) return;
        setDeployment(state.activeDeployment);
        if (isSetupComplete({ displayName: saved, activeDeployment: state.activeDeployment })) {
          setComplete(true);
          props.onComplete?.();
        }
      } catch (e) {
        setNameError(e instanceof SetupError ? e.message : safeFailure(e, 'Status-Refresh fehlgeschlagen.'));
      }
    } catch (e) {
      setNameError(e instanceof SetupError ? e.message : safeFailure(e, 'Speichern fehlgeschlagen.'));
    } finally {
      setNameBusy(false);
    }
  };

  const submitDeployment = async () => {
    if (nameBusy || deployBusy) return;
    const current = revision.current;
    setDeployBusy(true);
    setDeployError('');
    setDeployMessage('');
    try {
      if (props.guided && nameInput.trim() && nameInput.trim() !== savedName) {
        const saved = await saveBowlName(organizationId, nodeId, nameInput);
        if (current !== revision.current) return;
        setSavedName(saved); setNameInput(saved);
      }
      const outcome = await ensureInitialDeployment(organizationId, nodeId, selectedSite);
      if (current !== revision.current) return;
      setDeployment(outcome.deployment);
      if (outcome.alreadyAssigned && !outcome.created) {
        setDeployMessage('Napf ist bereits einer Futterstelle zugeordnet. Bestehende Zuordnung wird angezeigt und nicht überschrieben.');
      } else {
        setDeployMessage('Einrichtung abgeschlossen!');
      }
      // Best-effort refresh: the assignment above is already confirmed
      // (created or deduplicated to the persisted state).
      try {
        const state = await fetchNodeSetupState(organizationId, nodeId);
        if (current !== revision.current) return;
        setSavedName(state.displayName);
        if (state.displayName) setNameInput(state.displayName);
        setDeployment(state.activeDeployment);
        if (isSetupComplete(state)) {
          setComplete(true);
          props.onComplete?.();
        }
      } catch (e) {
        setDeployError(e instanceof SetupError ? e.message : safeFailure(e, 'Status-Refresh fehlgeschlagen.'));
      }
    } catch (e) {
      setDeployError(e instanceof SetupError ? e.message : safeFailure(e, 'Zuordnung fehlgeschlagen.'));
    } finally {
      setDeployBusy(false);
    }
  };

  const loadFailure = errorStatus(loadError);
  const sitesFailure = errorStatus(sitesError);
  const deployFailure = errorStatus(deployError);
  const reloadDeployment = Boolean(deployment) || /neu laden/i.test(deployError);
  if (loading) return <div aria-label="Napf-Einrichtung"><Status kind="loading" title="Einrichtungsstatus wird geladen …"/></div>;
  if (loadError) return <div aria-label="Napf-Einrichtung">
    <Status {...loadFailure} action={loadFailure.retry ? { label: 'Erneut versuchen', onClick: () => void reload() } : undefined}/>
  </div>;
  if (props.guided && complete) return <div aria-label="Napf-Einrichtung">
    <Status kind="success" title="Einrichtung abgeschlossen!"
      message={`${savedName ? `Napf „${savedName}“` : 'Der Napf'} ist einer Futterstelle zugeordnet und kann beim nächsten Auslesen mitmachen.`}/>
  </div>;

  return <div aria-label="Napf-Einrichtung">
    <h2>Napf-Einrichtung</h2>
    {!props.guided && <p>Node <code>{nodeId}</code></p>}
    {claimed
      ? <Status kind="success" title="Napf registriert!" message="Der Napf wurde sicher registriert."/>
      : <Status kind="error" code={errorCodes.SYNC_UNCLAIMED} title="Napf noch nicht registriert" message="Node ist noch nicht als CLAIMED bestätigt. Bitte zuerst claimen; diese Einrichtung wiederholt kein Claiming."/>}
    {!complete && claimed && <Status kind="info" title="Einrichtung noch offen" message="Dieser Napf wurde registriert, aber noch nicht vollständig eingerichtet."/>}
    {complete
      ? <Status kind="success" title="Einrichtung abgeschlossen!" message={`${savedName ? `Napf „${savedName}“` : 'Napf'}${deployment ? ' ist einer Futterstelle zugeordnet.' : '.'}`}/>
      : <p>Optional einen Namen eingeben, Futterstelle wählen und Einrichtung abschließen.</p>}

    <div>
      <h3>1. Napf-Name</h3>
      {savedName && <p>Gespeicherter Name: <strong>{savedName}</strong></p>}
      <label>Napf-Bezeichnung (z. B. „Der Grüne“)
        <input autoFocus={props.guided} disabled={nameBusy || deployBusy} maxLength={100} value={nameInput} onChange={e => setNameInput(e.target.value)} placeholder="Der Grüne" />
      </label>
      {!props.guided && <button type="button" disabled={nameBusy || deployBusy} onClick={() => void submitName()}>
        {nameBusy ? 'Speichere …' : 'Name speichern'}
      </button>}
      {nameBusy && <Status kind="loading" title="Name wird gespeichert …"/>}
      {nameMessage && <Status kind="success" title="Name gespeichert" message={nameMessage}/>}
      {nameError && <Status {...errorStatus(nameError)}/>}
    </div>

    <div>
      <h3>2. Futterstelle</h3>
      {deployment
        ? <p>Bestehende Zuordnung wird nicht überschrieben. Spätere Umzüge erfolgen im Admin-Backend.</p>
        : sitesError
          ? <Status {...sitesFailure} action={sitesFailure.retry ? { label: 'Erneut versuchen', onClick: () => void reload(), disabled: deployBusy || nameBusy } : undefined}/>
          : sites.length === 0
            ? <Status {...statusMessages.emptySites}/>
            : <><label>Futterstelle auswählen
              <select disabled={deployBusy || nameBusy} value={selectedSite} onChange={e => setSelectedSite(e.target.value)}>
                {sites.map(s => <option key={s.id} value={s.id}>{s.name}</option>)}
              </select>
            </label>
            <button type="button" disabled={deployBusy || nameBusy || !selectedSite} onClick={() => void submitDeployment()}>
              {deployBusy ? 'Speichere …' : 'Einrichtung abschließen'}
            </button></>}
      {deployBusy && <Status kind="loading" title="Zuordnung wird gespeichert …"/>}
      {deployMessage && <Status kind="success" title="Zuordnung gespeichert" message={deployMessage}/>}
      {deployError && <Status {...deployFailure} action={deployFailure.retry ? {
        label: reloadDeployment ? 'Einrichtungsstatus erneut laden' : 'Erneut versuchen',
        onClick: () => void (reloadDeployment ? reload() : submitDeployment()), disabled: deployBusy || nameBusy,
      } : undefined}/>}
    </div>

    <button type="button" disabled={deployBusy || nameBusy} onClick={() => void reload()}>Status neu laden</button>
  </div>;
}
