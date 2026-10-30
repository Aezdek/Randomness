import type { StoryPayload } from '../../core/payloads';
import type { Entity } from '../../core/store';
import { MediaImage } from '../../ui/Media';
import { backgroundOf } from './backgrounds';

export function StoryThumb({ story }: { story: Entity<StoryPayload> }) {
  const p = story.payload;
  if (p.media) {
    const still = p.media.mime.startsWith('image/') ? p.media : p.media.poster;
    if (still) return <MediaImage media={still} style={{ width: '100%', height: '100%' }} />;
  }
  return (
    <div className="story-thumb-text" style={{ background: backgroundOf(p.text?.background) }}>
      <span>{p.text?.text}</span>
    </div>
  );
}
