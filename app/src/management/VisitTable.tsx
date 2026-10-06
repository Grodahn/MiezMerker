import { catName, duration, millis, siteName, Table, type Cat, type Site, type Visit } from './common';

export function VisitTable({ rows, sites, cats }: { rows: Visit[]; sites: Site[]; cats: Cat[] }) {
  if (!rows.length) return <p>Keine abgeleiteten Besuche vorhanden.</p>;
  return <Table label="Abgeleitete Besuche" headings={['Beginn / Ende', 'Dauer / Reads', 'Futterstelle', 'Katze / Chip', 'Ableitung']}>
    {rows.map(row => <tr key={row.id}><td>{millis(row.startAtMillis)}<small>bis {millis(row.endAtMillis)}</small></td>
      <td>{duration(row)}<small>{row.observationCount} Reads</small></td>
      <td>{siteName(sites, row.feedingSiteId)}</td><td>{catName(cats, row.chipId)}<code>{row.chipId}</code></td>
      <td>{row.algorithmVersion}<small>Gap: {row.gapSeconds} s</small><details><summary>Herkunft</summary>
        <dl><dt>Erste Rohbeobachtung</dt><dd>{row.firstObservationId}</dd>
          <dt>Letzte Rohbeobachtung</dt><dd>{row.lastObservationId}</dd></dl></details></td></tr>)}
  </Table>;
}
