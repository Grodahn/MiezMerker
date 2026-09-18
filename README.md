# MiezMerker

MiezMerker ist ein Open-Source-Hardware-/Softwareprojekt zur Unterstützung der Betreuung von Streunerkatzen an Futterstellen.

Ein kleiner, batteriebetriebener **NapfNode** erkennt gechipte Katzen über ihren 134,2-kHz-Tierchip, versieht jeden Read mit Zeitstempel und speichert die Rohdaten lokal. Beim nächsten Besuch an der Futterstelle wird die **Android-App** geöffnet, übernimmt die Daten per Bluetooth LE und synchronisiert sie anschließend mit einem zentralen Backend. Eine kleine Weboberfläche ermöglicht die Verwaltung von Futterstellen, Nodes und Katzen sowie die Auswertung der erfassten Besuche.

## Grundidee

```text
Katze
  ↓
134,2-kHz-Tierchip
  ↓
NapfNode
  ↓ Bluetooth LE
Android-Collector
  ↓ HTTPS
Backend
  ↓
Webapp / Auswertung
```

Der Node soll bewusst einfach und robust bleiben: **Er misst, das Backend interpretiert.** Gespeichert werden zunächst Rohbeobachtungen wie

```text
node_id
sequence
chip_id
timestamp
```

Erst serverseitig werden daraus zusammenhängende Besuche abgeleitet. Dadurch bleiben die ursprünglichen Messdaten erhalten und die Auswertungslogik kann später verändert oder verbessert werden.

## MVP-Ziele

- 134,2-kHz-Tierchips an einer Futterstelle zuverlässig erkennen
- mindestens ca. 48 Stunden Betrieb mit einer kleinen USB-Powerbank
- Rohdaten lokal und ausfallsicher speichern
- manueller Vor-Ort-Sync: **App öffnen → Daten übernehmen → „Fertig“**
- kein Mobilfunk oder WLAN am Napf erforderlich
- Offline-Pufferung auf dem Smartphone, falls vor Ort kein Internet verfügbar ist
- zentrale Verwaltung von Futterstellen, Nodes, Katzen und Beobachtungen
- reproduzierbare Visit-Aggregation im Backend
- Firmware-Core und Sync-Verhalten weitgehend ohne echte Hardware in Simulator und CI testbar

Die aktuelle Planung und Aufteilung des MVP befindet sich in [Epic #1](https://github.com/Grodahn/MiezMerker/issues/1).
