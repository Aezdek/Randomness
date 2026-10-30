import { Camera, Lock, Sparkles } from 'lucide-react';
import { useMemo } from 'react';
import { Link } from 'react-router-dom';
import { displayName, useIndex } from '../../core/selectors';
import { useApp } from '../../core/store';
import { openCreate } from '../../core/ui';
import { Avatar } from '../../ui/Avatar';
import { Center, Spinner } from '../../ui/bits';
import { FollowButton } from '../profile/FollowButton';
import { StoriesTray } from '../stories/StoriesTray';
import { PostCard } from './PostCard';

function Members() {
  const users = useApp((s) => s.users);
  const me = useApp((s) => s.me!);
  const index = useIndex();
  const others = Object.values(users).filter((u) => u.id !== me.id && !u.deleted && !u.blockedByMe);
  if (!others.length) return null;
  const suggested = others.sort((a, b) => Number(a.followState === 'following') - Number(b.followState === 'following'));
  return (
    <section className="side-section">
      <div className="row" style={{ justifyContent: 'space-between' }}>
        <span className="muted" style={{ fontWeight: 650, fontSize: 14 }}>
          On Hush
        </span>
      </div>
      {suggested.slice(0, 9).map((u) => (
        <div className="user-row" key={u.id}>
          <Link to={`/u/${u.username}`}>
            <Avatar userId={u.id} size={44} presence />
          </Link>
          <div className="user-row-text">
            <Link to={`/u/${u.username}`} className="author-name">
              {u.username}
            </Link>
            <span className="faint truncate">
              {u.followsMe === 'following' ? 'Follows you' : displayName(u, index.profiles[u.id]?.payload)}
            </span>
          </div>
          <FollowButton user={u} compact />
        </div>
      ))}
    </section>
  );
}

function Sidebar() {
  const me = useApp((s) => s.me!);
  const index = useIndex();
  return (
    <aside className="home-side">
      <div className="user-row">
        <Link to={`/u/${me.username}`}>
          <Avatar userId={me.id} size={48} />
        </Link>
        <div className="user-row-text">
          <Link to={`/u/${me.username}`} className="author-name">
            {me.username}
          </Link>
          <span className="faint">{index.profiles[me.id]?.payload.displayName ?? ''}</span>
        </div>
        <Link to="/settings" className="btn btn-link" style={{ fontSize: 13 }}>
          Settings
        </Link>
      </div>
      <Members />
      <footer className="side-foot">
        <Lock size={12} /> Everything here is end-to-end encrypted. The server stores only ciphertext.
      </footer>
    </aside>
  );
}

function Welcome() {
  const users = useApp((s) => s.users);
  const me = useApp((s) => s.me!);
  const others = Object.values(users).filter((u) => u.id !== me.id && !u.deleted);
  return (
    <div className="welcome-card">
      <div className="welcome-glow" aria-hidden />
      <Sparkles size={28} className="grad-icon" />
      <h2>Welcome to Hush</h2>
      <p className="muted">Follow the people you know and share your first moment. Only the people you choose can ever see it.</p>
      {others.length > 0 && (
        <div className="welcome-people">
          {others.map((u) => (
            <div key={u.id} className="welcome-person">
              <Avatar userId={u.id} size={64} />
              <b className="truncate">{u.username}</b>
              <FollowButton user={u} />
            </div>
          ))}
        </div>
      )}
      <button className="btn btn-brand btn-lg" onClick={() => openCreate('post')}>
        <Camera size={18} /> Share your first post
      </button>
    </div>
  );
}

export function Home() {
  const index = useIndex();
  const users = useApp((s) => s.users);
  const me = useApp((s) => s.me!);
  const synced = useApp((s) => s.initialSyncDone);
  const feed = useMemo(
    () =>
      index.posts.filter((p) => {
        if (p.dto.archived) return false;
        if (p.dto.authorId === me.id) return true;
        const author = users[p.dto.authorId];
        return author && !author.blockedByMe && author.followState === 'following';
      }),
    [index.posts, users, me.id],
  );
  return (
    <div className="home">
      <div className="home-feed">
        <StoriesTray />
        {!synced && !feed.length ? (
          <Center>
            <Spinner />
          </Center>
        ) : feed.length ? (
          feed.map((entity) => <PostCard key={entity.dto.id} entity={entity} />)
        ) : (
          <Welcome />
        )}
        {feed.length > 0 && (
          <div className="caught-up">
            <span className="check">✓</span>
            <b>You’re all caught up</b>
            <span className="faint">You’ve seen everything new from the people you follow.</span>
          </div>
        )}
      </div>
      <Sidebar />
    </div>
  );
}
