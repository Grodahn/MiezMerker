import { useEffect, useRef, useState } from 'react';
import { contextSignal } from './context';
import { fetchCsrfToken, markSessionExpired } from '../platform/auth';

export function errorMessage(failure: unknown): string {
  return failure instanceof Error ? failure.message : 'Daten konnten nicht geladen werden.';
}
export function result<T>({ data, response }: { data?: T; response: Response }): T {
  if (!response.ok) {
    // #31: an expired/missing backend session must immediately hide protected
    // organization data. Central transition forces the app-shell gate back to
    // login/offline-sync-only; the auth subscription aborts stale loads and
    // unmounts management views so no stale data stays visible.
    if (response.status === 401) {
      try { markSessionExpired(); } catch { /* Gate transition must not mask the user message. */ }
    }
    const messages: Record<number, string> = {
      400: 'Bitte Eingaben prüfen.', 401: 'Sitzung abgelaufen. Bitte erneut anmelden.',
      403: 'Keine Berechtigung. Bitte die aktive Mitgliedschaft prüfen.',
      404: 'Eintrag in dieser Organisation nicht verfügbar.',
      409: 'Konflikt: Chip bereits vorhanden oder zeitliche Zuordnung überlappt. Bitte neu laden.',
    };
    throw new Error(messages[response.status] ?? 'Server nicht erreichbar. Bitte erneut versuchen.');
  }
  if (data === undefined) throw new Error('Serverantwort unvollständig. Bitte erneut versuchen.');
  return data;
}
export function requestOptions(signal: AbortSignal) {
  return { signal: AbortSignal.any([signal, AbortSignal.timeout(15_000)]), cache: 'no-store' as const };
}
export function useLoad<T>(load: (signal: AbortSignal) => Promise<T>) {
  const [state, setState] = useState<{ data?: T; error?: string; loading: boolean }>({ loading: true });
  const [revision, setRevision] = useState(0);
  useEffect(() => {
    const controller = contextSignal();
    setState({ loading: true });
    void load(controller.signal).then(data => {
      if (!controller.signal.aborted) setState({ data, loading: false });
    }).catch(failure => {
      if (!controller.signal.aborted) setState({ error: failure instanceof TypeError
        ? 'Keine Verbindung zum Server. Bitte erneut versuchen.' : errorMessage(failure), loading: false });
    });
    return () => controller.abort();
  }, [load, revision]);
  return { ...state, reload: () => setRevision(value => value + 1) };
}

export function useMutation() {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [message, setMessage] = useState('');
  const pending = useRef<AbortController | null>(null);
  const mounted = useRef(true);
  useEffect(() => {
    mounted.current = true;
    return () => { mounted.current = false; pending.current?.abort(); };
  }, []);
  async function run(action: (headers: Record<string, string>, signal: AbortSignal) => Promise<unknown>,
      done: () => void, success = 'Gespeichert.') {
    if (pending.current || !mounted.current) return;
    const controller = contextSignal();
    pending.current = controller;
    setBusy(true); setError(''); setMessage('');
    try {
      const token = await fetchCsrfToken();
      controller.signal.throwIfAborted();
      await action({ 'X-XSRF-TOKEN': token }, controller.signal);
      controller.signal.throwIfAborted();
      setMessage(success); done();
    } catch (failure) {
      if (!controller.signal.aborted) setError(failure instanceof TypeError
        ? 'Keine Verbindung zum Server. Bitte erneut versuchen.' : errorMessage(failure));
    } finally {
      if (!controller.signal.aborted) setBusy(false);
      controller.abort(); // Release the auth subscription after this operation.
      if (pending.current === controller) pending.current = null;
    }
  }
  return { busy, error, message, run };
}
