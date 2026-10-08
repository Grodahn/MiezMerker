// #31: login-only view. Reuses the current login behavior (same auth API,
// same organization restore logic in auth.ts). Rendered without navigation or
// protected contents until authorization is established.
import { useState, type FormEvent } from 'react';
import { login } from './auth';
import { Status } from '../ui/Status';
import { errorStatus, statusMessages } from '../ui/status-messages';

export function LoginView({ online }: { online: boolean }) {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError('');
    try {
      await login(email, password);
      setPassword('');
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : 'Login fehlgeschlagen');
    } finally {
      setBusy(false);
    }
  }

  return <section aria-label="Anmeldung"><h1>Anmelden</h1>
    <p>Bitte anmelden, um fortzufahren.</p>
    {!online && <Status {...statusMessages.offline}/>}
    <form onSubmit={event => void submit(event)}>
      <label>E-Mail<input type="email" autoComplete="username" required value={email}
        onChange={event => setEmail(event.target.value)}/></label>
      <label>Passwort<input type="password" autoComplete="current-password" required value={password}
        onChange={event => setPassword(event.target.value)}/></label>
      <button type="submit" disabled={busy}>Anmelden</button>
    </form>
    {busy && <Status kind="loading" title="Anmeldung wird geprüft …"/>}
    {error && <Status {...errorStatus(error)}/>}
  </section>;
}
