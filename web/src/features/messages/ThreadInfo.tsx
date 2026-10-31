import { Bell, BellOff, Check, ChevronRight, Ghost, LogOut, Palette, Pin, Search, ShieldCheck, Smile, Tag, User, UserPlus } from 'lucide-react';
import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { chatCustom, MAX_NICKNAME, themeGradient, themeOf, THEMES } from '../../core/chat';
import { addMembers, edit, leaveThread, saveSettings, sendChatEvent } from '../../core/compose';
import { messageOf } from '../../core/errors';
import { Audience, MessageType, type ChatEvent, type MessagePayload, type ThreadPayload } from '../../core/payloads';
import { displayName, nameIn, threadTitle, useIndex } from '../../core/selectors';
import { toast, useApp, type Entity } from '../../core/store';
import type { ThreadDto } from '../../core/types';
import { Avatar } from '../../ui/Avatar';
import { longDate, Switch } from '../../ui/bits';
import { MediaImage } from '../../ui/Media';
import { Confirm, Sheet } from '../../ui/Sheet';

const REACTIONS = ['❤️', '😂', '😮', '😢', '😡', '👍', '🔥', '🎉', '🙏', '💯', '😍', '👏', '🥹', '✨', '💀', '🫶'];

type Panel = null | 'theme' | 'nicknames' | 'reaction' | 'search' | 'media';

interface Props {
  thread: ThreadDto;
  onClose: () => void;
  vanish: boolean;
  setVanish: (v: boolean) => void;
  /** Closes the details and scrolls the chat to a message. */
  onJump: (messageId: string) => void;
}

