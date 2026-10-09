import { useCallback, useEffect, useState } from 'react';
import { flushSync } from 'react-dom';
import { assets } from '../assets';
import { api } from '../api/client';
import { useManagementContext } from '../management/context';
import { requestOptions, result, useLoad } from '../management/data';
import { LoadState, time, type Deployment, type Node, type Site } from '../management/common';
import type { components } from '../api/generated';
import { Graphic } from '../ui/Graphic';
import { Card } from '../ui/primitives';
import { Status } from '../ui/Status';
import { statusMessages } from '../ui/status-messages';
import { documentNavigation } from '../ui/navigation';
import './feeding-sites.css';

export type FeedingSiteActivity = components['schemas']['FeedingSiteCatActivityView'];

export type FeedingSiteRoute =
  | { kind: 'overview' }
  | { kind: 'detail'; siteId: string }
  | { kind: 'unknown' };

export function parseFeedingSitePath(path: string): FeedingSiteRoute {
  if (path === '/feeding-sites' || path === '/feeding-sites/') return { kind: 'overview' };
  if (path.startsWith('/feeding-sites/')) {
    const rest = path.slice('/feeding-sites/'.length);
    if (rest && !rest.includes('/')) {
      try {
        const siteId = decodeURIComponent(rest);
        if (siteId) return { kind: 'detail', siteId };
      } catch {
        return { kind: 'unknown' };
      }
    }
    return { kind: 'unknown' };
  }
  return { kind: 'unknown' };
}

// Intentional legacy handling for #68: the technical node list is replaced by
// the feeding-site overview. Node identities cannot be mapped to a single
// current site (bowls move, history stays frozen), so every /nodes deep link
// lands on the canonical overview instead of guessing a site.
export function isLegacyNodesPath(path: string): boolean {
  return path === '/nodes' || path.startsWith('/nodes/');
}

function isCurrentlyAssigned(deployment: Deployment, siteId: string, now: number): boolean {
  if (deployment.feedingSiteId !== siteId) return false;
  const from = Date.parse(deployment.validFrom ?? '');
  if (!Number.isFinite(from) || from > now) return false;
  if (!deployment.validUntil) return true;
  const until = Date.parse(deployment.validUntil);
  return Number.isFinite(until) && until > now;
}

export function currentlyAssignedDeployments(history: Deployment[], siteId?: string): Deployment[] {
  if (!siteId) return [];
  const now = Date.now();
  return history.filter(d => isCurrentlyAssigned(d, siteId, now));
}

function siteDisplayName(site: Site): string {
  const name = site.name?.trim();
  return name ? name : 'Futterstelle ohne Namen';
}

function bowlDisplayName(node?: Node): string | null {
  const name = node?.displayName?.trim();
  return name ? name : null;
}

export function FeedingSitesOverview({ organizationId }: { organizationId: string }) {
  const list = useLoad(useCallback(async (signal: AbortSignal) => {
    const options = requestOptions(signal);
    const [sites, deployments, nodes] = await Promise.all([
      api.GET('/api/v1/organizations/{organizationId}/feeding-sites', { ...options, params: { path: { organizationId } } }).then(result),
      api.GET('/api/v1/organizations/{organizationId}/deployments', { ...options, params: { path: { organizationId } } }).then(result),
      api.GET('/api/v1/nodes', { ...options, params: { query: { organizationId } } }).then(result),
    ]);
    return { sites, deployments, nodes };
  }, [organizationId]));
  const data = list.data;
  return <section aria-labelledby="feeding-sites-title" className="feeding-sites-overview">
    <h1 id="feeding-sites-title">Futterstellen</h1>
    <p>Futterstellen der aktiven Organisation. Namen stehen im Vordergrund; Details öffnen die jeweilige Futterstelle.</p>
    <div className="actions"><button type="button" onClick={list.reload}>Aktualisieren</button></div>
    <LoadState {...list}/>
    {data && (data.sites.length ? <ul className="feeding-sites-grid">
      {[...data.sites].sort((a, b) => siteDisplayName(a).localeCompare(siteDisplayName(b), 'de'))
        .map(site => {
          const assigned = currentlyAssignedDeployments(data.deployments, site.id);
          // A site without an id cannot be deep-linked; render its name
          // without a link instead of guessing a broken href.
          const detailHref = site.id ? `/feeding-sites/${encodeURIComponent(site.id)}` : undefined;
          return <li key={site.id ?? siteDisplayName(site)}>
            <Card className="feeding-site-card">
              <Graphic src={assets.placeholders.feedingSite} alt="" className="feeding-site-illustration"/>
              <h2>{detailHref ? <a className="feeding-site-name-link" href={detailHref}>{siteDisplayName(site)}</a> : siteDisplayName(site)}</h2>
              {site.locationLabel?.trim() && <p>{site.locationLabel.trim()}</p>}
              {site.description?.trim() && <p><small>{site.description.trim()}</small></p>}
              <p><small>{assigned.length === 0 ? 'Aktuell keine Näpfe zugeordnet.'
                : assigned.length === 1 ? '1 Napf zugeordnet.'
                : `${assigned.length} Näpfe zugeordnet.`}</small></p>
            </Card>
          </li>;
        })}
    </ul> : <Status {...statusMessages.emptySites} graphic="feedingSite"/>)}
  </section>;
}

