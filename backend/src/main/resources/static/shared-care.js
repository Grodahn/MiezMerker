// Shared read-only presenter for the field PWA and server-rendered Admin.
// Foreign projections exist only in this live DOM/request scope, never in storage.
export function mountSharedCare(root, { organizationId, chipId, api, controllerFactory = () => new AbortController(), pageSize = 5, onReturn }) {
  let pending;
  let disposed = false;
  let suspended = false;
  let profileOffset = 0;
  let visitOffsets = {};
  let focusIndex;
  const listeners = [];
  const listen = (target, name, callback) => {
    target.addEventListener(name, callback);
    listeners.push(() => target.removeEventListener(name, callback));
  };
  function element(tag, text, parent = root) {
    const node = document.createElement(tag);
    if (text !== undefined) node.textContent = text;
    parent.append(node);
    return node;
  }
  function button(label, action, parent = root, disabled = false) {
    const node = element('button', label, parent);
    node.type = 'button'; node.disabled = disabled;
    node.addEventListener('click', () => {
      focusIndex = [...root.querySelectorAll('button')].indexOf(node);
      action();
    });
    return node;
  }
  function clear(message) {
    pending?.abort(); pending = undefined;
    root.replaceChildren();
    const status = element('p', message);
    status.setAttribute('role', 'status');
  }
  function unavailable() {
    suspended = true;
    focusIndex = undefined;
    clear(navigator.onLine ? 'Geteilte Informationen werden beim Zurückkehren neu geprüft.'
      : 'Geteilte Informationen sind nur online verfügbar.');
  }
  async function load() {
    if (disposed || !navigator.onLine || document.visibilityState === 'hidden') { unavailable(); return; }
    suspended = false;
    clear('Geteilte Betreuung wird geprüft …');
    const controller = controllerFactory(); pending = controller;
    const signal = controller.signal;
    try {
      // Only server-persisted, tenant-owned observations can prove this chip.
      const observations = await api.observations(organizationId, chipId, signal);
      signal.throwIfAborted();
      const proof = observations.find(row => row.id && row.organizationId === organizationId && row.chipId === chipId)?.id;
      if (!proof) {
        clear('Keine eigene gespeicherte Beobachtung. Geteilte Betreuung kann nicht geprüft werden.');
        return;
      }
      const profiles = await api.resolve(organizationId, { ownObservationRefs: [proof], limit: pageSize, offset: profileOffset }, signal);
      signal.throwIfAborted();
      const items = profiles.items ?? [];
      // All pages refresh CARE as well as VISITS. Never retain a revoked name
      // while refreshing only the visits. A failed page leaves no foreign DOM.
      const pages = await Promise.all(items.map(profile => api.visits(organizationId, {
        ownObservationRef: proof, sourceOrganizationId: profile.source.organizationId,
        limit: pageSize, offset: visitOffsets[profile.source.organizationId] ?? 0, newestFirst: true,
      }, signal)));
      signal.throwIfAborted();
      if (disposed || suspended || pending !== controller) return;
      root.replaceChildren();
      element('p', 'Eigene Angaben bleiben maßgeblich. Geteilte Profile sind schreibgeschützt. Fremde Besuche zählen nicht zur eigenen Aktivität.');
      if (!items.length) element('p', 'Keine freigegebene Betreuung verfügbar.');
      items.forEach((profile, index) => {
        const source = profile.source;
        const card = element('section'); card.className = 'shared-care__source';
        card.setAttribute('aria-label', `Geteilte Betreuung von ${source.displayName}`);
        element('h3', `Wird auch von ${source.displayName} betreut`, card);
        if (profile.catDisplayName) element('p', `Name bei ${source.displayName}: ${profile.catDisplayName}`, card);
        element('h4', `Freigegebene Besuche von ${source.displayName}`, card);
        element('p', 'Nur gespeicherte Besuche mit verlässlicher Uhrzeit. UNKNOWN-Reads und Serverempfang sind keine Besuchszeiten.', card);
        const page = pages[index];
        if (!page.items?.length) element('p', 'Keine freigegebenen Besuche verfügbar. Besuchsverlauf benötigt CARE und VISITS.', card);
        else {
          const wrap = element('div', undefined, card); wrap.className = 'table-scroll shared-care__table'; wrap.tabIndex = 0;
          wrap.setAttribute('role', 'region'); wrap.setAttribute('aria-label', `Besuche von ${source.displayName}`);
          const table = element('table', undefined, wrap); table.setAttribute('role', 'table');
          element('caption', `Fremde Besuche · ${source.displayName} · UTC`, table);
          const head = element('thead', undefined, table); head.setAttribute('role', 'rowgroup');
          const header = element('tr', undefined, head); header.setAttribute('role', 'row');
          ['Beginn (UTC)', 'Ende (UTC)', 'Futterstelle'].forEach(label => {
            const cell = element('th', label, header); cell.scope = 'col'; cell.setAttribute('role', 'columnheader');
          });
          const body = element('tbody', undefined, table); body.setAttribute('role', 'rowgroup');
          for (const visit of page.items) {
            const row = element('tr', undefined, body); row.setAttribute('role', 'row');
            for (const value of [visit.startAt, visit.endAt]) {
              const cell = element('td', undefined, row); cell.setAttribute('role', 'cell');
              if (value && Number.isFinite(Date.parse(value))) {
                const stamp = element('time', new Date(value).toISOString(), cell); stamp.dateTime = value;
              } else element('span', 'Unbekannt', cell);
            }
            element('td', visit.siteDisplayName ?? 'Futterstellenname nicht freigegeben', row).setAttribute('role', 'cell');
          }
        }
        const offset = visitOffsets[source.organizationId] ?? 0;
        const pager = element('div', undefined, card); pager.className = 'actions shared-care__pager';
        pager.setAttribute('role', 'group'); pager.setAttribute('aria-label', `Besuchsseiten von ${source.displayName}`);
        const move = next => { visitOffsets = { ...visitOffsets, [source.organizationId]: next }; void load(); };
        button('Neuere Besuche', () => move(Math.max(0, offset - pageSize)), pager, offset === 0);
        element('span', `Seite ${Math.floor(offset / pageSize) + 1}`, pager);
        button('Ältere Besuche', () => move(page.nextOffset), pager, page.nextOffset == null);
      });
      const pager = element('div'); pager.className = 'actions shared-care__pager';
      const move = next => { profileOffset = next; visitOffsets = {}; void load(); };
      button('Vorherige Organisationen', () => move(Math.max(0, profileOffset - pageSize)), pager, profileOffset === 0);
      button('Weitere Organisationen', () => move(profiles.nextOffset), pager, profiles.nextOffset == null);
      button('Freigaben neu prüfen', () => { profileOffset = 0; visitOffsets = {}; void load(); });
      if (focusIndex != null && document.activeElement === document.body) {
        const buttons = root.querySelectorAll('button');
        (buttons[focusIndex] ?? buttons[buttons.length - 1])?.focus();
      }
      focusIndex = undefined;
    } catch {
      if (!signal.aborted && pending === controller && !disposed) {
        clear('Geteilte Informationen nicht verfügbar. Verbindung, Sitzung oder Berechtigung prüfen.');
        button('Erneut prüfen', () => void load());
      }
    }
  }
  function resume() {
    if (disposed || !suspended || document.visibilityState === 'hidden' || !navigator.onLine) return;
    if (onReturn) onReturn(); else void load();
  }
  listen(window, 'offline', unavailable);
  listen(window, 'online', resume);
  listen(window, 'blur', unavailable);
  listen(window, 'focus', resume);
  listen(document, 'visibilitychange', () => document.visibilityState === 'hidden' ? unavailable() : resume());
  listen(window, 'pagehide', unavailable);
  listen(window, 'pageshow', event => { if (event.persisted) resume(); });
  // Admin logout/org-switch forms clear transient foreign information before submit.
  listen(document, 'submit', unavailable);
  void load();
  return () => {
    disposed = true; pending?.abort(); pending = undefined;
    visitOffsets = {}; root.replaceChildren(); listeners.forEach(remove => remove());
  };
}
