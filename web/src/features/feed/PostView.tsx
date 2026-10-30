import { Bookmark, Heart, MessageCircle, MoreHorizontal, Send, X } from 'lucide-react';
import { motion } from 'motion/react';
import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { api } from '../../core/api';
import { toggleSaved } from '../../core/compose';
import { messageOf } from '../../core/errors';
import type { PostPayload } from '../../core/payloads';
import { likesOf, savedPostIds, useIndex } from '../../core/selectors';
import { toast, useApp, type Entity } from '../../core/store';
import { engine } from '../../core/sync';
import type { ObjectDto } from '../../core/types';
import { Ago, Center, compact, Empty, longDate, RichText, Spinner } from '../../ui/bits';
import { Avatar } from '../../ui/Avatar';
import { ShareSheet } from '../messages/ShareSheet';
import { Carousel } from './Carousel';
import { CommentComposer, Comments } from './Comments';
import { AuthorLine, LikeButton, mediaOfPost, useLikeToggle } from './PostCard';
import { PostMenu } from './PostMenu';

/** The caption reads as the first comment, with the author's name in front, as on Instagram. */
function Caption({ entity, text }: { entity: Entity; text: string }) {
  const me = useApp((s) => s.me!);
  const user = useApp((s) => s.users[entity.dto.authorId]);
  const username = entity.dto.authorId === me.id ? me.username : user?.username;
  return (
    <div className="comment">
      <Avatar userId={entity.dto.authorId} size={34} />
      <div className="comment-body">
        <p>
          <Link to={`/u/${username}`} className="author-name">
            {username}
          </Link>{' '}
          <RichText text={text} />
        </p>
        <div className="comment-meta">
          <Ago at={entity.dto.createdAt} />
        </div>
      </div>
    </div>
  );
}

function usePost(id: string | undefined): { entity: Entity | undefined; missing: boolean } {
  const entity = useApp((s) => (id ? s.entities[id] : undefined));
  const [missing, setMissing] = useState(false);
  useEffect(() => {
    if (!id || entity) return;
    let alive = true;
    api
      .get<ObjectDto>(`/objects/${id}`)
      .then((dto) => alive && engine.ingest([dto]))
      .catch(() => alive && setMissing(true));
    return () => {
      alive = false;
    };
  }, [id, entity]);
  return { entity, missing: missing && !entity };
}

function Details({ entity, onClose }: { entity: Entity; onClose?: () => void }) {
  const index = useIndex();
  const me = useApp((s) => s.me!);
  const payload = entity.payload as PostPayload;
  const { liked, toggle, set } = useLikeToggle(entity.dto.id);
  const [menu, setMenu] = useState(false);
  const [share, setShare] = useState(false);
  const likes = likesOf(index, entity.dto.id);
  const saved = savedPostIds(index).has(entity.dto.id);
  const isMine = entity.dto.authorId === me.id;
  return (
    <div className="post-detail">
      <div className="post-detail-media">
        <Carousel media={mediaOfPost(entity)} onDoubleTap={() => set(true)} />
      </div>
      <aside className="post-detail-side">
        <header className="post-head">
          <AuthorLine userId={entity.dto.authorId} sub={payload.location} />
          <span className="spacer" />
          <button className="icon-btn sm" onClick={() => setMenu(true)} aria-label="More options">
            <MoreHorizontal size={20} />
          </button>
          {onClose && (
            <button className="icon-btn sm hide-mobile" onClick={onClose} aria-label="Close">
              <X size={20} />
            </button>
          )}
        </header>
        <div className="post-detail-scroll">
          <Comments
            parent={entity}
            header={
              payload.caption ? <Caption entity={entity} text={payload.caption} /> : undefined
            }
          />
        </div>
        <div className="post-actions" style={{ borderTop: '1px solid var(--border)' }}>
          <LikeButton liked={liked} onToggle={toggle} />
          <button className="icon-btn action" onClick={() => document.getElementById('comment-input')?.focus()} aria-label="Comment">
            <MessageCircle size={25} strokeWidth={1.8} style={{ transform: 'scaleX(-1)' }} />
          </button>
          <button className="icon-btn action" onClick={() => setShare(true)} aria-label="Share">
            <Send size={24} strokeWidth={1.8} />
          </button>
          <span className="spacer" />
          <button className="icon-btn action" onClick={() => void toggleSaved(entity.dto.id).catch((e) => toast(messageOf(e), 'error'))} aria-label="Save">
            <Bookmark size={25} strokeWidth={1.8} fill={saved ? 'currentColor' : 'none'} />
          </button>
        </div>
        <div className="post-body" style={{ paddingTop: 0 }}>
          {(!payload.hideLikes || isMine) && (
            <b className="likes">{likes.length ? `${compact(likes.length)} ${likes.length === 1 ? 'like' : 'likes'}` : 'Be the first to like this'}</b>
          )}
          <div className="faint" style={{ fontSize: 12, marginTop: 4, textTransform: 'uppercase', letterSpacing: '0.04em' }}>
            {longDate(entity.dto.createdAt)}
          </div>
        </div>
        {!payload.commentsOff ? <CommentComposer parent={entity} /> : <p className="faint" style={{ padding: 16, borderTop: '1px solid var(--border)' }}>Commenting is turned off.</p>}
      </aside>
      <PostMenu entity={entity} open={menu} onClose={() => setMenu(false)} />
      <ShareSheet open={share} onClose={() => setShare(false)} objectId={entity.dto.id} authorId={entity.dto.authorId} />
    </div>
  );
}

/** Opened from a feed or grid: a modal over the page you were on. */
export function PostModal() {
  const { id } = useParams();
  const navigate = useNavigate();
  const { entity, missing } = usePost(id);
  const close = () => navigate(-1);
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && close();
    window.addEventListener('keydown', onKey);
    const overflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      window.removeEventListener('keydown', onKey);
      document.body.style.overflow = overflow;
    };
  });
  return (
    <motion.div className="scrim post-scrim" initial={{ opacity: 0 }} animate={{ opacity: 1 }} onMouseDown={(e) => e.target === e.currentTarget && close()}>
      <button className="icon-btn scrim-close hide-mobile" onClick={close} aria-label="Close">
        <X size={28} color="#fff" />
      </button>
      <motion.div className="post-modal" initial={{ opacity: 0, scale: 0.97 }} animate={{ opacity: 1, scale: 1 }} transition={{ type: 'spring', stiffness: 420, damping: 34 }}>
        {entity ? <Details entity={entity} onClose={close} /> : missing ? <Empty icon={<Heart size={36} />} title="Post unavailable">It may have been deleted, or you no longer have access.</Empty> : <Center><Spinner /></Center>}
      </motion.div>
    </motion.div>
  );
}

/** Opened directly (a link, a notification): a page of its own. */
export function PostPage() {
  const { id } = useParams();
  const { entity, missing } = usePost(id);
  return (
    <div className="page post-page">
      {entity ? <Details entity={entity} /> : missing ? <Empty icon={<Heart size={36} />} title="Post unavailable">It may have been deleted, or you no longer have access.</Empty> : <Center><Spinner /></Center>}
    </div>
  );
}
