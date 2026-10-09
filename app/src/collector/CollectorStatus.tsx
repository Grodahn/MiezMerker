import type { CollectorViewState } from './collector-sync';
import { Status } from '../ui/Status';
import { errorStatus, statusMessages } from '../ui/status-messages';

const phases = {
  connecting: 'Napf wird verbunden …', receiving: 'Beobachtungen werden empfangen …',
  persisting: 'Beobachtungen werden lokal gespeichert …', acknowledging: 'Übernahme wird am Napf quittiert …',
};

export function NodeStatus({ view, retry, disabled }: { view: CollectorViewState; retry: () => void; disabled: boolean }) {
  if (view.nodeState === 'idle') return null;
  if (view.fertig && view.nodeState === 'complete') return <Status {...statusMessages.localSuccess} scope="Vor-Ort-Sync"
    message={`${statusMessages.localSuccess.message}${view.nodeMessage ? ` ${view.nodeMessage}` : ''}`}>
    <p>Übernommen: {view.recordsReceived}{view.watermark ? ` · Stand: ${view.watermark}` : ''}</p>
  </Status>;
  if (view.nodeState === 'failed') {
    // Foreign/unclaimed have their own authoritative explanation and actions.
    if (view.foreign || view.unclaimed) return null;
    const status = errorStatus(view.nodeMessage);
    return <Status {...status} scope="Vor-Ort-Sync" action={status.retry
      ? { label: 'Napf erneut auswählen', onClick: retry, disabled } : undefined}/>;
  }
  return <Status kind="loading" graphic="bluetooth" scope="Vor-Ort-Sync"
    longWaitMessage="Das dauert länger. Bitte Napf einschalten, Smartphone näher heranhalten und Bluetooth prüfen. Eine offene Geräteauswahl können Sie im Browser abbrechen; die Navigation bleibt verfügbar."
    title={phases[view.nodeState as keyof typeof phases] ?? 'Übernahme wird geprüft …'}
    message={view.nodeMessage || 'Bitte Smartphone in der Nähe des Napfes halten.'}>
    {view.recordsReceived > 0 && <p>Empfangen: {view.recordsReceived} — lokale Übernahme noch nicht bestätigt.</p>}
  </Status>;
}

export function UploadStatus({ view, retry, disabled }: { view: CollectorViewState; retry: () => void; disabled: boolean }) {
  const status = view.backendState === 'uploading'
    ? { kind: 'loading' as const, title: 'Backend-Upload läuft …', message: view.backendMessage }
    : view.backendState === 'waiting-for-network'
      ? { ...statusMessages.uploadPending, kind: 'offline' as const, title: 'Upload wartet auf Verbindung', message: view.backendMessage || statusMessages.uploadPending.message }
      : view.backendState === 'failed' ? errorStatus(view.backendMessage)
        : view.backendState === 'complete' && view.pendingUploads === 0
          ? view.uploadedCount > 0 ? { ...statusMessages.uploadSuccess, message: view.backendMessage || statusMessages.uploadSuccess.message }
            : statusMessages.noUploads
          : view.backendState === 'idle' ? statusMessages.uploadReady : statusMessages.uploadPending;
  const canRetry = view.backendState === 'idle' || view.backendState === 'waiting-for-network' ||
    (view.backendState === 'complete' && view.pendingUploads > 0) ||
    (view.backendState === 'failed' && 'retry' in status && status.retry === true);
  const snapshot = view.backendState === 'complete';
  const countsKnown = ['complete', 'waiting-for-network'].includes(view.backendState) || view.pendingUploads > 0 || view.uploadedCount > 0;
  return <Status {...status} scope={snapshot ? 'Backend-Upload · Letzter geprüfter Uploadstand (gesamte Organisation)' : 'Backend-Upload (gesamte Organisation)'}
    action={canRetry ? { label: 'Backend-Upload erneut versuchen', onClick: retry, disabled } : undefined}>
    {countsKnown && <p>{snapshot ? '' : 'Zuletzt geprüft: '}Ausstehend: {view.pendingUploads} · Hochgeladen: {view.uploadedCount}</p>}
    {snapshot && <p>Neu übernommene Daten werden separat geprüft. Spätere lokale Übernahmen sind durch diesen Stand noch nicht als hochgeladen bestätigt.</p>}
    {view.backendState === 'failed' && <p>Ein bestätigter Vor-Ort-Sync bleibt gültig. Noch ausstehende Daten sind nicht als Servererfolg bestätigt.</p>}
  </Status>;
}
