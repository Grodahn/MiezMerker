import type { ReactNode } from 'react';
import { assets } from '../assets';
import { AuthPanel } from '../platform/AuthPanel';
import { Graphic } from './Graphic';
import { BottomNavigation } from './navigation';

export function BrandHeader({ account = false }: { account?: boolean }) {
  return <header className="app-header"><div className="header-content">
    <div className="brand"><Graphic src={assets.brand.logo} alt="MiezMerker" className="brand-logo"/>
      <span aria-hidden="true">MiezMerker</span></div>
    {account && <AuthPanel/>}
  </div></header>;
}
export function ApplicationShell({ path, authenticated, children }: {
  path: string; authenticated: boolean; children: ReactNode;
}) {
  return <div className="application-shell">
    <a className="skip-link" href="#main-content">Zum Inhalt</a>
    <BrandHeader account={authenticated}/>
    <main id="main-content" tabIndex={-1} style={{ backgroundImage: `url("${assets.illustrations.background}")` }}><div className="page-container">{children}</div></main>
    {authenticated && <BottomNavigation path={path}/>}
  </div>;
}