export function ThreadInfo({ thread, onClose, vanish, setVanish, onJump }: Props) {
  const me = useApp((s) => s.me!);
  const users = useApp((s) => s.users);
  const index = useIndex();
  const navigate = useNavigate();
  const meta = index.threadMeta[thread.id] as Entity<ThreadPayload> | undefined;
  const settings = index.settings?.payload ?? {};
  const messages = index.messages[thread.id] as Entity<MessagePayload>[] | undefined;
  const custom = chatCustom(messages);
  const theme = themeOf(custom.theme);
  const [name, setName] = useState(meta?.payload.name ?? '');
  const [adding, setAdding] = useState(false);
  const [leaving, setLeaving] = useState(false);
  const [panel, setPanel] = useState<Panel>(null);
  const isCreator = thread.createdBy === me.id;
  const others = thread.members.filter((m) => m.userId !== me.id).map((m) => m.userId);
  const muted = (settings.mutedThreads ?? []).includes(thread.id);
  const pinned = (settings.pinnedThreads ?? []).includes(thread.id);
  const run = (p: Promise<unknown>) => p.catch((e) => toast(messageOf(e), 'error'));
  const toggleList = (key: 'mutedThreads' | 'pinnedThreads', on: boolean) =>
    run(saveSettings({ [key]: on ? [...(settings[key] ?? []), thread.id] : (settings[key] ?? []).filter((id) => id !== thread.id) }));
  const change = (event: ChatEvent) => run(sendChatEvent(thread.id, event));
  const jump = (id: string) => (setPanel(null), onJump(id));

  // Photos and videos shared in the chat, newest first (view-once ones stay private).
  const media = useMemo(
    () =>
      (messages ?? [])
        .filter((m) => m.payload.type === MessageType.MEDIA && m.dto.audience !== Audience.ONCE)
        .flatMap((m) => (m.payload.media ?? []).map((ref) => ({ message: m, ref })))
        .reverse(),
    [messages],
  );

  return (
    <>
      <Sheet open onClose={onClose} title="Details" bodyClass="flush">
        <div className="info-hero">
          {thread.isGroup ? (
            <span className="group-avatar large">
              {others.slice(0, 2).map((id) => (
                <Avatar key={id} userId={id} size={64} />
              ))}
            </span>
          ) : (
            <Avatar userId={others[0]} size={88} presence />
          )}
          <h3>{threadTitle(thread, index, users, me.id)}</h3>
          <span className="faint">{thread.isGroup ? `${thread.members.length} members` : `@${users[others[0]]?.username ?? ''}`}</span>
          <div className="info-actions">
            {!thread.isGroup && others[0] && (
              <button onClick={() => (onClose(), navigate(`/u/${users[others[0]]?.username}`))}>
                <User size={20} />
                Profile
              </button>
            )}
            <button onClick={() => setPanel('search')}>
              <Search size={20} />
              Search
            </button>
            <button onClick={() => void toggleList('mutedThreads', !muted)} aria-pressed={muted}>
              {muted ? <BellOff size={20} /> : <Bell size={20} />}
              {muted ? 'Unmute' : 'Mute'}
            </button>
          </div>
        </div>

        {thread.isGroup && isCreator && meta && (
          <div className="info-section">
            <label className="field">
              <span>Group name</span>
              <div className="row">
                <input className="input" value={name} onChange={(e) => setName(e.target.value)} maxLength={60} />
                <button className="btn btn-secondary btn-sm" onClick={() => run(edit(meta, { ...meta.payload, name: name.trim() || null }))}>
                  Save
                </button>
              </div>
            </label>
          </div>
        )}

        <div className="info-list">
          <button className="info-row" onClick={() => setPanel('theme')}>
            <Palette size={20} />
            <span className="info-row-label">Theme</span>
            <span className="info-row-value">
              <span className="theme-dot" style={{ background: themeGradient(theme) }} />
              {theme.name}
            </span>
            <ChevronRight size={18} className="faint" />
          </button>
          <button className="info-row" onClick={() => setPanel('nicknames')}>
            <Tag size={20} />
            <span className="info-row-label">Nicknames</span>
            <ChevronRight size={18} className="faint" />
          </button>
          <button className="info-row" onClick={() => setPanel('reaction')}>
            <Smile size={20} />
            <span className="info-row-label">Quick reaction</span>
            <span className="info-row-value emoji">{custom.quickReaction}</span>
            <ChevronRight size={18} className="faint" />
          </button>
        </div>

        <div className="info-list">
          <div className="info-row">
            {muted ? <BellOff size={20} /> : <Bell size={20} />}
            <span className="info-row-label">Mute messages</span>
            <Switch checked={muted} onChange={(v) => void toggleList('mutedThreads', v)} label="Mute messages" />
          </div>
          <div className="info-row">
            <Pin size={20} />
            <span className="info-row-label">Pin chat</span>
            <Switch checked={pinned} onChange={(v) => void toggleList('pinnedThreads', v)} label="Pin chat" />
          </div>
          <div className="info-row">
            <Ghost size={20} />
            <span className="info-row-label">
              Vanish mode
              <small>Seen messages are deleted from the server (they can stay in backups for up to 30 days).</small>
            </span>
            <Switch checked={vanish} onChange={setVanish} label="Vanish mode" />
          </div>
        </div>

        {media.length > 0 && (
          <div className="info-section">
            <div className="row" style={{ justifyContent: 'space-between' }}>
              <b>Photos and videos</b>
              {media.length > 6 && (
                <button className="btn btn-link" onClick={() => setPanel('media')}>
                  See all
                </button>
              )}
            </div>
            <MediaGrid items={media.slice(0, 6)} onPick={jump} />
          </div>
        )}

        <div className="info-section">
          <div className="row" style={{ justifyContent: 'space-between' }}>
            <b>Members</b>
            {thread.isGroup && thread.members.length < 10 && (
              <button className="btn btn-link" onClick={() => setAdding(true)}>
                <UserPlus size={16} /> Add people
              </button>
            )}
          </div>
          {thread.members.map((m) => {
            const user = users[m.userId];
            const isMe = m.userId === me.id;
            const nickname = custom.nicknames[m.userId];
            return (
              <div key={m.userId} className="user-row">
                <Avatar userId={m.userId} size={44} presence />
                <div className="user-row-text">
                  <b>{nickname ?? (isMe ? me.username : user?.username)}</b>
                  <span className="faint">
                    {nickname ? `@${isMe ? me.username : user?.username}` : m.role === 'admin' ? 'Admin' : isMe ? 'You' : displayName(user, index.profiles[m.userId]?.payload)}
                  </span>
                </div>
                {!isMe && (
                  <button className="icon-btn sm" onClick={() => (onClose(), navigate(`/verify/${m.userId}`))} aria-label="Verify security code">
                    <ShieldCheck size={18} />
                  </button>
                )}
                {!isMe && isCreator && thread.isGroup && (
                  <button className="btn btn-ghost btn-sm" onClick={() => run(leaveThread(thread.id, m.userId))}>
                    Remove
                  </button>
                )}
              </div>
            );
          })}
        </div>
        <div className="menu">
          <button className="danger" onClick={() => setLeaving(true)}>
            <LogOut size={18} /> {thread.isGroup ? 'Leave chat' : 'Delete chat for me'}
          </button>
        </div>
      </Sheet>

      {panel === 'theme' && (
        <Sheet open onClose={() => setPanel(null)} title="Theme">
          <div className="theme-grid">
            {THEMES.map((t) => (
              <button
                key={t.id}
                className={`theme-option ${t.id === theme.id ? 'on' : ''}`}
                aria-pressed={t.id === theme.id}
                onClick={() => (setPanel(null), t.id !== theme.id && void change({ theme: t.id }))}
              >
                <span className="theme-swatch" style={{ background: themeGradient(t) }}>
                  {t.id === theme.id && <Check size={20} color="#fff" />}
                </span>
                {t.name}
              </button>
            ))}
          </div>
          <p className="faint" style={{ fontSize: 13, margin: '14px 0 0' }}>
            Everyone in the chat sees the new theme.
          </p>
        </Sheet>
      )}

      {panel === 'nicknames' && <Nicknames thread={thread} nicknames={custom.nicknames} onSet={(userId, nickname) => void change({ nicknameFor: userId, nickname })} onClose={() => setPanel(null)} />}

      {panel === 'reaction' && (
        <Sheet open onClose={() => setPanel(null)} title="Quick reaction">
          <p className="faint" style={{ fontSize: 13, marginTop: 0 }}>
            The emoji the button next to the message box sends. Everyone in the chat gets the same one.
          </p>
          <div className="emoji-grid">
            {REACTIONS.map((e) => (
              <button
                key={e}
                className={e === custom.quickReaction ? 'on' : ''}
                aria-pressed={e === custom.quickReaction}
                onClick={() => (setPanel(null), e !== custom.quickReaction && void change({ quickReaction: e }))}
              >
                {e}
              </button>
            ))}
          </div>
        </Sheet>
      )}

      {panel === 'search' && <SearchChat thread={thread} messages={messages ?? []} onPick={jump} onClose={() => setPanel(null)} />}

      {panel === 'media' && (
        <Sheet open onClose={() => setPanel(null)} title="Photos and videos" size="wide">
          <MediaGrid items={media} onPick={jump} />
        </Sheet>
      )}

      {adding && <AddPeople threadId={thread.id} existing={thread.members.map((m) => m.userId)} onClose={() => setAdding(false)} />}
      <Confirm
        open={leaving}
        title="Leave this chat?"
        message="You won’t get new messages. The others’ next message will use a new key you don’t have."
        confirm="Leave"
        danger
        onConfirm={() => void leaveThread(thread.id).then(() => (onClose(), navigate('/direct'))).catch((e) => toast(messageOf(e), 'error'))}
        onClose={() => setLeaving(false)}
      />
    </>
  );
}

