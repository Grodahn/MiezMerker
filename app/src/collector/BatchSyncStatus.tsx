// Batch result presentation for issue #76.
//
// Uses the shared Status components (#65) and the centralized asset registry
// (#64). No business logic, no BLE, no backend fetches here: callers own
// truth, retries and operation lifetime.
//
// Rules:
// - Feeding-site/cat context appears only for confirmed successes and only
//   when reliably resolved (BatchNodeContext). Never invented.
// - BLE browser labels are untrusted hints, never bowl names. Bowl names come
//   from BatchNodeContext.displayName (backend Node.displayName).
// - Local success ("Napf ausgelesen") requires durable persist + ACK, which
//   the orchestrator guarantees before reporting kind=success.

import type { BatchNodeContext } from './batch-context';
import type { BatchNodeResult } from './batch-sync';
import { batchSummaryMessage } from './batch-sync';
import { assets } from '../assets';
import { Graphic } from '../ui/Graphic';
import { Status } from '../ui/Status';
import { time } from '../management/common';

function bowlTitle(context: BatchNodeContext | undefined, result: BatchNodeResult): string {
  const name = context?.displayName?.trim();
  if (name) return `Napf ${name}`;
  if (result.nodeId) return 'Napf ohne Namen';
  return `Browser-Gerät „${result.browserLabel}“ (nicht verifiziert)`;
}

function kindStatus(kind: BatchNodeResult['kind']): { title: string; kind: 'success' | 'error' | 'info' | 'offline' | 'loading' | 'empty' } {
  switch (kind) {
    case 'success': return { title: 'Napf ausgelesen', kind: 'success' };
    case 'unclaimed': return { title: 'Napf noch nicht registriert', kind: 'info' };
    case 'foreign': return { title: 'Napf gehört einer anderen Organisation', kind: 'error' };
    case 'unauthorized': return { title: 'Berechtigung prüfen', kind: 'error' };
    case 'unreachable': return { title: 'Napf nicht erreichbar', kind: 'error' };
    case 'cancelled': return { title: 'Auslesen abgebrochen', kind: 'info' };
    case 'skipped': return { title: 'Übersprungen', kind: 'info' };
    default: return { title: 'Vorgang nicht abgeschlossen', kind: 'error' };
  }
}

export function BatchSummary({ results }: { results: BatchNodeResult[] }) {
  if (!results.length) return null;
  const succeeded = results.filter(r => r.kind === 'success').length;
  const interrupted = results.some(r => r.kind === 'cancelled' || r.kind === 'skipped');
  const total = results.length;
  const message = batchSummaryMessage(results);
  if (succeeded === total) {
    const records = results.reduce((sum, r) => sum + r.recordsReceived, 0);
    return <Status kind="success" title={message} scope="Vor-Ort-Sync"
      message={`Fertig — ${records} Beobachtungen sicher übernommen und quittiert (einschließlich möglicher Wiederholungen). Der Backend-Upload wird separat angezeigt.`}
      graphic="success"/>;
  }
  if (succeeded === 0) {
    return <Status kind={interrupted ? 'info' : 'error'} title={message} scope="Vor-Ort-Sync"
      message={interrupted ? 'Sammel-Sync unterbrochen. Für keinen Napf wurde eine vollständige lokale Übernahme bestätigt. Offene Näpfe können gezielt erneut versucht werden.'
        : 'Kein Napf konnte ausgelesen werden. Details je Napf unten; bereits abgeschlossene Näpfe bleiben bei Retry erhalten.'}
      graphic={interrupted ? 'info' : 'error'}/>;
  }
  return <Status kind="info" title={message} scope="Vor-Ort-Sync"
    message={interrupted ? 'Sammel-Sync unterbrochen. Bestätigte Übernahmen bleiben gültig. Offene Näpfe können gezielt erneut versucht werden, ohne erfolgreiche zu wiederholen.'
      : 'Teilerfolg: übrige Näpfe wurden weiter bearbeitet. Fehlgeschlagene Näpfe können gezielt erneut versucht werden, ohne erfolgreiche zu wiederholen.'}
    graphic="info"/>;
}

