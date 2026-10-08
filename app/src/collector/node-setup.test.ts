// @vitest-environment node
import { beforeEach, describe, expect, test, vi } from 'vitest';

const get = vi.hoisted(() => vi.fn());
const post = vi.hoisted(() => vi.fn());
const patch = vi.hoisted(() => vi.fn());
const authState = vi.hoisted(() => vi.fn());
vi.mock('../api/client', () => ({ api: { GET: get, POST: post, PATCH: patch } }));
vi.mock('../platform/auth', () => ({
  getAuthState: authState,
  fetchCsrfToken: async () => 'csrf',
  markSessionExpired: vi.fn(),
}));

import {
  assertAdminMayProvision,
  ensureInitialDeployment,
  fetchNodeSetupState,
  isSetupComplete,
  listFeedingSiteOptions,
  normalizeBowlName,
  saveBowlName,
  SetupError,
  validateBowlName,
} from './node-setup';

const ORG = '22222222-2222-4222-8222-222222222222';
const NODE = '44444444-4444-4444-8444-444444444444';
const SITE = '66666666-6666-4666-8666-666666666666';

function adminAuth() {
  authState.mockReturnValue({
    user: {
      userId: 'admin', memberships: [{ organizationId: ORG, status: 'ACTIVE', role: 'ADMIN' }],
    },
    csrfToken: 'csrf',
    activeOrganizationId: ORG,
  });
}

function memberAuth() {
  authState.mockReturnValue({
    user: {
      userId: 'member', memberships: [{ organizationId: ORG, status: 'ACTIVE', role: 'MEMBER' }],
    },
    csrfToken: 'csrf',
    activeOrganizationId: ORG,
  });
}

beforeEach(() => {
  get.mockReset();
  post.mockReset();
  patch.mockReset();
  authState.mockReset();
  adminAuth();
  Object.defineProperty(globalThis, 'navigator', { value: { onLine: true }, configurable: true });
});

describe('bowl name normalization (#50 rules reused)', () => {
  test('trims, preserves inner spacing, blank clears to null', () => {
    expect(normalizeBowlName('  Der Grüne  ')).toBe('Der Grüne');
    expect(normalizeBowlName('  Napf  2  ')).toBe('Napf  2');
    expect(normalizeBowlName('   ')).toBeNull();
    expect(normalizeBowlName(null)).toBeNull();
  });

  test('unicode works and max length enforced after trimming', () => {
    expect(validateBowlName('Grüner Napf 🐈')).toBe('Grüner Napf 🐈');
    expect(validateBowlName(`  ${'b'.repeat(100)}  `)).toBe('b'.repeat(100));
    expect(() => validateBowlName('c'.repeat(101))).toThrow(SetupError);
    expect(() => validateBowlName('   ')).toThrow(SetupError);
  });

  test('duplicate names remain valid (never unique)', async () => {
    patch.mockResolvedValue({ data: { nodeId: NODE, displayName: 'Silberner Napf' }, response: { ok: true } });
    await expect(saveBowlName(ORG, NODE, 'Silberner Napf')).resolves.toBe('Silberner Napf');
    patch.mockResolvedValue({ data: { nodeId: 'other', displayName: 'Silberner Napf' }, response: { ok: true } });
    await expect(saveBowlName(ORG, 'other', 'Silberner Napf')).resolves.toBe('Silberner Napf');
  });
});

