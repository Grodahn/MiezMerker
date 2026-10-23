import { test, expect } from '@playwright/test';
test('compiled PWA exposes actual policy and honest risk before onboarding', async ({ page }) => {
  await page.goto('/');
  await page.getByText('Zeit-Policy / Diagnose', { exact: true }).click();
  const policy = process.env.MM_E2E_CLOCK_POLICY ?? 'FIELD_ALPHA_PHONE_CLOCK';
  await expect(page.getByText(`PWA v0.1.0 · ${policy}`, { exact: true })).toBeVisible();
  await expect(page.getByText(policy === 'FIELD_ALPHA_PHONE_CLOCK'
    ? /Feldtest: unbestätigte Handyzeit/ : /Signierte Backend-Zeit: ein Napf/)).toBeVisible();
});
