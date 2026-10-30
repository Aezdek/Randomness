import { useState } from 'react';
import { createNote, deleteObject } from '../../core/compose';
import { messageOf } from '../../core/errors';
import { useIndex } from '../../core/selectors';
import { toast, useApp } from '../../core/store';
import { Avatar } from '../../ui/Avatar';
import { Sheet } from '../../ui/Sheet';

/** Notes: a 60-character thought floating over your avatar in Direct, for 24 hours. */
export function CreateNote({ onClose }: { onClose: () => void }) {
  const me = useApp((s) => s.me!);
  const index = useIndex();
  const current = index.notes[me.id];
  const [text, setText] = useState(current?.payload.text ?? '');
  const [busy, setBusy] = useState(false);
  const share = async () => {
    setBusy(true);
    try {
      await createNote({ text: text.trim() });
      toast('Note shared', 'success');
      onClose();
    } catch (e) {
      toast(messageOf(e), 'error');
    } finally {
      setBusy(false);
    }
  };
  return (
    <Sheet
      open
      onClose={onClose}
      title="New note"
      bottom
      right={
        <button className="btn btn-link" onClick={share} disabled={busy || !text.trim()}>
          Share
        </button>
      }
    >
      <div className="note-composer">
        <div className="note-bubble big">
          <input value={text} onChange={(e) => setText(e.target.value)} placeholder="Share a thought…" maxLength={60} autoFocus />
        </div>
        <Avatar userId={me.id} size={88} />
        <span className="faint">{60 - text.length} characters left · visible for 24 hours</span>
        {current && (
          <button className="btn btn-danger btn-sm" onClick={() => void deleteObject(current.dto.id).then(onClose)}>
            Delete current note
          </button>
        )}
      </div>
    </Sheet>
  );
}
