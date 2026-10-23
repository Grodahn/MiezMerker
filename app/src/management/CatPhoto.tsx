import { errorCodes, withCode, DiagnosticError, safeFailure } from '../diagnostics/errors';
import { useCallback, useEffect, useRef, useState } from 'react';
import { assets } from '../assets';
import { Graphic } from '../ui/Graphic';
import { fetchCsrfToken, markSessionExpired } from '../platform/auth';
import { contextSignal, useManagementContext } from './context';
import { requestOptions } from './data';
import type { Cat } from './common';

/**
 * Organization-owned cat profile photo (#95). The authoritative photo of the
 * local cat; foreign shared photos are never substituted here. Image bytes
 * live only as transient blob URLs: they are revoked on unmount, context
 * switch, new selection and after every reload. Nothing is written to
 * Dexie, IndexedDB, localStorage, Cache Storage or the service worker.
 */
export function photoUrl(organizationId: string, catId: string, version?: string | null): string {
  const base = `/api/v1/organizations/${encodeURIComponent(organizationId)}/cats/${encodeURIComponent(catId)}/photo`;
  return version ? `${base}?v=${encodeURIComponent(version)}` : base;
}

async function fetchPhotoBlob(url: string, signal: AbortSignal): Promise<Blob> {
  const response = await fetch(url, { ...requestOptions(signal), credentials: 'same-origin' });
  signal.throwIfAborted();
  if (response.status === 401) markSessionExpired();
  if (!response.ok) throw new DiagnosticError(errorCodes.PHOTO_FAILED, `Foto nicht verfügbar (${response.status}).`);
  const blob = await response.blob();
  signal.throwIfAborted();
  if (!blob.type.startsWith('image/')) throw new DiagnosticError(errorCodes.PHOTO_FAILED, 'Foto nicht verfügbar.');
  return blob;
}

type CatPhotoProps = {
  organizationId: string; cat: Cat; onChanged: () => void;
};

export function CatPhoto(props: CatPhotoProps) {
  const { generation } = useManagementContext();
  return <CatPhotoContent key={JSON.stringify([generation, props.organizationId, props.cat.id])} {...props} />;
}

