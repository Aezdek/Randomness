import { Bookmark, ChevronLeft, Edit, Heart, MessageCircle, Plus, Search, Send, Trash2 } from 'lucide-react';
import { motion } from 'motion/react';
import { useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Kind, MessageType, type MessagePayload, type NotePayload } from '../../core/payloads';
import { deleteObject, like, openDirect, sendMessage, unlike } from '../../core/compose';
import { messageOf } from '../../core/errors';
import { nameIn, threadTitle, unreadCount, useIndex } from '../../core/selectors';
import { eventText } from '../../core/chat';
import { toast, useApp, type Entity } from '../../core/store';
import { openCreate } from '../../core/ui';
import { Avatar } from '../../ui/Avatar';
import { Ago, Empty } from '../../ui/bits';
import { Sheet } from '../../ui/Sheet';
import { NewChat } from './NewChat';
import { previewText, safeText } from '../../core/safe';
import { Guard } from '../../ui/Guard';
import { notesToSelfId, openNotesToSelf, searchMessages } from '../../core/circle';

export function previewOf(payload: MessagePayload, mine: boolean): string {
  const you = mine ? 'You: ' : '';
  // Whatever a modified client sent, the row shows text.
  payload = { ...payload, text: previewText(safeText(payload.text)) || null };
  switch (payload.type) {
    case MessageType.TEXT:
      return you + (payload.text ?? '');
    case MessageType.MEDIA:
      return mine ? 'You sent an attachment' : 'Sent an attachment';
    case MessageType.VOICE:
      return mine ? 'You sent a voice message' : 'Sent a voice message';
    case MessageType.SHARE:
      return mine ? 'You shared a post' : 'Shared a post';
    case MessageType.STORY_REPLY:
      return mine ? `You replied to their story` : `Replied to your story: ${payload.text ?? ''}`;
    case MessageType.STORY_REACTION:
      return mine ? `You reacted ${payload.text} to their story` : `Reacted ${payload.text} to your story`;
    case MessageType.CALL:
      return payload.call?.missed ? 'Missed call' : `${payload.call?.video ? 'Video' : 'Audio'} call`;
    case MessageType.NOTE_REPLY:
      return mine ? `You replied to their note: ${payload.text ?? ''}` : `Replied to your note: ${payload.text ?? ''}`;
    case MessageType.HEART:
      return you + (payload.text || '❤️');
    case MessageType.POLL:
      return `${mine ? 'You created a poll' : 'Poll'}: ${payload.poll?.question ?? ''}`;
    case MessageType.PLAN:
      return `${mine ? 'You made a plan' : 'Plan'}: ${payload.plan?.title ?? ''}`;
    case MessageType.LOCATION:
      return mine ? 'You’re sharing your live location' : 'Shared their live location';
    case MessageType.FILE:
      return mine ? 'You sent a file' : 'Sent a file';
    case MessageType.NUDGE:
      return mine ? 'You sent a nudge' : 'Nudged you 👋';
    case MessageType.LIST:
      return `${mine ? 'You made a list' : 'List'}: ${payload.text ?? ''}`;
    case MessageType.LIST_CHANGE:
      return mine ? 'You changed a list' : 'Changed a list';
    case MessageType.DAILY:
      return mine ? 'You answered the question of the day' : 'Answered the question of the day';
    case MessageType.ARRIVED:
      return `${mine ? 'You arrived' : 'Arrived'} at ${payload.text || 'their destination'}`;
    default:
      return payload.text ?? '';
  }
}

