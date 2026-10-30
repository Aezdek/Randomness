import { ChevronLeft, ChevronRight, Eye, Heart, MoreHorizontal, Pause, Play, Send, Volume2, VolumeX, X } from 'lucide-react';
import { AnimatePresence, motion } from 'motion/react';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { api } from '../../core/api';
import { deleteObject, openDirect, recordView, sendMessage } from '../../core/compose';
import { messageOf } from '../../core/errors';
import { Audience, Kind, MessageType, type AnswerPayload, type HighlightPayload, type StoryPayload } from '../../core/payloads';
import { useIndex } from '../../core/selectors';
import { toast, useApp, type Entity } from '../../core/store';
import { engine } from '../../core/sync';
import type { ViewDto } from '../../core/types';
import { Avatar } from '../../ui/Avatar';
import { Ago } from '../../ui/bits';
import { Blurhash, MediaImage, MediaVideo } from '../../ui/Media';
import { Confirm, Sheet } from '../../ui/Sheet';
import { ShareSheet } from '../messages/ShareSheet';
import { backgroundOf, FONTS } from './backgrounds';
import { Stickers } from './Stickers';

const REACTIONS = ['😂', '😮', '😍', '😢', '👏', '🔥'];

interface Group {
  userId: string;
  stories: Entity<StoryPayload>[];
  title?: string;
}

function Viewers({ story, onClose }: { story: Entity<StoryPayload>; onClose: () => void }) {
  const [views, setViews] = useState<ViewDto[] | null>(null);
  const users = useApp((s) => s.users);
  const index = useIndex();
  useEffect(() => {
    void api.get<ViewDto[]>(`/objects/${story.dto.id}/views`).then(setViews).catch(() => setViews([]));
  }, [story.dto.id]);
  const answers = index.answers.filter((a) => (a.payload as AnswerPayload).storyId === story.dto.id);
  const likes = (index.children[story.dto.id] ?? []).filter((e) => e.dto.kind === Kind.LIKE).map((e) => e.dto.authorId);
  return (
    <Sheet open onClose={onClose} title={`Viewers · ${views?.length ?? '…'}`} bottom bodyClass="flush">
      {answers.length > 0 && (
        <div className="answers">
          {answers.map((a) => (
            <div key={a.dto.id} className="answer-bubble">
              <Avatar userId={a.dto.authorId} size={22} />
              <span>{a.payload.text}</span>
            </div>
          ))}
        </div>
      )}
      <div style={{ padding: '4px 16px 16px' }}>
        {(views ?? []).map((v) => (
          <div className="user-row" key={v.userId}>
            <Avatar userId={v.userId} size={40} />
            <div className="user-row-text">
              <b>{users[v.userId]?.username}</b>
              <Ago at={v.at} />
            </div>
            {likes.includes(v.userId) && <Heart size={16} fill="var(--heart)" strokeWidth={0} />}
          </div>
        ))}
        {views && !views.length && <p className="faint" style={{ textAlign: 'center', padding: 24 }}>No views yet</p>}
      </div>
    </Sheet>
  );
}

