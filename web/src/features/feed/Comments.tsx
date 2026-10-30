import { Heart } from 'lucide-react';
import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { comment, deleteObject } from '../../core/compose';
import { messageOf } from '../../core/errors';
import { Kind, type CommentPayload } from '../../core/payloads';
import { commentsOf, likesOf, useIndex } from '../../core/selectors';
import { toast, useApp, type Entity } from '../../core/store';
import { Avatar } from '../../ui/Avatar';
import { Ago, RichText } from '../../ui/bits';
import { useLikeToggle } from './PostCard';

function CommentRow({ entity, onReply, depth = 0 }: { entity: Entity<CommentPayload>; onReply: (c: Entity<CommentPayload>) => void; depth?: number }) {
  const index = useIndex();
  const me = useApp((s) => s.me!);
  const user = useApp((s) => s.users[entity.dto.authorId]);
  const username = entity.dto.authorId === me.id ? me.username : user?.username;
  const { liked, toggle } = useLikeToggle(entity.dto.id);
  const likes = likesOf(index, entity.dto.id).length;
  const replies = commentsOf(index, entity.dto.id) as Entity<CommentPayload>[];
  const [showReplies, setShowReplies] = useState(false);
  return (
    <div className="comment" style={{ marginLeft: depth ? 44 : 0 }}>
      <Avatar userId={entity.dto.authorId} size={depth ? 28 : 34} />
      <div className="comment-body">
        <p>
          <Link to={`/u/${username}`} className="author-name">
            {username}
          </Link>{' '}
          <RichText text={entity.payload.text} />
        </p>
        <div className="comment-meta">
          <Ago at={entity.dto.createdAt} />
          {likes > 0 && <span>{likes === 1 ? '1 like' : `${likes} likes`}</span>}
          <button onClick={() => onReply(entity)}>Reply</button>
          {entity.dto.authorId === me.id && (
            <button onClick={() => void deleteObject(entity.dto.id).catch((e) => toast(messageOf(e), 'error'))}>Delete</button>
          )}
        </div>
        {replies.length > 0 && depth === 0 && (
          <button className="view-replies" onClick={() => setShowReplies((s) => !s)}>
            <span className="line" /> {showReplies ? 'Hide replies' : `View replies (${replies.length})`}
          </button>
        )}
        {showReplies && replies.map((r) => <CommentRow key={r.dto.id} entity={r} onReply={() => onReply(entity)} depth={1} />)}
      </div>
      <button className="comment-like" onClick={toggle} aria-label={liked ? 'Unlike comment' : 'Like comment'}>
        <Heart size={13} fill={liked ? 'var(--heart)' : 'none'} color={liked ? 'var(--heart)' : 'currentColor'} strokeWidth={liked ? 0 : 2} />
      </button>
    </div>
  );
}

/** Threaded comments (one level of replies, like Instagram), each likeable. */
export function Comments({ parent, header }: { parent: Entity; header?: React.ReactNode }) {
  const index = useIndex();
  const top = commentsOf(index, parent.dto.id) as Entity<CommentPayload>[];
  return (
    <div className="comments">
      {header}
      {top.map((c) => (
        <CommentRow key={c.dto.id} entity={c} onReply={(target) => window.dispatchEvent(new CustomEvent('aezgram:reply', { detail: target }))} />
      ))}
      {!top.length && !header && <p className="faint" style={{ textAlign: 'center', padding: 32 }}>No comments yet. Start the conversation.</p>}
    </div>
  );
}

export function CommentComposer({ parent }: { parent: Entity }) {
  const [text, setText] = useState('');
  const [replyTo, setReplyTo] = useState<Entity<CommentPayload> | null>(null);
  const users = useApp((s) => s.users);
  const me = useApp((s) => s.me!);
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    const onReply = (e: Event) => {
      const target = (e as CustomEvent<Entity<CommentPayload>>).detail;
      setReplyTo(target);
      const name = target.dto.authorId === me.id ? me.username : users[target.dto.authorId]?.username;
      setText(`@${name} `);
      document.getElementById('comment-input')?.focus();
    };
    window.addEventListener('aezgram:reply', onReply);
    return () => window.removeEventListener('aezgram:reply', onReply);
  }, [users, me]);
  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    const value = text.trim();
    if (!value) return;
    setBusy(true);
    try {
      await comment(replyTo && replyTo.dto.kind === Kind.COMMENT ? replyTo.dto.id : parent.dto.id, value);
      setText('');
      setReplyTo(null);
    } catch (err) {
      toast(messageOf(err), 'error');
    } finally {
      setBusy(false);
    }
  };
  return (
    <form className="comment-composer" onSubmit={submit}>
      <Avatar userId={me.id} size={32} />
      <input id="comment-input" className="input bare" placeholder={replyTo ? 'Write a reply…' : 'Add a comment…'} value={text} onChange={(e) => setText(e.target.value)} maxLength={2200} />
      <button className="btn btn-link" disabled={!text.trim() || busy}>
        Post
      </button>
    </form>
  );
}
