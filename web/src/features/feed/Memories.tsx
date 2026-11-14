// "On this day": your own posts, reels and archived stories from this date in earlier years. Worked out on the device
// from what it already holds; the server never learns what anything is, or that you looked.
import { History, X } from 'lucide-react';
import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { onThisDay } from '../../core/circle';
import { Kind, type StoryPayload } from '../../core/payloads';
import { useIndex } from '../../core/selectors';
import { useApp, type Entity } from '../../core/store';
import { MediaImage } from '../../ui/Media';
import { coverOf } from '../profile/PostGrid';
import { StoryThumb } from '../stories/StoryThumb';

const dayKey = () => new Date().toDateString();

function dismissedToday(): boolean {
  try {
    return localStorage.getItem('hush.memoriesHidden') === dayKey();
  } catch {
    return false;
  }
}

export function OnThisDay() {
  const index = useIndex();
  const me = useApp((s) => s.me!.id);
  const [hidden, setHidden] = useState(dismissedToday);
  const memories = useMemo(() => onThisDay(index, me), [index, me]);
  if (hidden || memories.length === 0) return null;
  const years = new Date().getFullYear() - new Date(memories[0].dto.createdAt).getFullYear();
  const hide = () => {
    try {
      localStorage.setItem('hush.memoriesHidden', dayKey());
    } catch {
      /* private mode */
    }
    setHidden(true);
  };
  return (
    <section className="memories" aria-label="On this day">
      <header>
        <History size={18} />
        <span>
          <b>On this day</b>
          <span className="faint"> · {years === 1 ? 'a year ago' : `${years} years ago`}</span>
        </span>
        <button className="icon-btn sm" onClick={hide} aria-label="Hide until tomorrow">
          <X size={16} />
        </button>
      </header>
      <div className="memories-row">
        {memories.slice(0, 12).map((m) => (
          <Memory key={m.dto.id} entity={m} />
        ))}
      </div>
    </section>
  );
}

function Memory({ entity }: { entity: Entity }) {
  const year = new Date(entity.dto.createdAt).getFullYear();
  if (entity.dto.kind === Kind.STORY) {
    return (
      <Link to="/settings/archive" className="memory story" aria-label={`Story from ${year}`}>
        <StoryThumb story={entity as Entity<StoryPayload>} />
        <span className="memory-year">{year}</span>
      </Link>
    );
  }
  const cover = coverOf(entity);
  return (
    <Link to={`/p/${entity.dto.id}`} className="memory" aria-label={`Post from ${year}`}>
      {cover ? <MediaImage media={cover} /> : <span className="memory-blank" />}
      <span className="memory-year">{year}</span>
    </Link>
  );
}
