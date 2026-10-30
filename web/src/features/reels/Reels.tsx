import { Bookmark, Clapperboard, Heart, MessageCircle, Music2, Send, Volume2, VolumeX } from 'lucide-react';
import { AnimatePresence, motion } from 'motion/react';
import { useEffect, useMemo, useRef, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { toggleSaved } from '../../core/compose';
import { messageOf } from '../../core/errors';
import { Kind, type ReelPayload } from '../../core/payloads';
import { commentsOf, likesOf, savedPostIds, useIndex } from '../../core/selectors';
import { toast, useApp, type Entity } from '../../core/store';
import { Avatar } from '../../ui/Avatar';
import { compact, Empty, RichText } from '../../ui/bits';
import { MediaVideo } from '../../ui/Media';
import { Sheet } from '../../ui/Sheet';
import { CommentComposer, Comments } from '../feed/Comments';
import { useLikeToggle } from '../feed/PostCard';
import { ShareSheet } from '../messages/ShareSheet';
import { FollowButton } from '../profile/FollowButton';
import { openCreate } from '../../core/ui';

function Reel({ entity, muted, onMute }: { entity: Entity<ReelPayload>; muted: boolean; onMute: () => void }) {
  const index = useIndex();
  const me = useApp((s) => s.me!);
  const author = useApp((s) => s.users[entity.dto.authorId]);
  const { liked, toggle, set } = useLikeToggle(entity.dto.id);
  const [comments, setComments] = useState(false);
  const [share, setShare] = useState(false);
  const [burst, setBurst] = useState(0);
  const [expanded, setExpanded] = useState(false);
  const lastTap = useRef(0);
  const saved = savedPostIds(index).has(entity.dto.id);
  const username = entity.dto.authorId === me.id ? me.username : author?.username;
  const likes = likesOf(index, entity.dto.id).length;

  return (
    <section className="reel">
      <div
        className="reel-video"
        onClick={() => {
          const now = Date.now();
          if (now - lastTap.current < 280) {
            setBurst((b) => b + 1);
            set(true);
          } else setTimeout(() => Date.now() - lastTap.current >= 280 && onMute(), 290);
          lastTap.current = now;
        }}
      >
        <MediaVideo media={entity.payload.video} muted={muted} style={{ width: '100%', height: '100%' }} />
        <AnimatePresence>
          {burst > 0 && (
            <motion.div key={burst} className="burst" initial={{ scale: 0.2, opacity: 0 }} animate={{ scale: [0.2, 1.25, 1], opacity: [0, 1, 1] }} exit={{ opacity: 0 }} onAnimationComplete={() => setTimeout(() => setBurst(0), 250)}>
              <Heart size={110} fill="#fff" strokeWidth={0} />
            </motion.div>
          )}
        </AnimatePresence>
        <button className="icon-btn on-media reel-mute" onClick={(e) => (e.stopPropagation(), onMute())} aria-label={muted ? 'Unmute' : 'Mute'}>
          {muted ? <VolumeX size={18} /> : <Volume2 size={18} />}
        </button>
        <div className="reel-info">
          <div className="row" style={{ gap: 10 }}>
            <Link to={`/u/${username}`} onClick={(e) => e.stopPropagation()}>
              <Avatar userId={entity.dto.authorId} size={34} />
            </Link>
            <Link to={`/u/${username}`} className="author-name" onClick={(e) => e.stopPropagation()}>
              {username}
            </Link>
            {author && entity.dto.authorId !== me.id && author.followState !== 'following' && (
              <span onClick={(e) => e.stopPropagation()}>
                <FollowButton user={author} />
              </span>
            )}
          </div>
          {entity.payload.caption && (
            <p className={`reel-caption ${expanded ? '' : 'clamped'}`} onClick={(e) => (e.stopPropagation(), setExpanded((x) => !x))}>
              <RichText text={entity.payload.caption} />
            </p>
          )}
          <span className="reel-audio">
            <Music2 size={13} /> {username} · Original audio
          </span>
        </div>
      </div>
      <div className="reel-actions">
        <button onClick={toggle} aria-label={liked ? 'Unlike' : 'Like'}>
          <Heart size={28} fill={liked ? 'var(--heart)' : 'none'} color={liked ? 'var(--heart)' : 'currentColor'} strokeWidth={liked ? 0 : 1.8} />
          {(!entity.payload.hideLikes || entity.dto.authorId === me.id) && <span>{compact(likes)}</span>}
        </button>
        {!entity.payload.commentsOff && (
          <button onClick={() => setComments(true)} aria-label="Comments">
            <MessageCircle size={27} strokeWidth={1.8} style={{ transform: 'scaleX(-1)' }} />
            <span>{compact(commentsOf(index, entity.dto.id).length)}</span>
          </button>
        )}
        <button onClick={() => setShare(true)} aria-label="Share">
          <Send size={26} strokeWidth={1.8} />
        </button>
        <button onClick={() => void toggleSaved(entity.dto.id).catch((e) => toast(messageOf(e), 'error'))} aria-label="Save">
          <Bookmark size={26} strokeWidth={1.8} fill={saved ? 'currentColor' : 'none'} />
        </button>
        <span className="reel-disc">
          <Avatar userId={entity.dto.authorId} size={26} />
        </span>
      </div>
      <Sheet open={comments} onClose={() => setComments(false)} title="Comments" bottom bodyClass="flush">
        <div style={{ maxHeight: '55vh', overflowY: 'auto' }}>
          <Comments parent={entity} />
        </div>
        <CommentComposer parent={entity} />
      </Sheet>
      <ShareSheet open={share} onClose={() => setShare(false)} objectId={entity.dto.id} authorId={entity.dto.authorId} />
    </section>
  );
}

export function Reels() {
  const { id } = useParams();
  const index = useIndex();
  const users = useApp((s) => s.users);
  const [muted, setMuted] = useState(true);
  const scroller = useRef<HTMLDivElement>(null);
  const reels = useMemo(() => {
    const all = index.posts.filter((p) => p.dto.kind === Kind.REEL && !p.dto.archived && !users[p.dto.authorId]?.blockedByMe) as Entity<ReelPayload>[];
    if (!id) return all;
    const first = all.find((r) => r.dto.id === id);
    return first ? [first, ...all.filter((r) => r !== first)] : all;
  }, [index.posts, users, id]);
  useEffect(() => scroller.current?.scrollTo({ top: 0 }), [id]);
  if (!reels.length) {
    return (
      <div className="page">
        <Empty icon={<Clapperboard size={36} />} title="No reels yet" action={<button className="btn btn-brand" onClick={() => openCreate('reel')}>Create a reel</button>}>
          Short vertical videos from the people you follow will play here.
        </Empty>
      </div>
    );
  }
  return (
    <div className="reels" ref={scroller}>
      {reels.map((reel) => (
        <Reel key={reel.dto.id} entity={reel} muted={muted} onMute={() => setMuted((m) => !m)} />
      ))}
    </div>
  );
}
