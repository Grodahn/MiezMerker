import { AppIcon } from './Graphic';
import type { IconName } from '../assets';

export const destinations: { href: string; label: string; icon: IconName }[] = [
  { href: '/', label: 'Home', icon: 'home' },
  { href: '/sync', label: 'Sync', icon: 'sync' },
  { href: '/feeding-sites', label: 'Futterstellen', icon: 'feedingSite' },
  { href: '/cats', label: 'Katzen', icon: 'cat' },
];
export function sectionPath(path: string) {
  if (path === '/nodes') return '/feeding-sites'; // Legacy management until #68.
  return destinations.find(d => d.href !== '/' && (path === d.href || path.startsWith(`${d.href}/`)))?.href ?? path;
}
export function BottomNavigation({ path }: { path: string }) {
  return <nav className="bottom-navigation" aria-label="Bereiche"><ul>
    {destinations.map(({ href, label, icon }) => <li key={href}><a href={href}
      aria-current={sectionPath(path) === href ? 'page' : undefined}>
      <AppIcon name={icon}/><span>{label}</span></a></li>)}
  </ul></nav>;
}


export const documentNavigation = {
  replace: (path: string) => window.location.replace(path),
};
