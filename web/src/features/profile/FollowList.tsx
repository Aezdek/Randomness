import { Search } from 'lucide-react';
import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../../core/api';
import { removeFollower } from '../../core/compose';
import { displayName, useIndex } from '../../core/selectors';
import { useApp } from '../../core/store';
import type { IdList } from '../../core/types';
import { Avatar } from '../../ui/Avatar';
import { Center, Spinner } from '../../ui/bits';
import { Sheet } from '../../ui/Sheet';
import { FollowButton } from './FollowButton';

export function FollowList({ userId, kind, onClose }: { userId: string; kind: 'followers' | 'following'; onClose: () => void }) {
  const [ids, setIds] = useState<string[] | null>(null);
  const [query, setQuery] = useState('');
  const users = useApp((s) => s.users);
  const me = useApp((s) => s.me!);
  const index = useIndex();
  useEffect(() => {
    void api.get<IdList>(`/users/${userId}/${kind}`).then((r) => setIds(r.ids));
  }, [userId, kind, users]);
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
