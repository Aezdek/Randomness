import { Camera, ChevronLeft, Clapperboard, Compass, Heart, Home, Menu, MessageCircle, PlusSquare, Search, WifiOff } from 'lucide-react';
import { useEffect, useMemo, type ReactNode } from 'react';
import { Link, NavLink, Route, Routes, useLocation, useNavigate, type Location } from 'react-router-dom';
import { usePushPrefsMirror } from '../../core/notifyPrefs';
import { unreadCount, useIndex } from '../../core/selectors';
import { useApp } from '../../core/store';
import { engine } from '../../core/sync';
import { useUi } from '../../core/ui';
import { realtime } from '../../core/ws';
import { Avatar } from '../../ui/Avatar';
import { BrandMark, Wordmark } from '../../ui/Brand';
import { Activity, useActivityBadge } from '../activity/Activity';
import { CallHost } from '../calls/CallHost';
import { calls } from '../calls/calls';
import { CreateHost } from '../create/CreateHost';
import { Explore } from '../explore/Explore';
import { Home as HomePage } from '../feed/Home';
import { PostModal, PostPage } from '../feed/PostView';
import { Chat } from '../messages/Chat';
import { Inbox } from '../messages/Inbox';
import { Profile } from '../profile/Profile';
import { Reels } from '../reels/Reels';
import { Settings } from '../settings/Settings';
import { Verify } from '../settings/Verify';
import { StoryViewerPage } from '../stories/StoryViewer';

function useRealtimeWiring() {
  const navigate = useNavigate();
  useEffect(() => {
    const off = realtime.on((frame) => {
      switch (frame.t) {
        case 'sync':
          void engine.sync().catch(() => undefined);
          break;
        case 'typing':
          if (frame.threadId && frame.userId) {
            const until = Date.now() + 5000;
            useApp.setState((s) => ({ typing: { ...s.typing, [frame.threadId!]: { ...(s.typing[frame.threadId!] ?? {}), [frame.userId!]: until } } }));
          }
          break;
        case 'presence':
          if (frame.userId) useApp.setState((s) => ({ presence: { ...s.presence, [frame.userId!]: !!frame.online } }));
          break;
        case 'signal':
          calls.onSignal(frame);
          break;
        case 'ring_test':
          calls.testRing();
          break;
      }
    });
    const onWorker = (event: MessageEvent) => event.data?.type === 'navigate' && navigate(event.data.url);
    navigator.serviceWorker?.addEventListener('message', onWorker);
    const poll = setInterval(() => void engine.sync().catch(() => undefined), 120_000);
    return () => {
      off();
      navigator.serviceWorker?.removeEventListener('message', onWorker);
      clearInterval(poll);
    };
  }, [navigate]);
}

function useUnreadMessages() {
  const threads = useApp((s) => s.threads);
  const me = useApp((s) => s.me?.id ?? '');
  const index = useIndex();
  return useMemo(() => Object.values(threads).filter((t) => unreadCount(t, index, me) > 0).length, [threads, index, me]);
}

function NavItem({ to, icon, label, badge, end }: { to: string; icon: (active: boolean) => ReactNode; label: string; badge?: number; end?: boolean }) {
  return (
    <NavLink to={to} end={end} className={({ isActive }) => `nav-item ${isActive ? 'active' : ''}`}>
      {({ isActive }) => (
        <>
          {icon(isActive)}
          {!!badge && <span className="badge">{badge > 9 ? '9+' : badge}</span>}
          <span className="label">{label}</span>
        </>
      )}
    </NavLink>
  );
}

const stroke = (active: boolean) => (active ? 2.6 : 1.8);

