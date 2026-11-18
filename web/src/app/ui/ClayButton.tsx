import type { ButtonHTMLAttributes, ReactNode } from 'react'

interface ClayButtonProps extends Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'children'> {
  variant?: 'primary' | 'danger' | 'ghost'
  children: ReactNode
}

export function ClayButton({ variant = 'primary', children, className = '', ...props }: ClayButtonProps) {
  return (
    <button className={`clay-button clay-button--${variant} ${className}`.trim()} {...props}>
      {children}
    </button>
  )
}
