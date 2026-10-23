import { expect, test } from '@playwright/test';
import { webcrypto } from 'node:crypto';
import { encodeClaimAdvertisement, encodeFrame, encodeHelloPublic, encodeOwnerResponse, Opcode } from '../src/collector/ble-codec';

const NODE = '44444444-4444-4444-8444-444444444444';
const ORG = '22222222-2222-4222-8222-222222222222';
const SITE = '66666666-6666-4666-8666-666666666666';
const alpha = process.env.MM_E2E_CLOCK_POLICY !== 'SIGNED_BACKEND_TIME';

for (const { width, stallFirstDiscovery } of [
  { width: 320, stallFirstDiscovery: false }, { width: 430, stallFirstDiscovery: false },
  { width: 390, stallFirstDiscovery: true },
]) test(`guided registration at ${width}px preserves focus, confirmation and setup${stallFirstDiscovery ? ' after service timeout' : ''}`, async ({ page }) => {
  await page.setViewportSize({ width, height: 844 });
  const key = await webcrypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, true, ['sign', 'verify']);
  const publicKey = new Uint8Array(await webcrypto.subtle.exportKey('raw', key.publicKey));
  const timestampMillis = BigInt(Date.now());
  const x = Buffer.from(publicKey.slice(1, 33)).toString('base64url'), y = Buffer.from(publicKey.slice(33)).toString('base64url');
  const message = new TextEncoder().encode(`MM-CLAIM-v1\n${NODE}\n${x}\n${y}\n${timestampMillis}\nclaim-mode`);
  const signature = new Uint8Array(await webcrypto.subtle.sign({ name: 'ECDSA', hash: 'SHA-256' }, key.privateKey, message));
  const hello = (claimed: boolean) => Array.from(encodeFrame({ version: 1, opcode: Opcode.HelloPublic,
    payload: encodeHelloPublic({ serverVer: 1, caps: 0x1f | (alpha ? 0x80 : 0), nodeId: NODE,
      incarnation: '55555555-5555-4555-9555-555555555555', firmwareVersion: 'mock', claimState: claimed ? 1 : 0, clockStatus: 2 }) }));
  const owner = (claimed: boolean) => Array.from(encodeFrame({ version: 1, opcode: Opcode.OwnerResponse,
    payload: encodeOwnerResponse({ nodeId: NODE, claimState: claimed ? 1 : 0, organizationId: claimed ? ORG : '00000000-0000-0000-0000-000000000000',
      organizationName: claimed ? 'Feldteam' : '', organizationSlug: '', publicContact: '' }) }));
  const proof = Array.from(encodeFrame({ version: 1, opcode: Opcode.ClaimAdvertisementResponse,
    payload: encodeClaimAdvertisement({ nodeId: NODE, publicKey, timestampMillis, signature }) }));
  const receiptAck = Array.from(encodeFrame({ version: 1, opcode: Opcode.ClaimReceiptResponse, payload: new Uint8Array([0]) }));
  const alphaAck = Array.from(encodeFrame({ version: 1, opcode: Opcode.AlphaPhoneClockResponse, payload: new Uint8Array([0]) }));
  let timeBootstrapRequests = 0;
  let claims = 0, deployments = 0, displayName: string | null = null;
  await page.route('**/api/v1/**', route => {
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith('/nodes/time-bootstrap')) timeBootstrapRequests++;
    const send = (data: unknown) => route.fulfill({ contentType: 'application/json', body: JSON.stringify(data) });
    if (path.endsWith('/auth/session')) return send({ userId: 'admin', email: 'admin@example.org', memberships: [
      { membershipId: 'member', organizationId: ORG, organizationName: 'Feldteam', role: 'ADMIN', status: 'ACTIVE' }] });
    if (path.endsWith('/auth/csrf')) return send({ token: 'csrf' });
    if (path.endsWith('/nodes/claim')) { claims++; return send({ nodeId: NODE, organizationId: ORG, receipt: 'mock-backend-receipt' }); }
    if (path.endsWith(`/nodes/${NODE}`)) {
      if (route.request().method() === 'PATCH') displayName = route.request().postDataJSON().displayName;
      return send({ nodeId: NODE, organizationId: ORG, state: 'CLAIMED', displayName, publicKeyX: x, publicKeyY: y });
    }
    if (path.endsWith('/feeding-sites')) return send([{ id: SITE, organizationId: ORG, name: 'Am Friedhof' }]);
    if (path.endsWith('/deployments')) {
      if (route.request().method() === 'POST') { deployments++; return send({ id: 'dep', nodeId: NODE, feedingSiteId: SITE, validUntil: null }); }
      return send(deployments ? [{ id: 'dep', nodeId: NODE, feedingSiteId: SITE, validUntil: null }] : []);
    }
    return send([]);
  });
  await page.addInitScript(({ hello, claimedHello, owner, claimedOwner, proof, receiptAck, alphaAck, stallFirstDiscovery }) => {
    let claimed = false, permitted = false;
    let disconnecting = false;
    let serviceDiscoveries = 0;
    const connectionEvents = new EventTarget();
    const events = { chooser: 0, activation: false, connects: 0 };
    Object.assign(window, { registrationEvents: events });
    const device = { id: 'browser-bowl', name: 'Untrusted BLE label',
      addEventListener: connectionEvents.addEventListener.bind(connectionEvents),
      removeEventListener: connectionEvents.removeEventListener.bind(connectionEvents), gatt: {
      connected: false,
      async connect() {
        if (disconnecting) throw new DOMException('Previous GATT connection is closing', 'NetworkError');
        events.connects++; this.connected = true; return this;
      },
      disconnect() {
        if (!this.connected) return;
        this.connected = false; disconnecting = true;
        setTimeout(() => {
          disconnecting = false; connectionEvents.dispatchEvent(new Event('gattserverdisconnected'));
        }, 50);
      },
      async getPrimaryService() {
        if (++serviceDiscoveries === 1 && stallFirstDiscovery) await new Promise(() => {});
        return { async getCharacteristic(uuid: string) {
          if (claimed) throw new DOMException('Post-claim characteristic rediscovery failed', 'NetworkError');
          let requestOpcode = 0;
          return {
        async writeValueWithResponse(value: Uint8Array) {
          requestOpcode = value[1];
          if (uuid.endsWith('5b0b') && requestOpcode !== 0x25) claimed = true;
        },
        async readValue() {
          const bytes = uuid.endsWith('5b01') ? (claimed ? claimedHello : hello)
            : uuid.endsWith('5b02') ? (claimed ? claimedOwner : owner)
            : uuid.endsWith('5b0a') ? proof : requestOpcode === 0x25 ? alphaAck : receiptAck;
          return new DataView(new Uint8Array(bytes).buffer);
        },
      }; } }; },
    } };
    Object.defineProperty(navigator, 'bluetooth', { configurable: true, value: {
      getDevices: async () => permitted ? [device] : [],
      requestDevice: async () => { events.chooser++; events.activation = navigator.userActivation.isActive; permitted = true; return device; },
    } });
  }, { hello: hello(false), claimedHello: hello(true), owner: owner(false), claimedOwner: owner(true), proof, receiptAck, alphaAck, stallFirstDiscovery });
  await page.goto('/sync');
  const add = page.getByRole('button', { name: 'Weiteren Napf hinzufügen' });
  await expect(add).toBeEnabled();
  expect(await page.evaluate(() => (window as unknown as { registrationEvents: { chooser: number } }).registrationEvents.chooser)).toBe(0);
  await add.click();
  const dialog = page.getByRole('dialog', { name: 'Napf registrieren' });
  await expect(dialog).toBeVisible({ timeout: stallFirstDiscovery ? 15_000 : 5_000 });
  await expect(dialog.getByRole('button', { name: 'Nein', exact: true })).toBeFocused();
  expect(claims).toBe(0); expect(deployments).toBe(0);
  await page.keyboard.press('Escape');
  await expect(dialog).not.toBeVisible();
  await expect(add).toBeFocused();
  await add.click();
  await expect(dialog).toContainText('Kein Tastendruck am Napf erforderlich.');
  await dialog.getByRole('button', { name: 'Napf registrieren', exact: true }).click();
  await expect(dialog.getByLabel('Futterstelle auswählen')).toBeVisible();
  await expect(dialog.getByPlaceholder('Der Grüne')).toBeFocused();
  await dialog.getByPlaceholder('Der Grüne').fill('Der Grüne');
  await dialog.getByRole('button', { name: 'Einrichtung abschließen' }).click();
  await expect(dialog.getByText(/kann beim nächsten Auslesen mitmachen/)).toBeVisible();
  expect(claims).toBe(1); expect(deployments).toBe(1); expect(displayName).toBe('Der Grüne');
  expect(timeBootstrapRequests).toBe(0);
  // Two preflight visits plus provisioning, including post-claim owner reads.
  expect(await page.evaluate(() => (window as unknown as { registrationEvents: { connects: number } }).registrationEvents.connects)).toBe(3 + Number(stallFirstDiscovery));
  // Two explicit Add clicks open the chooser; the automatic retry adds none.
  expect(await page.evaluate(() => (window as unknown as { registrationEvents: { chooser: number } }).registrationEvents.chooser)).toBe(2);
  await expect.poll(() => page.evaluate(() => localStorage.getItem('miezmerker-last-setup-node'))).toBeNull();
  expect(await dialog.evaluate(el => el.scrollWidth <= el.clientWidth)).toBe(true);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  expect(await page.evaluate(() => (window as unknown as { registrationEvents: { activation: boolean } }).registrationEvents.activation)).toBe(true);
  await page.screenshot({ path: test.info().outputPath(`registration-${width}.png`) });
});
