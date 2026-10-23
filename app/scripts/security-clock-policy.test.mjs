import { test } from 'node:test';
import assert from 'node:assert/strict';
import { buildClockPolicy } from './security-clock-policy.mjs';
test('normal dev, HTTPS, build and signed/release profiles have fixed policy', () => {
  for (const mode of ['development', 'local-https', 'production']) {
    assert.equal(buildClockPolicy(mode), 'FIELD_ALPHA_PHONE_CLOCK');
    assert.equal(buildClockPolicy(mode, 'SIGNED_BACKEND_TIME'), 'SIGNED_BACKEND_TIME');
  }
  for (const mode of ['signed-time', 'release']) {
    assert.equal(buildClockPolicy(mode), 'SIGNED_BACKEND_TIME');
    assert.throws(() => buildClockPolicy(mode, 'FIELD_ALPHA_PHONE_CLOCK'), /requires SIGNED_BACKEND_TIME/);
  }
  for (const bad of ['', 'typo']) assert.throws(() => buildClockPolicy('production', bad), /Invalid/);
});
