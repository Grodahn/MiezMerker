import { useEffect, useState, type FormEvent } from 'react';
import { AppIcon } from '../ui/Graphic';
import { fetchSession, getAuthState, login, logout, selectOrganization, subscribeAuth } from './auth';

export function AuthPanel() {
  const [auth, setAuth] = useState(getAuthState);
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    const unsubscribe = subscribeAuth(setAuth);
    // Network availability must never prevent the offline collector from opening.
    if (navigator.onLine) void fetchSession().catch(() => {});
    return unsubscribe;
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
  return <details className="auth-panel">
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
    {error && <p role="alert">{error}</p>}
  </section></details>;
}
