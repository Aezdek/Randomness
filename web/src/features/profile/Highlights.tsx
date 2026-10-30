import { Check, Plus } from 'lucide-react';
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { createHighlight } from '../../core/compose';
import { messageOf } from '../../core/errors';
import type { HighlightPayload, StoryPayload } from '../../core/payloads';
import { useIndex } from '../../core/selectors';
import { b64 } from '../../crypto/sodium';
import { toast, type Entity } from '../../core/store';
import { MediaImage } from '../../ui/Media';
import { Sheet } from '../../ui/Sheet';
import { StoryThumb } from '../stories/StoryThumb';

export function Highlights({ userId, isMe }: { userId: string; isMe: boolean }) {
  const index = useIndex();
  const navigate = useNavigate();
  const [creating, setCreating] = useState(false);
  const highlights = index.highlights[userId] ?? [];
  if (!highlights.length && !isMe) return null;
  return (
    <div className="highlights">
      {highlights.map((h) => (
        <button key={h.dto.id} className="highlight" onClick={() => navigate(`/stories/${userId}?highlight=${h.dto.id}`)}>
          <span className="highlight-cover">{h.payload.cover ? <MediaImage media={h.payload.cover} /> : <HighlightFallback highlight={h} />}</span>
          <span className="truncate">{h.payload.title}</span>
        </button>
      ))}
      {isMe && (
        <button className="highlight" onClick={() => setCreating(true)}>
          <span className="highlight-cover new">
            <Plus size={30} strokeWidth={1.4} />
          </span>
          <span>New</span>
        </button>
      )}
      {creating && <HighlightCreator onClose={() => setCreating(false)} />}
    </div>
  );
}

function HighlightFallback({ highlight }: { highlight: Entity<HighlightPayload> }) {
  const index = useIndex();
  const first = highlight.payload.stories[0];
  const story = index.myStoryArchive.find((s) => s.dto.id === first?.id);
  return story ? <StoryThumb story={story} /> : <span className="faint">{highlight.payload.title.charAt(0)}</span>;
}

/** Pick stories from your archive (even expired ones), name it, done. */
function HighlightCreator({ onClose }: { onClose: () => void }) {
  const index = useIndex();
  const [picked, setPicked] = useState<string[]>([]);
  const [step, setStep] = useState<'pick' | 'name'>('pick');
  const [title, setTitle] = useState('Highlights');
  const [busy, setBusy] = useState(false);
  const archive = index.myStoryArchive;
  const save = async () => {
    setBusy(true);
    try {
      const stories = picked.map((id) => archive.find((s) => s.dto.id === id)!).map((s) => ({ id: s.dto.id, key: b64(s.key) }));
      const first = archive.find((s) => s.dto.id === picked[0]) as Entity<StoryPayload> | undefined;
      const cover = first?.payload.media?.mime.startsWith('image/') ? first.payload.media : first?.payload.media?.poster ?? null;
      await createHighlight({ title: title.trim() || 'Highlights', stories, cover });
      onClose();
    } catch (e) {
      toast(messageOf(e), 'error');
    } finally {
      setBusy(false);
    }
  };
  return (
    <Sheet
      open
      onClose={onClose}
      size="wide"
      title={step === 'pick' ? 'Stories' : 'Title'}
      right={
        step === 'pick' ? (
          <button className="btn btn-link" disabled={!picked.length} onClick={() => setStep('name')}>
            Next
          </button>
        ) : (
          <button className="btn btn-link" disabled={busy} onClick={save}>
            Done
          </button>
        )
      }
    >
      {step === 'pick' ? (
        archive.length ? (
          <div className="story-pick">
            {archive.map((s) => {
              const n = picked.indexOf(s.dto.id);
              return (
                <button key={s.dto.id} className="story-pick-item" onClick={() => setPicked((p) => (n >= 0 ? p.filter((x) => x !== s.dto.id) : [...p, s.dto.id]))}>
                  <StoryThumb story={s} />
                  <span className={`pick-badge ${n >= 0 ? 'on' : ''}`}>{n >= 0 ? n + 1 : <Check size={12} />}</span>
                  <span className="story-pick-date">{new Date(s.dto.createdAt).toLocaleDateString(undefined, { day: 'numeric', month: 'short' })}</span>
                </button>
              );
            })}
          </div>
        ) : (
          <p className="muted" style={{ textAlign: 'center', padding: 32 }}>
            Your stories are kept in your archive after they disappear. Share one first.
          </p>
        )
      ) : (
        <div className="stack" style={{ alignItems: 'center', padding: 16 }}>
          <input className="input" value={title} onChange={(e) => setTitle(e.target.value)} maxLength={20} style={{ textAlign: 'center', maxWidth: 280 }} autoFocus />
        </div>
      )}
    </Sheet>
  );
}
