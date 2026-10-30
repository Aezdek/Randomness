import { Check, Search } from 'lucide-react';
import { useState } from 'react';
import { createThread, openDirect } from '../../core/compose';
import { messageOf } from '../../core/errors';
import { displayName, useIndex } from '../../core/selectors';
import { toast, useApp } from '../../core/store';
import { Avatar } from '../../ui/Avatar';
import { Spinner } from '../../ui/bits';
import { Sheet } from '../../ui/Sheet';

export function NewChat({ onClose, onOpen }: { onClose: () => void; onOpen: (threadId: string) => void }) {
  const users = useApp((s) => s.users);
  const me = useApp((s) => s.me!);
  const index = useIndex();
  const [picked, setPicked] = useState<string[]>([]);
  const [query, setQuery] = useState('');
  const [name, setName] = useState('');
  const [busy, setBusy] = useState(false);
  const people = Object.values(users).filter((u) => u.id !== me.id && !u.deleted && !u.blockedByMe && (!query || u.username.includes(query.toLowerCase())));
  const start = async () => {
    setBusy(true);
    try {
      const id = picked.length === 1 ? await openDirect(picked[0]) : await createThread(picked, true, { name: name.trim() || null });
      onClose();
      onOpen(id);
    } catch (e) {
      toast(messageOf(e), 'error');
      setBusy(false);
    }
  };
  return (
    <Sheet
      open
      onClose={onClose}
      title="New message"
      right={
        <button className="btn btn-link" disabled={!picked.length || busy} onClick={start}>
          {busy ? <Spinner size={16} /> : 'Chat'}
        </button>
      }
      bodyClass="flush"
    >
      <div className="new-chat-to">
        <b>To:</b>
        {picked.map((id) => (
          <button key={id} className="chip on" onClick={() => setPicked((p) => p.filter((x) => x !== id))}>
            {users[id]?.username} ×
          </button>
        ))}
        <input className="input bare" placeholder="Search…" value={query} onChange={(e) => setQuery(e.target.value)} autoFocus />
      </div>
      {picked.length > 1 && (
        <div style={{ padding: '0 16px 12px' }}>
          <input className="input" placeholder="Group name (optional)" value={name} onChange={(e) => setName(e.target.value)} maxLength={60} />
        </div>
      )}
      <div style={{ padding: '0 8px 8px' }}>
        {people.map((u) => {
          const on = picked.includes(u.id);
          return (
            <button key={u.id} className="user-row result" style={{ width: '100%' }} onClick={() => setPicked((p) => (on ? p.filter((x) => x !== u.id) : p.length >= 9 ? p : [...p, u.id]))}>
              <Avatar userId={u.id} size={44} />
              <span className="user-row-text" style={{ textAlign: 'left' }}>
                <b>{displayName(u, index.profiles[u.id]?.payload)}</b>
                <span className="faint">{u.username}</span>
              </span>
              <span className={`radio ${on ? 'on' : ''}`}>{on && <Check size={14} strokeWidth={3} />}</span>
            </button>
          );
        })}
        {!people.length && <Search size={0} />}
      </div>
    </Sheet>
  );
}
