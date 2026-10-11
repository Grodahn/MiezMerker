/// <reference types="node" />
import { beforeEach, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';

const script = readFileSync('../backend/src/main/resources/static/admin-organization-settings.js', 'utf8');
beforeEach(() => {
  document.body.innerHTML = `
    <form id="organization-settings">
      <input id="hidden" type="checkbox" checked>
      <p id="hidden-help"></p>
      <fieldset data-scope="CARE">
        <input type="radio" name="CARE" value="PRIVATE" checked>
        <input type="radio" name="CARE" value="ALLOWLIST">
        <div data-recipients><input type="checkbox" value="eligible"></div>
      </fieldset>
      <button id="save-settings">Speichern</button><span id="save-status"></span>
    </form>`;
  window.eval(script);
});
it('disables outgoing choices while hidden without silently selecting sharing', () => {
  const fieldset = document.querySelector('fieldset')!;
  expect(fieldset.disabled).toBe(true);
  const hidden = document.querySelector<HTMLInputElement>('#hidden')!;
  hidden.checked = false;
  hidden.dispatchEvent(new Event('change', { bubbles: true }));
  expect(fieldset.disabled).toBe(false);
  expect(document.querySelector<HTMLInputElement>('[value=PRIVATE]')!.checked).toBe(true);
  expect(document.querySelector<HTMLElement>('[data-recipients]')!.hidden).toBe(true);
});
it('only offers checkboxes for the selected allowlist audience', () => {
  document.querySelector<HTMLInputElement>('#hidden')!.checked = false;
  const allowlist = document.querySelector<HTMLInputElement>('[value=ALLOWLIST]')!;
  allowlist.checked = true;
  allowlist.dispatchEvent(new Event('change', { bubbles: true }));
  expect(document.querySelector<HTMLElement>('[data-recipients]')!.hidden).toBe(false);
  expect(document.querySelector<HTMLInputElement>('[value=eligible]')!.disabled).toBe(false);
});
it('announces saving and prevents duplicate submissions', () => {
  document.querySelector('form')!.dispatchEvent(new Event('submit'));
  expect(document.querySelector<HTMLButtonElement>('button')!.disabled).toBe(true);
  expect(document.querySelector('#save-status')!.textContent).toContain('werden gespeichert');
  expect(document.querySelector('form')!.getAttribute('aria-busy')).toBe('true');
});
