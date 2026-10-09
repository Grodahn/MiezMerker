import { useEffect, useRef, useState, type FormEvent } from 'react';
import { AppIcon } from '../ui/Graphic';
import { Status } from '../ui/Status';
import { errorStatus } from '../ui/status-messages';
import { fetchSession, getAuthState, login, logout, selectOrganization, subscribeAuth } from './auth';

export function AuthPanel() {
  const panel = useRef<HTMLDetailsElement>(null);
  const [auth, setAuth] = useState(getAuthState);
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    const unsubscribe = subscribeAuth(setAuth);
    // Network availability must never prevent the offline collector from opening.
    if (navigator.onLine) void fetchSession().catch(() => {});
    const dismissOutside = (event: PointerEvent) => {
      if (panel.current && event.target instanceof Node && !panel.current.contains(event.target)) {
        panel.current.open = false;
      }
    };
    document.addEventListener('pointerdown', dismissOutside);
    return () => {
      unsubscribe();
      document.removeEventListener('pointerdown', dismissOutside);
    };
  }, []);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError('');
    try { await login(email, password); setPassword(''); }
    catch (failure) { setError(failure instanceof Error ? failure.message : 'Login fehlgeschlagen'); }
    finally { setBusy(false); }
  }

  async function signOut() {
    setBusy(true);
    setError('');
    try { await logout(); }
    catch (failure) { setError(failure instanceof Error ? failure.message : 'Abmelden fehlgeschlagen'); }
    finally { setBusy(false); }
  }

  const memberships = auth.user?.memberships.filter(m => m.status === 'ACTIVE') ?? [];
  // #32: global display name with email fallback for pre-#32 users without a name.
  const accountLabel = auth.user?.displayName?.trim() ? auth.user.displayName : auth.user?.email;
  return <details ref={panel} className="auth-panel"
    onBlur={event => {
      if (!event.currentTarget.contains(event.relatedTarget)) event.currentTarget.open = false;
    }}
    onKeyDown={event => {
      if (event.key === 'Escape' && event.currentTarget.open) {
        event.preventDefault();
        event.currentTarget.open = false;
        event.currentTarget.querySelector('summary')?.focus();
      }
    }}>
    <summary><AppIcon name="account"/><span><span>Konto<span className="sr-only"> &amp; Organisation</span></span>
      <small>{memberships.find(m => m.organizationId === auth.activeOrganizationId)?.organizationName ?? 'Organisation auswählen'}</small>
    </span></summary>
    <section aria-label="Benutzerkonto" className="account-content">
    {auth.user ? <>
      <p>Angemeldet als {accountLabel}</p>
      {memberships.length ? <label>Aktive Organisation
        <select value={auth.activeOrganizationId ?? ''}
          onChange={event => selectOrganization(event.target.value)}>
          <option value="" disabled>Organisation auswählen</option>
          {memberships.map(m => <option key={m.membershipId} value={m.organizationId}>
            {m.organizationName} ({m.role})
          </option>)}
        </select>
      </label> : <p>Keine aktive Organisationsmitgliedschaft.</p>}
      <button type="button" disabled={busy} onClick={() => void signOut()}><AppIcon name="logout"/>Abmelden</button>
    </> : <form onSubmit={event => void submit(event)}>
      <label>E-Mail<input type="email" autoComplete="username" required value={email}
        onChange={event => setEmail(event.target.value)}/></label>
      <label>Passwort<input type="password" autoComplete="current-password" required value={password}
        onChange={event => setPassword(event.target.value)}/></label>
      <button type="submit" disabled={busy}>Anmelden</button>
    </form>}
    {busy && <Status kind="loading" title="Konto wird aktualisiert …"/>}
    {error && <Status {...errorStatus(error)}/>}
  </section></details>;
}
