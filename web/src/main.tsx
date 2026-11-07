import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';
import { applyTheme, useUi } from './core/ui';
// Colour emoji that look the same everywhere (Google's Noto, vector, in pieces loaded as needed; OFL, see
// public/licenses). Apple devices keep their own: Apple Color Emoji comes first in the font stack.
import '@infolektuell/noto-color-emoji/index.css';
import './styles/tokens.css';
import './styles/base.css';
import './styles/components.css';
import './styles/shell.css';
import './styles/features.css';
import './styles/landing.css';

applyTheme(useUi.getState().theme);

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
