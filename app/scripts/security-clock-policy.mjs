export function buildClockPolicy(mode, value) {
  const policy = value ?? (mode === 'signed-time' || mode === 'release' ? 'SIGNED_BACKEND_TIME' : 'FIELD_ALPHA_PHONE_CLOCK');
  if (!['FIELD_ALPHA_PHONE_CLOCK', 'SIGNED_BACKEND_TIME'].includes(policy)) {
    throw new Error(`Invalid VITE_MM_SECURITY_CLOCK_POLICY: ${policy}`);
  }
  if (['signed-time', 'release'].includes(mode) && policy !== 'SIGNED_BACKEND_TIME') {
    throw new Error(`${mode} requires SIGNED_BACKEND_TIME; refusing FIELD_ALPHA_PHONE_CLOCK`);
  }
  return policy;
}