function BowlList({ deployments, nodes, siteId }: { deployments: Deployment[]; nodes: Node[]; siteId: string }) {
  const assigned = currentlyAssignedDeployments(deployments, siteId)
    .map(deployment => ({ deployment, node: nodes.find(n => n.nodeId === deployment.nodeId) }))
    // Named bowls first (human-readable names foreground per #50), unnamed last; node id breaks ties.
    .sort((a, b) => (bowlDisplayName(a.node) ?? '\uffff').localeCompare(bowlDisplayName(b.node) ?? '\uffff', 'de')
      || (a.node?.nodeId ?? a.deployment.nodeId ?? '').localeCompare(b.node?.nodeId ?? b.deployment.nodeId ?? ''));
  if (!assigned.length) {
    return <Status kind="empty" title="Keine Näpfe zugeordnet."
      message="Dieser Futterstelle ist aktuell kein Napf zugeordnet. Die Zuordnung erfolgt im Admin-Backend."
      graphic="node"/>;
  }
  return <ul className="bowl-list">
    {assigned.map(({ deployment, node }) => {
      const label = bowlDisplayName(node);
      const technicalId = node?.nodeId ?? deployment.nodeId ?? '';
      return <li key={deployment.id ?? technicalId}>
        <Card className="bowl-card">
          <Graphic src={assets.placeholders.bowl} alt="" className="avatar"/>
          <div className="bowl-content">
            <p className="bowl-name">{label ?? 'Ohne Namen'}{technicalId && <><br /><code>{technicalId}</code></>}</p>
          </div>
        </Card>
      </li>;
    })}
  </ul>;
}

function CatActivityList({ activity }: { activity: FeedingSiteActivity[] }) {
  if (!activity.length) {
    return <Status kind="empty" title="Noch keine Sichtungen an dieser Futterstelle."
      message="Nach verlässlichen Besuchen erscheinen Katzen und Chips hier. Der Serverempfang allein ist keine Sichtungszeit."
      graphic="cat"/>;
  }
  return <>
    <ul className="cat-list">
      {activity.map((row, index) => {
        const chip = row.chipId ?? '';
        const trimmedName = row.catName?.trim();
        const catLabel = trimmedName ? trimmedName
          : row.catId ? 'Ohne Namen' : 'Unbekannter Chip';
        return <li key={chip || row.catId || `row-${index}`}>
          <Card className="cat-card">
            <Graphic src={assets.placeholders.cat} alt="" className="avatar"/>
            <div className="cat-content">
              <p className="cat-name">{catLabel}<br /><code>{chip}</code></p>
              {row.lastReliableSightingAt
                ? <p>Zuletzt verlässlich gesehen: {time(row.lastReliableSightingAt)}</p>
                : <p>Keine verlässliche Sichtungszeit. {statusMessages.unknownClock.message}</p>}
              <p><small>Serverempfang (keine Sichtungszeit): {time(row.lastReceivedAt)}</small></p>
              {!!row.visitCount && row.visitCount > 0 && <p><small>
                {row.visitCount === 1 ? '1 verlässlicher Besuch' : `${row.visitCount} verlässliche Besuche`}</small></p>}
            </div>
          </Card>
        </li>;
      })}
    </ul>
    <p><a href="/cats">Alle Katzen und Chips ansehen</a></p>
  </>;
}

