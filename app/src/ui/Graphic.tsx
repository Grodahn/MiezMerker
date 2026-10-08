import { useState } from 'react';
import { assets, type IconName } from '../assets';

export function Graphic({ src, alt, className = '', fallback = assets.placeholders.generic }: {
  src: string; alt: string; className?: string; fallback?: string;
}) {
  const [failedSource, setFailedSource] = useState<string>();
  return <img className={`graphic ${className}`} src={failedSource === src ? fallback : src}
    alt={alt} aria-hidden={alt === '' ? true : undefined}
    onError={() => setFailedSource(src)}/>;
}

// Icons alongside text are decorative; standalone icons require a label.
export function AppIcon({ name, label }: { name: IconName; label?: string }) {
  return <Graphic src={assets.icons[name]} fallback={assets.icons.error}
    alt={label ?? ''} className="app-icon"/>;
}
