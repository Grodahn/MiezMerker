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

test.each([
  ['unauthorized', 'Sitzung abgelaufen. Bitte erneut anmelden; der Claim bleibt gültig.'],
  ['forbidden-role', 'Einrichtung verweigert: ACTIVE ADMIN erforderlich oder fremde Organisation.'],
] as const)('setup %s directs to recovery instead of offering a futile retry', async (kind, message) => {
  fetchMock.mockRejectedValue(new SetupError(kind, message));
  render(<NodeSetupWizard organizationId={ORG} nodeId={NODE}/>);
  await screen.findByRole('alert');
  expect(screen.queryByRole('button', { name: 'Erneut versuchen' })).toBeNull();
});

test('a failed refresh after confirmed assignment retries reading the state rather than the write', async () => {
  deployMock.mockResolvedValue({ deployment: { id: 'confirmed', feedingSiteId: SITE } as never, created: true, alreadyAssigned: false });
  render(<NodeSetupWizard organizationId={ORG} nodeId={NODE}/>);
  const submit = await screen.findByRole('button', { name: 'Einrichtung abschließen' });
  fetchMock.mockRejectedValueOnce(new SetupError('backend', 'Status-Refresh fehlgeschlagen.'));
  fireEvent.click(submit);
  await screen.findByText('Status-Refresh fehlgeschlagen.');
  fireEvent.click(screen.getByRole('button', { name: 'Einrichtungsstatus erneut laden' }));
  await screen.findByRole('button', { name: 'Name speichern' });
  expect(deployMock).toHaveBeenCalledTimes(1);
  expect(fetchMock).toHaveBeenCalledTimes(3);
});

test('site-list permission failures retain the saved setup and do not offer a futile retry', async () => {
  sitesMock.mockRejectedValue(new SetupError('forbidden-role', 'Einrichtung verweigert: ACTIVE ADMIN erforderlich oder fremde Organisation.'));
  render(<NodeSetupWizard organizationId={ORG} nodeId={NODE}/>);
  await screen.findByText(/ACTIVE ADMIN erforderlich/);
  expect(screen.getByRole('button', { name: 'Name speichern' })).toBeTruthy();
  expect(screen.queryByRole('button', { name: 'Erneut versuchen' })).toBeNull();
});

test('assignment session failures preserve claim guidance and do not retry provisioning', async () => {
  deployMock.mockRejectedValue(new SetupError('unauthorized', 'Sitzung abgelaufen. Bitte erneut anmelden; der Claim bleibt gültig.'));
  render(<NodeSetupWizard organizationId={ORG} nodeId={NODE}/>);
  fireEvent.click(await screen.findByRole('button', { name: 'Einrichtung abschließen' }));
  await screen.findByText(/Claim bleibt gültig/);
  expect(screen.queryByRole('button', { name: 'Erneut versuchen' })).toBeNull();
  expect(deployMock).toHaveBeenCalledTimes(1);
});