function Notes() {
  const index = useIndex();
  const me = useApp((s) => s.me!);
  const users = useApp((s) => s.users);
  const [open, setOpen] = useState<string | null>(null);
  const authors = [me.id, ...Object.keys(index.notes).filter((id) => id !== me.id && users[id]?.followState === 'following')];
  return (
    <div className="notes-row">
      {authors.map((id) => {
        const note = index.notes[id];
        if (id !== me.id && !note) return null;
        return (
          <button key={id} className="note" onClick={() => (note ? setOpen(id) : openCreate('note'))} aria-label={note ? `Note from ${id === me.id ? 'you' : users[id]?.username}` : 'Leave a note'}>
            <motion.span className={`note-bubble ${note ? '' : 'empty'}`} initial={{ opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }}>
              <span className="note-text">{note ? note.payload.text : <><Plus size={12} /> Note</>}</span>
            </motion.span>
            <Avatar userId={id} size={64} />
            <span className="faint truncate note-name">{id === me.id ? 'Your note' : users[id]?.username}</span>
          </button>
        );
      })}
      {open && index.notes[open] && <NoteSheet note={index.notes[open] as Entity<NotePayload>} onClose={() => setOpen(null)} />}
    </div>
  );
}

/** A note opened: someone else's can be liked and replied to (the reply goes to them as a message); your own shows who liked it. */
function NoteSheet({ note, onClose }: { note: Entity<NotePayload>; onClose: () => void }) {
  const index = useIndex();
  const me = useApp((s) => s.me!);
  const users = useApp((s) => s.users);
  const navigate = useNavigate();
  const [reply, setReply] = useState('');
  const mine = note.dto.authorId === me.id;
  const likes = (index.children[note.dto.id] ?? []).filter((e) => e.dto.kind === Kind.LIKE);
  const liked = likes.some((e) => e.dto.authorId === me.id);
  const author = users[note.dto.authorId];
  const send = async () => {
    const text = reply.trim();
    if (!text) return;
    try {
      const threadId = await openDirect(note.dto.authorId);
      await sendMessage(threadId, { type: MessageType.NOTE_REPLY, text, quote: note.payload.text, sharedObjectId: note.dto.id });
      toast('Reply sent', 'success');
      onClose();
    } catch (e) {
      toast(messageOf(e), 'error');
    }
  };
  return (
    <Sheet open onClose={onClose} hideHeader bottom>
      <div className="note-open">
        <span className="note-bubble big">
          <span className="note-text">{note.payload.text}</span>
        </span>
        <Avatar userId={note.dto.authorId} size={84} />
        <b>{mine ? 'Your note' : author?.username}</b>
        <span className="faint">
          <Ago at={note.dto.receivedAt} className="" suffix /> · disappears after 24 hours
        </span>
        {mine ? (
          <>
            <p className="faint" style={{ margin: '6px 0 0' }}>
              {likes.length ? `Liked by ${likes.map((l) => (l.dto.authorId === me.id ? 'you' : users[l.dto.authorId]?.username)).join(', ')}` : 'No likes yet'}
            </p>
            <div className="row" style={{ gap: 8, marginTop: 10 }}>
              <button className="btn btn-secondary" onClick={() => (onClose(), openCreate('note'))}>
                <Plus size={16} /> New note
              </button>
              <button className="btn btn-danger" onClick={() => void deleteObject(note.dto.id).then(onClose).catch((e) => toast(messageOf(e), 'error'))}>
                <Trash2 size={16} /> Delete
              </button>
            </div>
          </>
        ) : (
          <form
            className="note-reply"
            onSubmit={(e) => {
              e.preventDefault();
              void send();
            }}
          >
            <button
              type="button"
              className={`icon-btn ${liked ? 'liked' : ''}`}
              aria-label={liked ? 'Unlike note' : 'Like note'}
              aria-pressed={liked}
              onClick={() => void (liked ? unlike(note.dto.id) : like(note.dto.id)).catch((e) => toast(messageOf(e), 'error'))}
            >
              <Heart size={22} fill={liked ? 'var(--heart)' : 'none'} color={liked ? 'var(--heart)' : 'currentColor'} />
            </button>
            <input className="input" autoFocus placeholder={`Reply to ${author?.username ?? ''}…`} value={reply} onChange={(e) => setReply(e.target.value)} maxLength={500} />
            <button className="icon-btn" disabled={!reply.trim()} aria-label="Send reply">
              <Send size={20} />
            </button>
          </form>
        )}
        {!mine && (
          <button className="btn btn-link" onClick={() => (onClose(), navigate(`/u/${author?.username}`))}>
            View profile
          </button>
        )}
      </div>
    </Sheet>
  );
}