describe('setup state separates claim from business setup', () => {
  test('successful complete claim -> name -> site setup', async () => {
    get.mockImplementation(async (path: string) => {
      if (path === '/api/v1/nodes/{nodeId}') {
        return { data: { nodeId: NODE, organizationId: ORG, state: 'CLAIMED', displayName: null }, response: { ok: true } };
      }
      if (path === '/api/v1/organizations/{organizationId}/deployments') {
        return { data: [], response: { ok: true } };
      }
      return { data: [{ id: SITE, name: 'Am Friedhof' }], response: { ok: true } };
    });
    const state = await fetchNodeSetupState(ORG, NODE);
    expect(state.claimed).toBe(true);
    expect(state.displayName).toBeNull();
    expect(state.activeDeployment).toBeNull();
    expect(isSetupComplete(state)).toBe(false);

    patch.mockResolvedValue({ data: { nodeId: NODE, displayName: 'Der Grüne' }, response: { ok: true } });
    await expect(saveBowlName(ORG, NODE, '  Der Grüne  ')).resolves.toBe('Der Grüne');
    expect(patch.mock.calls[0][1].body.displayName).toBe('Der Grüne');

    post.mockResolvedValue({ data: { id: 'dep-1', nodeId: NODE, feedingSiteId: SITE, validUntil: null }, response: { ok: true } });
    // ensureInitialDeployment first lists (empty) then creates
    get.mockImplementation(async (path: string) => {
      if (path === '/api/v1/nodes/{nodeId}') {
        return { data: { nodeId: NODE, organizationId: ORG, state: 'CLAIMED', displayName: 'Der Grüne' }, response: { ok: true } };
      }
      return { data: [], response: { ok: true } };
    });
    const outcome = await ensureInitialDeployment(ORG, NODE, SITE);
    expect(outcome.created).toBe(true);
    expect(outcome.deployment.feedingSiteId).toBe(SITE);
  });

  test('claim succeeds, setup interrupted before name stays resumable', async () => {
    get.mockImplementation(async (path: string) => {
      if (path === '/api/v1/nodes/{nodeId}') {
        return { data: { nodeId: NODE, organizationId: ORG, state: 'CLAIMED', displayName: null }, response: { ok: true } };
      }
      return { data: [], response: { ok: true } };
    });
    const state = await fetchNodeSetupState(ORG, NODE);
    expect(state).toMatchObject({ claimed: true, displayName: null, activeDeployment: null });
    expect(isSetupComplete(state)).toBe(false);
    expect(post).not.toHaveBeenCalled();
    expect(patch).not.toHaveBeenCalled();
  });

  test('name saved, deployment fails stays retryable without discarding name', async () => {
    patch.mockResolvedValue({ data: { nodeId: NODE, displayName: 'Der Grüne' }, response: { ok: true } });
    await saveBowlName(ORG, NODE, 'Der Grüne');
    get.mockResolvedValue({ data: [], response: { ok: true } });
    post.mockResolvedValue({ error: {}, response: { status: 500 } });
    await expect(ensureInitialDeployment(ORG, NODE, SITE)).rejects.toThrow(SetupError);
    // Name persists backend-side; refetch shows it with still no deployment.
    get.mockImplementation(async (path: string) => {
      if (path === '/api/v1/nodes/{nodeId}') {
        return { data: { nodeId: NODE, organizationId: ORG, state: 'CLAIMED', displayName: 'Der Grüne' }, response: { ok: true } };
      }
      return { data: [], response: { ok: true } };
    });
    const resumed = await fetchNodeSetupState(ORG, NODE);
    expect(resumed.displayName).toBe('Der Grüne');
    expect(resumed.activeDeployment).toBeNull();
  });

  test('lost response after successful creation does not duplicate on retry', async () => {
    const persisted = { id: 'dep-1', nodeId: NODE, feedingSiteId: SITE, validUntil: null };
    // First attempt: pre-check empty, POST 409 (backend already created, response lost),
    // refetch finds the persisted open deployment.
    let calls = 0;
    get.mockImplementation(async () => {
      calls++;
      return { data: calls === 1 ? [] : [persisted], response: { ok: true } };
    });
    post.mockResolvedValue({ error: {}, response: { status: 409 } });
    const outcome = await ensureInitialDeployment(ORG, NODE, SITE);
    expect(outcome.created).toBe(false);
    expect(outcome.alreadyAssigned).toBe(true);
    expect(outcome.deployment).toEqual(persisted);
    expect(post).toHaveBeenCalledTimes(1);
  });

  test('repeated setup requests do not create duplicate deployments', async () => {
    const persisted = { id: 'dep-1', nodeId: NODE, feedingSiteId: SITE, validUntil: null };
    get.mockResolvedValue({ data: [persisted], response: { ok: true } });
    const outcome = await ensureInitialDeployment(ORG, NODE, SITE);
    expect(outcome.created).toBe(false);
    expect(post).not.toHaveBeenCalled();
  });

  test('reload resumes from persisted name and deployment', async () => {
    const persisted = { id: 'dep-1', nodeId: NODE, feedingSiteId: SITE, validUntil: null };
    get.mockImplementation(async (path: string) => {
      if (path === '/api/v1/nodes/{nodeId}') {
        return { data: { nodeId: NODE, organizationId: ORG, state: 'CLAIMED', displayName: 'Der Grüne' }, response: { ok: true } };
      }
      return { data: [persisted], response: { ok: true } };
    });
    const state = await fetchNodeSetupState(ORG, NODE);
    expect(state.displayName).toBe('Der Grüne');
    expect(state.activeDeployment).toEqual(persisted);
    expect(isSetupComplete(state)).toBe(true);
  });

  test('already claimed, unassigned node completes without original claim state', async () => {
    get.mockImplementation(async (path: string) => {
      if (path === '/api/v1/nodes/{nodeId}') {
        return { data: { nodeId: NODE, organizationId: ORG, state: 'CLAIMED', displayName: null }, response: { ok: true } };
      }
      return { data: [], response: { ok: true } };
    });
    const state = await fetchNodeSetupState(ORG, NODE);
    expect(state.claimed).toBe(true);
    patch.mockResolvedValue({ data: { nodeId: NODE, displayName: 'Unterstand links' }, response: { ok: true } });
    await expect(saveBowlName(ORG, NODE, 'Unterstand links')).resolves.toBe('Unterstand links');
    post.mockResolvedValue({ data: { id: 'dep-2', nodeId: NODE, feedingSiteId: SITE, validUntil: null }, response: { ok: true } });
    await expect(ensureInitialDeployment(ORG, NODE, SITE)).resolves.toMatchObject({ created: true });
  });

  test('existing active deployment is never overwritten', async () => {
    const existing = { id: 'dep-old', nodeId: NODE, feedingSiteId: 'other-site', validUntil: null };
    get.mockResolvedValue({ data: [existing], response: { ok: true } });
    const outcome = await ensureInitialDeployment(ORG, NODE, SITE);
    expect(outcome.deployment).toEqual(existing);
    expect(outcome.alreadyAssigned).toBe(true);
    expect(post).not.toHaveBeenCalled();
  });
});

