import { decode } from 'blurhash';
import { useEffect, useRef, useState, type CSSProperties } from 'react';
import { mediaUrl, videoUrl } from '../core/media';
import type { MediaRef } from '../core/payloads';

/** The blurhash, painted at once from the encrypted metadata, so nothing ever looks empty while media decrypts. */
export function Blurhash({ hash, className = 'blur' }: { hash: string; className?: string }) {
  const ref = useRef<HTMLCanvasElement>(null);
  useEffect(() => {
    const canvas = ref.current;
    if (!canvas) return;
    try {
      const pixels = decode(hash, 32, 32);
      const ctx = canvas.getContext('2d')!;
      const image = ctx.createImageData(32, 32);
      image.data.set(pixels);
      ctx.putImageData(image, 0, 0);
    } catch {
      /* malformed hash: leave the surface colour */
    }
  }, [hash]);
  return <canvas ref={ref} width={32} height={32} className={className} aria-hidden />;
}

export function useMediaUrl(media: MediaRef | null | undefined): string | null {
  const [url, setUrl] = useState<string | null>(null);
  useEffect(() => {
    if (!media) return;
    let alive = true;
    mediaUrl(media).then((u) => alive && setUrl(u)).catch(() => undefined);
    return () => {
      alive = false;
    };
  }, [media?.blobId]); // eslint-disable-line react-hooks/exhaustive-deps
  return media ? url : null;
}

interface FrameProps {
  media: MediaRef;
  className?: string;
  style?: CSSProperties;
  contain?: boolean;
  /** Use the media's own aspect ratio (clamped like Instagram's 4:5 … 1.91:1). */
  natural?: boolean;
  alt?: string;
}

export function aspectOf(media: MediaRef, clamp = true): number {
  const ratio = media.width && media.height ? media.width / media.height : 1;
  return clamp ? Math.min(1.91, Math.max(0.8, ratio)) : ratio;
}

export function MediaImage({ media, className = '', style, contain, natural, alt }: FrameProps) {
  const url = useMediaUrl(media);
  const [loaded, setLoaded] = useState(false);
  return (
    <div
      className={`media-frame ${contain ? 'contain' : ''} ${className}`}
      style={{ ...(natural ? { aspectRatio: String(aspectOf(media)) } : {}), ...style }}
    >
      {media.blurhash && <Blurhash hash={media.blurhash} />}
      {url && <img src={url} alt={alt ?? media.alt ?? ''} className={loaded ? 'loaded' : ''} onLoad={() => setLoaded(true)} draggable={false} />}
    </div>
  );
}

interface VideoProps extends FrameProps {
  autoPlay?: boolean;
  muted?: boolean;
  loop?: boolean;
  controls?: boolean;
  onEnded?: () => void;
  onTime?: (fraction: number) => void;
  paused?: boolean;
  videoRef?: React.Ref<HTMLVideoElement>;
}

/** Streams through the service worker (decrypting on the fly) and only plays while on screen. */
export function MediaVideo({
  media,
  className = '',
  style,
  contain,
  natural,
  autoPlay = true,
  muted = true,
  loop = true,
  controls = false,
  onEnded,
  onTime,
  paused,
  videoRef,
}: VideoProps) {
  const frame = useRef<HTMLDivElement>(null);
  const video = useRef<HTMLVideoElement | null>(null);
  const [src, setSrc] = useState<string | null>(null);
  const [visible, setVisible] = useState(false);
  const [loaded, setLoaded] = useState(false);
  const poster = useMediaUrl(media.poster);

  useEffect(() => {
    const el = frame.current;
    if (!el) return;
    const observer = new IntersectionObserver(([entry]) => setVisible(entry.intersectionRatio > 0.6), { threshold: [0, 0.6, 1] });
    observer.observe(el);
    return () => observer.disconnect();
  }, []);

  useEffect(() => {
    if (!visible || src) return;
    let alive = true;
    videoUrl(media).then((u) => alive && setSrc(u)).catch(() => undefined);
    return () => {
      alive = false;
    };
  }, [visible, src, media]);

  useEffect(() => {
    const el = video.current;
    if (!el || !src) return;
    if (visible && autoPlay && !paused) void el.play().catch(() => undefined);
    else el.pause();
  }, [visible, src, autoPlay, paused]);

  return (
    <div ref={frame} className={`media-frame ${contain ? 'contain' : ''} ${className}`} style={{ ...(natural ? { aspectRatio: String(aspectOf(media)) } : {}), ...style }}>
      {media.blurhash && <Blurhash hash={media.blurhash} />}
      {poster && !loaded && <img src={poster} alt="" className="loaded" draggable={false} />}
      {src && (
        <video
          ref={(el) => {
            video.current = el;
            if (typeof videoRef === 'function') videoRef(el);
            else if (videoRef) (videoRef as React.MutableRefObject<HTMLVideoElement | null>).current = el;
          }}
          src={src}
          muted={muted}
          loop={loop}
          playsInline
          controls={controls}
          preload="metadata"
          className={loaded ? 'loaded' : ''}
          onLoadedData={() => setLoaded(true)}
          onEnded={onEnded}
          onTimeUpdate={(e) => onTime?.(e.currentTarget.currentTime / (e.currentTarget.duration || 1))}
        />
      )}
    </div>
  );
}

export function MediaView(props: FrameProps & Partial<VideoProps>) {
  return props.media.mime.startsWith('video/') ? <MediaVideo {...props} /> : <MediaImage {...props} />;
}
