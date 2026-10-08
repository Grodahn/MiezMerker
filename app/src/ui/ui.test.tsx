import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, expect, test } from 'vitest';
import { assets } from '../assets';
import { BrandHeader } from './ApplicationShell';
import { HomeEntry, FeedingSitesEntry } from './TransitionalPages';
import { AppIcon, Graphic } from './Graphic';
import { Button, Card, Field, ListLink } from './primitives';
import { BottomNavigation } from './navigation';

afterEach(cleanup);

test('primitives retain native labels, button behavior and link semantics', () => {
  render(<Card><Field label="Napfname" required/><Button disabled><AppIcon name="sync"/>Speichern</Button>
    <Button variant="secondary">Abbrechen</Button><ListLink href="/sync" icon="sync">Auslesen</ListLink></Card>);
  expect(screen.getByLabelText('Napfname').hasAttribute('required')).toBe(true);
  expect(screen.getByRole('button', { name: 'Speichern' }).hasAttribute('disabled')).toBe(true);
  expect(screen.getByRole('link', { name: 'Auslesen' }).getAttribute('href')).toBe('/sync');
});

test('meaningful graphics have alternatives; decoration never adds icon noise', () => {
  const { container } = render(<><Graphic src={assets.placeholders.cat} alt="Katze ohne Foto"/>
    <AppIcon name="offline" label="Offline"/><AppIcon name="sync"/></>);
  expect(screen.getByRole('img', { name: 'Katze ohne Foto' })).toBeTruthy();
  expect(screen.getByRole('img', { name: 'Offline' })).toBeTruthy();
  expect(container.querySelectorAll('[aria-hidden="true"]')).toHaveLength(1);
});

test('failed images fall back, and a new source is tried after replacement', () => {
  const { rerender } = render(<Graphic src="/missing.webp" alt="Katze" fallback={assets.placeholders.cat}/>);
  fireEvent.error(screen.getByAltText('Katze'));
  expect(screen.getByAltText('Katze').getAttribute('src')).toBe(assets.placeholders.cat);
  rerender(<Graphic src="/replacement.webp" alt="Katze" fallback={assets.placeholders.cat}/>);
  expect(screen.getByAltText('Katze').getAttribute('src')).toBe('/replacement.webp');
});

test('replacing central logo, Home and FeedingSite entries updates their consumers', () => {
  const original = [assets.brand.logo, assets.illustrations.home, assets.placeholders.feedingSite];
  try {
    assets.brand.logo = '/replacement-logo.svg';
    assets.illustrations.home = '/replacement-home.svg';
    assets.placeholders.feedingSite = '/replacement-site.svg';
    const { container } = render(<><BrandHeader/><HomeEntry/><FeedingSitesEntry/></>);
    expect(screen.getByAltText('MiezMerker').getAttribute('src')).toBe('/replacement-logo.svg');
    expect([...container.querySelectorAll('.page-illustration')].map(img => img.getAttribute('src')))
      .toEqual(['/replacement-home.svg', '/replacement-site.svg']);
  } finally {
    [assets.brand.logo, assets.illustrations.home, assets.placeholders.feedingSite] = original;
  }
});

test('navigation icons resolve through the central adapter, including future detail sections', () => {
  const original = assets.icons.cat;
  try {
    assets.icons.cat = '/replacement-cat-icon.svg';
    const { container } = render(<BottomNavigation path="/cats/cat-id"/>);
    expect(screen.getByRole('link', { name: 'Katzen' }).getAttribute('aria-current')).toBe('page');
    expect(container.querySelector('a[href="/cats"] img')?.getAttribute('src')).toBe('/replacement-cat-icon.svg');
  } finally { assets.icons.cat = original; }
});
