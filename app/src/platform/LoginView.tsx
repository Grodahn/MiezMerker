// #31: login-only view. Reuses the current login behavior (same auth API,
// same organization restore logic in auth.ts). Rendered without navigation or
// protected contents until authorization is established.
import { useId, useState, type FormEvent } from 'react';
import { login } from './auth';
import { assets } from '../assets';
import { Graphic } from '../ui/Graphic';
import { Status } from '../ui/Status';
import { statusMessages } from '../ui/status-messages';
import './login.css';

export function LoginView({ online }: { online: boolean }) {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const feedbackId = useId();

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (busy) return;
    setBusy(true);
    setError('');
    try {
      await login(email, password);
      setPassword('');
    } catch (failure) {
      // Presentation only: never display transport, server or credential details.
      setError(failure instanceof TypeError
        ? 'Die Verbindung zum Server ist nicht verfügbar. Bitte die Internetverbindung prüfen und erneut versuchen.'
        : 'Bitte E-Mail und Passwort prüfen und erneut versuchen. Falls die Anmeldung weiterhin nicht möglich ist, bitte später versuchen.');
    } finally {
      setBusy(false);
    }
  }

  return <section className="login-page" aria-label="Anmeldung">
    <Graphic src={assets.illustrations.loginBackground} alt="" className="login-background"/>
    <div className="login-card card">
      <Graphic src={assets.brand.logo} alt="MiezMerker" className="login-logo"/>
      <p className="login-welcome">Schön, dass du da bist.</p>
      <h1>Anmelden</h1>
      <p className="login-intro">Gemeinsam für glückliche Katzen an jedem Futterplatz.</p>
      {!online && <Status {...statusMessages.offline}/>}
      <form aria-label="Anmelden" onSubmit={event => void submit(event)}>
        <label>E-Mail<input type="email" name="email" autoComplete="username" autoCapitalize="none" spellCheck={false} required value={email}
          aria-describedby={error ? feedbackId : undefined} readOnly={busy}
          onChange={event => setEmail(event.target.value)}/></label>
        <label>Passwort<input type="password" name="password" autoComplete="current-password" required value={password}
          aria-describedby={error ? feedbackId : undefined} readOnly={busy}
          onChange={event => setPassword(event.target.value)}/></label>
        <button className="button--primary login-submit" type="submit" disabled={busy}>{busy ? 'Anmeldung läuft …' : 'Anmelden'}</button>
      </form>
      {busy && <Status kind="loading" title="Anmeldung wird geprüft …"
        longWaitMessage="Das dauert länger. Bitte die Verbindung prüfen und einen Moment warten."/>}
      {error && <div id={feedbackId}><Status kind="error" title="Anmeldung nicht möglich" message={error}/></div>}
    </div>
  </section>;
}
