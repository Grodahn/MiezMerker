import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';

vi.mock('./node-setup', async importOriginal => {
  const actual = await importOriginal<typeof import('./node-setup')>();
  return {
    ...actual,
    fetchNodeSetupState: vi.fn(),
    listFeedingSiteOptions: vi.fn(),
    saveBowlName: vi.fn(),
    ensureInitialDeployment: vi.fn(),
  };
});

import {
  ensureInitialDeployment,
  fetchNodeSetupState,
  listFeedingSiteOptions,
  saveBowlName,
  SetupError,
} from './node-setup';
import { NodeSetupWizard } from './NodeSetupWizard';

const fetchMock = vi.mocked(fetchNodeSetupState);
const sitesMock = vi.mocked(listFeedingSiteOptions);
const saveMock = vi.mocked(saveBowlName);
const deployMock = vi.mocked(ensureInitialDeployment);

const ORG = '22222222-2222-4222-8222-222222222222';
const NODE = '44444444-4444-4444-8444-444444444444';
const SITE = '66666666-6666-4666-8666-666666666666';

afterEach(cleanup);
beforeEach(() => {
  vi.clearAllMocks();
  fetchMock.mockResolvedValue({
    nodeId: NODE, organizationId: ORG, claimed: true,
    displayName: null, activeDeployment: null, deploymentCount: 0,
  });
  sitesMock.mockResolvedValue([{ id: SITE, name: 'Am Friedhof' } as never]);
});

test('incomplete setup shows name form and existing site selection', async () => {
  render(<NodeSetupWizard organizationId={ORG} nodeId={NODE} />);
  await screen.findByText('Dieser Napf wurde registriert, aber noch nicht vollständig eingerichtet.');
  expect(screen.getByPlaceholderText('Der Grüne')).toBeTruthy();
  expect(screen.getByText('Am Friedhof')).toBeTruthy();
  expect(screen.getByRole('button', { name: 'Einrichtung abschließen' })).toBeTruthy();
});

test('complete setup confirms without prompting for repeats', async () => {
  fetchMock.mockResolvedValue({
    nodeId: NODE, organizationId: ORG, claimed: true,
    displayName: 'Der Grüne',
    activeDeployment: { id: 'dep-1', feedingSiteId: SITE, validUntil: null } as never,
    deploymentCount: 1,
  });
  render(<NodeSetupWizard organizationId={ORG} nodeId={NODE} />);
  await screen.findByText(/Einrichtung abgeschlossen!/);
  expect(screen.getByDisplayValue('Der Grüne')).toBeTruthy();
});

test('empty site list explains Admin-backend creation, offers no PWA creation', async () => {
  sitesMock.mockResolvedValue([]);
  render(<NodeSetupWizard organizationId={ORG} nodeId={NODE} />);
  await screen.findByText(/Keine Futterstellen vorhanden/);
  expect(screen.queryByText('Bestehende Futterstelle wählen')).toBeNull();
});

test('site-list failure keeps the resumable name state visible', async () => {
  fetchMock.mockResolvedValue({
    nodeId: NODE, organizationId: ORG, claimed: true,
    displayName: 'Der Grüne', activeDeployment: null, deploymentCount: 0,
  });
  sitesMock.mockRejectedValue(new SetupError('backend', 'Futterstellen konnten nicht geladen werden.'));
  render(<NodeSetupWizard organizationId={ORG} nodeId={NODE} />);
  // Node state (saved name) stays visible instead of a generic load failure.
  await screen.findByDisplayValue('Der Grüne');
  await screen.findByText('Futterstellen konnten nicht geladen werden.');
  expect(fetchMock).toHaveBeenCalled();
});

test('saved name is confirmed and preserved for deployment retry', async () => {
  saveMock.mockResolvedValue('Der Grüne');
  fetchMock.mockResolvedValue({
    nodeId: NODE, organizationId: ORG, claimed: true,
    displayName: null, activeDeployment: null, deploymentCount: 0,
  });
  render(<NodeSetupWizard organizationId={ORG} nodeId={NODE} />);
  fireEvent.change(await screen.findByPlaceholderText('Der Grüne'), { target: { value: 'Der Grüne' } });
  fireEvent.click(screen.getByRole('button', { name: 'Name speichern' }));
  await screen.findByText('Napf-Name gespeichert: Der Grüne');
  expect(saveMock).toHaveBeenCalledWith(ORG, NODE, 'Der Grüne');
});

test('existing assignment is surfaced, never overwritten', async () => {
  deployMock.mockResolvedValue({
    deployment: { id: 'dep-old', feedingSiteId: 'other-site', validUntil: null } as never,
    created: false,
    alreadyAssigned: true,
  });
  fetchMock.mockResolvedValue({
    nodeId: NODE, organizationId: ORG, claimed: true,
    displayName: 'Der Grüne', activeDeployment: null, deploymentCount: 0,
  });
  render(<NodeSetupWizard organizationId={ORG} nodeId={NODE} />);
  fireEvent.click(await screen.findByRole('button', { name: 'Einrichtung abschließen' }));
  await screen.findByText(/bereits einer Futterstelle zugeordnet/);
  expect(deployMock).toHaveBeenCalledWith(ORG, NODE, SITE);
});
