import { ArrowDown, ArrowLeft, Camera, CornerUpLeft, Eye, Forward, Ghost, Heart, ImagePlus, Info, ListChecks, Mic, MoreHorizontal, Pause, Phone, PhoneMissed, Pin, Play, Plus, Smile, Square, Video, X } from 'lucide-react';
import { AnimatePresence, motion } from 'motion/react';
import { Fragment, useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom';
import { chatCustom, DEFAULT_REACTION, eventText, themeOf, themeStyle, type ChatCustom } from '../../core/chat';
import { deleteObject, edit, markRead, react as reactTo, recordView, sendChatEvent, sendMessage, votePoll } from '../../core/compose';
import { messageOf } from '../../core/errors';
import { prepare, prepareVoice, upload } from '../../core/media';
import { Audience, Kind, MessageType, type MessagePayload, type Poll, type ReactionPayload, type StoryPayload, type VotePayload } from '../../core/payloads';
import { nameIn, threadTitle, useIndex } from '../../core/selectors';
import { toast, useApp, type Entity } from '../../core/store';
import { realtime } from '../../core/ws';
import { Avatar } from '../../ui/Avatar';
import { ago, Center, RichText, Spinner } from '../../ui/bits';
import { MediaImage, MediaVideo, useMediaUrl } from '../../ui/Media';
import { Sheet } from '../../ui/Sheet';
import { calls } from '../calls/calls';
import { coverOf } from '../profile/PostGrid';
import { ThreadList } from './Inbox';
import { ShareSheet } from './ShareSheet';
import { ThreadInfo } from './ThreadInfo';

const QUICK = ['❤️', '😂', '😮', '😢', '😡', '👍'];
/** The "+" in the reaction row: the emojis people react with most, as on Instagram. */
const MORE_EMOJI = [
  '😀', '😃', '😄', '😁', '😆', '🥹', '😅', '🤣', '😊', '😇', '🙂', '😉', '😌', '😍', '🥰', '😘',
  '😋', '😜', '🤪', '😎', '🤩', '🥳', '😏', '😒', '😔', '😟', '🥺', '😭', '😤', '🤯', '😳', '😱',
  '🤔', '🫡', '🤭', '🫢', '😶', '🙄', '😬', '😴', '🤤', '🤢', '🤮', '🤧', '😵', '🤠', '😈', '💀',
  '👏', '🙌', '🙏', '🤝', '👌', '✌️', '🤞', '🫶', '💪', '👀', '🔥', '✨', '💯', '🎉', '💔', '💕',
];

/** "14:32" in the viewer's own clock format. */
const clock = (at: number) => new Date(at).toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' });
/** Instagram's centred stamps between messages: "Today 14:32", "Yesterday 09:10", "Mon 14:32", "3 Oct 14:32". */
function chatStamp(at: number, now = Date.now()) {
  const d = new Date(at);
  const today = new Date(now);
  const startOf = (x: Date) => new Date(x.getFullYear(), x.getMonth(), x.getDate()).getTime();
  const days = Math.round((startOf(today) - startOf(d)) / 86_400_000);
  const time = clock(at);
  if (days === 0) return `Today ${time}`;
  if (days === 1) return `Yesterday ${time}`;
  if (days < 7) return `${d.toLocaleDateString(undefined, { weekday: 'short' })} ${time}`;
  return `${d.toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: d.getFullYear() === today.getFullYear() ? undefined : 'numeric' })}, ${time}`;
}
const fullTime = (at: number) => new Date(at).toLocaleString(undefined, { weekday: 'short', month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' });

/**
 * The chat's touch gestures, as on Instagram: drag the chat left to slide every message over and show its time (it
 * springs back on release), or drag one message right to reply to it. Vertical scrolling stays the browser's own.
 */
function useChatGestures(scroller: React.RefObject<HTMLDivElement | null>, onReply: (messageId: string) => void) {
  useEffect(() => {
    const el = scroller.current;
    if (!el) return;
    let start: { x: number; y: number; id: number } | null = null;
    let mode: 'times' | 'reply' | 'none' | null = null;
    let row: HTMLElement | null = null;
    let dx = 0;
    const reset = () => {
      el.classList.remove('dragging');
      el.style.setProperty('--reveal', '0px');
      if (row) {
        row.style.left = '';
        row.classList.remove('replying', 'swiping');
      }
      start = null;
      mode = null;
      row = null;
      dx = 0;
    };
    const down = (e: PointerEvent) => {
      if (e.pointerType === 'mouse' || !e.isPrimary) return;
      start = { x: e.clientX, y: e.clientY, id: e.pointerId };
      row = (e.target as HTMLElement).closest<HTMLElement>('.msg[id^="msg-"]');
      mode = null;
    };
    const move = (e: PointerEvent) => {
      if (!start || e.pointerId !== start.id) return;
      dx = e.clientX - start.x;
      const dy = e.clientY - start.y;
      if (!mode) {
        if (Math.abs(dx) < 10 && Math.abs(dy) < 10) return;
        mode = Math.abs(dx) > Math.abs(dy) * 1.2 ? (dx < 0 ? 'times' : row ? 'reply' : 'none') : 'none';
        if (mode !== 'none') el.classList.add('dragging');
      }
      if (mode === 'times') el.style.setProperty('--reveal', `${Math.max(-84, Math.min(0, dx))}px`);
      if (mode === 'reply' && row) {
        row.classList.add('swiping');
        row.style.left = `${Math.max(0, Math.min(84, dx))}px`;
        row.classList.toggle('replying', dx > 60);
      }
    };
    const up = () => {
      if (mode === 'reply' && row && dx > 60) {
        navigator.vibrate?.(10);
        onReply(row.id.slice(4));
      }
      reset();
    };
    el.addEventListener('pointerdown', down);
    el.addEventListener('pointermove', move);
    el.addEventListener('pointerup', up);
    el.addEventListener('pointercancel', reset);
    return () => {
      el.removeEventListener('pointerdown', down);
      el.removeEventListener('pointermove', move);
      el.removeEventListener('pointerup', up);
      el.removeEventListener('pointercancel', reset);
    };
  }, [scroller, onReply]);
}

/** Opens a message's menu on a long press (phones have no right-click, and Safari never fires one). */
function useLongPress(onLong: () => void) {
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const start = useRef<{ x: number; y: number } | null>(null);
  const cancel = () => {
    if (timer.current) clearTimeout(timer.current);
    timer.current = null;
  };
  return {
    onPointerDown: (e: React.PointerEvent) => {
      if (e.pointerType === 'mouse') return;
      start.current = { x: e.clientX, y: e.clientY };
      cancel();
      timer.current = setTimeout(() => {
        timer.current = null;
        navigator.vibrate?.(10);
        onLong();
      }, 430);
    },
    onPointerMove: (e: React.PointerEvent) => {
      if (start.current && Math.hypot(e.clientX - start.current.x, e.clientY - start.current.y) > 10) cancel();
    },
    onPointerUp: cancel,
    onPointerCancel: cancel,
  };
}

/** A photo, video or voice note on its way (converting, then encrypting and uploading). */
interface Outgoing {
  id: string;
  label: string;
  preview: string | null;
  phase: 'converting' | 'sending';
  progress: number;
}

function useVanish(threadId: string) {
  const key = `hush.vanish.${threadId}`;
  const [on, setOn] = useState(() => localStorage.getItem(key) === '1');
  return [on, (v: boolean) => (localStorage.setItem(key, v ? '1' : '0'), setOn(v))] as const;
}

function duration(ms: number) {
  const s = Math.round(ms / 1000);
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
}

function Voice({ message }: { message: Entity<MessagePayload> }) {
  const ref = message.payload.media?.[0];
  const url = useMediaUrl(ref);
  const audio = useRef<HTMLAudioElement>(null);
  const [playing, setPlaying] = useState(false);
  const [progress, setProgress] = useState(0);
  const bars = useMemo(() => Array.from({ length: 28 }, (_, i) => 0.3 + Math.abs(Math.sin(i * 1.7 + (ref?.size ?? 0) % 7)) * 0.7), [ref?.size]);
  return (
    <div className="voice">
      <button
        className="voice-play"
        onClick={() => {
          const el = audio.current;
          if (!el) return;
          if (el.paused) void el.play().catch(() => toast('This browser can’t play that voice message', 'error'));
          else el.pause();
        }}
        disabled={!url}
        aria-label={playing ? 'Pause' : 'Play'}
      >
        {!url ? <Spinner size={14} /> : playing ? <Pause size={16} fill="currentColor" /> : <Play size={16} fill="currentColor" />}
      </button>
      <span className="voice-wave">
        {bars.map((h, i) => (
          <span key={i} style={{ height: `${h * 100}%`, opacity: i / bars.length < progress ? 1 : 0.45 }} />
        ))}
      </span>
      <span className="voice-time tabular">{duration(ref?.durationMs ?? 0)}</span>
      {url && (
        <audio ref={audio} src={url} preload="auto" onPlay={() => setPlaying(true)} onPause={() => setPlaying(false)} onEnded={() => (setPlaying(false), setProgress(0))} onTimeUpdate={(e) => setProgress(e.currentTarget.currentTime / (e.currentTarget.duration || 1))} />
      )}
    </div>
  );
}

function Shared({ message }: { message: Entity<MessagePayload> }) {
  const entity = useApp((s) => (message.payload.sharedObjectId ? s.entities[message.payload.sharedObjectId] : undefined));
  const users = useApp((s) => s.users);
  const navigate = useNavigate();
  const location = useLocation();
  const author = users[message.payload.sharedAuthorId ?? ''];
  if (!entity) {
    return (
      <div className="shared unavailable">
        <b>Post unavailable</b>
        <span className="faint">It may have been deleted, or the account is private.</span>
      </div>
    );
  }
  const cover = coverOf(entity) ?? (entity.payload as StoryPayload).media ?? null;
  return (
    <button className="shared" onClick={() => (entity.dto.kind === Kind.STORY ? navigate(`/stories/${entity.dto.authorId}`) : navigate(`/p/${entity.dto.id}`, { state: { background: location } }))}>
      <span className="shared-head">
        <Avatar userId={entity.dto.authorId} size={26} />
        <b>{author?.username ?? 'you'}</b>
      </span>
      {cover && <MediaImage media={cover.mime.startsWith('video/') && cover.poster ? cover.poster : cover} className="shared-media" />}
    </button>
  );
}

function Bubble({
  message,
  mine,
  onReply,
  onEdit,
  showAvatar,
  showName,
  custom,
}: {
  message: Entity<MessagePayload>;
  mine: boolean;
  onReply: () => void;
  onEdit: () => void;
  showAvatar: boolean;
  /** Group chats name the sender above the first of their messages in a row. */
  showName: boolean;
  custom: ChatCustom;
}) {
  const index = useIndex();
  const entities = useApp((s) => s.entities);
  const users = useApp((s) => s.users);
  const me = useApp((s) => s.me!);
  const [menu, setMenu] = useState(false);
  const [moreEmoji, setMoreEmoji] = useState(false);
  const [opened, setOpened] = useState(false);
  const [forwarding, setForwarding] = useState(false);
  const longPress = useLongPress(() => setMenu(true));
  const p = message.payload;
  const reactions = (index.children[message.dto.id] ?? []).filter((e) => e.dto.kind === Kind.REACTION) as Entity<ReactionPayload>[];
  const replyTarget = p.replyTo ? (entities[p.replyTo] as Entity<MessagePayload> | undefined) : undefined;
  const once = message.dto.audience === Audience.ONCE;
  const vanish = message.dto.audience === Audience.VANISH;
  const big = p.type === MessageType.HEART || (p.type === MessageType.TEXT && /^\p{Extended_Pictographic}{1,3}$/u.test(p.text ?? ''));
  const story = p.storyId ? (entities[p.storyId] as Entity<StoryPayload> | undefined) : undefined;
  const threadId = message.dto.threadId ?? null;
  const name = (id: string) => (id === me.id ? 'You' : nameIn(threadId, id, index, users));
  const pinned = custom.pinned.includes(message.dto.id);
  const forwardable =
    !once && !vanish && ([MessageType.TEXT, MessageType.MEDIA, MessageType.VOICE, MessageType.SHARE, MessageType.HEART] as string[]).includes(p.type);

  let content: React.ReactNode;
  if (p.type === MessageType.CALL && p.call) {
    content = (
      <div className={`call-bubble ${p.call.missed ? 'missed' : ''}`}>
        <span className="call-icon">{p.call.missed ? <PhoneMissed size={18} /> : p.call.video ? <Video size={18} /> : <Phone size={18} />}</span>
        <span>
          <b>{p.call.missed ? 'Missed call' : `${p.call.video ? 'Video' : 'Audio'} call`}</b>
          <span className="faint">{p.call.missed ? 'Tap to call back' : duration(p.call.durationMs)}</span>
        </span>
      </div>
    );
  } else if (p.type === MessageType.VOICE) {
    content = <Voice message={message} />;
  } else if (p.type === MessageType.SHARE) {
    content = <Shared message={message} />;
  } else if (p.type === MessageType.MEDIA && p.media?.length) {
    content =
      once && !mine && !opened ? (
        <button className="once" onClick={() => (setOpened(true), void recordView(message.dto.id))}>
          <Eye size={18} /> View once · tap to open
        </button>
      ) : (
        <div className={`bubble-media ${p.media.length > 1 ? 'many' : ''}`}>
          {p.media.map((m) => (m.mime.startsWith('video/') ? <MediaVideo key={m.blobId} media={m} controls autoPlay={false} muted={false} natural /> : <MediaImage key={m.blobId} media={m} natural />))}
          {p.text && <p className="bubble-caption">{p.text}</p>}
        </div>
      );
  } else if (p.type === MessageType.POLL && p.poll) {
    content = <PollCard message={message} />;
  } else {
    content = big ? <span className="big-emoji">{p.type === MessageType.HEART ? p.text || DEFAULT_REACTION : p.text}</span> : <RichText text={p.text ?? ''} />;
  }

  const plainBubble = big || p.type === MessageType.SHARE || p.type === MessageType.POLL || (p.type === MessageType.MEDIA && !(once && !mine && !opened));
  return (
    <div className={`msg ${mine ? 'mine' : 'theirs'}`} id={`msg-${message.dto.id}`}>
      {!mine && <span className="msg-avatar">{showAvatar && <Avatar userId={message.dto.authorId} size={28} />}</span>}
      <div className="msg-col">
        {showName && !mine && <span className="msg-sender">{name(message.dto.authorId)}</span>}
        {p.type === MessageType.NOTE_REPLY && (
          <div className="story-ref note-ref">
            <span className="faint">{mine ? 'You replied to their note' : 'Replied to your note'}</span>
            {p.quote && <span className="note-bubble">{p.quote}</span>}
          </div>
        )}
        {(p.type === MessageType.STORY_REPLY || p.type === MessageType.STORY_REACTION) && (
          <div className="story-ref">
            <span className="faint">{mine ? 'You replied to their story' : `Replied to your story`}</span>
            {story?.payload.media && <MediaImage media={story.payload.media.mime.startsWith('video/') && story.payload.media.poster ? story.payload.media.poster : story.payload.media} className="story-ref-thumb" />}
          </div>
        )}
        {p.forwarded && (
          <span className="msg-forwarded">
            <Forward size={11} /> {mine ? 'You forwarded a message' : 'Forwarded'}
          </span>
        )}
        {replyTarget && (
          <div className="reply-ref">
            <CornerUpLeft size={12} /> {name(replyTarget.dto.authorId)}: {replyTarget.payload.text ?? 'Attachment'}
          </div>
        )}
        <div className="msg-row">
          <div
            className={`bubble ${plainBubble ? 'plain' : ''} ${vanish ? 'vanish' : ''}`}
            onContextMenu={(e) => (e.preventDefault(), setMenu(true))}
            onDoubleClick={() => void reactTo(message, '❤️')}
            {...longPress}
          >
            {content}
            {p.edited && <span className="edited">Edited</span>}
          </div>
          <span className="msg-actions">
            <time className="msg-hover-time" dateTime={new Date(message.dto.receivedAt).toISOString()} title={fullTime(message.dto.receivedAt)}>
              {clock(message.dto.receivedAt)}
            </time>
            <button className="icon-btn sm" onClick={() => setMenu(true)} aria-label="React">
              <Smile size={16} />
            </button>
            <button className="icon-btn sm" onClick={onReply} aria-label="Reply">
              <CornerUpLeft size={16} />
            </button>
            <button className="icon-btn sm" onClick={() => setMenu(true)} aria-label="More">
              <MoreHorizontal size={16} />
            </button>
          </span>
        </div>
        <span className="msg-swipe-reply" aria-hidden>
          <CornerUpLeft size={18} />
        </span>
        {reactions.length > 0 && (
          <div className="reactions" onClick={() => setMenu(true)}>
            {[...new Set(reactions.map((r) => r.payload.emoji))].slice(0, 3).join('')}
            {reactions.length > 1 && <span>{reactions.length}</span>}
          </div>
        )}
      </div>
      <time className="msg-time" dateTime={new Date(message.dto.receivedAt).toISOString()}>
        {clock(message.dto.receivedAt)}
      </time>
      <Sheet open={menu} onClose={() => (setMenu(false), setMoreEmoji(false))} hideHeader bottom bodyClass="flush">
        <p className="menu-time">{fullTime(message.dto.receivedAt)}</p>
        <div className="quick-react">
          {QUICK.map((e) => (
            <motion.button key={e} whileHover={{ scale: 1.25 }} whileTap={{ scale: 0.9 }} onClick={() => (setMenu(false), void reactTo(message, e))}>
              {e}
            </motion.button>
          ))}
          <button className={`quick-react-more ${moreEmoji ? 'on' : ''}`} onClick={() => setMoreEmoji((v) => !v)} aria-label="More emojis" aria-expanded={moreEmoji}>
            +
          </button>
        </div>
        {moreEmoji && (
          <div className="emoji-grid react-grid">
            {MORE_EMOJI.map((e) => (
              <button key={e} onClick={() => (setMenu(false), setMoreEmoji(false), void reactTo(message, e))}>
                {e}
              </button>
            ))}
          </div>
        )}
        <div className="menu">
          <button onClick={() => (setMenu(false), onReply())}>Reply</button>
          {forwardable && <button onClick={() => (setMenu(false), setForwarding(true))}>Forward</button>}
          {p.text && <button onClick={() => (setMenu(false), void navigator.clipboard.writeText(p.text ?? ''))}>Copy</button>}
          {threadId && message.dto.audience !== Audience.VANISH && message.dto.audience !== Audience.ONCE && (
            <button onClick={() => (setMenu(false), void sendChatEvent(threadId, pinned ? { unpin: message.dto.id } : { pin: message.dto.id }).catch((e) => toast(messageOf(e), 'error')))}>
              {pinned ? 'Unpin' : 'Pin'}
            </button>
          )}
          {mine && p.type === MessageType.TEXT && <button onClick={() => (setMenu(false), onEdit())}>Edit</button>}
          {mine && (
            <button className="danger" onClick={() => (setMenu(false), void deleteObject(message.dto.id).catch((e) => toast(messageOf(e), 'error')))}>
              Unsend
            </button>
          )}
          {reactions.length > 0 && (
            <div style={{ padding: '8px 18px' }}>
              {reactions.map((r) => (
                <div key={r.dto.id} className="user-row">
                  <Avatar userId={r.dto.authorId} size={28} />
                  <span className="user-row-text">{name(r.dto.authorId)}</span>
                  <span style={{ fontSize: 20 }}>{r.payload.emoji}</span>
                </div>
              ))}
            </div>
          )}
        </div>
      </Sheet>
      {forwarding && <ShareSheet open onClose={() => setForwarding(false)} forward={p} />}
    </div>
  );
}

/** A group-chat poll: tap an option to vote (tap it again to take the vote back); everyone sees who voted for what. */
function PollCard({ message }: { message: Entity<MessagePayload> }) {
  const index = useIndex();
  const me = useApp((s) => s.me!);
  const users = useApp((s) => s.users);
  const poll = message.payload.poll!;
  const votes = (index.children[message.dto.id] ?? []).filter((e) => e.dto.kind === Kind.VOTE) as Entity<VotePayload>[];
  const myVote = votes.find((v) => v.dto.authorId === me.id)?.payload.option;
  const total = votes.length;
  const vote = (i: number) => void votePoll(message, i).catch((e) => toast(messageOf(e), 'error'));
  return (
    <div className="chat-poll-card">
      <p className="chat-poll-question">
        <ListChecks size={16} /> {poll.question}
      </p>
      {poll.options.map((option, i) => {
        const voters = votes.filter((v) => v.payload.option === i);
        const share = total ? voters.length / total : 0;
        return (
          <button
            key={i}
            className={`chat-poll-option ${myVote === i ? 'on' : ''}`}
            onClick={() => vote(i)}
            aria-pressed={myVote === i}
            title={voters.map((v) => (v.dto.authorId === me.id ? 'You' : nameIn(message.dto.threadId ?? null, v.dto.authorId, index, users))).join(', ')}
          >
            <span className="chat-poll-fill" style={{ width: `${Math.round(share * 100)}%` }} />
            <span className="chat-poll-label">{option}</span>
            <span className="chat-poll-voters">
              {voters.slice(0, 3).map((v) => (
                <Avatar key={v.dto.id} userId={v.dto.authorId} size={18} />
              ))}
            </span>
            {total > 0 && <span className="chat-poll-count">{voters.length}</span>}
          </button>
        );
      })}
      <p className="chat-poll-total">{total === 0 ? 'No votes yet · tap to vote' : `${total} vote${total === 1 ? '' : 's'}`}</p>
    </div>
  );
}

function PollSheet({ open, onClose, onSend }: { open: boolean; onClose: () => void; onSend: (poll: Poll) => void }) {
  const [question, setQuestion] = useState('');
  const [options, setOptions] = useState(['', '']);
  const filled = options.map((o) => o.trim()).filter(Boolean);
  const ready = question.trim().length > 0 && filled.length >= 2;
  const close = () => (setQuestion(''), setOptions(['', '']), onClose());
  return (
    <Sheet open={open} onClose={close} title="Poll" bottom>
      <form
        className="chat-poll-form"
        onSubmit={(e) => {
          e.preventDefault();
          if (!ready) return;
          onSend({ question: question.trim(), options: filled });
          close();
        }}
      >
        <input className="input" placeholder="Ask a question…" value={question} maxLength={140} onChange={(e) => setQuestion(e.target.value)} autoFocus />
        {options.map((o, i) => (
          <div className="chat-poll-form-option" key={i}>
            <input className="input" placeholder={`Option ${i + 1}`} value={o} maxLength={60} onChange={(e) => setOptions((all) => all.map((x, j) => (j === i ? e.target.value : x)))} />
            {options.length > 2 && (
              <button type="button" className="icon-btn sm" aria-label={`Remove option ${i + 1}`} onClick={() => setOptions((all) => all.filter((_, j) => j !== i))}>
                <X size={16} />
              </button>
            )}
          </div>
        ))}
        {options.length < 6 && (
          <button type="button" className="btn btn-link chat-poll-add" onClick={() => setOptions((all) => [...all, ''])}>
            <Plus size={16} /> Add option
          </button>
        )}
        <button className="btn btn-primary btn-block" disabled={!ready}>
          Send poll
        </button>
      </form>
    </Sheet>
  );
}

function Recorder({ onDone, onCancel }: { onDone: (blob: Blob, ms: number) => void; onCancel: () => void }) {
  const [elapsed, setElapsed] = useState(0);
  const recorder = useRef<MediaRecorder | null>(null);
  const started = useRef(Date.now());
  const cancelled = useRef(false);
  useEffect(() => {
    let stream: MediaStream | null = null;
    const chunks: Blob[] = [];
    navigator.mediaDevices
      .getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true } })
      .then((s) => {
        stream = s;
        const rec = new MediaRecorder(s);
        rec.ondataavailable = (e) => chunks.push(e.data);
        rec.onstop = () => {
          stream?.getTracks().forEach((t) => t.stop());
          if (!cancelled.current) onDone(new Blob(chunks, { type: rec.mimeType }), Date.now() - started.current);
        };
        rec.start();
        recorder.current = rec;
        started.current = Date.now();
      })
      .catch(() => {
        toast('Microphone unavailable', 'error');
        onCancel();
      });
    const timer = setInterval(() => setElapsed(Date.now() - started.current), 200);
    return () => {
      clearInterval(timer);
      stream?.getTracks().forEach((t) => t.stop());
    };
  }, []); // eslint-disable-line react-hooks/exhaustive-deps
  return (
    <div className="recorder">
      <button
        className="icon-btn sm"
        onClick={() => {
          cancelled.current = true;
          recorder.current?.stop();
          onCancel();
        }}
        aria-label="Cancel"
      >
        <X size={18} />
      </button>
      <span className="rec-dot" />
      <span className="tabular">{duration(elapsed)}</span>
      <span className="spacer" />
      <button className="btn btn-primary btn-sm" onClick={() => recorder.current?.stop()}>
        <Square size={12} fill="currentColor" /> Send
      </button>
    </div>
  );
}

