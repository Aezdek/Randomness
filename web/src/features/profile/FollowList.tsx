import { Search } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { cachedGet, peekCache } from '../../core/cache';
import { removeFollower } from '../../core/compose';
import { displayName, useIndex } from '../../core/selectors';
import { useApp } from '../../core/store';
import type { IdList } from '../../core/types';
import { Avatar } from '../../ui/Avatar';
import { Center, Spinner } from '../../ui/bits';
import { Sheet } from '../../ui/Sheet';
import { FollowButton } from './FollowButton';

export function FollowList({ userId, kind, onClose }: { userId: string; kind: 'followers' | 'following'; onClose: () => void }) {
  const path = `/users/${userId}/${kind}`;
  const [ids, setIds] = useState<string[] | null>(() => peekCache<IdList>(path)?.ids ?? null);
  const [query, setQuery] = useState('');
  const users = useApp((s) => s.users);
  const me = useApp((s) => s.me!);
  const index = useIndex();
  const relation = useFollowRelation(userId);
  const seen = useRef(relation);
  useEffect(() => {
    const changed = seen.current !== relation;
    seen.current = relation;
    let alive = true;
    cachedGet<IdList>(path, changed)
      .then((r) => alive && setIds(r.ids))
      .catch(() => undefined);
    return () => {
      alive = false;
    };
  }, [path, relation]);
  const shown = (ids ?? []).filter((id) => {
    const u = id === me.id ? null : users[id];
    const name = id === me.id ? me.username : u?.username ?? '';
    return !query || name.includes(query.toLowerCase());
  });
  return (
    <Sheet open onClose={onClose} title={kind === 'followers' ? 'Followers' : 'Following'} bodyClass="flush">
      <div className="search" style={{ padding: 12 }}>
        <Search size={17} />
        <input className="input" placeholder="Search" value={query} onChange={(e) => setQuery(e.target.value)} />
      </div>
      {!ids ? (
        <Center>
          <Spinner />
        </Center>
      ) : (
        <div style={{ padding: '0 16px 12px' }}>
          {shown.map((id) => {
            const u = users[id];
            const name = id === me.id ? me.username : u?.username;
            return (
              <div className="user-row" key={id}>
                <Link to={`/u/${name}`} onClick={onClose}>
                  <Avatar userId={id} size={44} />
                </Link>
                <div className="user-row-text">
                  <Link to={`/u/${name}`} className="author-name" onClick={onClose}>
                    {name}
                  </Link>
                  <span className="faint truncate">{displayName(u, index.profiles[id]?.payload)}</span>
                </div>
                {userId === me.id && kind === 'followers' ? (
                  <button className="btn btn-secondary btn-sm" onClick={() => void removeFollower(id)}>
                    Remove
                  </button>
                ) : (
                  u && id !== me.id && <FollowButton user={u} />
                )}
              </div>
            );
          })}
          {!shown.length && <p className="faint" style={{ textAlign: 'center', padding: 24 }}>Nobody here yet.</p>}
        </div>
      )}
    </Sheet>
  );
}

/**
 * A short summary of what this app knows about [userId]'s follows, which changes when a follow does: our own follows
 * and followers for our profile, and our relationship to them for anyone else's. Follow lists are asked for again
 * when it changes, and otherwise come from the cache.
 */
export function useFollowRelation(userId: string | undefined): string {
  return useApp((s) => {
    if (!userId) return '';
    if (userId === s.me?.id) {
      const users = Object.values(s.users);
      const followers = users.filter((u) => u.followsMe === 'following').map((u) => u.id).sort();
      const following = users.filter((u) => u.followState === 'following').map((u) => u.id).sort();
      return `${followers.join(',')}|${following.join(',')}`;
    }
    const user = s.users[userId];
    return user ? `${user.followState}|${user.followsMe}|${user.blockedByMe}|${user.deleted}` : '';
  });
}
