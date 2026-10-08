import type { StatusKind } from './Status';

export interface StatusMessage { kind: StatusKind; title: string; message: string; retry?: boolean }
export const statusMessages = {
  loading: { kind: 'loading', title: 'Daten werden geladen …', message: 'Bitte einen Moment warten.' },
  offline: { kind: 'offline', title: 'Offline', message: 'Verwaltung benötigt eine Serververbindung. Der Vor-Ort-Sync bleibt mit gültiger Offline-Berechtigung verfügbar.' },
  localSuccess: { kind: 'success', title: 'Napf ausgelesen', message: 'Fertig — Beobachtungen sicher vom Node übernommen und quittiert. Der Backend-Upload wird separat angezeigt.' },
  uploadPending: { kind: 'offline', title: 'Upload noch ausstehend', message: 'Lokal gespeicherte Daten bleiben erhalten. Bitte mit Internet erneut versuchen.' },
  uploadSuccess: { kind: 'success', title: 'Daten an Server übertragen', message: 'Der Backend-Upload ist bestätigt.' },
  noUploads: { kind: 'empty', title: 'Keine ausstehenden Uploads', message: 'Aktuell sind keine lokal gespeicherten Daten zur Übertragung offen.' },
  unknownClock: { kind: 'info', title: 'Keine verlässliche Sichtungszeit', message: 'Uhrzeit unbekannt (UNKNOWN) oder ungültig. Der Serverempfang ist keine genaue Sichtungszeit.' },
  emptyCats: { kind: 'empty', title: 'Noch keine Katzen oder Chips vorhanden.', message: 'Nach einem bestätigten Backend-Upload erscheinen neue Chips hier.' },
  emptyNodes: { kind: 'empty', title: 'Noch keine Nodes vorhanden.', message: 'Ein ADMIN kann im Vor-Ort-Sync einen physischen Node claimen; die Futterstellenzuordnung erfolgt im Admin-Backend.' },
  emptySites: { kind: 'empty', title: 'Keine Futterstellen vorhanden.', message: 'Bitte zuerst im Admin-Backend eine Futterstelle anlegen. Es wird keine Futterstelle in der PWA erstellt.' },
  unassigned: { kind: 'empty', title: 'Noch keine Zuordnung vorhanden.', message: 'Noch keiner Futterstelle zugeordnet.' },
  emptyVisits: { kind: 'empty', title: 'Keine abgeleiteten Besuche vorhanden.', message: 'Es liegen noch keine Besuchsdaten für diese Auswahl vor.' },
} satisfies Record<string, StatusMessage>;

export const syncHelp = [
  'Bluetooth benötigt einen unterstützten Browser und HTTPS, zum Beispiel Chrome auf Android.',
  'Bei verweigerter Berechtigung bitte den Zugriff in den Browser-Einstellungen erlauben und erneut wählen.',
  'Bei leerer oder abgebrochener Geräteauswahl bitte den Napf einschalten, näher herangehen und erneut auswählen.',
  'Bei mehreren Näpfen erfolgt die Auswahl im Browser-Dialog.',
  'Nach einem Verbindungsabbruch können Sie erneut versuchen. Bereits gespeicherte Beobachtungen werden ohne Duplikate wiederholt.',
  'Bei fremder Organisation werden nur öffentliche Eigentümerangaben gezeigt; keine Beobachtungen abgerufen.',
  'Bei fehlender oder abgelaufener Offline-Berechtigung bitte einmal mit Internet anmelden und erneuern.',
  'Bei vollem Speicher bitte Browser-Speicher freigeben. Ohne dauerhafte Speicherung wird dem Napf keine Übernahme quittiert.',
  'Bei ausstehendem Backend-Upload bleibt ein bestätigter Vor-Ort-Sync gültig. Bitte später mit Internet erneut hochladen.',
  'Bei inkompatibler Version bitte Firmware und PWA-Version prüfen lassen.',
];

