import { Check, Search } from 'lucide-react';
import { useMemo, useState } from 'react';
import { openDirect, sendMessage } from '../../core/compose';
import { messageOf } from '../../core/errors';
import { MessageType, type MessagePayload } from '../../core/payloads';
import { displayName, threadTitle, useIndex } from '../../core/selectors';
import { toast, useApp } from '../../core/store';
import { Avatar } from '../../ui/Avatar';
import { Sheet } from '../../ui/Sheet';

/**
 * Send a post, reel or story to people or groups in Direct (the message only references it, so access rules still
 * apply), or forward a message ([forward]): a copy marked "Forwarded", whose media the new chat can then open.
 */
export function ShareSheet({
  open, onClose, objectId, authorId, storyId, forward,
}: { open: boolean; onClose: () => void; objectId?: string; authorId?: string; storyId?: string; forward?: MessagePayload }) {
  const users = useApp((s) => s.users);
  const threads = useApp((s) => s.threads);
  const me = useApp((s) => s.me!);
  const index = useIndex();
  const [query, setQuery] = useState('');
  const [picked, setPicked] = useState<string[]>([]);
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);
  const people = useMemo(
    () =>
      Object.values(users)
        .filter((u) => u.id !== me.id && !u.deleted && !u.blockedByMe)
        .filter((u) => !query || u.username.includes(query.toLowerCase()) || displayName(u, index.profiles[u.id]?.payload).toLowerCase().includes(query.toLowerCase())),
    [users, me.id, query, index],
  );
  const groups = useMemo(
    () =>
      Object.values(threads)
        .filter((t) => t.isGroup)
        .map((t) => ({ thread: t, title: threadTitle(t, index, users, me.id) }))
        .filter((g) => !query || g.title.toLowerCase().includes(query.toLowerCase())),
    [threads, index, users, me.id, query],
  );
  const send = async () => {
    setBusy(true);
    try {
      for (const target of picked) {
        const threadId = target.startsWith('t:') ? target.slice(2) : await openDirect(target);
        if (forward) {
          const copy: MessagePayload = { ...forward, replyTo: null, edited: false, forwarded: true };
          await sendMessage(threadId, copy);
        } else {
          await sendMessage(threadId, { type: MessageType.SHARE, sharedObjectId: objectId, sharedAuthorId: authorId, storyId: storyId ?? null });
        }
        if (note.trim()) await sendMessage(threadId, { type: MessageType.TEXT, text: note.trim() });
      }
      toast(picked.length === 1 ? 'Sent' : `Sent to ${picked.length} people`, 'success');
      setPicked([]);
      setNote('');
      onClose();
    } catch (e) {
      toast(messageOf(e), 'error');
    } finally {
      setBusy(false);
    }
  };
  return (
    <Sheet open={open} onClose={onClose} title={forward ? 'Forward' : 'Share'} bottom bodyClass="flush">
      <div style={{ padding: '12px 16px' }} className="search">
        <Search size={17} />
        <input className="input" placeholder="Search" value={query} onChange={(e) => setQuery(e.target.value)} />
      </div>
      <div className="share-grid">
        {groups.map(({ thread, title }) => {
          const key = `t:${thread.id}`;
          const on = picked.includes(key);
          return (
            <button key={key} className="share-person" onClick={() => setPicked((p) => (on ? p.filter((x) => x !== key) : [...p, key]))} aria-pressed={on}>
              <span style={{ position: 'relative' }}>
                <span className="group-avatar share">
                  {thread.members.filter((m) => m.userId !== me.id).slice(0, 2).map((m) => (
                    <Avatar key={m.userId} userId={m.userId} size={42} />
                  ))}
                </span>
                {on && (
                  <span className="pick-check">
                    <Check size={14} strokeWidth={3} />
                  </span>
                )}
              </span>
              <span className="truncate" style={{ maxWidth: 84, fontSize: 12.5 }}>
                {title}
              </span>
            </button>
          );
        })}
        {people.map((u) => {
          const on = picked.includes(u.id);
          return (
            <button key={u.id} className="share-person" onClick={() => setPicked((p) => (on ? p.filter((x) => x !== u.id) : [...p, u.id]))} aria-pressed={on}>
              <span style={{ position: 'relative' }}>
                <Avatar userId={u.id} size={64} />
                {on && (
                  <span className="pick-check">
                    <Check size={14} strokeWidth={3} />
                  </span>
                )}
              </span>
              <span className="truncate" style={{ maxWidth: 84, fontSize: 12.5 }}>
                {displayName(u, index.profiles[u.id]?.payload)}
              </span>
            </button>
          );
        })}
        {!people.length && !groups.length && <p className="muted" style={{ gridColumn: '1 / -1', textAlign: 'center' }}>No one to share with yet.</p>}
      </div>
      {picked.length > 0 && (
        <div className="share-send">
          <input className="input" placeholder="Write a message…" value={note} onChange={(e) => setNote(e.target.value)} />
          <button className="btn btn-primary btn-block btn-lg" onClick={send} disabled={busy}>
            {picked.length > 1 ? 'Send separately' : 'Send'}
          </button>
        </div>
      )}
    </Sheet>
  );
}
