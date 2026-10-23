export type SecurityClockPolicy = 'FIELD_ALPHA_PHONE_CLOCK' | 'SIGNED_BACKEND_TIME';

// Build-time contract, shared by diagnostics and every BLE policy decision.
export function securityClockPolicy(value = import.meta.env.VITE_MM_SECURITY_CLOCK_POLICY): SecurityClockPolicy {
  if (value === undefined) return 'FIELD_ALPHA_PHONE_CLOCK';
  if (value === 'FIELD_ALPHA_PHONE_CLOCK' || value === 'SIGNED_BACKEND_TIME') return value;
  throw new Error(`Ungültige PWA-Sicherheitszeit-Policy: ${value}`);
}
