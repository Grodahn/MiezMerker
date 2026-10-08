import { useEffect, useState } from 'react';
import {
  ensureInitialDeployment, fetchNodeSetupState, isSetupComplete, listFeedingSiteOptions,
  saveBowlName, SetupError, type DeploymentView, type FeedingSiteView,
} from './node-setup';

// Simple functional setup UI for #53 (visual refinement belongs to #67).
// Claim success and business setup stay visibly separate; no generic success
// is shown before the relevant backend confirmation arrives.
export function NodeSetupWizard(props: { organizationId: string; nodeId: string; onComplete?: () => void }) {
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

  const reload = async () => {
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
      setLoadError(e instanceof SetupError ? e.message : e instanceof Error ? e.message : 'Laden fehlgeschlagen.');
      return null;
    });
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
    if (isSetupComplete(state)) setComplete(true);
    try {
      const options = await listFeedingSiteOptions(organizationId);
      setSites(options);
      setSitesError('');
      if (!state.activeDeployment && !selectedSite && options.length > 0) {
        setSelectedSite(options[0]?.id ?? '');
      }
    } catch (e) {
      setSites([]);
      setSitesError(e instanceof SetupError ? e.message : e instanceof Error ? e.message : 'Futterstellen konnten nicht geladen werden.');
    } finally {
      setLoading(false);
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
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [organizationId, nodeId]);

  const submitName = async () => {
    if (nameBusy || deployBusy) return;
    setNameBusy(true);
    setNameError('');
    setNameMessage('');
    try {
      const saved = await saveBowlName(organizationId, nodeId, nameInput);
      setSavedName(saved);
      setNameInput(saved);
      setNameMessage(`Napf-Name gespeichert: ${saved}`);
      // Best-effort refresh: the save above is already confirmed, so a
      // refresh failure must not mask it as an error.
      try {
        const state = await fetchNodeSetupState(organizationId, nodeId);
        setDeployment(state.activeDeployment);
        if (isSetupComplete({ displayName: saved, activeDeployment: state.activeDeployment })) {
          setComplete(true);
          props.onComplete?.();
        }
      } catch (e) {
        setNameError(e instanceof SetupError ? e.message : e instanceof Error ? e.message : 'Status-Refresh fehlgeschlagen.');
      }
    } catch (e) {
      setNameError(e instanceof SetupError ? e.message : e instanceof Error ? e.message : 'Speichern fehlgeschlagen.');
    } finally {
      setNameBusy(false);
    }
  };

  const submitDeployment = async () => {
    if (nameBusy || deployBusy) return;
    setDeployBusy(true);
    setDeployError('');
    setDeployMessage('');
    try {
      const outcome = await ensureInitialDeployment(organizationId, nodeId, selectedSite);
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
        setSavedName(state.displayName);
        if (state.displayName) setNameInput(state.displayName);
        setDeployment(state.activeDeployment);
        if (isSetupComplete(state)) {
          setComplete(true);
          props.onComplete?.();
        }
      } catch (e) {
        setDeployError(e instanceof SetupError ? e.message : e instanceof Error ? e.message : 'Status-Refresh fehlgeschlagen.');
      }
    } catch (e) {
      setDeployError(e instanceof SetupError ? e.message : e instanceof Error ? e.message : 'Zuordnung fehlgeschlagen.');
    } finally {
      setDeployBusy(false);
    }
  };

  if (loading) return <div aria-label="Napf-Einrichtung"><p role="status">Einrichtungsstatus wird geladen …</p></div>;
  if (loadError) return <div aria-label="Napf-Einrichtung">
    <p role="alert">{loadError}</p>
    <button type="button" onClick={() => void reload()}>Erneut versuchen</button>
  </div>;

  return <div aria-label="Napf-Einrichtung">
    <h2>Napf-Einrichtung</h2>
    <p>Node <code>{nodeId}</code></p>
    {claimed
      ? <p role="status"><strong>Napf registriert!</strong> Kryptografisches Claiming ist bestätigt.</p>
      : <p role="alert">Node ist noch nicht als CLAIMED bestätigt. Bitte zuerst claimen; diese Einrichtung wiederholt kein Claiming.</p>}
    {!complete && claimed && <p>Dieser Napf wurde registriert, aber noch nicht vollständig eingerichtet.</p>}
    {complete
      ? <p role="status"><strong>Einrichtung abgeschlossen!</strong>{savedName ? ` Napf „${savedName}“` : ''}{deployment ? ' ist einer Futterstelle zugeordnet.' : '.'}</p>
      : <p>Schritte: Name eingeben → Futterstelle wählen → Einrichtung abschließen.</p>}

    <div>
      <h3>1. Napf-Name</h3>
      {savedName && <p>Gespeicherter Name: <strong>{savedName}</strong> (UUID bleibt technische Identität)</p>}
      <label>Napf-Bezeichnung (z. B. „Der Grüne“)
        <input maxLength={100} value={nameInput} onChange={e => setNameInput(e.target.value)} placeholder="Der Grüne" />
      </label>
      <button type="button" disabled={nameBusy || deployBusy} onClick={() => void submitName()}>
        {nameBusy ? 'Speichere …' : 'Name speichern'}
      </button>
      {nameMessage && <p role="status">{nameMessage}</p>}
      {nameError && <p role="alert">{nameError}</p>}
    </div>

    <div>
      <h3>2. Futterstelle</h3>
      {deployment
        ? <p>Bestehende Zuordnung wird nicht überschrieben. Spätere Umzüge erfolgen im Admin-Backend.</p>
        : sitesError
          ? <div role="alert"><p>{sitesError}</p>
            <button type="button" onClick={() => void reload()}>Erneut versuchen</button></div>
          : sites.length === 0
            ? <p role="alert">Keine Futterstellen vorhanden. Bitte zuerst im Admin-Backend eine Futterstelle anlegen. Es wird keine Futterstelle in der PWA erstellt.</p>
            : <><label>Bestehende Futterstelle wählen
              <select value={selectedSite} onChange={e => setSelectedSite(e.target.value)}>
                {sites.map(s => <option key={s.id} value={s.id}>{s.name}</option>)}
              </select>
            </label>
            <button type="button" disabled={deployBusy || nameBusy || !selectedSite} onClick={() => void submitDeployment()}>
              {deployBusy ? 'Speichere …' : 'Einrichtung abschließen'}
            </button></>}
      {deployMessage && <p role="status">{deployMessage}</p>}
      {deployError && <div role="alert"><p>{deployError}</p>
        <button type="button" onClick={() => void submitDeployment()}>Erneut versuchen</button></div>}
    </div>

    <button type="button" onClick={() => void reload()}>Status neu laden</button>
  </div>;
}
