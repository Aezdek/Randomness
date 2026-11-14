// A shared album: a group whose photos and videos everyone in it adds to, shown as a gallery (the chat is one tap away).
import { ChevronLeft, ChevronRight, ImagePlus, MessageCircle } from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';
import { albumMedia } from '../../core/circle';
import { Audience } from '../../core/payloads';
import { nameIn, useIndex } from '../../core/selectors';
import { useApp } from '../../core/store';
import { Avatar } from '../../ui/Avatar';
import { MediaImage, MediaVideo } from '../../ui/Media';
import { Sheet } from '../../ui/Sheet';

export function AlbumGallery({ threadId, onAdd, onOpenChat }: { threadId: string; onAdd: () => void; onOpenChat: () => void }) {
  const index = useIndex();
  const users = useApp((s) => s.users);
  const me = useApp((s) => s.me!);
  // View-once photos stay private to the moment they were sent.
  const items = useMemo(() => albumMedia(index, threadId).filter(({ message }) => message.dto.audience !== Audience.ONCE), [index, threadId]);
  const [open, setOpen] = useState<number | null>(null);
  const current = open === null ? null : items[open];
  useEffect(() => {
    if (open === null) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'ArrowRight') setOpen((i) => (i === null ? i : Math.min(items.length - 1, i + 1)));
      if (e.key === 'ArrowLeft') setOpen((i) => (i === null ? i : Math.max(0, i - 1)));
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, items.length]);

  return (
    <div className="album">
      <div className="album-bar">
        <span className="faint">{items.length === 0 ? 'No photos yet' : `${items.length} photo${items.length === 1 ? '' : 's'} and videos`}</span>
        <button className="btn btn-primary btn-sm" onClick={onAdd}>
          <ImagePlus size={16} /> Add
        </button>
      </div>
      {items.length === 0 ? (
        <div className="album-empty">
          <ImagePlus size={40} strokeWidth={1.4} />
          <p>Add the first photos. Everyone in the album can add theirs, and only the people in it can see them.</p>
          <button className="btn btn-secondary btn-sm" onClick={onOpenChat}>
            <MessageCircle size={15} /> Open the chat
          </button>
        </div>
      ) : (
        <div className="album-grid">
          {items.map(({ message, media }, i) => (
            <button key={`${message.dto.id}-${media.blobId}`} onClick={() => setOpen(i)} aria-label={`Photo ${i + 1} of ${items.length}`}>
              <MediaImage media={media.mime.startsWith('video/') && media.poster ? media.poster : media} />
              {media.mime.startsWith('video/') && <span className="album-video-badge">▶</span>}
            </button>
          ))}
        </div>
      )}
      <Sheet open={!!current} onClose={() => setOpen(null)} size="full" title={current ? `${open! + 1} of ${items.length}` : ''}>
        {current && (
          <div className="album-viewer">
            <div className="album-viewer-media">
              {current.media.mime.startsWith('video/') ? <MediaVideo key={current.media.blobId} media={current.media} controls contain autoPlay muted={false} /> : <MediaImage key={current.media.blobId} media={current.media} contain />}
              {open! > 0 && (
                <button className="album-nav prev" onClick={() => setOpen(open! - 1)} aria-label="Newer">
                  <ChevronLeft size={26} />
                </button>
              )}
              {open! < items.length - 1 && (
                <button className="album-nav next" onClick={() => setOpen(open! + 1)} aria-label="Older">
                  <ChevronRight size={26} />
                </button>
              )}
            </div>
            <div className="album-viewer-info">
              <Avatar userId={current.message.dto.authorId} size={28} />
              <span>
                <b>{current.message.dto.authorId === me.id ? 'You' : nameIn(threadId, current.message.dto.authorId, index, users)}</b>{' '}
                <span className="faint">added this {new Date(current.message.dto.createdAt).toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' })}</span>
              </span>
            </div>
          </div>
        )}
      </Sheet>
    </div>
  );
}
