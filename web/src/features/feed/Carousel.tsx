import { ChevronLeft, ChevronRight } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import type { MediaRef } from '../../core/payloads';
import { aspectOf, MediaView } from '../../ui/Media';

/** Scroll-snap carousel with dots; keyboard and chevrons on desktop, swipe on touch. */
export function Carousel({ media, onIndex, onDoubleTap }: { media: MediaRef[]; onIndex?: (i: number) => void; onDoubleTap?: () => void }) {
  const track = useRef<HTMLDivElement>(null);
  const [index, setIndex] = useState(0);
  const lastTap = useRef(0);
  const ratio = aspectOf(media[0]);

  useEffect(() => {
    const el = track.current;
    if (!el) return;
    const onScroll = () => {
      const i = Math.round(el.scrollLeft / el.clientWidth);
      setIndex(i);
      onIndex?.(i);
    };
    el.addEventListener('scroll', onScroll, { passive: true });
    return () => el.removeEventListener('scroll', onScroll);
  }, [onIndex]);

  const go = (i: number) => track.current?.scrollTo({ left: i * track.current.clientWidth, behavior: 'smooth' });

  return (
    <div
      className="carousel"
      style={{ aspectRatio: String(ratio) }}
      onClick={() => {
        const now = Date.now();
        if (now - lastTap.current < 280) onDoubleTap?.();
        lastTap.current = now;
      }}
    >
      <div className="carousel-track" ref={track}>
        {media.map((m) => (
          <div className="carousel-slide" key={m.blobId}>
            <MediaView media={m} style={{ width: '100%', height: '100%' }} />
          </div>
        ))}
      </div>
      {media.length > 1 && (
        <>
          {index > 0 && (
            <button className="carousel-nav prev hide-mobile" onClick={(e) => (e.stopPropagation(), go(index - 1))} aria-label="Previous">
              <ChevronLeft size={18} strokeWidth={2.5} />
            </button>
          )}
          {index < media.length - 1 && (
            <button className="carousel-nav next hide-mobile" onClick={(e) => (e.stopPropagation(), go(index + 1))} aria-label="Next">
              <ChevronRight size={18} strokeWidth={2.5} />
            </button>
          )}
          <span className="carousel-count">
            {index + 1}/{media.length}
          </span>
        </>
      )}
      {media.length > 1 && (
        <div className="dots">
          {media.map((m, i) => (
            <span key={m.blobId} className={i === index ? 'on' : ''} />
          ))}
        </div>
      )}
    </div>
  );
}