/** Full-screen stories: progress bars, tap to step, hold to pause, swipe to move between people. */
export function StoryViewerPage() {
  const { userId } = useParams();
  const [params] = useSearchParams();
  const highlightId = params.get('highlight');
  const navigate = useNavigate();
  const index = useIndex();
  const entities = useApp((s) => s.entities);
  const me = useApp((s) => s.me!);
  const users = useApp((s) => s.users);
  const seen = useApp((s) => s.seenStories);

  const groups: Group[] = useMemo(() => {
    if (highlightId) {
      const highlight = entities[highlightId] as Entity<HighlightPayload> | undefined;
      if (!highlight) return [];
      const stories = highlight.payload.stories.map((s) => entities[s.id] as Entity<StoryPayload> | undefined).filter(Boolean) as Entity<StoryPayload>[];
      return [{ userId: highlight.dto.authorId, stories, title: highlight.payload.title }];
    }
    const order = [me.id, ...Object.keys(index.stories).filter((id) => id !== me.id && users[id] && !users[id].blockedByMe)];
    return order.filter((id) => index.stories[id]?.length).map((id) => ({ userId: id, stories: index.stories[id] }));
  }, [highlightId, entities, index.stories, me.id, users]);

  const [groupIndex, setGroupIndex] = useState(() => Math.max(0, groups.findIndex((g) => g.userId === userId)));
  const group = groups[groupIndex];
  const [storyIndex, setStoryIndex] = useState(() => {
    const g = groups[Math.max(0, groups.findIndex((x) => x.userId === userId))];
    if (!g || highlightId) return 0;
    const firstUnseen = g.stories.findIndex((s) => !seen[s.dto.id]);
    return firstUnseen >= 0 ? firstUnseen : 0;
  });
  const story = group?.stories[storyIndex];
  const [progress, setProgress] = useState(0);
  const [paused, setPaused] = useState(false);
  const [muted, setMuted] = useState(true);
  const [reply, setReply] = useState('');
  const [sheet, setSheet] = useState<'viewers' | 'share' | 'menu' | 'delete' | null>(null);
  const holdTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const held = useRef(false);
  const touchStart = useRef<{ x: number; y: number } | null>(null);
  const isMine = story?.dto.authorId === me.id;
  const typing = reply.length > 0;
  const halted = paused || typing || sheet !== null;

  const close = useCallback(() => (history.length > 1 ? navigate(-1) : navigate('/')), [navigate]);

  const next = useCallback(() => {
    if (!group) return close();
    if (storyIndex < group.stories.length - 1) setStoryIndex((i) => i + 1);
    else if (groupIndex < groups.length - 1) {
      const g = groups[groupIndex + 1];
      const firstUnseen = g.stories.findIndex((s) => !useApp.getState().seenStories[s.dto.id]);
      setGroupIndex(groupIndex + 1);
      setStoryIndex(firstUnseen >= 0 ? firstUnseen : 0);
    } else close();
  }, [group, storyIndex, groupIndex, groups, close]);

  const prev = useCallback(() => {
    if (storyIndex > 0) setStoryIndex((i) => i - 1);
    else if (groupIndex > 0) {
      setGroupIndex(groupIndex - 1);
      setStoryIndex(groups[groupIndex - 1].stories.length - 1);
    } else setProgress(0);
  }, [storyIndex, groupIndex, groups]);

  // Seen + view receipt.
  useEffect(() => {
    if (!story) return;
    setProgress(0);
    engine.markStorySeen(story.dto.id);
    if (!isMine) void recordView(story.dto.id);
  }, [story?.dto.id]); // eslint-disable-line react-hooks/exhaustive-deps

  // Images (and text stories) advance on a clock; videos report their own progress.
  const isVideo = story?.payload.media?.mime.startsWith('video/');
  useEffect(() => {
    if (!story || isVideo || halted) return;
    const duration = story.payload.durationMs ?? 5000;
    let raf = 0;
    let last = performance.now();
    const tick = (now: number) => {
      setProgress((p) => {
        const value = p + (now - last) / duration;
        last = now;
        if (value >= 1) {
          queueMicrotask(next);
          return 1;
        }
        return value;
      });
      raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, [story, isVideo, halted, next]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.target as HTMLElement).tagName === 'INPUT') return;
      if (e.key === 'ArrowRight') next();
      else if (e.key === 'ArrowLeft') prev();
      else if (e.key === 'Escape') close();
      else if (e.key === ' ') setPaused((p) => !p);
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [next, prev, close]);

  if (!group || !story) {
    return (
      <div className="story-stage">
        <button className="icon-btn story-close" onClick={close} aria-label="Close">
          <X size={28} color="#fff" />
        </button>
        <p style={{ color: '#fff', margin: 'auto' }}>No stories right now.</p>
      </div>
    );
  }

  const send = async (text: string, reaction = false) => {
    try {
      const threadId = await openDirect(story.dto.authorId);
      await sendMessage(threadId, { type: reaction ? MessageType.STORY_REACTION : MessageType.STORY_REPLY, text, storyId: story.dto.id, sharedAuthorId: story.dto.authorId });
      toast(reaction ? `Sent ${text}` : 'Reply sent', 'success');
      setReply('');
    } catch (e) {
      toast(messageOf(e), 'error');
    }
  };

  const onPointerDown = (e: React.PointerEvent) => {
    touchStart.current = { x: e.clientX, y: e.clientY };
    held.current = false;
    holdTimer.current = setTimeout(() => {
      held.current = true;
      setPaused(true);
    }, 220);
  };
  const onPointerUp = (e: React.PointerEvent) => {
    if (holdTimer.current) clearTimeout(holdTimer.current);
    const start = touchStart.current;
    touchStart.current = null;
    if (held.current) {
      setPaused(false);
      return;
    }
    if (start) {
      const dx = e.clientX - start.x;
      const dy = e.clientY - start.y;
      if (dy > 90 && Math.abs(dy) > Math.abs(dx)) return close();
      if (Math.abs(dx) > 60) {
        if (dx < 0 && groupIndex < groups.length - 1) {
          setGroupIndex(groupIndex + 1);
          setStoryIndex(0);
        } else if (dx > 0 && groupIndex > 0) {
          setGroupIndex(groupIndex - 1);
          setStoryIndex(0);
        }
        return;
      }
    }
    const rect = (e.currentTarget as HTMLElement).getBoundingClientRect();
    if (e.clientX - rect.left < rect.width * 0.3) prev();
    else next();
  };

  const media = story.payload.media;
  const text = story.payload.text;
  const font = FONTS[text?.font ?? 'modern'] ?? FONTS.modern;
  const author = users[story.dto.authorId];
  const backdrop = media?.blurhash ?? media?.poster?.blurhash;

  return (
    <div className="story-stage" role="dialog" aria-label="Stories">
      {backdrop && <Blurhash hash={backdrop} className="story-backdrop" />}
      <button className="icon-btn story-close hide-mobile" onClick={close} aria-label="Close">
        <X size={30} color="#fff" />
      </button>
      {groupIndex > 0 && (
        <button className="story-side prev hide-mobile" onClick={prev} aria-label="Previous">
          <ChevronLeft size={20} strokeWidth={2.6} />
        </button>
      )}
      <button className="story-side next hide-mobile" onClick={next} aria-label="Next">
        <ChevronRight size={20} strokeWidth={2.6} />
      </button>

      <AnimatePresence mode="popLayout" initial={false}>
        <motion.div
          key={group.userId + (group.title ?? '')}
          className="story-card"
          initial={{ opacity: 0, scale: 0.94, rotateY: 12 }}
          animate={{ opacity: 1, scale: 1, rotateY: 0 }}
          exit={{ opacity: 0, scale: 0.94 }}
          transition={{ type: 'spring', stiffness: 300, damping: 32 }}
        >
          <div className="story-progress">
            {group.stories.map((s, i) => (
              <span key={s.dto.id} className="bar">
                <span style={{ transform: `scaleX(${i < storyIndex ? 1 : i === storyIndex ? progress : 0})` }} />
              </span>
            ))}
          </div>
          <header className="story-head">
            <Link to={`/u/${story.dto.authorId === me.id ? me.username : author?.username}`} className="row" style={{ gap: 10 }}>
              <Avatar userId={story.dto.authorId} size={32} />
              <b>{group.title ?? (story.dto.authorId === me.id ? me.username : author?.username)}</b>
            </Link>
            <span className="story-time">
              <Ago at={story.dto.createdAt} className="" />
            </span>
            {story.dto.audience === Audience.CLOSE_FRIENDS && <span className="cf-badge">Close Friends</span>}
            <span className="spacer" />
            {isVideo && (
              <button className="icon-btn sm story-icon" onClick={() => setMuted((m) => !m)} aria-label={muted ? 'Unmute' : 'Mute'}>
                {muted ? <VolumeX size={20} /> : <Volume2 size={20} />}
              </button>
            )}
            <button className="icon-btn sm story-icon" onClick={() => setPaused((p) => !p)} aria-label={paused ? 'Play' : 'Pause'}>
              {paused ? <Play size={20} fill="#fff" /> : <Pause size={20} fill="#fff" />}
            </button>
            {isMine && (
              <button className="icon-btn sm story-icon" onClick={() => setSheet('menu')} aria-label="More">
                <MoreHorizontal size={20} />
              </button>
            )}
            <button className="icon-btn sm story-icon only-mobile" onClick={close} aria-label="Close">
              <X size={24} />
            </button>
          </header>

          <div className="story-media" onPointerDown={onPointerDown} onPointerUp={onPointerUp} onContextMenu={(e) => e.preventDefault()}>
            {media ? (
              media.mime.startsWith('video/') ? (
                <MediaVideo
                  key={story.dto.id}
                  media={media}
                  contain
                  muted={muted}
                  loop={false}
                  paused={halted}
                  onTime={setProgress}
                  onEnded={next}
                  style={{ width: '100%', height: '100%' }}
                />
              ) : (
                <MediaImage key={story.dto.id} media={media} contain style={{ width: '100%', height: '100%' }} />
              )
            ) : (
              <div className="story-text" style={{ background: backgroundOf(text?.background) }}>
                <p style={{ fontFamily: font.family, fontStyle: font.style, fontWeight: font.weight, textTransform: font.transform as React.CSSProperties['textTransform'], letterSpacing: font.tracking, textAlign: (text?.align ?? 'center') as React.CSSProperties['textAlign'] }}>
                  {text?.text}
                </p>
              </div>
            )}
            {story.payload.drawing && <MediaImage media={story.payload.drawing} contain className="story-drawing" />}
            <Stickers story={story} />
          </div>

          <footer className="story-foot">
            {isMine ? (
              <button className="story-viewers" onClick={() => setSheet('viewers')}>
                <Eye size={18} /> Activity
              </button>
            ) : (
              <>
                <form
                  className="story-reply"
                  onSubmit={(e) => {
                    e.preventDefault();
                    if (reply.trim()) void send(reply.trim());
                  }}
                >
                  <input placeholder={`Reply to ${author?.username ?? ''}…`} value={reply} onChange={(e) => setReply(e.target.value)} />
                </form>
                {!typing && (
                  <>
                    <button className="icon-btn story-icon" onClick={() => void send('❤️', true)} aria-label="Like">
                      <Heart size={26} />
                    </button>
                    <button className="icon-btn story-icon" onClick={() => setSheet('share')} aria-label="Share">
                      <Send size={24} />
                    </button>
                  </>
                )}
              </>
            )}
          </footer>
          {typing && (
            <div className="quick-reactions">
              {REACTIONS.map((r) => (
                <button key={r} onClick={() => void send(r, true)}>
                  {r}
                </button>
              ))}
            </div>
          )}
        </motion.div>
      </AnimatePresence>

      {sheet === 'viewers' && <Viewers story={story} onClose={() => setSheet(null)} />}
      <ShareSheet open={sheet === 'share'} onClose={() => setSheet(null)} objectId={story.dto.id} authorId={story.dto.authorId} storyId={story.dto.id} />
      <Sheet open={sheet === 'menu'} onClose={() => setSheet(null)} hideHeader bottom bodyClass="flush">
        <div className="menu centered">
          <button className="danger" onClick={() => setSheet('delete')}>
            Delete
          </button>
          <button onClick={() => setSheet(null)}>Cancel</button>
        </div>
      </Sheet>
      <Confirm
        open={sheet === 'delete'}
        title="Delete story?"
        message="It will also be removed from your archive and highlights."
        confirm="Delete"
        danger
        onConfirm={() => void deleteObject(story.dto.id).then(next).catch((e) => toast(messageOf(e), 'error'))}
        onClose={() => setSheet(null)}
      />
    </div>
  );
}
