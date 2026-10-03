import 'fake-indexeddb/auto';
import { render, screen, cleanup } from '@testing-library/react';
import { afterEach, expect, test, vi } from 'vitest';
vi.mock('./api/client', () => ({ api: { GET: vi.fn().mockResolvedValue({ response: { status: 401 }, error: {} }) } }));
import { App } from './App';
afterEach(cleanup);
test('collector initializes without any backend request', async () => {
  window.history.replaceState({}, '', '/sync');
  render(<App/>);
  expect(await screen.findByText('Lokaler Speicher bereit')).toBeTruthy();
});
test('administration does not mount collector storage', () => {
  window.history.replaceState({}, '', '/sites');
  render(<App/>);
  expect(screen.getByRole('heading', { name: 'Futterstellen' })).toBeTruthy();
  expect(screen.queryByText('Lokaler Speicher bereit')).toBeNull();
});
