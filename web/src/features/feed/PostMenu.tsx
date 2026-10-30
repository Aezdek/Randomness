import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { block, deleteObject, edit, setArchived, unfollow } from '../../core/compose';
import { messageOf } from '../../core/errors';
import type { PostPayload } from '../../core/payloads';
import { toast, useApp, type Entity } from '../../core/store';
import { Confirm, Sheet } from '../../ui/Sheet';

/** The ⋯ menu on a post: owner tools for your own, relationship tools for others'. */
export function PostMenu({ entity, open, onClose }: { entity: Entity; open: boolean; onClose: () => void }) {
  const me = useApp((s) => s.me!);
  const author = useApp((s) => s.users[entity.dto.authorId]);
  const navigate = useNavigate();
  const [confirm, setConfirm] = useState<'delete' | 'block' | null>(null);
  const [editing, setEditing] = useState(false);
  const payload = entity.payload as PostPayload;
  const mine = entity.dto.authorId === me.id;
  const run = (action: () => Promise<unknown>, done?: string) => async () => {
    onClose();
    try {
      await action();
      if (done) toast(done, 'success');
    } catch (e) {
      toast(messageOf(e), 'error');
    }
  };
  const update = (patch: Partial<PostPayload>) => edit(entity as Entity<PostPayload>, { ...payload, ...patch });

  return (
    <>
      <Sheet open={open} onClose={onClose} hideHeader bottom bodyClass="flush">
        <div className="menu centered">
          {mine ? (
            <>
              <button className="danger" onClick={() => (onClose(), setConfirm('delete'))}>
                Delete
              </button>
              <button onClick={() => (onClose(), setEditing(true))}>Edit</button>
              <button onClick={run(() => setArchived(entity.dto.id, !entity.dto.archived), entity.dto.archived ? 'Shown on your profile again' : 'Archived — only you can see it')}>
                {entity.dto.archived ? 'Show on profile' : 'Archive'}
              </button>
              <button onClick={run(() => update({ hideLikes: !payload.hideLikes }))}>{payload.hideLikes ? 'Unhide like count' : 'Hide like count to others'}</button>
              <button onClick={run(() => update({ commentsOff: !payload.commentsOff }))}>{payload.commentsOff ? 'Turn on commenting' : 'Turn off commenting'}</button>
            </>
          ) : (
            <>
              {author?.followState === 'following' && (
                <button className="danger" onClick={run(() => unfollow(entity.dto.authorId), `Unfollowed ${author.username}`)}>
                  Unfollow
                </button>
              )}
              <button className="danger" onClick={() => (onClose(), setConfirm('block'))}>
                Block
              </button>
              <button onClick={() => (onClose(), navigate(`/verify/${entity.dto.authorId}`))}>Verify security code</button>
            </>
          )}
          <button onClick={() => (onClose(), navigate(`/p/${entity.dto.id}`))}>Go to post</button>
          <button onClick={run(() => navigator.clipboard.writeText(`${location.origin}/p/${entity.dto.id}`), 'Link copied — only people who can see the post can open it')}>Copy link</button>
          <button onClick={onClose}>Cancel</button>
        </div>
      </Sheet>
      <Confirm
        open={confirm === 'delete'}
        title="Delete post?"
        message="It will be removed for everyone, along with its comments and likes."
        confirm="Delete"
        danger
        onConfirm={run(() => deleteObject(entity.dto.id), 'Post deleted')}
        onClose={() => setConfirm(null)}
      />
      <Confirm
        open={confirm === 'block'}
        title={`Block ${author?.username}?`}
        message="They won’t be able to see your posts, stories or profile, or message you. They won’t be told."
        confirm="Block"
        danger
        onConfirm={run(() => block(entity.dto.authorId), 'Blocked')}
        onClose={() => setConfirm(null)}
      />
      <EditCaption entity={entity as Entity<PostPayload>} open={editing} onClose={() => setEditing(false)} />
    </>
  );
}

function EditCaption({ entity, open, onClose }: { entity: Entity<PostPayload>; open: boolean; onClose: () => void }) {
  const [caption, setCaption] = useState(entity.payload.caption ?? '');
  const [location, setLocation] = useState(entity.payload.location ?? '');
  const [busy, setBusy] = useState(false);
  const save = async () => {
    setBusy(true);
    try {
      await edit(entity, { ...entity.payload, caption, location: location || null });
      onClose();
    } catch (e) {
      toast(messageOf(e), 'error');
    } finally {
      setBusy(false);
    }
  };
  return (
    <Sheet
      open={open}
      onClose={onClose}
      title="Edit info"
      left={<button className="btn btn-ghost btn-sm" onClick={onClose}>Cancel</button>}
      right={<button className="btn btn-link" onClick={save} disabled={busy}>Done</button>}
    >
      <div className="stack">
        <textarea className="textarea" rows={6} value={caption} onChange={(e) => setCaption(e.target.value)} placeholder="Write a caption…" maxLength={2200} />
        <input className="input" value={location} onChange={(e) => setLocation(e.target.value)} placeholder="Add location" />
      </div>
    </Sheet>
  );
}
