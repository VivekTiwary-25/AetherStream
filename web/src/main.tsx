import React from 'react'
import ReactDOM from 'react-dom/client'
import App from './App'
import { GloriousApp } from './glorious/GloriousApp'
import './styles/global.css'

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    {new URLSearchParams(window.location.search).get('ui') === 'classic' ? <App /> : <GloriousApp />}
  </React.StrictMode>,
)
