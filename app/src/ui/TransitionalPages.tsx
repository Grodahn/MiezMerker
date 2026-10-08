import { assets } from '../assets';
import { Graphic } from './Graphic';
import { Card, ListLink } from './primitives';

// Route adapter only. Final content belongs to #68.
export function FeedingSitesEntry() {
  return <section aria-labelledby="sites-title"><h1 id="sites-title">Futterstellen</h1>
    <Card><Graphic src={assets.placeholders.feedingSite} alt="" className="page-illustration"/>
      <p>Die Futterstellenübersicht wird hier ergänzt. Bis dahin sind Näpfe und ihre Zuordnungen in der bisherigen Ansicht verfügbar.</p>
      <ListLink href="/nodes" icon="feedingSite">Näpfe und Zuordnungen öffnen</ListLink>
    </Card>
  </section>;
}
