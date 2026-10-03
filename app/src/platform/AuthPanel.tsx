import { useEffect, useState, type FormEvent } from 'react';
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
  return <section aria-label="Benutzerkonto" className="auth-panel">
    {auth.user ? <>
      <p>Angemeldet als {auth.user.email}</p>
      {memberships.length ? <label>Aktive Organisation
        <select value={auth.activeOrganizationId ?? ''}
          onChange={event => selectOrganization(event.target.value)}>
          <option value="" disabled>Organisation auswählen</option>
          {memberships.map(m => <option key={m.membershipId} value={m.organizationId}>
            {m.organizationName} ({m.role})
          </option>)}
        </select>
      </label> : <p>Keine aktive Organisationsmitgliedschaft.</p>}
      <button type="button" disabled={busy} onClick={() => void signOut()}>Abmelden</button>
    </> : <form onSubmit={event => void submit(event)}>
      <label>E-Mail<input type="email" autoComplete="username" required value={email}
        onChange={event => setEmail(event.target.value)}/></label>
      <label>Passwort<input type="password" autoComplete="current-password" required value={password}
        onChange={event => setPassword(event.target.value)}/></label>
      <button type="submit" disabled={busy}>Anmelden</button>
    </form>}
    {error && <p role="alert">{error}</p>}
  </section>;
}
