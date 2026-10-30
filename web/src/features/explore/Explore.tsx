import { Compass, Hash, Search, X } from 'lucide-react';
import { useMemo, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { Kind, type PostPayload, type ReelPayload } from '../../core/payloads';
import { displayName, useIndex } from '../../core/selectors';
import { useApp } from '../../core/store';
import { Avatar } from '../../ui/Avatar';
import { Empty } from '../../ui/bits';
import { FollowButton } from '../profile/FollowButton';
import { PostGrid } from '../profile/PostGrid';

const captionOf = (payload: unknown) => ((payload as PostPayload).caption ?? (payload as ReelPayload).caption ?? '').toLowerCase();

/**
 * Search runs on this device: hashtags and captions are encrypted, so only your own copy can be searched.
 * `/explore` leads with the grid; `/search` (the sidebar's Search) leads with people.
 */
export function Explore({ mode = 'explore' }: { mode?: 'explore' | 'search' }) {
  const { tag } = useParams();
  const index = useIndex();
  const users = useApp((s) => s.users);
  const me = useApp((s) => s.me!);
  const navigate = useNavigate();
  const [query, setQuery] = useState('');
  const q = query.trim().toLowerCase().replace(/^@/, '');

  const posts = useMemo(() => index.posts.filter((p) => !p.dto.archived && !users[p.dto.authorId]?.blockedByMe && (p.dto.kind === Kind.POST || p.dto.kind === Kind.REEL)), [index.posts, users]);
  const tagged = useMemo(() => (tag ? posts.filter((p) => captionOf(p.payload).includes(`#${tag.toLowerCase()}`)) : []), [posts, tag]);
  // Explore favours accounts you don't follow yet, then everything else, newest first.
  const explore = useMemo(
    () => [...posts].sort((a, b) => Number(users[a.dto.authorId]?.followState === 'following') - Number(users[b.dto.authorId]?.followState === 'following') || b.dto.createdAt - a.dto.createdAt),
    [posts, users],
  );
  const people = useMemo(
    () => Object.values(users).filter((u) => !u.deleted && u.id !== me.id && (u.username.includes(q) || displayName(u, index.profiles[u.id]?.payload).toLowerCase().includes(q))),
    [users, q, index, me.id],
  );
  const tags = useMemo(() => {
    if (!q) return [];
    const counts = new Map<string, number>();
    for (const p of posts) for (const m of captionOf(p.payload).matchAll(/#([\p{L}\p{N}_]{2,30})/gu)) if (m[1].includes(q)) counts.set(m[1], (counts.get(m[1]) ?? 0) + 1);
    return [...counts.entries()].sort((a, b) => b[1] - a[1]).slice(0, 8);
  }, [posts, q]);

  if (tag) {
    return (
      <div className="page">
        <header className="tag-head">
          <span className="tag-glyph">
            <Hash size={40} />
          </span>
          <div>
            <h1>#{tag}</h1>
            <span className="muted">{tagged.length} posts</span>
          </div>
        </header>
        {tagged.length ? <PostGrid posts={tagged} /> : <Empty icon={<Hash size={34} />} title="No posts yet" />}
      </div>
    );
  }

  return (
    <div className="page explore">
      <div className="search explore-search">
        <Search size={17} />
        <input className="input" placeholder="Search people and hashtags" value={query} onChange={(e) => setQuery(e.target.value)} autoFocus={mode === 'search'} />
        {query && (
          <button className="icon-btn sm clear" onClick={() => setQuery('')} aria-label="Clear">
            <X size={16} />
          </button>
        )}
      </div>
      {q || mode === 'search' ? (
        <div className="search-results">
          {!q && people.length > 0 && <h3 className="section-label">Everyone on AezGram</h3>}
          {tags.map(([t, n]) => (
            <button key={t} className="user-row result" onClick={() => navigate(`/explore/tags/${encodeURIComponent(t)}`)}>
              <span className="tag-glyph small">
                <Hash size={18} />
              </span>
              <div className="user-row-text">
                <b>#{t}</b>
                <span className="faint">{n} posts</span>
              </div>
            </button>
          ))}
          {people.map((u) => (
            <div key={u.id} className="user-row result">
              <Link to={`/u/${u.username}`}>
                <Avatar userId={u.id} size={44} presence />
              </Link>
              <Link to={`/u/${u.username}`} className="user-row-text">
                <b>{u.username}</b>
                <span className="faint truncate">
                  {displayName(u, index.profiles[u.id]?.payload)}
                  {u.followState === 'following' ? ' · Following' : ''}
                </span>
              </Link>
              <FollowButton user={u} compact />
            </div>
          ))}
          {q && !people.length && !tags.length && <p className="faint" style={{ padding: 24, textAlign: 'center' }}>No results for “{query}”.</p>}
        </div>
      ) : explore.length ? (
        <PostGrid posts={explore} />
      ) : (
        <Empty icon={<Compass size={36} />} title="Nothing to explore yet">
          Public posts from everyone on AezGram will show up here.
        </Empty>
      )}
    </div>
  );
}
