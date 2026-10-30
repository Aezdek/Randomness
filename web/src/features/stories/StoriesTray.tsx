import { Plus } from 'lucide-react';
import { useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { useIndex } from '../../core/selectors';
import { useApp } from '../../core/store';
import { openCreate } from '../../core/ui';
import { Avatar, RingAvatar } from '../../ui/Avatar';
import { useRing } from './ring';
import { calls } from '../calls/calls';

function TrayItem({ userId, label }: { userId: string; label: string }) {
  const ring = useRing(userId);
  const navigate = useNavigate();
  const index = useIndex();
  const live = index.lives.find((l) => l.dto.authorId === userId);
  return (
    <button className="tray-item" onClick={() => (live ? void calls.joinLive(userId, live.payload.callId) : navigate(`/stories/${userId}`))}>
      <RingAvatar userId={userId} size={66} ring={ring} />
      <span className={`tray-label ${ring === 'seen' ? 'faint' : ''}`}>{label}</span>
    </button>
  );
}

/** Your story first, then people with unseen stories, then the rest. */
export function StoriesTray() {
  const index = useIndex();
  const users = useApp((s) => s.users);
  const me = useApp((s) => s.me)!;
  const seen = useApp((s) => s.seenStories);
  const authors = useMemo(() => {
    const live = index.lives.map((l) => l.dto.authorId);
    return [...new Set([...live, ...Object.keys(index.stories)])]
      .filter((id) => id !== me.id && users[id] && !users[id].blockedByMe)
      .sort((a, b) => {
        const unseenA = live.includes(a) ? 2 : index.stories[a]?.some((s) => !seen[s.dto.id]) ? 1 : 0;
        const unseenB = live.includes(b) ? 2 : index.stories[b]?.some((s) => !seen[s.dto.id]) ? 1 : 0;
        if (unseenA !== unseenB) return unseenB - unseenA;
        return (index.stories[b]?.at(-1)?.dto.createdAt ?? 0) - (index.stories[a]?.at(-1)?.dto.createdAt ?? 0);
      });
  }, [index, users, seen, me.id]);
  const mine = index.stories[me.id]?.length;
  const myRing = useRing(me.id);
  const navigate = useNavigate();

  return (
    <div className="tray" role="list">
      <div className="tray-item" role="listitem">
        <button onClick={() => (mine ? navigate(`/stories/${me.id}`) : openCreate('story'))} aria-label="Your story" style={{ position: 'relative', borderRadius: '50%' }}>
          {mine ? <RingAvatar userId={me.id} size={66} ring={myRing} /> : (
            <span className="ring none" style={{ ['--ring-size' as string]: '66px' }}>
              <Avatar userId={me.id} size={57} />
            </span>
          )}
          <span
            className="tray-add"
            role="button"
            aria-label="Add to your story"
            onClick={(e) => {
              e.stopPropagation();
              openCreate('story');
            }}
          >
            <Plus size={14} strokeWidth={3} />
          </span>
        </button>
        <span className="tray-label faint">Your story</span>
      </div>
      {authors.map((id) => (
        <TrayItem key={id} userId={id} label={users[id].username} />
      ))}
    </div>
  );
}