export function Chat() {
  const { threadId = '' } = useParams();
  const thread = useApp((s) => s.threads[threadId]);
  const users = useApp((s) => s.users);
  const me = useApp((s) => s.me!);
  const typing = useApp((s) => s.typing[threadId]);
  const presence = useApp((s) => s.presence);
  const index = useIndex();
  const navigate = useNavigate();
  const messages = index.messages[threadId] ?? [];
  const custom = chatCustom(index.messages[threadId]);
  const theme = themeOf(custom.theme);
  const [text, setText] = useState('');
  const [replyTo, setReplyTo] = useState<Entity<MessagePayload> | null>(null);
  const [editing, setEditing] = useState<Entity<MessagePayload> | null>(null);
  const [recording, setRecording] = useState(false);
  // Photos, videos and voice notes on their way: each uploads in the background with its own bubble, so the chat stays
  // usable meanwhile (several can go at once).
  const [outbox, setOutbox] = useState<Outgoing[]>([]);
  const [info, setInfo] = useState(false);
  const [once, setOnce] = useState(false);
  const [vanish, setVanish] = useVanish(threadId);
  const [polling, setPolling] = useState(false);
  const scroller = useRef<HTMLDivElement>(null);
  const file = useRef<HTMLInputElement>(null);
  const lastTyping = useRef(0);
  // Scrolled up to read? New messages then don't yank the chat down; a "jump to latest" button counts them instead.
  const atBottom = useRef(true);
  const shownThread = useRef<string | null>(null);
  const [below, setBelow] = useState<number | null>(null);
  const lastAuthor = messages.at(-1)?.dto.authorId;

  useLayoutEffect(() => {
    const el = scroller.current;
    if (!el) return;
    const fresh = shownThread.current !== threadId;
    shownThread.current = threadId;
    if (fresh || atBottom.current || lastAuthor === me.id) {
      el.scrollTop = el.scrollHeight;
      atBottom.current = true;
      setBelow(null);
    } else {
      setBelow((n) => (n ?? 0) + 1);
    }
  }, [messages.length, outbox.length, threadId]); // eslint-disable-line react-hooks/exhaustive-deps
  const onScroll = () => {
    const el = scroller.current;
    if (!el) return;
    atBottom.current = el.scrollHeight - el.scrollTop - el.clientHeight < 120;
    setBelow((n) => (atBottom.current ? null : (n ?? 0)));
  };
  const toLatest = () => scroller.current?.scrollTo({ top: scroller.current.scrollHeight, behavior: 'smooth' });

  const lastId = messages.at(-1)?.dto.id;
  useEffect(() => {
    if (!thread || !lastId || document.visibilityState !== 'visible') return;
    const mine = thread.members.find((m) => m.userId === me.id);
    if (mine && messages.at(-1)!.dto.receivedAt > mine.lastReadAt) void markRead(threadId).catch(() => undefined);
  }, [lastId, threadId]); // eslint-disable-line react-hooks/exhaustive-deps

  if (!thread) {
    return (
      <Center style={{ minHeight: '60vh' }}>
        <Spinner />
      </Center>
    );
  }

  const others = thread.members.filter((m) => m.userId !== me.id).map((m) => m.userId);
  const title = threadTitle(thread, index, users, me.id);
  const typers = Object.entries(typing ?? {}).filter(([u, until]) => u !== me.id && until > Date.now()).map(([u]) => u);
  const onlineNow = others.some((id) => presence[id]);
  const lastActive = Math.max(...others.map((id) => users[id]?.lastActiveAt ?? 0));
  const audience = vanish ? Audience.VANISH : Audience.THREAD;

  const send = async (payload: MessagePayload, opts: { audience?: string; blobIds?: string[] } = {}) => {
    try {
      await sendMessage(threadId, payload, { audience: opts.audience ?? audience, blobIds: opts.blobIds });
    } catch (e) {
      toast(messageOf(e), 'error');
    }
  };

  /** Runs one outgoing attachment in the background: convert, encrypt and upload (with progress), then send. */
  const dispatch = (item: Outgoing, work: (progress: (phase: Outgoing['phase'], fraction: number) => void) => Promise<void>) => {
    setOutbox((o) => [...o, item]);
    const progress = (phase: Outgoing['phase'], fraction: number) =>
      setOutbox((o) => o.map((x) => (x.id === item.id ? { ...x, phase, progress: Math.max(0, Math.min(1, fraction)) } : x)));
    void work(progress)
      .catch((e) => toast(messageOf(e), 'error'))
      .finally(() => {
        setOutbox((o) => o.filter((x) => x.id !== item.id));
        if (item.preview) URL.revokeObjectURL(item.preview);
      });
  };

  const submit = async (e?: React.FormEvent) => {
    e?.preventDefault();
    const value = text.trim();
    if (!value) return;
    setText('');
    if (editing) {
      try {
        await edit(editing, { ...editing.payload, text: value, edited: true });
      } catch (err) {
        toast(messageOf(err), 'error');
      }
      setEditing(null);
      return;
    }
    const reply = replyTo?.dto.id ?? null;
    setReplyTo(null);
    await send({ type: MessageType.TEXT, text: value, replyTo: reply });
  };

  const attach = (list: FileList) => {
    const files = [...list].slice(0, 10);
    if (!files.length) return;
    const viewOnce = once;
    setOnce(false);
    const first = files[0];
    dispatch(
      {
        id: crypto.randomUUID(),
        label: files.length > 1 ? `${files.length} photos and videos` : first.type.startsWith('video/') ? 'Video' : 'Photo',
        preview: first.type.startsWith('image/') ? URL.createObjectURL(first) : null,
        phase: 'converting',
        progress: 0,
      },
      async (progress) => {
        const refs = [];
        for (const [i, f] of files.entries()) {
          const prepared = await prepare(f, 'message', (p) => progress('converting', (i + p) / files.length));
          refs.push(await upload(prepared, (p) => progress('sending', (i + p) / files.length)));
        }
        await sendMessage(threadId, { type: MessageType.MEDIA, media: refs }, { audience: viewOnce ? Audience.ONCE : audience });
      },
    );
  };

  const voice = (blob: Blob, ms: number) => {
    setRecording(false);
    dispatch({ id: crypto.randomUUID(), label: 'Voice message', preview: null, phase: 'converting', progress: 0 }, async (progress) => {
      const ref = await upload(await prepareVoice(blob, ms), (p) => progress('sending', p));
      await sendMessage(threadId, { type: MessageType.VOICE, media: [ref] }, { audience });
    });
  };

  const replyById = useCallback(
    (id: string) => {
      const target = (index.messages[threadId] ?? []).find((m) => m.dto.id === id) as Entity<MessagePayload> | undefined;
      if (!target) return;
      setReplyTo(target);
      document.getElementById('chat-input')?.focus();
    },
    [index, threadId],
  );
  useChatGestures(scroller, replyById);

  /** Scrolls to a message (a pin, a search result) and flashes it. */
  const jump = (messageId: string) => {
    setInfo(false);
    requestAnimationFrame(() => {
      const el = document.getElementById(`msg-${messageId}`);
      if (!el) return toast('That message is no longer in the chat');
      el.scrollIntoView({ block: 'center', behavior: 'smooth' });
      el.classList.remove('flash');
      void el.offsetWidth;
      el.classList.add('flash');
    });
  };
  const pinnedMessage = [...custom.pinned].reverse().map((id) => messages.find((m) => m.dto.id === id)).find(Boolean) as Entity<MessagePayload> | undefined;
  const nameOf = (id: string) => nameIn(threadId, id, index, users);

  // Seen receipts: under my newest message, who has read up to it.
  const lastMine = [...messages].reverse().find((m) => m.dto.authorId === me.id && (m.payload as MessagePayload).type !== MessageType.SYSTEM);
  const seenBy = lastMine ? thread.members.filter((m) => m.userId !== me.id && m.lastReadAt >= lastMine.dto.receivedAt).map((m) => m.userId) : [];

  return (
    <div className={`direct ${vanish ? 'vanish-mode' : ''}`}>
      <aside className="direct-list hide-mobile">
        <header className="direct-head">
          <b>{me.username}</b>
        </header>
        <ThreadList activeId={threadId} />
      </aside>
      <section
        className={`chat ${theme.dark ? 'themed' : ''}`}
        style={themeStyle(theme) as React.CSSProperties}
      >
        <header className="chat-head">
          <button className="icon-btn only-mobile" onClick={() => navigate('/direct')} aria-label="Back">
            <ArrowLeft size={24} />
          </button>
          <button className="row chat-title" onClick={() => setInfo(true)}>
            {thread.isGroup ? (
              <span className="group-avatar small">
                {others.slice(0, 2).map((id) => (
                  <Avatar key={id} userId={id} size={30} />
                ))}
              </span>
            ) : (
              <Avatar userId={others[0]} size={44} presence />
            )}
            <span className="stack" style={{ gap: 0, alignItems: 'flex-start' }}>
              <b className="truncate" style={{ maxWidth: 260 }}>
                {title}
              </b>
              <span className="faint" style={{ fontSize: 12.5 }}>
                {typers.length ? 'typing…' : onlineNow ? 'Active now' : lastActive ? activeLabel(lastActive) : thread.isGroup ? `${thread.members.length} members` : ''}
              </span>
            </span>
          </button>
          <span className="spacer" />
          <button className="icon-btn" onClick={() => calls.start(threadId, false)} aria-label="Audio call">
            <Phone size={24} />
          </button>
          <button className="icon-btn" onClick={() => calls.start(threadId, true)} aria-label="Video call">
            <Video size={26} />
          </button>
          <button className="icon-btn" onClick={() => setInfo(true)} aria-label="Details">
            <Info size={24} />
          </button>
        </header>

        {pinnedMessage && (
          <button className="pinned-bar" onClick={() => jump(pinnedMessage.dto.id)}>
            <Pin size={15} />
            <span className="truncate">
              <b>{pinnedMessage.dto.authorId === me.id ? 'You' : nameOf(pinnedMessage.dto.authorId)}</b>{' '}
              {pinnedMessage.payload.text || (pinnedMessage.payload.media?.length ? 'Photo or video' : 'Message')}
            </span>
            {custom.pinned.length > 1 && <span className="faint">{custom.pinned.length} pinned</span>}
          </button>
        )}
        <div className="chat-scroll-wrap">
        <div className="chat-scroll" ref={scroller} onScroll={onScroll}>
          <div className="chat-intro">
            {thread.isGroup ? (
              <span className="group-avatar large">
                {others.slice(0, 2).map((id) => (
                  <Avatar key={id} userId={id} size={72} />
                ))}
              </span>
            ) : (
              <Avatar userId={others[0]} size={96} />
            )}
            <h2>{title}</h2>
            {!thread.isGroup && others[0] && (
              <Link className="btn btn-secondary btn-sm" to={`/u/${users[others[0]]?.username}`}>
                View profile
              </Link>
            )}
            <p className="faint e2ee-note">🔒 Messages and calls are end-to-end encrypted. Not even the server can read them.</p>
          </div>
          {messages.map((m, i) => {
            const prev = messages[i - 1];
            const nextMsg = messages[i + 1];
            const newDay = !prev || new Date(prev.dto.receivedAt).toDateString() !== new Date(m.dto.receivedAt).toDateString();
            const gap = prev && m.dto.receivedAt - prev.dto.receivedAt > 30 * 60_000;
            const payload = m.payload as MessagePayload;
            const isEvent = (x: Entity | undefined) => (x?.payload as MessagePayload | undefined)?.type === MessageType.SYSTEM;
            const showAvatar = !nextMsg || nextMsg.dto.authorId !== m.dto.authorId || isEvent(nextMsg);
            const showName = thread.isGroup && (!prev || prev.dto.authorId !== m.dto.authorId || isEvent(prev) || newDay || !!gap);
            return (
              <Fragment key={m.dto.id}>
                {(newDay || gap) && <div className="day">{chatStamp(m.dto.receivedAt)}</div>}
                {payload.type === MessageType.SYSTEM ? (
                  payload.event && <div className="chat-event" id={`msg-${m.dto.id}`}>{eventText(payload.event, m.dto.authorId, me.id, users, nameOf)}</div>
                ) : (
                  <Bubble
                    message={m as Entity<MessagePayload>}
                    mine={m.dto.authorId === me.id}
                    showAvatar={showAvatar}
                    showName={showName}
                    custom={custom}
                    onReply={() => {
                      setReplyTo(m as Entity<MessagePayload>);
                      document.getElementById('chat-input')?.focus();
                    }}
                    onEdit={() => {
                      setEditing(m as Entity<MessagePayload>);
                      setText(payload.text ?? '');
                    }}
                  />
                )}
                {m === lastMine && seenBy.length > 0 && (
                  <div className="seen">{thread.isGroup ? `Seen by ${seenBy.map((id) => nameOf(id)).join(', ')}` : 'Seen'}</div>
                )}
              </Fragment>
            );
          })}
          {outbox.map((item) => (
            <div className="msg mine outgoing" key={item.id}>
              <div className="msg-col">
                <div className="outgoing-bubble">
                  {item.preview ? <img src={item.preview} alt="" /> : <span className="outgoing-icon">{item.label === 'Voice message' ? <Mic size={18} /> : <ImagePlus size={18} />}</span>}
                  <span className="outgoing-text">
                    <b>{item.label}</b>
                    <span>
                      {item.phase === 'converting' ? 'Preparing' : 'Sending'} · {Math.round(item.progress * 100)}%
                    </span>
                    <span className="outgoing-bar">
                      <span style={{ width: `${Math.round(item.progress * 100)}%` }} />
                    </span>
                  </span>
                </div>
              </div>
            </div>
          ))}
          <AnimatePresence>
            {typers.length > 0 && (
              <motion.div className="msg theirs" initial={{ opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0 }}>
                <span className="msg-avatar">
                  <Avatar userId={typers[0]} size={28} />
                </span>
                <div className="bubble typing-bubble">
                  <span />
                  <span />
                  <span />
                </div>
              </motion.div>
            )}
          </AnimatePresence>
        </div>
          <AnimatePresence>
            {below !== null && (
              <motion.button
                className={`jump-latest ${below > 0 ? 'has-new' : ''}`}
                initial={{ opacity: 0, y: 10, x: '-50%' }}
                animate={{ opacity: 1, y: 0, x: '-50%' }}
                exit={{ opacity: 0, y: 10, x: '-50%' }}
                onClick={toLatest}
                aria-label="Jump to latest messages"
              >
                <ArrowDown size={18} />
                {below > 0 && <span>{below === 1 ? '1 new message' : `${below} new messages`}</span>}
              </motion.button>
            )}
          </AnimatePresence>
        </div>

        {vanish && (
          <div className="vanish-banner">
            <Ghost size={15} /> Vanish mode · seen messages disappear from the server
            <button className="btn btn-link" onClick={() => setVanish(false)}>
              Turn off
            </button>
          </div>
        )}
        {(replyTo || editing) && (
          <div className="reply-bar">
            <span className="truncate">
              {editing ? 'Editing message' : <>Replying to {replyTo!.dto.authorId === me.id ? 'yourself' : users[replyTo!.dto.authorId]?.username}: <span className="faint">{replyTo!.payload.text ?? 'Attachment'}</span></>}
            </span>
            <button className="icon-btn sm" onClick={() => (setReplyTo(null), setEditing(null), editing && setText(''))} aria-label="Cancel">
              <X size={16} />
            </button>
          </div>
        )}
        <div className="composer-bar">
          {recording ? (
            <Recorder onDone={voice} onCancel={() => setRecording(false)} />
          ) : (
            <form className="chat-composer" onSubmit={submit}>
              <button type="button" className="icon-btn sm cam" onClick={() => file.current?.click()} aria-label="Send photo or video">
                <Camera size={18} />
              </button>
              <input
                id="chat-input"
                className="input bare"
                placeholder={vanish ? 'Vanish message…' : 'Message…'}
                value={text}
                autoComplete="off"
                onChange={(e) => {
                  setText(e.target.value);
                  if (Date.now() - lastTyping.current > 2500) {
                    lastTyping.current = Date.now();
                    realtime.send({ t: 'typing', threadId });
                  }
                }}
              />
              {text.trim() ? (
                <button className="btn btn-link">
                  {editing ? 'Save' : 'Send'}
                </button>
              ) : (
                <>
                  <button type="button" className="icon-btn sm" onClick={() => setRecording(true)} aria-label="Record voice message">
                    <Mic size={20} />
                  </button>
                  <button type="button" className={`icon-btn sm ${once ? 'on-once' : ''}`} onClick={() => setOnce((o) => !o)} aria-label="View once" title="Next photo is view-once">
                    <Eye size={20} />
                  </button>
                  <button type="button" className="icon-btn sm" onClick={() => file.current?.click()} aria-label="Gallery">
                    <ImagePlus size={20} />
                  </button>
                  {thread.isGroup && (
                    <button type="button" className="icon-btn sm" onClick={() => setPolling(true)} aria-label="Create poll">
                      <ListChecks size={20} />
                    </button>
                  )}
                  <button
                    type="button"
                    className="icon-btn sm quick-reaction"
                    onClick={() => void send({ type: MessageType.HEART, text: custom.quickReaction === DEFAULT_REACTION ? null : custom.quickReaction })}
                    aria-label={`Send ${custom.quickReaction}`}
                  >
                    {custom.quickReaction === DEFAULT_REACTION ? <Heart size={20} /> : <span aria-hidden>{custom.quickReaction}</span>}
                  </button>
                </>
              )}
              <input ref={file} type="file" accept="image/*,video/*,.heic,.heif" multiple hidden onChange={(e) => e.target.files && attach(e.target.files)} />
            </form>
          )}
        </div>
      </section>
      <PollSheet open={polling} onClose={() => setPolling(false)} onSend={(poll) => void send({ type: MessageType.POLL, text: poll.question, poll }, { audience: Audience.THREAD })} />
      {info && <ThreadInfo thread={thread} onClose={() => setInfo(false)} vanish={vanish} setVanish={setVanish} onJump={jump} />}
    </div>
  );
}

function activeLabel(at: number) {
  const since = ago(at);
  return since === 'now' ? 'Active now' : `Active ${since} ago`;
}