function SideNav() {
  const me = useApp((s) => s.me);
  const unread = useUnreadMessages();
  const activity = useActivityBadge();
  return (
    <nav className="sidenav" aria-label="Main">
      <Link to="/" className="brand" aria-label="Hush home">
        <Wordmark className="word" />
        <span className="mark">
          <BrandMark size={28} />
        </span>
      </Link>
      <NavItem to="/" end label="Home" icon={(a) => <Home size={26} strokeWidth={stroke(a)} />} />
      <NavItem to="/search" label="Search" icon={(a) => <Search size={26} strokeWidth={stroke(a)} />} />
      <NavItem to="/explore" label="Explore" icon={(a) => <Compass size={26} strokeWidth={stroke(a)} />} />
      <NavItem to="/reels" label="Reels" icon={(a) => <Clapperboard size={26} strokeWidth={stroke(a)} />} />
      <NavItem to="/direct" label="Messages" badge={unread} icon={(a) => <MessageCircle size={26} strokeWidth={stroke(a)} />} />
      <NavItem to="/activity" label="Notifications" badge={activity} icon={(a) => <Heart size={26} strokeWidth={stroke(a)} fill={a ? 'currentColor' : 'none'} />} />
      <button className="nav-item" onClick={() => useUi.setState({ createMenu: true })}>
        <PlusSquare size={26} strokeWidth={1.8} />
        <span className="label">Create</span>
      </button>
      {me && <NavItem to={`/u/${me.username}`} label="Profile" icon={() => <Avatar userId={me.id} size={26} />} />}
      <span className="spacer" />
      <NavItem to="/settings" label="More" icon={(a) => <Menu size={26} strokeWidth={stroke(a)} />} />
    </nav>
  );
}

