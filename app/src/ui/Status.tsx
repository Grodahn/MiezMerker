import { useEffect, useId, useState, type ReactNode } from 'react';
import { assets } from '../assets';
import { Graphic } from './Graphic';
import { Button, Card } from './primitives';

export type StatusKind = 'loading' | 'empty' | 'error' | 'offline' | 'success' | 'info';
export interface StatusProps {
  kind: StatusKind;
  title: string;
  message?: string;
  graphic?: keyof typeof assets.status;
  scope?: string;
  progress?: { value: number; max: number };
  action?: { label: string; onClick: () => void; disabled?: boolean };
  children?: ReactNode;
  longWaitMessage?: string;
}

// Presentation only: callers own truth, retries and operation lifetime. Optional
// scope and real totals also support independently rendered per-node outcomes.
export function Status({ kind, title, message, graphic = kind, scope, progress, action, children,
  longWaitMessage = 'Das dauert länger. Bitte Verbindung prüfen. Sie können weiterhin die Navigation verwenden.' }: StatusProps) {
  const titleId = useId();
  const [liveReady, setLiveReady] = useState(false);
  useEffect(() => {
    // Polite regions must exist before their initial text is inserted.
    const timer = setTimeout(() => setLiveReady(true), 0);
    return () => clearTimeout(timer);
  }, []);
  const [longWait, setLongWait] = useState(false);
  useEffect(() => {
    setLongWait(false);
    if (kind !== 'loading') return;
    const timer = setTimeout(() => setLongWait(true), 15_000);
    return () => clearTimeout(timer);
  }, [kind]);
  const measured = progress && Number.isFinite(progress.value) && Number.isFinite(progress.max) &&
    progress.max > 0 && progress.value >= 0 && progress.value <= progress.max;
  const content = <>
    {scope && <p className="status-scope">{scope}</p>}
    <p id={titleId} className="status-title">{title}</p>
    {message && message !== title && <p>{message}</p>}
    {longWait && kind === 'loading' && <p>{longWaitMessage}</p>}
    {measured && <p>{progress.value} von {progress.max}</p>}
  </>;
  const announce = liveReady || kind === 'error';
  return <Card className={`status-card status-card--${kind}`}>
    <Graphic src={assets.status[graphic]} alt="" className="status-graphic"/>
    <div className="status-content">
      <div role={kind === 'error' ? 'alert' : 'status'} aria-live={kind === 'error' ? 'assertive' : 'polite'} aria-atomic="true">
        {announce && content}
      </div>
      {!announce && <div>{content}</div>}
      {kind === 'loading' && <progress aria-labelledby={titleId}
        value={measured ? progress.value : undefined} max={measured ? progress.max : undefined}/>}
      {children}
      {action && <Button type="button" variant="secondary" disabled={action.disabled} onClick={action.onClick}>{action.label}</Button>}
    </div>
  </Card>;
}
