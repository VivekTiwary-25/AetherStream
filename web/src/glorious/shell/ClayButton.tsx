import type { ButtonHTMLAttributes } from 'react'

export function ClayButton({ className = '', type = 'button', ...props }: ButtonHTMLAttributes<HTMLButtonElement>) {
  return <button {...props} type={type} className={`gl-clay-button ${className}`} />
}