export function FeedingSiteDetail({ organizationId, siteId }: { organizationId: string; siteId: string }) {
  const detail = useLoad(useCallback(async (signal: AbortSignal) => {
    const options = requestOptions(signal);
    const [site, deployments, nodes, activity] = await Promise.all([
      api.GET('/api/v1/organizations/{organizationId}/feeding-sites/{siteId}', {
        ...options, params: { path: { organizationId, siteId } } }).then(result),
      api.GET('/api/v1/organizations/{organizationId}/deployments', { ...options, params: { path: { organizationId } } }).then(result),
      api.GET('/api/v1/nodes', { ...options, params: { query: { organizationId } } }).then(result),
      api.GET('/api/v1/organizations/{organizationId}/feeding-sites/{siteId}/cat-activity', {
        ...options, params: { path: { organizationId, siteId } } }).then(result),
    ]);
    return { site, deployments, nodes, activity };
  }, [organizationId, siteId]));
  const data = detail.data;
  const siteName = data ? siteDisplayName(data.site) : '';
  const hasLocation = Boolean(data?.site.locationLabel?.trim() || data?.site.description?.trim()
    || (typeof data?.site.locationLat === 'number' && typeof data?.site.locationLng === 'number'
      && Number.isFinite(data.site.locationLat) && Number.isFinite(data.site.locationLng)));
  return <section aria-labelledby="feeding-site-detail-title" className="feeding-site-detail">
    <p><a href="/feeding-sites">← Zurück zu Futterstellen</a></p>
    {data ? <h1 id="feeding-site-detail-title">{siteName}</h1> : <h1 id="feeding-site-detail-title">Futterstelle</h1>}
    <div className="actions"><button type="button" onClick={detail.reload}>Aktualisieren</button></div>
    <LoadState {...detail}/>
    {data && <article>
      <Graphic src={assets.placeholders.feedingSite} alt="" className="page-illustration feeding-site-illustration"/>
      <section aria-labelledby="feeding-site-location-title" className="detail-section">
        <h2 id="feeding-site-location-title">Ort</h2>
        {hasLocation ? <dl>
          {data.site.locationLabel?.trim() && <><dt>Bezeichnung</dt><dd>{data.site.locationLabel.trim()}</dd></>}
          {data.site.description?.trim() && <><dt>Beschreibung</dt><dd className="preserve-lines">{data.site.description.trim()}</dd></>}
          {typeof data.site.locationLat === 'number' && typeof data.site.locationLng === 'number'
            && Number.isFinite(data.site.locationLat) && Number.isFinite(data.site.locationLng)
            && <><dt>Koordinaten</dt><dd>{data.site.locationLat}, {data.site.locationLng}</dd></>}
        </dl> : <p><small>Keine Ortsangaben vorhanden.</small></p>}
      </section>
      <section aria-labelledby="feeding-site-bowls-title" className="detail-section">
        <h2 id="feeding-site-bowls-title">Zugeordnete Näpfe ({currentlyAssignedDeployments(data.deployments, data.site.id).length})</h2>
        <p><small>Aktuell zugeordnete benannte Näpfe. Umgezogene Näpfe ändern keine früheren Sichtungen.</small></p>
        <BowlList deployments={data.deployments} nodes={data.nodes} siteId={data.site.id ?? ''}/>
      </section>
      <section aria-labelledby="feeding-site-cats-title" className="detail-section">
        <h2 id="feeding-site-cats-title">Zuletzt gesehen</h2>
        <p><small>Historische Futterstellenzuordnung: Sichtungen bleiben bei der damaligen Futterstelle, auch wenn ein Napf umgezogen ist.</small></p>
        <CatActivityList activity={data.activity}/>
      </section>
    </article>}
  </section>;
}

export function LegacyNodesRedirect() {
  useEffect(() => {
    documentNavigation.replace('/feeding-sites');
  }, []);
  // Info, not loading: the redirect itself is immediate; no progress to report.
  return <section aria-labelledby="legacy-nodes-title">
    <h1 id="legacy-nodes-title">Futterstellen</h1>
    <Status kind="info" title="Futterstellen werden geöffnet …"
      message="Die bisherige Nodes-Ansicht wurde durch Futterstellen ersetzt."/>
  </section>;
}

export function FeedingSitesPage({ path }: { path: string }) {
  const context = useManagementContext();
  const [online, setOnline] = useState(navigator.onLine);
  const [departed, setDeparted] = useState(false);
  useEffect(() => {
    const update = () => setOnline(navigator.onLine);
    const hide = () => flushSync(() => setDeparted(true));
    const show = (event: PageTransitionEvent) => { if (event.persisted) window.location.reload(); };
    window.addEventListener('online', update); window.addEventListener('offline', update);
    window.addEventListener('pagehide', hide); window.addEventListener('pageshow', show);
    return () => {
      window.removeEventListener('online', update); window.removeEventListener('offline', update);
      window.removeEventListener('pagehide', hide); window.removeEventListener('pageshow', show);
    };
  }, []);
  const route = parseFeedingSitePath(path);
  return <section className="feeding-sites-page">
    {departed ? <p role="status">Verwaltung wird beim Zurückkehren neu geladen.</p>
      : route.kind === 'unknown' ? <><h1>Seite nicht gefunden</h1><p>Bitte einen Bereich in der Navigation auswählen.</p></>
      : !context.userId ? <><h1>Futterstellen</h1><p>Bitte anmelden, um die Daten Ihrer Organisation zu verwalten.</p></>
      : !context.organizationId ? <><h1>Futterstellen</h1><p>Bitte eine aktive Organisation auswählen.</p></>
      : !online ? <><h1>Futterstellen</h1><Status {...statusMessages.offline}><a href="/sync">Vor-Ort-Sync öffnen</a></Status></>
      : route.kind === 'overview'
        ? <FeedingSitesOverview key={context.generation} organizationId={context.organizationId}/>
        : <FeedingSiteDetail key={`${context.generation}:${route.siteId}`} organizationId={context.organizationId} siteId={route.siteId}/>}
  </section>;
}