function CatPhotoContent({ organizationId, cat, onChanged }: CatPhotoProps) {
  const [objectUrl, setObjectUrl] = useState<string | null>(null);
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);
  const [previewName, setPreviewName] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [message, setMessage] = useState('');
  const [confirmRemove, setConfirmRemove] = useState(false);
  const fileRef = useRef<HTMLInputElement>(null);
  const objectUrlRef = useRef<string | null>(null);
  const previewUrlRef = useRef<string | null>(null);
  const pending = useRef<AbortController | null>(null);
  const photoLoad = useRef<AbortController | null>(null);
  const version = cat.photoUpdatedAt ?? null;

  const revoke = useCallback((ref: React.MutableRefObject<string | null>, set: (value: string | null) => void) => {
    if (ref.current) URL.revokeObjectURL(ref.current);
    ref.current = null; set(null);
  }, []);

  function checkAccess(status: number) {
    if (status !== 401 && status !== 403) return;
    photoLoad.current?.abort();
    revoke(objectUrlRef, setObjectUrl);
    revoke(previewUrlRef, setPreviewUrl); setPreviewName('');
    if (fileRef.current) fileRef.current.value = '';
    if (status === 401) markSessionExpired();
  }

  // Transient bytes only: every load mints a fresh blob URL and revokes the
  // previous one. The contextSignal aborts stale loads after logout or an
  // organization switch so a late response never restores a foreign-context
  // photo.
  useEffect(() => {
    const controller = contextSignal();
    photoLoad.current = controller;
    setError('');
    if (!cat.id || !cat.hasPhoto) {
      revoke(objectUrlRef, setObjectUrl);
      return () => controller.abort();
    }
    let cancelled = false;
    void fetchPhotoBlob(photoUrl(organizationId, cat.id, version), controller.signal)
      .then(blob => {
        if (cancelled || controller.signal.aborted) return;
        revoke(objectUrlRef, setObjectUrl);
        const url = URL.createObjectURL(blob);
        objectUrlRef.current = url; setObjectUrl(url);
      })
      .catch(failure => {
        if (!cancelled && !controller.signal.aborted) {
          revoke(objectUrlRef, setObjectUrl);
          setError(failure instanceof TypeError
            ? 'Keine Verbindung zum Server. Bitte erneut versuchen.'
            : 'Foto konnte nicht geladen werden.');
        }
      });
    return () => { cancelled = true; controller.abort(); revoke(objectUrlRef, setObjectUrl); };
  }, [organizationId, cat.id, cat.hasPhoto, version, revoke]);

  useEffect(() => () => {
    pending.current?.abort();
    if (objectUrlRef.current) URL.revokeObjectURL(objectUrlRef.current);
    if (previewUrlRef.current) URL.revokeObjectURL(previewUrlRef.current);
    objectUrlRef.current = null; previewUrlRef.current = null;
  }, []);

  function selectFile(file: File | undefined) {
    setError(''); setMessage('');
    revoke(previewUrlRef, setPreviewUrl); setPreviewName('');
    if (!file) return;
    if (file.type !== 'image/jpeg' && file.type !== 'image/png') {
      if (fileRef.current) fileRef.current.value = '';
      setError('Bitte eine Bilddatei (JPEG oder PNG) wählen.');
      return;
    }
    const url = URL.createObjectURL(file);
    previewUrlRef.current = url; setPreviewUrl(url); setPreviewName(file.name);
  }

  async function save() {
    const file = fileRef.current?.files?.[0];
    if (!file || !cat.id || !previewUrl || pending.current) return;
    const controller = contextSignal();
    pending.current = controller;
    setBusy(true); setError(''); setMessage('');
    try {
      const token = await fetchCsrfToken();
      controller.signal.throwIfAborted();
      const form = new FormData();
      // The server ignores filenames and declared types; validation uses
      // decoded content only.
      form.append('file', file, 'katzenfoto');
      const response = await fetch(photoUrl(organizationId, cat.id), {
        ...requestOptions(controller.signal),
        credentials: 'same-origin',
        method: 'PUT',
        headers: { 'X-XSRF-TOKEN': token },
        body: form,
      });
      controller.signal.throwIfAborted();
      if (!response.ok) {
        checkAccess(response.status);
        throw new DiagnosticError(errorCodes.PHOTO_FAILED, response.status === 413 || response.status === 400
          ? 'Datei wird abgelehnt. Bitte JPEG/PNG in angemessener Größe wählen.'
          : response.status === 409
          ? 'Foto hat sich gerade geändert. Bitte erneut versuchen.'
          : response.status === 401 ? 'Sitzung abgelaufen. Bitte erneut anmelden.'
          : response.status === 403 ? 'Keine Berechtigung. Bitte die aktive Mitgliedschaft prüfen.'
          : 'Foto konnte nicht gespeichert werden.');
      }
      // A failed upload never replaces the current photo server-side; only a
      // confirmed save clears the preview and reloads the list.
      photoLoad.current?.abort();
      revoke(objectUrlRef, setObjectUrl);
      revoke(previewUrlRef, setPreviewUrl); setPreviewName('');
      if (fileRef.current) fileRef.current.value = '';
      setMessage('Foto gespeichert.');
      onChanged();
    } catch (failure) {
      if (!controller.signal.aborted) setError(failure instanceof TypeError
        ? 'Keine Verbindung zum Server. Bitte erneut versuchen.'
        : safeFailure(failure, 'Foto konnte nicht gespeichert werden.', errorCodes.PHOTO_FAILED));
    } finally {
      if (!controller.signal.aborted) setBusy(false);
      controller.abort();
      if (pending.current === controller) pending.current = null;
    }
  }

  async function remove() {
    if (!cat.id || pending.current) return;
    const controller = contextSignal();
    pending.current = controller;
    setBusy(true); setError(''); setMessage('');
    try {
      const token = await fetchCsrfToken();
      controller.signal.throwIfAborted();
      const response = await fetch(photoUrl(organizationId, cat.id), {
        ...requestOptions(controller.signal),
        credentials: 'same-origin',
        method: 'DELETE',
        headers: { 'X-XSRF-TOKEN': token },
      });
      controller.signal.throwIfAborted();
      if (!response.ok && response.status !== 404) {
        checkAccess(response.status);
        throw new DiagnosticError(errorCodes.PHOTO_FAILED, response.status === 401 ? 'Sitzung abgelaufen. Bitte erneut anmelden.'
          : response.status === 403 ? 'Keine Berechtigung. Bitte die aktive Mitgliedschaft prüfen.'
          : 'Foto konnte nicht entfernt werden.');
      }
      setConfirmRemove(false);
      photoLoad.current?.abort();
      revoke(objectUrlRef, setObjectUrl);
      setMessage('Foto entfernt.');
      onChanged();
    } catch (failure) {
      if (!controller.signal.aborted) setError(failure instanceof TypeError
        ? 'Keine Verbindung zum Server. Bitte erneut versuchen.'
        : safeFailure(failure, 'Foto konnte nicht entfernt werden.', errorCodes.PHOTO_FAILED));
    } finally {
      if (!controller.signal.aborted) setBusy(false);
      controller.abort();
      if (pending.current === controller) pending.current = null;
    }
  }

  const shown = previewUrl ?? objectUrl;
  const title = cat.name?.trim() || 'Ohne Namen';
  return <section className="detail cat-photo" aria-label="Katzenfoto">
    <h2>Foto</h2>
    {shown
      ? <img src={shown} alt={previewUrl ? `Vorschau: neues Foto für ${title}` : `Katzenfoto von ${title}`} className="cat-photo__image" />
      : <Graphic src={assets.placeholders.cat} alt="Kein Foto vorhanden (Platzhalter)" className="avatar cat-photo__placeholder" />}
    <div className="field">
      <label htmlFor={`cat-photo-file-${cat.id ?? 'new'}`}>Bild wählen (JPEG oder PNG)</label>
      <input id={`cat-photo-file-${cat.id ?? 'new'}`} ref={fileRef} type="file" accept="image/jpeg,image/png" disabled={busy}
        onChange={event => selectFile(event.currentTarget.files?.[0])} />
      {previewName && <p aria-live="polite">Ausgewählt: {previewName}. Noch nicht gespeichert.</p>}
    </div>
    <div className="actions">
      <button type="button" disabled={busy || !previewUrl} onClick={save}>Foto speichern</button>
      {cat.hasPhoto && !confirmRemove && <button type="button" disabled={busy} onClick={() => setConfirmRemove(true)}>Foto entfernen</button>}
      {cat.hasPhoto && confirmRemove && <>
        <span aria-live="polite">Wirklich entfernen?</span>
        <button type="button" disabled={busy} onClick={remove}>Ja, entfernen</button>
        <button type="button" disabled={busy} onClick={() => setConfirmRemove(false)}>Abbrechen</button>
      </>}
    </div>
    {error && <p role="alert" className="error">{withCode(error, errorCodes.PHOTO_FAILED)}</p>}
    {message && <p role="status">{message}</p>}
  </section>;
}
