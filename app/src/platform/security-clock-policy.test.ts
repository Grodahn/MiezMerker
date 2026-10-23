import { afterEach, expect, test, vi } from 'vitest';
import { securityClockPolicy } from './security-clock-policy';
import { usesAlphaPhoneClock } from '../collector/phone-time';
afterEach(() => vi.unstubAllEnvs());

test('unconfigured checkout uses alpha and reports both modes on mismatch', () => {
  vi.stubEnv('VITE_MM_SECURITY_CLOCK_POLICY', undefined);
  expect(securityClockPolicy()).toBe('FIELD_ALPHA_PHONE_CLOCK');
  expect(usesAlphaPhoneClock(0x80)).toBe(true);
  expect(() => usesAlphaPhoneClock(0)).toThrow('PWA=FIELD_ALPHA_PHONE_CLOCK, Firmware=SIGNED_BACKEND_TIME');
});
test('explicit signed mode cannot fall back; invalid/empty config fails', () => {
  vi.stubEnv('VITE_MM_SECURITY_CLOCK_POLICY', 'SIGNED_BACKEND_TIME');
  expect(usesAlphaPhoneClock(0)).toBe(false);
  expect(() => usesAlphaPhoneClock(0x80)).toThrow('PWA=SIGNED_BACKEND_TIME, Firmware=FIELD_ALPHA_PHONE_CLOCK');
  expect(() => securityClockPolicy('' as never)).toThrow('Ungültige');
  expect(() => securityClockPolicy('typo' as never)).toThrow('Ungültige');
});
