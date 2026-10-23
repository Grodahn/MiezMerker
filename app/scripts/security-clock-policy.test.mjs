import { test } from 'node:test';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import { resolveConfig } from 'vite';
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

test('real Vite preview ignores current policy and does not claim to recompile an existing bundle', async t => {
  const previousPolicy = process.env.VITE_MM_SECURITY_CLOCK_POLICY;
  t.after(() => {
    if (previousPolicy === undefined) delete process.env.VITE_MM_SECURITY_CLOCK_POLICY;
    else process.env.VITE_MM_SECURITY_CLOCK_POLICY = previousPolicy;
  });
  const root = fileURLToPath(new URL('../', import.meta.url));
  const config = { root, configFile: fileURLToPath(new URL('../vite.config.ts', import.meta.url)) };
  const info = t.mock.method(console, 'info', () => {});
  process.env.VITE_MM_SECURITY_CLOCK_POLICY = 'SIGNED_BACKEND_TIME';
  const build = await resolveConfig(config, 'build', 'release');
  assert.equal(build.define['import.meta.env.VITE_MM_SECURITY_CLOCK_POLICY'], '"SIGNED_BACKEND_TIME"');
  info.mock.resetCalls();
  // Even an invalid current environment cannot alter/block an existing artifact.
  process.env.VITE_MM_SECURITY_CLOCK_POLICY = 'invalid-after-build';
  const preview = await resolveConfig(config, 'serve', 'production', 'production', true);
  assert.equal(preview.define['import.meta.env.VITE_MM_SECURITY_CLOCK_POLICY'], undefined);
  assert.equal(preview.define['import.meta.env.VITE_MM_BUILD_ID'], undefined);
  const messages = info.mock.calls.map(call => call.arguments.join(' '));
  assert.ok(messages.some(message => message.includes('PWA preview serves an existing bundle')));
  assert.ok(messages.every(message => !message.includes('compiled into bundle')));
  // Build/dev startup must still reject the same invalid configuration.
  await assert.rejects(resolveConfig(config, 'serve', 'production'), /Invalid VITE_MM_SECURITY_CLOCK_POLICY/);
});