export function ThreadList({ activeId }: { activeId?: string }) {
  const threads = useApp((s) => s.threads);
  const users = useApp((s) => s.users);
  const me = useApp((s) => s.me!);
  const typing = useApp((s) => s.typing);
  const index = useIndex();
  const [query, setQuery] = useState('');
  const [filter, setFilter] = useState<'all' | 'unread' | 'groups'>('all');
  const navigate = useNavigate();
  const hasNotes = !!notesToSelfId({ threads, me });
  // Searching also looks inside every chat, here on the device (the server can't: it only holds ciphertext).
  const found = useMemo(() => (query.trim().length >= 2 ? searchMessages(index, query, 30) : []), [index, query]);
  const pinned = new Set(index.settings?.payload.pinnedThreads ?? []);
  const sorted = useMemo(
    () =>
      Object.values(threads)
        .map((t) => ({ thread: t, last: index.messages[t.id]?.at(-1), title: threadTitle(t, index, users, me.id) }))
        .filter((x) => !query || x.title.toLowerCase().includes(query.toLowerCase()))
        .filter((x) => (filter === 'unread' ? unreadCount(x.thread, index, me.id) > 0 : filter === 'groups' ? x.thread.isGroup : true))
        .sort((a, b) => Number(pinned.has(b.thread.id)) - Number(pinned.has(a.thread.id)) || (b.last?.dto.receivedAt ?? b.thread.createdAt) - (a.last?.dto.receivedAt ?? a.thread.createdAt)),
    [threads, index, users, me.id, query, filter], // eslint-disable-line react-hooks/exhaustive-deps
  );
  return (
    <>
      <div className="search" style={{ padding: '4px 20px 12px' }}>
        <Search size={17} style={{ left: 34 }} />
        <input className="input" placeholder="Search chats and messages" value={query} onChange={(e) => setQuery(e.target.value)} aria-label="Search chats and messages" />
      </div>
      {!query && <Notes />}
      <div className="thread-list-head">
        <b>Messages</b>
      </div>
      <div className="inbox-filters" role="tablist" aria-label="Show">
        {(
          [
            ['all', 'All'],
            ['unread', 'Unread'],
            ['groups', 'Groups'],
          ] as const
        ).map(([key, label]) => (
          <button key={key} role="tab" aria-selected={filter === key} className={`chip ${filter === key ? 'on' : ''}`} onClick={() => setFilter(key)}>
            {label}
          </button>
        ))}
      </div>
      {sorted.map(({ thread, last, title }) => {
        const unread = unreadCount(thread, index, me.id);
        const other = thread.members.find((m) => m.userId !== me.id)?.userId;
        const isTyping = Object.entries(typing[thread.id] ?? {}).some(([u, until]) => u !== me.id && until > Date.now());
        return (
          <Guard key={thread.id}>
          <Link to={`/direct/${thread.id}`} className={`thread-row ${activeId === thread.id ? 'active' : ''} ${unread ? 'unread' : ''}`}>
            {thread.isGroup ? (
              <span className="group-avatar">
                {thread.members.filter((m) => m.userId !== me.id).slice(0, 2).map((m) => (
                  <Avatar key={m.userId} userId={m.userId} size={38} />
                ))}
              </span>
            ) : (
              <Avatar userId={other ?? me.id} size={56} presence={!!other} />
            )}
            <span className="thread-text">
              <span className="truncate thread-title">{title}</span>
              {/* The text shortens; the time after it always shows, as on Instagram. */}
              <span className="thread-preview">
                <span className="truncate">
                  {isTyping ? (
                    <span className="typing-label">typing…</span>
                  ) : last ? (
                    last.payload.type === MessageType.SYSTEM && last.payload.event ? (
                      eventText(last.payload.event, last.dto.authorId, me.id, users, (id) => nameIn(thread.id, id, index, users))
                    ) : (
                      previewOf(last.payload, last.dto.authorId === me.id)
                    )
                  ) : (
                    'Say hi 👋'
                  )}
                </span>
                {last && (
                  <span className="thread-preview-time">
                    {' · '}
                    <Ago at={last.dto.receivedAt} className="" />
                  </span>
                )}
              </span>
            </span>
            {unread > 0 && <span className="dot" style={{ width: 9, height: 9, background: 'var(--link)' }} />}
          </Link>
          </Guard>
        );
      })}
      {!query && filter === 'all' && !hasNotes && (
        <button className="thread-row" onClick={() => void openNotesToSelf().then((id) => navigate(`/direct/${id}`)).catch((e) => toast(messageOf(e), 'error'))}>
          <span className="notes-self-icon">
            <Bookmark size={24} />
          </span>
          <span className="thread-text">
            <span className="truncate thread-title">Notes to self</span>
            <span className="thread-preview">
              <span className="truncate">Save links, lists and photos for later</span>
            </span>
          </span>
        </button>
      )}
      {found.length > 0 && (
        <>
          <div className="thread-list-head">
            <b>In messages</b>
          </div>
          {found.map((m) => {
            const thread = threads[m.dto.threadId ?? ''];
            if (!thread) return null;
            return (
              <Link key={m.dto.id} to={`/direct/${thread.id}`} state={{ jumpTo: m.dto.id }} className="thread-row search-hit">
                <Avatar userId={m.dto.authorId} size={44} />
                <span className="thread-text">
                  <span className="truncate thread-title">{threadTitle(thread, index, users, me.id)}</span>
                  <span className="thread-preview">
                    <span className="truncate">
                      <Highlight text={`${m.dto.authorId === me.id ? 'You' : nameIn(thread.id, m.dto.authorId, index, users)}: ${previewText(m.payload.plan?.title ?? m.payload.poll?.question ?? m.payload.text ?? m.payload.quote ?? '')}`} query={query} />
                    </span>
                    <span className="thread-preview-time">
                      {' · '}
                      <Ago at={m.dto.receivedAt} className="" />
                    </span>
                  </span>
                </span>
              </Link>
            );
          })}
        </>
      )}
      {!sorted.length && !found.length && (
        <p className="faint" style={{ padding: '24px 20px' }}>
          {filter === 'unread' ? "You're all caught up." : filter === 'groups' ? 'No group chats yet.' : 'No chats yet.'}
        </p>
      )}
    </>
  );
}

