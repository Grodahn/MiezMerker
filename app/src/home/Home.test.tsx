import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, expect, test } from 'vitest';
import { assets } from '../assets';
import { Home } from './Home';

afterEach(cleanup);

test('Home offers three native navigation links without location context or administrative content', () => {
  const { container } = render(<Home/>);
  const home = screen.getByRole('region', { name: 'Hallo!' });
  expect(within(home).getAllByRole('link').map(link => [link.textContent, link.getAttribute('href')]))
    .toEqual([['Futterstelle auslesen', '/sync'], ['Futterstellen', '/feeding-sites'], ['Katzen', '/cats']]);
  expect(within(home).getAllByRole('heading')).toHaveLength(1);
  expect(container.querySelectorAll('table, form, button, [role="status"]')).toHaveLength(0);
  expect(home.textContent).not.toMatch(/zuletzt|besuche|statistik|napf|node|uuid|verwaltung|automatisch/i);
  expect(within(home).queryByRole('img')).toBeNull(); // The cat and labelled-link icons are decoration.
  for (const image of container.querySelectorAll('img')) {
    expect(image.getAttribute('alt')).toBe('');
    expect(image.getAttribute('aria-hidden')).toBe('true');
  }
});

test('Home illustration and every action icon can be replaced centrally; links survive failed artwork', () => {
  const original = { home: assets.illustrations.home, icons: { ...assets.icons } };
  try {
    assets.illustrations.home = '/replacement-home.webp';
    for (const name of ['sync', 'arrow', 'feedingSite', 'cat'] as const) assets.icons[name] = `/replacement-${name}.svg`;
    const { container } = render(<Home/>);
    expect(container.querySelector('.home-illustration')?.getAttribute('src')).toBe('/replacement-home.webp');
    expect([...container.querySelectorAll('.app-icon')].map(image => image.getAttribute('src')))
      .toEqual(['/replacement-sync.svg', '/replacement-arrow.svg', '/replacement-feedingSite.svg',
        '/replacement-arrow.svg', '/replacement-cat.svg', '/replacement-arrow.svg']);
    for (const image of container.querySelectorAll('img')) fireEvent.error(image);
    expect(screen.getByRole('link', { name: 'Futterstelle auslesen' }).getAttribute('href')).toBe('/sync');
    expect(screen.getByRole('link', { name: 'Futterstellen' })).toBeTruthy();
    expect(screen.getByRole('link', { name: 'Katzen' })).toBeTruthy();
  } finally {
    assets.illustrations.home = original.home;
    Object.assign(assets.icons, original.icons);
  }
});
