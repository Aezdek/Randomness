import { lazy, Suspense, useEffect, useState } from 'react';
import { BrowserRouter, useLocation } from 'react-router-dom';
import { registerServiceWorker, restoreSession } from './core/session';
import { useApp } from './core/store';
import { Shell } from './features/shell/Shell';
import { BrandMark } from './ui/Brand';
import { Toasts } from './ui/bits';

// The landing page is its own download: someone signed in never needs it.
const Welcome = lazy(() => import('./features/auth/Welcome').then((m) => ({ default: m.Welcome })));

type ServerState = 'checking' | 'ready' | 'setup' | 'locked';

const READY_KEY = 'hush.serverReady';
const wasReady = () => {
  try {
    return localStorage.getItem(READY_KEY) === '1';
  } catch {
    return false;
  }
};
const remember = (ready: boolean) => {
  try {
    if (ready) localStorage.setItem(READY_KEY, '1');
    else localStorage.removeItem(READY_KEY);
  } catch {
    // No storage: every start checks first, as before.
  }
};

/**
 * The server keeps its database encrypted; until the admin has set the server password (or, after a move to new
 * hardware, entered it) on the server itself, there is nothing to sign in to. This waits for that, quietly.
 */
function useServerState(): ServerState {
  // Last time it was ready, it almost certainly still is: the app starts at once from what this browser keeps, and the
  // check runs alongside instead of costing a round trip first. If the server turns out to be locked, that shows.
  const [state, setState] = useState<ServerState>(() => (wasReady() ? 'ready' : 'checking'));
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
      remember(next === 'ready');
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
      <Routed phase={phase} />
      <Toasts />
    </BrowserRouter>
  );
}

/** The downloads page is open to everyone; everything else is the landing page or, once signed in, the app. */
function Routed({ phase }: { phase: ReturnType<typeof useApp.getState>['phase'] }) {
  const download = useLocation().pathname.replace(/\/+$/, '') === '/download';
  if (phase === 'loading')
    return (
      <div className="splash">
        <BrandMark size={64} />
      </div>
    );
  const splash = (
    <div className="splash">
      <BrandMark size={64} />
    </div>
  );
  if (phase === 'signedOut')
    return (
      <Suspense fallback={splash}>
        <Welcome page={download ? 'download' : 'home'} />
      </Suspense>
    );
  if (phase === 'ready')
    return download ? (
      <Suspense fallback={splash}>
        <Welcome page="download" signedIn />
      </Suspense>
    ) : (
      <Shell />
    );
  return null;
}
