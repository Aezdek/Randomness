import { useEffect, useState } from 'react';
import { BrowserRouter } from 'react-router-dom';
import { registerServiceWorker, restoreSession } from './core/session';
import { useApp } from './core/store';
import { Welcome } from './features/auth/Welcome';
import { Shell } from './features/shell/Shell';
import { BrandMark } from './ui/Brand';
import { Toasts } from './ui/bits';

type ServerState = 'checking' | 'ready' | 'setup' | 'locked';

/**
 * The server keeps its database encrypted; until the admin has set the server password (or, after a move to new
 * hardware, entered it) on the server itself, there is nothing to sign in to. This waits for that, quietly.
 */
function useServerState(): ServerState {
  const [state, setState] = useState<ServerState>('checking');
  useEffect(() => {
    let stopped = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const check = async () => {
      let next: ServerState = 'ready';
      try {
        const response = await fetch('/api/v1/setup', { cache: 'no-store' });
        const body = (await response.json()) as { state?: string };
        if (body.state === 'setup' || body.state === 'locked') next = body.state;
      } catch {
        // Offline or an older server: carry on with what's cached.
      }
      if (stopped) return;
      setState(next);
      if (next !== 'ready') timer = setTimeout(() => void check(), 5000);
    };
    void check();
    return () => {
      stopped = true;
      clearTimeout(timer);
    };
  }, []);
  return state;
}

function ServerWaiting({ state }: { state: 'setup' | 'locked' }) {
  return (
    <div className="splash server-waiting">
      <BrandMark size={56} />
      <h1>{state === 'setup' ? 'Hush is being set up' : 'Hush is locked'}</h1>
      <p className="muted">
        {state === 'setup'
          ? 'The admin is choosing the server password, on the server itself. This page carries on by itself when it’s done.'
          : 'Hush moved to new hardware or was restored, so its database needs the server password. The admin unlocks it on the server; this page carries on by itself.'}
      </p>
      <p className="faint server-waiting-seal" title="Everything Hush stores is sealed">
        sealed with AezCrypt
      </p>
    </div>
  );
}

export function App() {
  const server = useServerState();
  return server === 'checking' ? (
    <div className="splash">
      <BrandMark size={64} />
    </div>
  ) : server === 'ready' ? (
    <SignedInOrOut />
  ) : (
    <ServerWaiting state={server} />
  );
}

function SignedInOrOut() {
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
