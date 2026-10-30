import { ArrowLeft, Camera, CornerUpLeft, Eye, Ghost, Heart, ImagePlus, Info, Mic, MoreHorizontal, Pause, Phone, PhoneMissed, Play, Smile, Square, Video, X } from 'lucide-react';
import { AnimatePresence, motion } from 'motion/react';
import { Fragment, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom';
import { deleteObject, edit, markRead, react as reactTo, recordView, sendMessage } from '../../core/compose';
import { messageOf } from '../../core/errors';
import { prepare, prepareVoice, upload } from '../../core/media';
import { Audience, Kind, MessageType, type MessagePayload, type ReactionPayload, type StoryPayload } from '../../core/payloads';
import { threadTitle, useIndex } from '../../core/selectors';
import { toast, useApp, type Entity } from '../../core/store';
import { realtime } from '../../core/ws';
import { Avatar } from '../../ui/Avatar';
import { ago, Center, longDate, RichText, Spinner } from '../../ui/bits';
import { MediaImage, MediaVideo, useMediaUrl } from '../../ui/Media';
import { Sheet } from '../../ui/Sheet';
import { calls } from '../calls/calls';
import { coverOf } from '../profile/PostGrid';
import { ThreadList } from './Inbox';
import { ThreadInfo } from './ThreadInfo';

const QUICK = ['❤️', '😂', '😮', '😢', '😡', '👍'];

function useVanish(threadId: string) {
  const key = `aezgram.vanish.${threadId}`;
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
          if (el.paused) void el.play();
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
        <audio ref={audio} src={url} onPlay={() => setPlaying(true)} onPause={() => setPlaying(false)} onEnded={() => (setPlaying(false), setProgress(0))} onTimeUpdate={(e) => setProgress(e.currentTarget.currentTime / (e.currentTarget.duration || 1))} />
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

function Bubble({ message, mine, onReply, onEdit, showAvatar }: { message: Entity<MessagePayload>; mine: boolean; onReply: () => void; onEdit: () => void; showAvatar: boolean }) {
  const index = useIndex();
  const entities = useApp((s) => s.entities);
  const users = useApp((s) => s.users);
  const me = useApp((s) => s.me!);
  const [menu, setMenu] = useState(false);
  const [opened, setOpened] = useState(false);
  const p = message.payload;
  const reactions = (index.children[message.dto.id] ?? []).filter((e) => e.dto.kind === Kind.REACTION) as Entity<ReactionPayload>[];
  const replyTarget = p.replyTo ? (entities[p.replyTo] as Entity<MessagePayload> | undefined) : undefined;
  const once = message.dto.audience === Audience.ONCE;
  const vanish = message.dto.audience === Audience.VANISH;
  const big = p.type === MessageType.HEART || (p.type === MessageType.TEXT && /^\p{Extended_Pictographic}{1,3}$/u.test(p.text ?? ''));
  const story = p.storyId ? (entities[p.storyId] as Entity<StoryPayload> | undefined) : undefined;

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
  } else {
    content = big ? <span className="big-emoji">{p.type === MessageType.HEART ? '❤️' : p.text}</span> : <RichText text={p.text ?? ''} />;
  }

  const plainBubble = big || p.type === MessageType.SHARE || (p.type === MessageType.MEDIA && !(once && !mine && !opened));
  return (
    <div className={`msg ${mine ? 'mine' : 'theirs'}`}>
      {!mine && <span className="msg-avatar">{showAvatar && <Avatar userId={message.dto.authorId} size={28} />}</span>}
      <div className="msg-col">
        {(p.type === MessageType.STORY_REPLY || p.type === MessageType.STORY_REACTION) && (
          <div className="story-ref">
            <span className="faint">{mine ? 'You replied to their story' : `Replied to your story`}</span>
            {story?.payload.media && <MediaImage media={story.payload.media.mime.startsWith('video/') && story.payload.media.poster ? story.payload.media.poster : story.payload.media} className="story-ref-thumb" />}
          </div>
        )}
        {replyTarget && (
          <div className="reply-ref">
            <CornerUpLeft size={12} /> {replyTarget.dto.authorId === me.id ? 'You' : users[replyTarget.dto.authorId]?.username}: {replyTarget.payload.text ?? 'Attachment'}
          </div>
        )}
        <div className="msg-row">
          <div className={`bubble ${plainBubble ? 'plain' : ''} ${vanish ? 'vanish' : ''}`} onContextMenu={(e) => (e.preventDefault(), setMenu(true))} onDoubleClick={() => void reactTo(message, '❤️')}>
            {content}
            {p.edited && <span className="edited">Edited</span>}
          </div>
          <span className="msg-actions">
            <button className="icon-btn sm" onClick={() => void reactTo(message, '❤️')} aria-label="React">
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
        {reactions.length > 0 && (
          <div className="reactions" onClick={() => setMenu(true)}>
            {[...new Set(reactions.map((r) => r.payload.emoji))].slice(0, 3).join('')}
            {reactions.length > 1 && <span>{reactions.length}</span>}
          </div>
        )}
      </div>
      <Sheet open={menu} onClose={() => setMenu(false)} hideHeader bottom bodyClass="flush">
        <div className="quick-react">
          {QUICK.map((e) => (
            <motion.button key={e} whileHover={{ scale: 1.25 }} whileTap={{ scale: 0.9 }} onClick={() => (setMenu(false), void reactTo(message, e))}>
              {e}
            </motion.button>
          ))}
        </div>
        <div className="menu">
          <button onClick={() => (setMenu(false), onReply())}>Reply</button>
          {p.text && <button onClick={() => (setMenu(false), void navigator.clipboard.writeText(p.text ?? ''))}>Copy</button>}
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
                  <span className="user-row-text">{r.dto.authorId === me.id ? 'You' : users[r.dto.authorId]?.username}</span>
                  <span style={{ fontSize: 20 }}>{r.payload.emoji}</span>
                </div>
              ))}
            </div>
          )}
        </div>
      </Sheet>
    </div>
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
  const [text, setText] = useState('');
  const [replyTo, setReplyTo] = useState<Entity<MessagePayload> | null>(null);
  const [editing, setEditing] = useState<Entity<MessagePayload> | null>(null);
  const [recording, setRecording] = useState(false);
  const [sending, setSending] = useState(false);
  const [info, setInfo] = useState(false);
  const [once, setOnce] = useState(false);
  const [vanish, setVanish] = useVanish(threadId);
  const scroller = useRef<HTMLDivElement>(null);
  const file = useRef<HTMLInputElement>(null);
  const lastTyping = useRef(0);

  useLayoutEffect(() => {
    const el = scroller.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [messages.length, threadId]);

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
    setSending(true);
    try {
      await sendMessage(threadId, payload, { audience: opts.audience ?? audience, blobIds: opts.blobIds });
    } catch (e) {
      toast(messageOf(e), 'error');
    } finally {
      setSending(false);
    }
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

  const attach = async (files: FileList) => {
    setSending(true);
    try {
      const refs = [];
      for (const f of [...files].slice(0, 10)) refs.push(await upload(await prepare(f, 'message')));
      await send({ type: MessageType.MEDIA, media: refs }, { audience: once ? Audience.ONCE : audience });
      setOnce(false);
    } catch (e) {
      toast(messageOf(e), 'error');
      setSending(false);
    }
  };

  const voice = async (blob: Blob, ms: number) => {
    setRecording(false);
    setSending(true);
    try {
      const ref = await upload(await prepareVoice(blob, ms));
      await send({ type: MessageType.VOICE, media: [ref] });
    } catch (e) {
      toast(messageOf(e), 'error');
      setSending(false);
    }
  };

  // Seen receipts: under my newest message, who has read up to it.
  const lastMine = [...messages].reverse().find((m) => m.dto.authorId === me.id);
  const seenBy = lastMine ? thread.members.filter((m) => m.userId !== me.id && m.lastReadAt >= lastMine.dto.receivedAt).map((m) => m.userId) : [];

  return (
    <div className={`direct ${vanish ? 'vanish-mode' : ''}`}>
      <aside className="direct-list hide-mobile">
        <header className="direct-head">
          <b>{me.username}</b>
        </header>
        <ThreadList activeId={threadId} />
      </aside>
      <section className="chat">
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

        <div className="chat-scroll" ref={scroller}>
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
            const showAvatar = !nextMsg || nextMsg.dto.authorId !== m.dto.authorId;
            return (
              <Fragment key={m.dto.id}>
                {(newDay || gap) && <div className="day">{newDay ? longDate(m.dto.receivedAt) : new Date(m.dto.receivedAt).toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' })}</div>}
                <Bubble
                  message={m}
                  mine={m.dto.authorId === me.id}
                  showAvatar={showAvatar}
                  onReply={() => {
                    setReplyTo(m);
                    document.getElementById('chat-input')?.focus();
                  }}
                  onEdit={() => {
                    setEditing(m);
                    setText(m.payload.text ?? '');
                  }}
                />
                {m === lastMine && seenBy.length > 0 && (
                  <div className="seen">{thread.isGroup ? `Seen by ${seenBy.map((id) => users[id]?.username).join(', ')}` : 'Seen'}</div>
                )}
              </Fragment>
            );
          })}
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
                <button className="btn btn-link" disabled={sending}>
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
                  <button type="button" className="icon-btn sm" onClick={() => void send({ type: MessageType.HEART })} aria-label="Send a heart">
                    <Heart size={20} />
                  </button>
                </>
              )}
              {sending && <Spinner size={16} />}
              <input ref={file} type="file" accept="image/*,video/*" multiple hidden onChange={(e) => e.target.files && attach(e.target.files)} />
            </form>
          )}
        </div>
      </section>
      {info && <ThreadInfo thread={thread} onClose={() => setInfo(false)} vanish={vanish} setVanish={setVanish} />}
    </div>
  );
}

function activeLabel(at: number) {
  const since = ago(at);
  return since === 'now' ? 'Active now' : `Active ${since} ago`;
}