function MediaGrid({ items, onPick }: { items: { message: Entity<MessagePayload>; ref: NonNullable<MessagePayload['media']>[number] }[]; onPick: (messageId: string) => void }) {
  return (
    <div className="info-media">
      {items.map(({ message, ref }) => (
        <button key={ref.blobId} onClick={() => onPick(message.dto.id)} aria-label="Show in chat">
          <MediaImage media={ref.mime.startsWith('video/') && ref.poster ? ref.poster : ref} />
        </button>
      ))}
    </div>
  );
}

function Nicknames({ thread, nicknames, onSet, onClose }: { thread: ThreadDto; nicknames: Record<string, string>; onSet: (userId: string, nickname: string | null) => void; onClose: () => void }) {
  const me = useApp((s) => s.me!);
  const users = useApp((s) => s.users);
  const [editing, setEditing] = useState<string | null>(null);
  const [value, setValue] = useState('');
  const username = (id: string) => (id === me.id ? me.username : (users[id]?.username ?? 'someone'));
  return (
    <Sheet open onClose={onClose} title="Nicknames">
      <p className="faint" style={{ fontSize: 13, marginTop: 0 }}>
        Nicknames are only used in this chat, and everyone in it sees them.
      </p>
      {thread.members.map((m) => (
        <div key={m.userId} className="nickname-row">
          <Avatar userId={m.userId} size={40} />
          {editing === m.userId ? (
            <form
              className="nickname-edit"
              onSubmit={(e) => {
                e.preventDefault();
                onSet(m.userId, value.trim() || null);
                setEditing(null);
              }}
            >
              <input className="input" autoFocus value={value} maxLength={MAX_NICKNAME} placeholder={username(m.userId)} onChange={(e) => setValue(e.target.value)} aria-label={`Nickname for ${username(m.userId)}`} />
              <button className="btn btn-primary btn-sm">Save</button>
            </form>
          ) : (
            <button className="nickname-pick" onClick={() => (setEditing(m.userId), setValue(nicknames[m.userId] ?? ''))}>
              <span className="user-row-text">
                <b>{nicknames[m.userId] ?? username(m.userId)}</b>
                <span className="faint">{nicknames[m.userId] ? `@${username(m.userId)}` : 'Set nickname'}</span>
              </span>
              {nicknames[m.userId] && (
                <span
                  role="button"
                  tabIndex={0}
                  className="btn btn-ghost btn-sm"
                  onClick={(e) => (e.stopPropagation(), onSet(m.userId, null))}
                  onKeyDown={(e) => e.key === 'Enter' && (e.stopPropagation(), onSet(m.userId, null))}
                >
                  Remove
                </span>
              )}
            </button>
          )}
        </div>
      ))}
    </Sheet>
  );
}

