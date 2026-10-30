import { Bell, BellOff, Ghost, LogOut, Pin, ShieldCheck, UserPlus } from 'lucide-react';
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { addMembers, edit, leaveThread, saveSettings } from '../../core/compose';
import { messageOf } from '../../core/errors';
import type { ThreadPayload } from '../../core/payloads';
import { displayName, useIndex } from '../../core/selectors';
import { toast, useApp, type Entity } from '../../core/store';
import type { ThreadDto } from '../../core/types';
import { Avatar } from '../../ui/Avatar';
import { Switch } from '../../ui/bits';
import { Confirm, Sheet } from '../../ui/Sheet';

export function ThreadInfo({ thread, onClose, vanish, setVanish }: { thread: ThreadDto; onClose: () => void; vanish: boolean; setVanish: (v: boolean) => void }) {
  const me = useApp((s) => s.me!);
  const users = useApp((s) => s.users);
  const index = useIndex();
  const navigate = useNavigate();
  const meta = index.threadMeta[thread.id] as Entity<ThreadPayload> | undefined;
  const settings = index.settings?.payload ?? {};
  const [name, setName] = useState(meta?.payload.name ?? '');
  const [adding, setAdding] = useState(false);
  const [leaving, setLeaving] = useState(false);
  const isCreator = thread.createdBy === me.id;
  const muted = (settings.mutedThreads ?? []).includes(thread.id);
  const pinned = (settings.pinnedThreads ?? []).includes(thread.id);
  const run = (p: Promise<unknown>) => p.catch((e) => toast(messageOf(e), 'error'));
  const toggleList = (key: 'mutedThreads' | 'pinnedThreads', on: boolean) =>
    run(saveSettings({ [key]: on ? [...(settings[key] ?? []), thread.id] : (settings[key] ?? []).filter((id) => id !== thread.id) }));

  return (
    <>
      <Sheet open onClose={onClose} title="Details" bodyClass="flush">
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
        <div className="info-section">
          <div className="setting-line">
            <span className="row" style={{ gap: 12 }}>
              {muted ? <BellOff size={20} /> : <Bell size={20} />} <b>Mute messages</b>
            </span>
            <Switch checked={muted} onChange={(v) => void toggleList('mutedThreads', v)} label="Mute messages" />
          </div>
          <div className="setting-line">
            <span className="row" style={{ gap: 12 }}>
              <Pin size={20} /> <b>Pin chat</b>
            </span>
            <Switch checked={pinned} onChange={(v) => void toggleList('pinnedThreads', v)} label="Pin chat" />
          </div>
          <div className="setting-line">
            <span className="row" style={{ gap: 12 }}>
              <Ghost size={20} />
              <span>
                <b>Vanish mode</b>
                <span className="faint" style={{ display: 'block', fontSize: 13 }}>
                  Seen messages are deleted from the server (they can stay in backups for up to 30 days).
                </span>
              </span>
            </span>
            <Switch checked={vanish} onChange={setVanish} label="Vanish mode" />
          </div>
        </div>
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
            return (
              <div key={m.userId} className="user-row">
                <Avatar userId={m.userId} size={44} presence />
                <div className="user-row-text">
                  <b>{isMe ? me.username : user?.username}</b>
                  <span className="faint">{m.role === 'admin' ? 'Admin' : isMe ? 'You' : displayName(user, index.profiles[m.userId]?.payload)}</span>
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
      {adding && <AddPeople threadId={thread.id} existing={thread.members.map((m) => m.userId)} onClose={() => setAdding(false)} />}
      <Confirm
        open={leaving}
        title={thread.isGroup ? 'Leave this chat?' : 'Leave this chat?'}
        message="You won’t get new messages. The others’ next message will use a new key you don’t have."
        confirm="Leave"
        danger
        onConfirm={() => void leaveThread(thread.id).then(() => (onClose(), navigate('/direct'))).catch((e) => toast(messageOf(e), 'error'))}
        onClose={() => setLeaving(false)}
      />
    </>
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
