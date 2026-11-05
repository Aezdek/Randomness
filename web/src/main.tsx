import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';
import { applyTheme, useUi } from './core/ui';
import './styles/tokens.css';
import './styles/base.css';
import './styles/components.css';
import './styles/shell.css';
import './styles/features.css';

applyTheme(useUi.getState().theme);
// For whoever opens the console.
console.info('%c🔒 sealed with AezCrypt', 'color:#8e8e93;font:11px ui-monospace,monospace;letter-spacing:.08em');

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
