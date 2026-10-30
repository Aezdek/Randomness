import { Bookmark, Heart, MapPin, MessageCircle, MoreHorizontal, Send } from 'lucide-react';
import { AnimatePresence, motion } from 'motion/react';
import { memo, useState } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { comment, like, toggleSaved, unlike } from '../../core/compose';
import { messageOf } from '../../core/errors';
import { Kind, type PostPayload, type ReelPayload } from '../../core/payloads';
import { commentsOf, isLiked, likesOf, savedPostIds, useIndex } from '../../core/selectors';
import { toast, useApp, type Entity } from '../../core/store';
import { RingAvatar } from '../../ui/Avatar';
import { Ago, compact, RichText, Verified } from '../../ui/bits';
import { useRing } from '../stories/ring';
import { Carousel } from './Carousel';
import { PostMenu } from './PostMenu';
import { ShareSheet } from '../messages/ShareSheet';

export function mediaOfPost(entity: Entity) {
  if (entity.dto.kind === Kind.REEL) {
    const reel = entity.payload as ReelPayload;
    return [reel.video];
  }
  return (entity.payload as PostPayload).media ?? [];
}

export function AuthorLine({ userId, sub, size = 32 }: { userId: string; sub?: React.ReactNode; size?: number }) {
  const user = useApp((s) => s.users[userId]);
  const me = useApp((s) => s.me);
  const verified = useApp((s) => s.pins[userId]?.verified);
  const ring = useRing(userId);
  const navigate = useNavigate();
  const username = userId === me?.id ? me.username : user?.username ?? '…';
  return (
    <div className="author-line">
      <RingAvatar userId={userId} size={size + 6} ring={ring} onClick={ring !== 'none' ? () => navigate(`/stories/${userId}`) : () => navigate(`/u/${username}`)} />
      <div className="author-text">
        <div className="row" style={{ gap: 4 }}>
          <Link to={`/u/${username}`} className="author-name">
            {username}
          </Link>
          {verified && <Verified />}
        </div>
        {sub && <div className="author-sub">{sub}</div>}
      </div>
    </div>
  );
}

/** The heart that pops: scale spring plus a burst when liking. */
export function LikeButton({ liked, onToggle, size = 26 }: { liked: boolean; onToggle: () => void; size?: number }) {
  return (
    <motion.button className="icon-btn action" onClick={onToggle} whileTap={{ scale: 0.8 }} aria-label={liked ? 'Unlike' : 'Like'} aria-pressed={liked}>
      <motion.span key={String(liked)} initial={{ scale: liked ? 0.6 : 1 }} animate={{ scale: 1 }} transition={{ type: 'spring', stiffness: 600, damping: 14 }} style={{ display: 'grid' }}>
        <Heart size={size} strokeWidth={liked ? 0 : 1.8} fill={liked ? 'var(--heart)' : 'none'} color={liked ? 'var(--heart)' : 'currentColor'} />
      </motion.span>
    </motion.button>
  );
}

export function useLikeToggle(id: string) {
  const index = useIndex();
  const me = useApp((s) => s.me?.id ?? '');
  const liked = isLiked(index, id, me);
  const [optimistic, setOptimistic] = useState<boolean | null>(null);
  const shown = optimistic ?? liked;
  const toggle = async () => {
    const next = !shown;
    setOptimistic(next);
    try {
      if (next) await like(id);
      else await unlike(id);
    } catch (e) {
      if ((e as { code?: string }).code !== 'exists') toast(messageOf(e), 'error');
    } finally {
      setOptimistic(null);
    }
  };
  return { liked: shown, toggle, set: (v: boolean) => v !== shown && toggle() };
}