function SearchChat({ thread, messages, onPick, onClose }: { thread: ThreadDto; messages: Entity<MessagePayload>[]; onPick: (id: string) => void; onClose: () => void }) {
  const me = useApp((s) => s.me!);
  const users = useApp((s) => s.users);
  const index = useIndex();
  const [query, setQuery] = useState('');
  const q = query.trim().toLowerCase();
  // Everything is decrypted on this device, so the search runs here; the server never sees the words.
  const results = q.length < 2 ? [] : messages.filter((m) => m.payload.type !== MessageType.SYSTEM && m.payload.text?.toLowerCase().includes(q)).reverse().slice(0, 100);
  return (
    <Sheet open onClose={onClose} title="Search in chat" size="wide">
      <div className="search">
        <Search size={16} />
        <input className="input" autoFocus placeholder="Search" value={query} onChange={(e) => setQuery(e.target.value)} />
      </div>
      {q.length >= 2 && results.length === 0 && <p className="faint" style={{ textAlign: 'center' }}>No messages with “{query.trim()}”.</p>}
      <div className="search-results">
        {results.map((m) => {
          const text = m.payload.text ?? '';
          const at = text.toLowerCase().indexOf(q);
          const start = Math.max(0, at - 30);
          return (
            <button key={m.dto.id} className="user-row result" onClick={() => onPick(m.dto.id)}>
              <Avatar userId={m.dto.authorId} size={36} />
              <span className="user-row-text" style={{ textAlign: 'left' }}>
                <b>{m.dto.authorId === me.id ? 'You' : nameIn(thread.id, m.dto.authorId, index, users)}</b>
                <span className="faint search-snippet">
                  {start > 0 && '…'}
                  {text.slice(start, at)}
                  <mark>{text.slice(at, at + q.length)}</mark>
                  {text.slice(at + q.length, at + q.length + 60)}
                </span>
              </span>
              <span className="faint" style={{ fontSize: 12, whiteSpace: 'nowrap' }}>
                {longDate(m.dto.receivedAt)}
              </span>
            </button>
          );
        })}
      </div>
    </Sheet>
  );
}

function AddPeople({ threadId, existing, onClose }: { threadId: string; existing: string[]; onClose: () => void }) {
  const users = useApp((s) => s.users);
  const [picked, setPicked] = useState<string[]>([]);
  const candidates = Object.values(users).filter((u) => !existing.includes(u.id) && !u.deleted && !u.blockedByMe);
  return (
    <Sheet
      open
      onClose={onClose}
      title="Add people"
      right={
        <button className="btn btn-link" disabled={!picked.length} onClick={() => void addMembers(threadId, picked).then(onClose).catch((e) => toast(messageOf(e), 'error'))}>
          Add
        </button>
      }
    >
      <p className="faint" style={{ marginTop: 0, fontSize: 13 }}>
        New people can read messages from now on, not earlier ones.
      </p>
      {candidates.map((u) => (
        <button key={u.id} className="user-row result" style={{ width: '100%' }} onClick={() => setPicked((p) => (p.includes(u.id) ? p.filter((x) => x !== u.id) : [...p, u.id]))}>
          <Avatar userId={u.id} size={40} />
          <span className="user-row-text" style={{ textAlign: 'left' }}>
            <b>{u.username}</b>
          </span>
          <span className={`radio ${picked.includes(u.id) ? 'on' : ''}`} />
        </button>
      ))}
    </Sheet>
  );
}