export function BatchNodeCard({ result, context, contextLoading, isAdmin, claimBusy, onClaim }: {
  result: BatchNodeResult;
  context?: BatchNodeContext;
  contextLoading?: boolean;
  isAdmin?: boolean;
  claimBusy?: boolean;
  onClaim?: (result: BatchNodeResult) => void;
}) {
  const status = kindStatus(result.kind);
  if (result.kind === 'success') {
    const title = bowlTitle(context, result);
    const detail = result.nodeId ? ` (${result.nodeId})` : '';
    const records = result.recordsReceived === 0
      ? 'Keine neuen Beobachtungen.'
      : `${result.recordsReceived} Beobachtungen sicher übernommen und quittiert${result.watermark ? ` (Stand ${result.watermark})` : ''}.`;
    return <Status kind="success" title={`${title} ausgelesen`} scope="Vor-Ort-Sync" graphic="success"
      message={`${records}${detail}`}>
      <p><Graphic src={assets.placeholders.bowl} alt="" className="avatar"/>{
        context?.displayName ? `Napf „${context.displayName}“` : 'Napf ohne Namen'
      }{result.nodeId ? <> · <code>{result.nodeId}</code></> : null}</p>
      {contextLoading && <p>Futterstellenkontext wird geladen …</p>}
      {!contextLoading && context?.contextAvailable && context.siteId && context.siteName && <>
        <p><Graphic src={assets.placeholders.feedingSite} alt="" className="avatar"/> Futterstelle: <a href={`/feeding-sites/${encodeURIComponent(context.siteId)}`}>{context.siteName}</a></p>
        {context.activity && context.activity.length > 0 && <ul>
          {context.activity.map(row => {
            const chip = row.chipId ?? '';
            const catLabel = row.catName?.trim() ? row.catName : row.catId ? 'Ohne Namen' : 'Unbekannter Chip';
            return <li key={chip}><Graphic src={assets.placeholders.cat} alt="" className="avatar"/>{catLabel} (<code>{chip}</code>) — {row.lastReliableSightingAt
              ? `zuletzt verlässlich gesehen: ${time(row.lastReliableSightingAt)}`
              : 'keine verlässliche Sichtungszeit (UNKNOWN oder ungültige Uhrzeit)'} · Serverempfang (keine Sichtungszeit): {time(row.lastReceivedAt)}</li>;
          })}
        </ul>}
        {context.activity && context.activity.length === 0 && <p>Noch keine verlässlichen Sichtungen an dieser Futterstelle.</p>}
        {context.activity === null && <p>Katzenkontext derzeit nicht verfügbar (offline oder keine Berechtigung). Lokale Übernahme bleibt gültig.</p>}
        <p><a href={`/feeding-sites/${encodeURIComponent(context.siteId)}`}>Details zur Futterstelle</a> · <a href="/cats">Alle Katzen und Chips ansehen</a></p>
      </>}
      {!contextLoading && context?.contextAvailable && !context.siteId && <p>Noch keiner Futterstelle zugeordnet.</p>}
      {!contextLoading && (!context || !context.contextAvailable) && <p>{context?.contextNote ?? 'Futterstellenkontext derzeit nicht verfügbar. Lokale Übernahme bleibt gültig.'}</p>}
    </Status>;
  }
  if (result.kind === 'unclaimed') {
    return <Status kind="info" title="Napf noch nicht registriert" scope="Vor-Ort-Sync" graphic="bluetooth"
      message={result.message || 'Dieser Napf ist noch nicht registriert. Die Einrichtung benötigt einen ADMIN.'}>
      {result.nodeId && <p><code>{result.nodeId}</code></p>}
      {!isAdmin
        ? <p>Nur ADMIN kann einen UNCLAIMED Node claimen. MEMBER hat keinen Zugriff.</p>
        : onClaim ? <p><button type="button" disabled={claimBusy} onClick={() => onClaim(result)}>Node claimen (ADMIN)</button></p>
          : <p>Physischen Claim-Modus am Node aktivieren (Taste/Power-On-Geste), dann claimen.</p>}
    </Status>;
  }
  if (result.kind === 'foreign') {
    return <Status kind="error" title={status.title} scope="Vor-Ort-Sync"
      message={`${result.message} Keine Beobachtungen abgerufen.`}/>;
  }
  if (result.kind === 'cancelled' || result.kind === 'skipped') {
    return <Status kind="info" title={status.title} scope="Vor-Ort-Sync" message={result.message}/>;
  }
  return <Status kind="error" title={status.title} scope="Vor-Ort-Sync" message={result.message || 'Vorgang nicht abgeschlossen.'}>
    <p>Browser-Gerät: „{result.browserLabel}“ (unverifizierter Hinweis){result.nodeId ? <> · Node: <code>{result.nodeId}</code></> : null}</p>
    {result.kind === 'unauthorized' && <p>Bitte mit Internet im Konto anmelden und Offline-Berechtigung erneuern, dann erneut versuchen.</p>}
    {result.kind === 'unreachable' && <p>Bitte Napf einschalten, Smartphone näher heranhalten und Bluetooth prüfen. Bereits gespeicherte Records werden idempotent wiederholt.</p>}
  </Status>;
}

export function BatchNodeList(props: {
  results: BatchNodeResult[];
  contexts: Map<string, BatchNodeContext>;
  contextsLoading?: boolean;
  isAdmin?: boolean;
  claimBusy?: boolean;
  onClaim?: (result: BatchNodeResult) => void;
}) {
  if (!props.results.length) return null;
  return <div aria-label="Ergebnisse je Napf">
    {props.results.map(result => <BatchNodeCard key={result.browserId} result={result}
      context={result.nodeId ? props.contexts.get(result.nodeId) : undefined}
      contextLoading={props.contextsLoading}
      isAdmin={props.isAdmin} claimBusy={props.claimBusy} onClaim={props.onClaim}/>)}
  </div>;
}