export const PostCard = memo(function PostCard({ entity }: { entity: Entity }) {
  const index = useIndex();
  const me = useApp((s) => s.me!);
  const users = useApp((s) => s.users);
  const navigate = useNavigate();
  const location = useLocation();
  const payload = entity.payload as PostPayload & ReelPayload;
  const media = mediaOfPost(entity);
  const { liked, toggle, set } = useLikeToggle(entity.dto.id);
  const [burst, setBurst] = useState(0);
  const [expanded, setExpanded] = useState(false);
  const [draft, setDraft] = useState('');
  const [menu, setMenu] = useState(false);
  const [share, setShare] = useState(false);
  const saved = savedPostIds(index).has(entity.dto.id);
  const likes = likesOf(index, entity.dto.id);
  const comments = commentsOf(index, entity.dto.id);
  const isMine = entity.dto.authorId === me.id;
  const caption = payload.caption ?? '';
  const long = caption.length > 120 || caption.split('\n').length > 2;
  const open = () => navigate(`/p/${entity.dto.id}`, { state: { background: location } });
  const likers = likes.map((l) => (l.dto.authorId === me.id ? me.username : users[l.dto.authorId]?.username)).filter(Boolean);

  return (
    <article className="post">
      <header className="post-head">
        <AuthorLine
          userId={entity.dto.authorId}
          sub={payload.location ? (
            <span className="row" style={{ gap: 3 }}>
              <MapPin size={11} /> {payload.location}
            </span>
          ) : undefined}
        />
        <span className="faint" style={{ fontSize: 14 }}>
          • <Ago at={entity.dto.createdAt} />
        </span>
        <span className="spacer" />
        <button className="icon-btn sm" onClick={() => setMenu(true)} aria-label="More options">
          <MoreHorizontal size={20} />
        </button>
      </header>

      <div className="post-media">
        <Carousel
          media={media}
          onDoubleTap={() => {
            setBurst((b) => b + 1);
            set(true);
          }}
        />
        <AnimatePresence>
          {burst > 0 && (
            <motion.div
              key={burst}
              className="burst"
              initial={{ scale: 0.2, opacity: 0 }}
              animate={{ scale: [0.2, 1.25, 1], opacity: [0, 1, 1] }}
              exit={{ scale: 1.4, opacity: 0 }}
              transition={{ duration: 0.45, ease: [0.16, 1, 0.3, 1] }}
              onAnimationComplete={() => setTimeout(() => setBurst(0), 250)}
            >
              <Heart size={96} fill="#fff" strokeWidth={0} />
            </motion.div>
          )}
        </AnimatePresence>
      </div>

      <div className="post-actions">
        <LikeButton liked={liked} onToggle={toggle} />
        {!payload.commentsOff && (
          <button className="icon-btn action" onClick={open} aria-label="Comment">
            <MessageCircle size={25} strokeWidth={1.8} style={{ transform: 'scaleX(-1)' }} />
          </button>
        )}
        <button className="icon-btn action" onClick={() => setShare(true)} aria-label="Share">
          <Send size={24} strokeWidth={1.8} />
        </button>
        <span className="spacer" />
        <motion.button className="icon-btn action" whileTap={{ scale: 0.8 }} onClick={() => void toggleSaved(entity.dto.id).catch((e) => toast(messageOf(e), 'error'))} aria-label={saved ? 'Remove from saved' : 'Save'}>
          <Bookmark size={25} strokeWidth={1.8} fill={saved ? 'currentColor' : 'none'} />
        </motion.button>
      </div>

      <div className="post-body">
        {(!payload.hideLikes || isMine) && likes.length > 0 && (
          <button className="likes" onClick={open}>
            {likes.length === 1 ? (
              <>
                Liked by <b>{likers[0]}</b>
              </>
            ) : likers.length ? (
              <>
                Liked by <b>{likers[0]}</b> and <b>{compact(likes.length - 1)} {likes.length === 2 ? 'other' : 'others'}</b>
              </>
            ) : (
              <b>{compact(likes.length)} likes</b>
            )}
          </button>
        )}
        {caption && (
          <p className={`caption ${expanded || !long ? '' : 'clamped'}`}>
            <Link to={`/u/${isMine ? me.username : users[entity.dto.authorId]?.username}`} className="author-name">
              {isMine ? me.username : users[entity.dto.authorId]?.username}
            </Link>{' '}
            <RichText text={caption} />
          </p>
        )}
        {long && !expanded && (
          <button className="faint more" onClick={() => setExpanded(true)}>
            more
          </button>
        )}
        {comments.length > 0 && (
          <button className="faint view-comments" onClick={open}>
            {comments.length === 1 ? 'View 1 comment' : `View all ${comments.length} comments`}
          </button>
        )}
        {!payload.commentsOff && (
          <form
            className="quick-comment"
            onSubmit={async (e) => {
              e.preventDefault();
              const text = draft.trim();
              if (!text) return;
              setDraft('');
              try {
                await comment(entity.dto.id, text);
              } catch (err) {
                setDraft(text);
                toast(messageOf(err), 'error');
              }
            }}
          >
            <input className="input bare" placeholder="Add a comment…" value={draft} onChange={(e) => setDraft(e.target.value)} aria-label="Add a comment" />
            {draft.trim() && <button className="btn btn-link">Post</button>}
          </form>
        )}
      </div>
      <PostMenu entity={entity} open={menu} onClose={() => setMenu(false)} />
      <ShareSheet open={share} onClose={() => setShare(false)} objectId={entity.dto.id} authorId={entity.dto.authorId} />
    </article>
  );
});
