import type { ReactNode } from 'react';
import { assets } from '../assets';
import { AuthPanel } from '../platform/AuthPanel';
import { Graphic } from './Graphic';
import { BottomNavigation } from './navigation';
import { securityClockPolicy } from '../platform/security-clock-policy';
import { version } from '../../package.json';

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
    <main id="main-content" tabIndex={-1} style={{ backgroundImage: `url("${assets.illustrations.background}")` }}><div className="page-container">{children}
      <details><summary>Zeit-Policy / Diagnose</summary><p>PWA v{version} · {securityClockPolicy()}</p>
        <p>Build: {import.meta.env.VITE_MM_BUILD_ID ?? 'Test'}</p>
        <p>{securityClockPolicy() === 'FIELD_ALPHA_PHONE_CLOCK'
          ? 'Feldtest: unbestätigte Handyzeit. Mit Credential und AppDevice-Schlüssel kann eine zurückgestellte Uhr Ablaufprüfungen einschließlich ACK/COMPACT umgehen.'
          : 'Signierte Backend-Zeit: ein Napf ohne vertrauenswürdige UTC benötigt den signierten Bootstrap.'}</p>
        <p>Policy gilt seit dem PWA-Build. Nach Änderungen neu starten und ein verfügbares App-Update übernehmen.</p>
      </details>
    </div></main>
    {authenticated && <BottomNavigation path={path}/>}
  </div>;
}
