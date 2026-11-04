import { Clapperboard, Copy, Heart, MessageCircle, Pin } from 'lucide-react';
import { useLocation, useNavigate } from 'react-router-dom';
import { Kind, type PostPayload, type ReelPayload } from '../../core/payloads';
import { commentsOf, likesOf, useIndex } from '../../core/selectors';
import type { Entity } from '../../core/store';
import { compact } from '../../ui/bits';
import { MediaImage } from '../../ui/Media';

export function coverOf(entity: Entity) {
  if (entity.dto.kind === Kind.REEL) {
    const reel = entity.payload as ReelPayload;
    return reel.cover ?? reel.video.poster ?? null;
  }
  const first = (entity.payload as PostPayload).media?.[0];
  return first?.mime.startsWith('video/') ? first.poster ?? null : first ?? null;
}

/** Three-up grid of square (or 9:16 for reels) tiles, with counts on hover. [pinned] tiles show a pin, as on Instagram. */
export function PostGrid({ posts, reels, pinned = [] }: { posts: Entity[]; reels?: boolean; pinned?: string[] }) {
  const index = useIndex();
  const navigate = useNavigate();
  const location = useLocation();
  return (
    <div className={`grid ${reels ? 'reels' : ''}`}>
      {posts.map((post) => {
        const cover = coverOf(post);
        const payload = post.payload as PostPayload;
        const many = (payload.media?.length ?? 0) > 1;
        const isReel = post.dto.kind === Kind.REEL || payload.media?.[0]?.mime.startsWith('video/');
        return (
          <button
            key={post.dto.id}
            className="tile"
            onClick={() => (post.dto.kind === Kind.REEL && reels ? navigate(`/reels/${post.dto.id}`) : navigate(`/p/${post.dto.id}`, { state: { background: location } }))}
            aria-label="Open post"
          >
            {cover ? <MediaImage media={cover} /> : <div className="media-frame" />}
            {pinned.includes(post.dto.id) ? (
              <span className="tile-kind" aria-label="Pinned">
                <Pin size={18} fill="#fff" strokeWidth={1.5} style={{ rotate: '45deg' }} />
              </span>
            ) : (
              (many || isReel) && <span className="tile-kind">{isReel ? <Clapperboard size={18} fill="#fff" strokeWidth={1.5} /> : <Copy size={18} fill="#fff" strokeWidth={1.5} />}</span>
            )}
            {post.dto.archived && <span className="tile-archived">Archived</span>}
            <span className="tile-hover">
              <span>
                <Heart size={19} fill="#fff" strokeWidth={0} /> {compact(likesOf(index, post.dto.id).length)}
              </span>
              <span>
                <MessageCircle size={19} fill="#fff" strokeWidth={0} style={{ transform: 'scaleX(-1)' }} /> {compact(commentsOf(index, post.dto.id).length)}
              </span>
            </span>
          </button>
        );
      })}
    </div>
  );
}
