import { useApp } from '../../core/store';
import { useIndex } from '../../core/selectors';
import { Audience } from '../../core/payloads';
import type { RingState } from '../../ui/Avatar';

/** How a user's avatar ring should look: unseen (gradient), Close Friends (green), seen (grey), or none. */
export function useRing(userId: string | undefined): RingState {
  const index = useIndex();
  const seen = useApp((s) => s.seenStories);
  if (!userId) return 'none';
  if (index.lives.some((l) => l.dto.authorId === userId)) return 'live';
  const stories = index.stories[userId];
  if (!stories?.length) return 'none';
  const unseen = stories.filter((s) => !seen[s.dto.id]);
  if (!unseen.length) return 'seen';
  return unseen.some((s) => s.dto.audience === Audience.CLOSE_FRIENDS) ? 'close' : 'unseen';
}