/** Pages that draw their own phone header (or none): the shell shows no top bar there. */
const OWN_HEADER = [/^\/direct(\/|$)/, /^\/stories\//, /^\/explore$/, /^\/search$/];

function topBarTitle(pathname: string, myName: string | undefined): { title: string; back: boolean } {
  if (pathname === `/u/${myName}` || pathname.startsWith(`/u/${myName}/`)) return { title: myName!, back: false };
  if (pathname.startsWith('/u/')) return { title: decodeURIComponent(pathname.split('/')[2]), back: true };
  if (pathname.startsWith('/reels')) return { title: 'Reels', back: false };
  if (pathname.startsWith('/explore/tags/')) return { title: `#${decodeURIComponent(pathname.split('/')[3])}`, back: true };
  if (pathname === '/activity') return { title: 'Notifications', back: true };
  if (pathname.startsWith('/settings')) return { title: 'Settings', back: true };
  if (pathname.startsWith('/verify/')) return { title: 'Security code', back: true };
  if (pathname.startsWith('/p/')) return { title: 'Post', back: true };
  return { title: '', back: true };
}

export function hasTopBar(pathname: string) {
  return !OWN_HEADER.some((r) => r.test(pathname));
}

function TopBar({ pathname }: { pathname: string }) {
  const unread = useUnreadMessages();
  const activity = useActivityBadge();
  const me = useApp((s) => s.me);
  const navigate = useNavigate();
  if (!hasTopBar(pathname)) return null;
  if (pathname === '/') {
    return (
      <header className="topbar only-mobile">
        <Wordmark className="title" />
        <Link to="/activity" className="icon-btn" aria-label="Notifications" style={{ position: 'relative' }}>
          <Heart size={25} strokeWidth={1.8} />
          {!!activity && <span className="dot" style={{ position: 'absolute', top: 8, right: 8 }} />}
        </Link>
        <Link to="/direct" className="icon-btn" aria-label="Messages" style={{ position: 'relative' }}>
          <MessageCircle size={25} strokeWidth={1.8} />
          {!!unread && (
            <span className="badge" style={{ position: 'absolute', top: 2, right: 0 }}>
              {unread}
            </span>
          )}
        </Link>
      </header>
    );
  }
  const { title, back } = topBarTitle(pathname, me?.username);
  const mine = !!me && (pathname === `/u/${me.username}` || pathname.startsWith(`/u/${me.username}/`));
  return (
    <header className={`topbar only-mobile ${back ? 'with-back' : ''}`}>
      {back && (
        <button className="icon-btn" onClick={() => (window.history.length > 1 ? navigate(-1) : navigate('/'))} aria-label="Back">
          <ChevronLeft size={28} strokeWidth={1.8} />
        </button>
      )}
      <h1 className="title truncate">{title}</h1>
      {mine && (
        <>
          <button className="icon-btn" onClick={() => useUi.setState({ createMenu: true })} aria-label="Create">
            <PlusSquare size={25} strokeWidth={1.8} />
          </button>
          <Link to="/settings" className="icon-btn" aria-label="Settings">
            <Menu size={25} strokeWidth={1.8} />
          </Link>
        </>
      )}
      {pathname.startsWith('/reels') && (
        <button className="icon-btn" onClick={() => useUi.setState({ createMenu: true })} aria-label="Create">
          <Camera size={25} strokeWidth={1.8} />
        </button>
      )}
    </header>
  );
}

function BottomBar() {
  const me = useApp((s) => s.me);
  const { pathname } = useLocation();
  if (pathname.startsWith('/direct/') || pathname.startsWith('/stories/')) return null;
  return (
    <nav className="bottombar only-mobile" aria-label="Main">
      <NavLink to="/" end aria-label="Home">
        {({ isActive }) => <Home size={26} strokeWidth={stroke(isActive)} />}
      </NavLink>
      <NavLink to="/explore" aria-label="Search">
        {({ isActive }) => <Search size={26} strokeWidth={stroke(isActive)} />}
      </NavLink>
      <button aria-label="Create" onClick={() => useUi.setState({ createMenu: true })}>
        <PlusSquare size={26} strokeWidth={1.8} />
      </button>
      <NavLink to="/reels" aria-label="Reels">
        {({ isActive }) => <Clapperboard size={26} strokeWidth={stroke(isActive)} />}
      </NavLink>
      {me && (
        <NavLink to={`/u/${me.username}`} aria-label="Profile" className={({ isActive }) => (isActive ? 'active' : '')}>
          <Avatar userId={me.id} size={26} />
        </NavLink>
      )}
    </nav>
  );
}

function ConnectionBanner() {
  const connection = useApp((s) => s.connection);
  if (connection !== 'offline') return null;
  return (
    <div className="offline-banner">
      <WifiOff size={15} /> Offline — showing what’s on this device
    </div>
  );
}

export function Shell() {
  useRealtimeWiring();
  usePushPrefsMirror();
  const location = useLocation();
  const background = (location.state as { background?: Location } | null)?.background;
  const page = (background ?? location).pathname;
  return (
    <div className={`shell ${hasTopBar(page) ? '' : 'no-topbar'}`}>
      <SideNav />
      <TopBar pathname={page} />
      <main className="main">
        <Routes location={background ?? location}>
          <Route path="/" element={<HomePage />} />
          <Route path="/explore" element={<Explore />} />
          <Route path="/search" element={<Explore mode="search" />} />
          <Route path="/explore/tags/:tag" element={<Explore />} />
          <Route path="/reels" element={<Reels />} />
          <Route path="/reels/:id" element={<Reels />} />
          <Route path="/direct" element={<Inbox />} />
          <Route path="/direct/:threadId" element={<Chat />} />
          <Route path="/activity" element={<Activity />} />
          <Route path="/u/:username" element={<Profile />} />
          <Route path="/u/:username/:tab" element={<Profile />} />
          <Route path="/p/:id" element={<PostPage />} />
          <Route path="/stories/:userId" element={<StoryViewerPage />} />
          <Route path="/settings" element={<Settings />} />
          <Route path="/settings/:section" element={<Settings />} />
          <Route path="/verify/:userId" element={<Verify />} />
          <Route path="*" element={<HomePage />} />
        </Routes>
        {background && (
          <Routes>
            <Route path="/p/:id" element={<PostModal />} />
          </Routes>
        )}
      </main>
      <BottomBar />
      <CreateHost />
      <CallHost />
      <ConnectionBanner />
    </div>
  );
}