// Display adapter for the existing error strings. Never infers a successful
// operation or changes transport/auth state. Unknown domain errors retain detail.
export function errorStatus(message: string): StatusMessage {
  if (/HTTP 401|Sitzung abgelaufen|Session.*(expired|abgelaufen)|zuerst anmelden/i.test(message)) {
    return { kind: 'error', title: 'Bitte erneut anmelden', message: 'Die Online-Sitzung fehlt oder ist abgelaufen. Bitte im Konto anmelden. Lokal gespeicherte Daten bleiben erhalten.' };
  }
  if (/Credential|Offline-Berechtigung/i.test(message)) {
    return { kind: 'error', title: 'Offline-Berechtigung prüfen', message: friendlyError(message) + ' Bitte mit Internet im Konto anmelden und erneut versuchen.' };
  }
  if (/Berechtigung|permission|notallowed|securityerror|HTTP 403|ACTIVE ADMIN/i.test(message)) {
    return { kind: 'error', title: 'Zugriff nicht erlaubt', message: friendlyError(message) + ' Bitte Berechtigungen und aktive Organisation prüfen.' };
  }
  if (/^Auswahl abgebrochen/i.test(message)) {
    return { kind: 'info', title: 'Auswahl abgebrochen', message, retry: true };
  }
  if (/Geräteauswahl beendet/i.test(message)) {
    return { kind: 'info', title: 'Geräteauswahl beendet', message, retry: true };
  }
  if (/Bluetooth.*(deaktiviert|nicht verfügbar|ausgeschaltet|not available|powered off|disabled)/i.test(message)) {
    return { kind: 'error', title: 'Bluetooth nicht verfügbar', message: 'Bitte Bluetooth am Gerät einschalten und erneut versuchen.', retry: true };
  }
  if (/unterstützt kein Web Bluetooth|unsupported/i.test(message)) {
    return { kind: 'error', title: 'Bluetooth wird nicht unterstützt', message: 'Bitte einen Browser mit Web Bluetooth in einer sicheren HTTPS-Verbindung verwenden, zum Beispiel Chrome auf Android.' };
  }
  if (/timeout|timed out|Zeitüberschreitung|zeitüberschritten|Zeitlimit/i.test(message)) {
    return { kind: 'error', title: 'Zeitüberschreitung', message: 'Die Verbindung hat nicht rechtzeitig geantwortet. Bitte Verbindung prüfen und erneut versuchen.', retry: true };
  }
  if (/Kein Node gefunden|nicht erreichbar|Verbindung.*(fehlgeschlagen|unterbrochen)|disconnected|NetworkError/i.test(message)) {
    return { kind: 'error', title: 'Verbindung nicht verfügbar', message: friendlyError(message), retry: true };
  }
  return { kind: 'error', title: 'Vorgang nicht abgeschlossen', message: friendlyError(message), retry: true };
}

export function friendlyError(message: string): string {
  return message.replace(/Failed to fetch|Network request failed|Load failed|fetch failed/gi,
    'Keine Verbindung zum Server. Bitte Internetverbindung prüfen und erneut versuchen.');
}

// requestNodeDevice wraps browser errors; inspect the preserved cause for UI
// wording without touching the platform contract. NotFoundError is ambiguous.
export function bluetoothSelectionMessage(error: unknown): string {
  // DOMException and errors can originate in a different realm (browser/jsdom).
  const outer = error && typeof error === 'object' ? error : undefined;
  const cause = outer && 'cause' in outer && outer.cause && typeof outer.cause === 'object' ? outer.cause : outer;
  const name = cause && 'name' in cause ? cause.name : undefined;
  if (name === 'NotFoundError') return 'Geräteauswahl beendet: Es wurde kein Napf ausgewählt. Die Auswahl wurde abgebrochen oder kein passendes Gerät angezeigt. Bei Bedarf Napf einschalten und erneut auswählen.';
  if (name === 'AbortError') return 'Auswahl abgebrochen. Sie können den Napf bei Bedarf erneut auswählen.';
  if (name === 'NotAllowedError' || name === 'SecurityError') return 'Bluetooth-Berechtigung verweigert. Bitte Zugriff in den Browser-Einstellungen erlauben und erneut auswählen.';
  if (name === 'NetworkError') return 'Bluetooth-Verbindung fehlgeschlagen. Bitte Napf einschalten, näher herangehen und erneut versuchen.';
  return outer && 'message' in outer && typeof outer.message === 'string' ? outer.message
    : 'Bluetooth-Verbindung konnte nicht hergestellt werden. Bitte erneut versuchen.';
}