/** The matched words in bold. */
function Highlight({ text, query }: { text: string; query: string }) {
  const q = query.trim().toLowerCase();
  const at = text.toLowerCase().indexOf(q);
  if (!q || at < 0) return <>{text}</>;
  return (
    <>
      {text.slice(0, at)}
      <b>{text.slice(at, at + q.length)}</b>
      {text.slice(at + q.length)}
    </>
  );
}

export function Inbox() {
  const me = useApp((s) => s.me!);
  const [newChat, setNewChat] = useState(false);
  const navigate = useNavigate();
  return (
    <div className="direct">
      <aside className="direct-list">
        <header className="direct-head">
          <button className="icon-btn only-mobile back" onClick={() => navigate('/')} aria-label="Back">
            <ChevronLeft size={28} strokeWidth={1.8} />
          </button>
          <b>{me.username}</b>
          <button className="icon-btn" onClick={() => setNewChat(true)} aria-label="New message">
            <Edit size={22} />
          </button>
        </header>
        <ThreadList />
      </aside>
      <section className="direct-empty hide-mobile">
        <Empty icon={<MessageCircle size={40} />} title="Your messages" action={<button className="btn btn-brand" onClick={() => setNewChat(true)}>Send message</button>}>
          Private, end-to-end encrypted messages, photos, voice notes and calls.
        </Empty>
      </section>
      {newChat && <NewChat onClose={() => setNewChat(false)} onOpen={(id) => navigate(`/direct/${id}`)} />}
    </div>
  );
}
