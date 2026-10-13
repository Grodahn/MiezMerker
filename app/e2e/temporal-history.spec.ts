import { test, expect } from '@playwright/test';

for (const width of [320, 430]) {
  test(`interval history wraps and exposes uncertainty at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.route('**/api/v1/**', async route => {
      const path = new URL(route.request().url()).pathname;
      const send = (data: unknown) => route.fulfill({ contentType: 'application/json', headers: { 'Cache-Control': 'no-store' }, body: JSON.stringify(data) });
      if (path.endsWith('/auth/session')) return send({ userId: 'user', email: 'staff@example.org', memberships: [
        { membershipId: 'member', organizationId: 'org', organizationName: 'Shelter', role: 'MEMBER', status: 'ACTIVE' },
      ] });
      if (path.endsWith('/auth/csrf')) return send({ token: 'csrf' });
      if (path.endsWith('/feeding-sites')) return send([{ id: 'historic', organizationId: 'org', name: 'Historischer Garten' }]);
      if (path.endsWith('/cats')) return send([{ id: 'cat', chipId: 'CHIP', name: 'Luna' }]);
      if (path.endsWith('/visits/temporal')) return send({ algorithmVersion: 'visit-interval-v1', timeProjectionVersion: 'boot-interval-v1', gapSeconds: 60, nextOffset: null, items: [
        { id: 'visit', kind: 'VISIT', chipId: 'CHIP', quality: 'RECONSTRUCTED', earliestUtcMs: '1791748800000', latestUtcMs: '1791748801000', feedingSiteId: 'historic', observationCount: 1,
          observations: [{ nodeId: '00000000-0000-4000-8000-000000000001', time: { observationId: 'read', sequence: '9007199254740993', bootCounter: 42, monotonicMs: '9007199254740993', anchorIds: ['long-anchor-identifier'], uncertaintyPolicies: ['synthetic-policy-provenance'], attributionProvenance: 'UNRESOLVED' } }] },
        { id: 'bounded', kind: 'OBSERVATION', chipId: 'CHIP', quality: 'BOUNDED', earliestUtcMs: '1791748800000', latestUtcMs: '1791748900000', feedingSiteId: null, observationCount: 1, ambiguousGap: true, observations: [] },
        { id: 'unknown', kind: 'OBSERVATION', chipId: 'CHIP', quality: 'UNKNOWN', earliestUtcMs: null, latestUtcMs: null, feedingSiteId: null, observationCount: 1, observations: [] },
      ] });
      return send([]);
    });
    await page.goto('/cats');
    await page.getByRole('button', { name: 'Details / bearbeiten' }).click();
    const history = page.getByRole('region', { name: 'Rekonstruierter Verlauf' });
    await expect(history.getByText('Rekonstruiert mit Unsicherheit')).toBeVisible();
    await expect(history.getByText('Nur zeitlich eingegrenzt')).toBeVisible();
    await expect(history.getByText('Zeitpunkt unbekannt', { exact: true })).toBeVisible();
    await expect(history.getByText('Futterstelle nicht bestätigt')).toHaveCount(2);
    await expect(history.getByRole('button', { name: 'Weitere Beobachtungen' })).toBeDisabled();
    await history.getByText('Herkunft und relative Reihenfolge').first().click();
    await expect(history.getByText(/Folge: 9007199254740993/)).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
    await expect(history).not.toContainText('1970');
    await page.screenshot({ path: `../test-results/temporal-history-${width}.png`, fullPage: true });
  });
}
