import { AtSign, Clock, Hash, Link2, MapPin, Send } from 'lucide-react';
import { motion } from 'motion/react';
import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { answer, vote } from '../../core/compose';
import { messageOf } from '../../core/errors';
import { Kind, StickerType, type Sticker, type StoryPayload, type VotePayload } from '../../core/payloads';
import { useIndex } from '../../core/selectors';
import { toast, useApp, type Entity } from '../../core/store';

function useVotes(story: Entity, stickerId: string) {
  const index = useIndex();
  const me = useApp((s) => s.me!.id);
  const votes = (index.children[story.dto.id] ?? []).filter((e) => e.dto.kind === Kind.VOTE && (e.payload as VotePayload).stickerId === stickerId) as Entity<VotePayload>[];
  const mine = votes.find((v) => v.dto.authorId === me);
  return { votes, mine };
}

function Poll({ story, sticker, quiz }: { story: Entity; sticker: Sticker; quiz?: boolean }) {
  const { votes, mine } = useVotes(story, sticker.id);
  const isAuthor = story.dto.authorId === useApp((s) => s.me!.id);
  const done = !!mine || isAuthor;
  const options = sticker.options ?? [];
  const counts = options.map((_, i) => votes.filter((v) => v.payload.option === i).length);
  const total = Math.max(1, votes.length);
  return (
    <div className="sticker-card poll" onClick={(e) => e.stopPropagation()}>
      {sticker.text && <div className="poll-q">{sticker.text}</div>}
      {options.map((option, i) => {
        const pct = Math.round((counts[i] / total) * 100);
        const chosen = mine?.payload.option === i;
        const correct = quiz && sticker.correct === i;
        return (
          <button
            key={i}
            className={`poll-option ${done ? 'done' : ''} ${chosen ? 'chosen' : ''} ${quiz && done ? (correct ? 'correct' : chosen ? 'wrong' : '') : ''}`}
            disabled={done}
            onClick={() => void vote(story.dto.id, { stickerId: sticker.id, option: i }).catch((e) => toast(messageOf(e), 'error'))}
          >
            {done && <motion.span className="poll-fill" initial={{ width: 0 }} animate={{ width: `${pct}%` }} transition={{ duration: 0.6, ease: [0.16, 1, 0.3, 1] }} />}
            <span className="poll-label">{option}</span>
            {done && <span className="poll-pct">{pct}%</span>}
          </button>
        );
      })}
    </div>
  );
}

function Slider({ story, sticker }: { story: Entity; sticker: Sticker }) {
  const { votes, mine } = useVotes(story, sticker.id);
  const [value, setValue] = useState(0.5);
  const isAuthor = story.dto.authorId === useApp((s) => s.me!.id);
  const average = votes.length ? votes.reduce((n, v) => n + (v.payload.value ?? 0), 0) / votes.length : null;
  const done = !!mine || isAuthor;
  return (
    <div className="sticker-card slider" onClick={(e) => e.stopPropagation()}>
      {sticker.text && <div className="poll-q">{sticker.text}</div>}
      <div className="slider-track">
        <input
          type="range"
          min={0}
          max={1}
          step={0.01}
          value={done ? mine?.payload.value ?? average ?? 0.5 : value}
          disabled={done}
          onChange={(e) => setValue(Number(e.target.value))}
          onPointerUp={() => !done && void vote(story.dto.id, { stickerId: sticker.id, value }).catch((e) => toast(messageOf(e), 'error'))}
          style={{ ['--emoji' as string]: `"${sticker.emoji ?? '😍'}"` }}
          aria-label="Slider"
        />
        <span className="slider-emoji" style={{ left: `calc(${(done ? mine?.payload.value ?? average ?? 0.5 : value) * 100}% - 16px)` }}>
          {sticker.emoji ?? '😍'}
        </span>
      </div>
      {done && average !== null && <div className="faint" style={{ fontSize: 12, textAlign: 'center' }}>Average of {votes.length}</div>}
    </div>
  );
}

