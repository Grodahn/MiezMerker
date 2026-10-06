import type { ReactNode } from 'react';
import type { components } from '../api/generated';

export type Site = components['schemas']['FeedingSiteView'];
export type Node = components['schemas']['NodeView'];
export type Cat = components['schemas']['CatView'];
export type Deployment = components['schemas']['DeploymentView'];
export type Visit = components['schemas']['VisitView'];
export type Activity = components['schemas']['ChipActivityView'];

export function time(value?: string | null): string {
  if (!value) return 'Unbekannt';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? 'Ungültige Zeit' : date.toLocaleString('de-DE');
}
export function millis(value?: string | null): string {
  if (!value) return 'Unbekannt';
  try {
    const raw = BigInt(value);
    if (raw <= 0n || raw >= 9224318016000000n) return `Ungültige Zeit (Rohwert: ${value})`;
    const date = new Date(Number(raw));
    return Number.isNaN(date.getTime()) ? `Nicht darstellbar (Rohwert: ${value})` : date.toLocaleString('de-DE');
  } catch { return `Ungültige Zeit (Rohwert: ${value})`; }
}
export function duration(visit: Visit) {
  try {
    const difference = BigInt(visit.endAtMillis!) - BigInt(visit.startAtMillis!);
    return difference < 0n ? 'Unbekannt' : `${difference / 1000n}${difference % 1000n ? ',' + String(difference % 1000n).padStart(3, '0') : ''} s`;
  } catch { return 'Unbekannt'; }
}
export function localNow() {
  const date = new Date();
  return new Date(date.getTime() - date.getTimezoneOffset() * 60_000).toISOString().slice(0, 19);
}
export function currentDeployment(history: Deployment[], nodeId?: string) {
  const now = Date.now();
  return history.find(d => d.nodeId === nodeId && Date.parse(d.validFrom ?? '') <= now &&
    (!d.validUntil || Date.parse(d.validUntil) > now));
}
export function siteName(sites: Site[], id?: string) {
  return id ? sites.find(site => site.id === id)?.name ?? 'Futterstelle nicht verfügbar' : 'Nicht zugeordnet';
}
export function catName(cats: Cat[], chip?: string) {
  return cats.find(cat => cat.chipId === chip)?.name || 'Unbekannte Katze';
}
export function LoadState({ loading, error, reload }: { loading: boolean; error?: string; reload: () => void }) {
  return <>{loading && <p role="status">Daten werden geladen …</p>}
    {error && <div role="alert"><p>{error}</p><button onClick={reload}>Erneut versuchen</button></div>}</>;
}
export function Feedback({ error, message }: { error: string; message: string }) {
  return <>{error && <p role="alert">{error}</p>}{message && <p role="status">{message}</p>}</>;
}
export function Table({ label, headings, children }: { label: string; headings: string[]; children: ReactNode }) {
  return <div className="table-scroll" role="region" aria-label={label} tabIndex={0}>
    <table><caption className="sr-only">{label}</caption><thead><tr>
      {headings.map(h => <th scope="col" key={h}>{h}</th>)}
    </tr></thead><tbody>{children}</tbody></table></div>;
}
