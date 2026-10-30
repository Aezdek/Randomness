import { Edit, MessageCircle, Plus, Search } from 'lucide-react';
import { motion } from 'motion/react';
import { useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { MessageType, type MessagePayload } from '../../core/payloads';
import { threadTitle, unreadCount, useIndex } from '../../core/selectors';
import { useApp } from '../../core/store';
import { openCreate } from '../../core/ui';
import { Avatar } from '../../ui/Avatar';
import { Ago, Empty } from '../../ui/bits';
import { NewChat } from './NewChat';

export function previewOf(payload: MessagePayload, mine: boolean): string {
  const you = mine ? 'You: ' : '';
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
    case MessageType.HEART:
      return you + '❤️';
    default:
      return payload.text ?? '';
  }
}

function Notes() {
  const index = useIndex();
  const me = useApp((s) => s.me!);
  const users = useApp((s) => s.users);
  const authors = [me.id, ...Object.keys(index.notes).filter((id) => id !== me.id && users[id]?.followState === 'following')];
  return (
    <div className="notes-row">
      {authors.map((id) => {
        const note = index.notes[id];
        if (id !== me.id && !note) return null;
        return (
          <button key={id} className="note" onClick={() => id === me.id && openCreate('note')}>
            <motion.span className={`note-bubble ${note ? '' : 'empty'}`} initial={{ opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }}>
              {note ? note.payload.text : <><Plus size={12} /> Note</>}
            </motion.span>
            <Avatar userId={id} size={68} />
            <span className="faint truncate" style={{ fontSize: 12, maxWidth: 76 }}>
              {id === me.id ? 'Your note' : users[id]?.username}
            </span>
          </button>
        );
      })}
    </div>
  );
}

export function ThreadList({ activeId }: { activeId?: string }) {
  const threads = useApp((s) => s.threads);
  const users = useApp((s) => s.users);
  const me = useApp((s) => s.me!);
  const typing = useApp((s) => s.typing);
  const index = useIndex();
  const [query, setQuery] = useState('');
  const pinned = new Set(index.settings?.payload.pinnedThreads ?? []);
  const sorted = useMemo(
    () =>
      Object.values(threads)
        .map((t) => ({ thread: t, last: index.messages[t.id]?.at(-1), title: threadTitle(t, index, users, me.id) }))
        .filter((x) => !query || x.title.toLowerCase().includes(query.toLowerCase()))
        .sort((a, b) => Number(pinned.has(b.thread.id)) - Number(pinned.has(a.thread.id)) || (b.last?.dto.receivedAt ?? b.thread.createdAt) - (a.last?.dto.receivedAt ?? a.thread.createdAt)),
    [threads, index, users, me.id, query], // eslint-disable-line react-hooks/exhaustive-deps
  );
  return (
    <>
      <div className="search" style={{ padding: '4px 20px 12px' }}>
        <Search size={17} style={{ left: 34 }} />
        <input className="input" placeholder="Search" value={query} onChange={(e) => setQuery(e.target.value)} />
      </div>
      <Notes />
      <div className="thread-list-head">
        <b>Messages</b>
      </div>
      {sorted.map(({ thread, last, title }) => {
        const unread = unreadCount(thread, index, me.id);
        const other = thread.members.find((m) => m.userId !== me.id)?.userId;
        const isTyping = Object.entries(typing[thread.id] ?? {}).some(([u, until]) => u !== me.id && until > Date.now());
        return (
          <Link key={thread.id} to={`/direct/${thread.id}`} className={`thread-row ${activeId === thread.id ? 'active' : ''} ${unread ? 'unread' : ''}`}>
            {thread.isGroup ? (
              <span className="group-avatar">
                {thread.members.filter((m) => m.userId !== me.id).slice(0, 2).map((m) => (
                  <Avatar key={m.userId} userId={m.userId} size={38} />
                ))}
              </span>
            ) : (
              <Avatar userId={other} size={56} presence />
            )}
            <span className="thread-text">
              <span className="truncate thread-title">{title}</span>
              <span className="truncate thread-preview">
                {isTyping ? <span className="typing-label">typing…</span> : last ? previewOf(last.payload, last.dto.authorId === me.id) : 'Say hi 👋'}
                {last && (
                  <>
                    {' · '}
                    <Ago at={last.dto.receivedAt} className="" />
                  </>
                )}
              </span>
            </span>
            {unread > 0 && <span className="dot" style={{ width: 9, height: 9, background: 'var(--link)' }} />}
          </Link>
        );
      })}
      {!sorted.length && <p className="faint" style={{ padding: '24px 20px' }}>No chats yet.</p>}
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
