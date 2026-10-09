import { catName, duration, millis, siteName, type Cat, type Site, type Visit } from './common';
import { Card } from '../ui/primitives';
import { Status } from '../ui/Status';
import { statusMessages } from '../ui/status-messages';

export function VisitTable({ rows, sites, cats }: { rows: Visit[]; sites: Site[]; cats: Cat[] }) {
  if (!rows.length) return <Status {...statusMessages.emptyVisits}/>;
  return <ul className="visit-list" aria-label="Abgeleitete Besuche">
    {rows.map(row => <li key={row.id}>
      <Card className="visit-card">
        <h3 className="visit-card__title">{catName(cats, row.chipId)}</h3>
        <p className="visit-card__chip">Chip-ID: <code>{row.chipId}</code></p>
        <dl className="visit-card__list">
          <div><dt>Beginn</dt><dd>{millis(row.startAtMillis)}</dd></div>
          <div><dt>Ende</dt><dd>{millis(row.endAtMillis)}</dd></div>
          <div><dt>Dauer</dt><dd>{duration(row)} <small>({row.observationCount} Reads)</small></dd></div>
          <div><dt>Futterstelle (historisch)</dt><dd>{siteName(sites, row.feedingSiteId)}</dd></div>
          <div><dt>Ableitung</dt><dd>{row.algorithmVersion}<small> Gap: {row.gapSeconds} s</small></dd></div>
        </dl>
        <details><summary>Herkunft</summary>
          <dl><dt>Erste Rohbeobachtung</dt><dd>{row.firstObservationId}</dd>
            <dt>Letzte Rohbeobachtung</dt><dd>{row.lastObservationId}</dd></dl></details>
      </Card>
    </li>)}
  </ul>;
}