function Question({ story, sticker }: { story: Entity; sticker: Sticker }) {
  const [text, setText] = useState('');
  const isAuthor = story.dto.authorId === useApp((s) => s.me!.id);
  return (
    <form
      className="sticker-card question"
      onClick={(e) => e.stopPropagation()}
      onSubmit={async (e) => {
        e.preventDefault();
        if (!text.trim()) return;
        try {
          await answer(story, { storyId: story.dto.id, stickerId: sticker.id, text: text.trim() });
          setText('');
          toast('Sent — only they can see it', 'success');
        } catch (err) {
          toast(messageOf(err), 'error');
        }
      }}
    >
      <div className="poll-q">{sticker.text || 'Ask me a question'}</div>
      {isAuthor ? (
        <div className="question-input faint">People reply here</div>
      ) : (
        <div className="row" style={{ gap: 6 }}>
          <input className="question-input" placeholder="Type something…" value={text} onChange={(e) => setText(e.target.value)} />
          {text.trim() && (
            <button className="icon-btn sm" aria-label="Send">
              <Send size={16} />
            </button>
          )}
        </div>
      )}
    </form>
  );
}

function Countdown({ sticker }: { sticker: Sticker }) {
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(t);
  }, []);
  const left = Math.max(0, (sticker.endsAt ?? now) - now);
  const d = Math.floor(left / 86_400_000);
  const h = Math.floor((left % 86_400_000) / 3_600_000);
  const m = Math.floor((left % 3_600_000) / 60_000);
  const s = Math.floor((left % 60_000) / 1000);
  return (
    <div className="sticker-card countdown">
      <div className="poll-q">
        <Clock size={14} /> {sticker.text || 'Countdown'}
      </div>
      <div className="countdown-digits tabular">
        {left === 0 ? (
          'Ended'
        ) : (
          <>
            {d > 0 && <span>{d}<small>d</small></span>}
            <span>{String(h).padStart(2, '0')}<small>h</small></span>
            <span>{String(m).padStart(2, '0')}<small>m</small></span>
            {d === 0 && <span>{String(s).padStart(2, '0')}<small>s</small></span>}
          </>
        )}
      </div>
    </div>
  );
}

function Pill({ icon, children, onClick }: { icon: React.ReactNode; children: React.ReactNode; onClick?: () => void }) {
  return (
    <button className="sticker-pill" onClick={(e) => (e.stopPropagation(), onClick?.())}>
      {icon}
      <span>{children}</span>
    </button>
  );
}

/** Interactive story stickers, positioned in fractions of the canvas. */
export function Stickers({ story }: { story: Entity<StoryPayload> }) {
  const navigate = useNavigate();
  const users = useApp((s) => s.users);
  return (
    <div className="stickers">
      {(story.payload.stickers ?? []).map((sticker) => {
        let content: React.ReactNode;
        switch (sticker.type) {
          case StickerType.POLL:
            content = <Poll story={story} sticker={sticker} />;
            break;
          case StickerType.QUIZ:
            content = <Poll story={story} sticker={sticker} quiz />;
            break;
          case StickerType.SLIDER:
            content = <Slider story={story} sticker={sticker} />;
            break;
          case StickerType.QUESTION:
            content = <Question story={story} sticker={sticker} />;
            break;
          case StickerType.COUNTDOWN:
            content = <Countdown sticker={sticker} />;
            break;
          case StickerType.MENTION: {
            const name = sticker.userId ? users[sticker.userId]?.username : sticker.text;
            content = <Pill icon={<AtSign size={16} />} onClick={() => navigate(`/u/${name}`)}>{name}</Pill>;
            break;
          }
          case StickerType.LINK:
            content = (
              <Pill icon={<Link2 size={16} />} onClick={() => sticker.url && window.open(sticker.url, '_blank', 'noopener,noreferrer')}>
                {(sticker.text || sticker.url || '').replace(/^https?:\/\//, '').slice(0, 32)}
              </Pill>
            );
            break;
          case StickerType.LOCATION:
            content = <Pill icon={<MapPin size={16} />}>{sticker.text}</Pill>;
            break;
          case StickerType.HASHTAG:
            content = <Pill icon={<Hash size={16} />} onClick={() => navigate(`/explore/tags/${encodeURIComponent((sticker.text ?? '').replace(/^#/, ''))}`)}>{(sticker.text ?? '').replace(/^#/, '')}</Pill>;
            break;
          case StickerType.EMOJI:
            content = <span className="sticker-emoji">{sticker.emoji}</span>;
            break;
          default:
            content = (
              <span className="sticker-text" style={{ color: sticker.color ?? '#fff' }}>
                {sticker.text}
              </span>
            );
        }
        return (
          <div
            key={sticker.id}
            className="sticker"
            style={{ left: `${sticker.x * 100}%`, top: `${sticker.y * 100}%`, transform: `translate(-50%, -50%) rotate(${sticker.rotation ?? 0}deg) scale(${sticker.scale ?? 1})` }}
          >
            {content}
          </div>
        );
      })}
    </div>
  );
}
