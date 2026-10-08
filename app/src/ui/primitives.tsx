import { useId, type ButtonHTMLAttributes, type InputHTMLAttributes, type ReactNode } from 'react';
import { AppIcon } from './Graphic';
import type { IconName } from '../assets';

export function Button({ variant = 'primary', className = '', ...props }:
  ButtonHTMLAttributes<HTMLButtonElement> & { variant?: 'primary' | 'secondary' }) {
  return <button {...props} className={`button button--${variant} ${className}`}/>;
}
export function Card({ children, className = '' }: { children: ReactNode; className?: string }) {
  return <div className={`card ${className}`}>{children}</div>;
}
export function Field({ label, id, ...props }: InputHTMLAttributes<HTMLInputElement> & { label: string }) {
  const generatedId = useId();
  return <div className="field"><label htmlFor={id ?? generatedId}>{label}</label>
    <input {...props} id={id ?? generatedId}/></div>;
}
export function ListLink({ href, icon, children }: { href: string; icon: IconName; children: ReactNode }) {
  return <a className="list-item" href={href}><AppIcon name={icon}/><span>{children}</span><AppIcon name="arrow"/></a>;
}
