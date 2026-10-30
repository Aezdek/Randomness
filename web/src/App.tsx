import { useEffect } from 'react';
import { BrowserRouter } from 'react-router-dom';
import { registerServiceWorker, restoreSession } from './core/session';
import { useApp } from './core/store';
import { Welcome } from './features/auth/Welcome';
import { Shell } from './features/shell/Shell';
import { BrandMark } from './ui/Brand';
import { Toasts } from './ui/bits';

export function App() {
  const phase = useApp((s) => s.phase);

  useEffect(() => {
    void registerServiceWorker();
    restoreSession().catch((e) => {
      console.error(e);
      useApp.setState({ phase: 'signedOut' });
    });
  }, []);

  return (
    <BrowserRouter>
      {phase === 'loading' && (
        <div className="splash">
          <BrandMark size={64} />
        </div>
      )}
      {phase === 'signedOut' && <Welcome />}
      {phase === 'ready' && <Shell />}
      <Toasts />
    </BrowserRouter>
  );
}
