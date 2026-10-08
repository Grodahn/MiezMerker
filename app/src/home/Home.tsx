import { assets } from '../assets';
import { AppIcon, Graphic } from '../ui/Graphic';
import { ListLink } from '../ui/primitives';
import './home.css';

export function Home() {
  return <section className="home-page" aria-labelledby="home-title">
    <div className="home-intro">
      <h1 id="home-title">Hallo!</h1>
      <p>Schön, dass du da bist. Lies vor Ort eine Futterstelle aus.</p>
    </div>
    <Graphic src={assets.illustrations.home} alt="" className="page-illustration home-illustration"/>
    <a className="button button--primary home-cta" href="/sync">
      <AppIcon name="sync"/><span>Futterstelle auslesen</span><AppIcon name="arrow"/>
    </a>
    <div className="home-destinations">
      <ListLink href="/feeding-sites" icon="feedingSite">Futterstellen</ListLink>
      <ListLink href="/cats" icon="cat">Katzen</ListLink>
    </div>
  </section>;
}