describe('feeding sites are read-only in the PWA', () => {
  test('empty site list surfaces a useful state (no creation in PWA)', async () => {
    get.mockResolvedValue({ data: [], response: { ok: true } });
    await expect(listFeedingSiteOptions(ORG)).resolves.toEqual([]);
  });

  test('only existing sites can be selected; blank is rejected client-side', async () => {
    await expect(ensureInitialDeployment(ORG, NODE, '')).rejects.toThrow('vorhandene Futterstelle');
    expect(post).not.toHaveBeenCalled();
  });
});

describe('authorization and tenant isolation', () => {
  test('MEMBER cannot perform provisioning (no hidden path)', async () => {
    memberAuth();
    expect(() => assertAdminMayProvision(ORG)).toThrow('Nur ADMIN');
    await expect(fetchNodeSetupState(ORG, NODE)).rejects.toThrow('Nur ADMIN');
    await expect(saveBowlName(ORG, NODE, 'x')).rejects.toThrow('Nur ADMIN');
    await expect(ensureInitialDeployment(ORG, NODE, SITE)).rejects.toThrow('Nur ADMIN');
    expect(get).not.toHaveBeenCalled();
    expect(post).not.toHaveBeenCalled();
    expect(patch).not.toHaveBeenCalled();
  });

  test('foreign organization node/site ids are rejected', async () => {
    get.mockResolvedValue({ error: {}, response: { status: 404 } });
    await expect(fetchNodeSetupState(ORG, NODE)).rejects.toThrowError(expect.objectContaining({ kind: 'foreign' }));
    get.mockResolvedValue({ data: [], response: { ok: true } });
    post.mockResolvedValue({ error: {}, response: { status: 404 } });
    await expect(ensureInitialDeployment(ORG, NODE, SITE)).rejects.toThrowError(expect.objectContaining({ kind: 'foreign' }));
  });

  test('expired session does not result in silent mutations', async () => {
    get.mockResolvedValue({ error: {}, response: { status: 401 } });
    await expect(fetchNodeSetupState(ORG, NODE)).rejects.toThrowError(expect.objectContaining({ kind: 'unauthorized' }));
  });
});

describe('offline behavior preserves claim and normal collection', () => {
  test('backend unavailable during business setup is explicit; claim stays valid', async () => {
    Object.defineProperty(globalThis, 'navigator', { value: { onLine: false }, configurable: true });
    await expect(fetchNodeSetupState(ORG, NODE)).rejects.toThrowError(expect.objectContaining({ kind: 'offline' }));
    await expect(saveBowlName(ORG, NODE, 'Der Grüne')).rejects.toThrowError(expect.objectContaining({ kind: 'offline' }));
    await expect(ensureInitialDeployment(ORG, NODE, SITE)).rejects.toThrowError(expect.objectContaining({ kind: 'offline' }));
    expect(get).not.toHaveBeenCalled();
  });

  test('backend 500 during setup is retryable and never pretends completion', async () => {
    get.mockResolvedValue({ error: {}, response: { status: 500 } });
    await expect(fetchNodeSetupState(ORG, NODE)).rejects.toThrowError(expect.objectContaining({ kind: 'backend' }));
  });
});
