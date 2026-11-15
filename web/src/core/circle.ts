// Small-circle features: Notes to self, shared albums, plans, live location, "On this day" and search across chats.
// Mirrors the same functions in shared/client (Composer.kt and Index.kt), so both apps read each other's messages.
import { deleteObject, edit, createThread, publish, sendMessage } from './compose';
import { buildIndex, type Index } from './selectors';
import {
  Audience,
  Kind,
  MessageType,
  PlanAnswer,
  type LiveLocation,
  type MediaRef,
  type MessagePayload,
  type Plan,
  type ThreadPayload,
  type VotePayload,
} from './payloads';
import { getState, type AppState, type Entity } from './store';
import { MAX_MEDIA, safeLocation, safePlan, safePoll, safeText } from './safe';

const MINUTE = 60_000;
/** How long live location can be shared for: at least 5 minutes, at most 8 hours. */
export const LOCATION_MIN = 5 * MINUTE;
export const LOCATION_MAX = 8 * 60 * MINUTE;
export const LOCATION_CHOICES = [15 * MINUTE, 60 * MINUTE, 8 * 60 * MINUTE] as const;

const clamp = (v: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, v));
const index = () => buildIndex(getState());
const myId = () => {
  const id = getState().me?.id;
  if (!id) throw new Error('Not signed in');
  return id;
};

// ------------------------------------------------------------------------------------------------- notes to self

/** Your "Notes to self" chat, once it exists: the one-to-one chat with only you in it. */
export function notesToSelfId(state: Pick<AppState, 'threads' | 'me'> = getState()): string | null {
  const me = state.me?.id;
  if (!me) return null;
  return Object.values(state.threads).find((t) => !t.isGroup && t.members.length === 1 && t.members[0].userId === me)?.id ?? null;
}

/** Opens Notes to self, making it the first time. */
export async function openNotesToSelf(): Promise<string> {
  return notesToSelfId() ?? createThread([], false, {});
}

// ------------------------------------------------------------------------------------------------------- albums

export const isAlbum = (idx: Index, threadId: string) => idx.threadMeta[threadId]?.payload.album === true;

export function createAlbum(name: string, memberIds: string[]): Promise<string> {
  const clean = name.trim().slice(0, 60) || 'Album';
  return createThread(memberIds, true, { name: clean, album: true } satisfies ThreadPayload);
}

/** Every photo and video in a chat, newest first, with the message it came in. */
export function albumMedia(idx: Index, threadId: string): { message: Entity<MessagePayload>; media: MediaRef }[] {
  return (idx.messages[threadId] ?? [])
    .flatMap((m) => (Array.isArray(m.payload.media) ? m.payload.media.slice(0, MAX_MEDIA) : []).filter((media) => typeof media?.blobId === 'string').map((media) => ({ message: m, media })))
    .reverse();
}

// -------------------------------------------------------------------------------------------------------- plans

export async function sendPlan(threadId: string, plan: Plan) {
  const title = plan.title.trim().slice(0, 100);
  if (!title) throw new Error('Say what the plan is');
  const place = plan.place?.trim().slice(0, 100) || null;
  if (!Number.isFinite(plan.at)) throw new Error('Pick a time');
  const clean: Plan = { title, at: plan.at, place };
  return sendMessage(threadId, { type: MessageType.PLAN, text: title, plan: clean });
}

const isPlanVote = (e: Entity) => e.dto.kind === Kind.VOTE && (e.payload as VotePayload | undefined)?.stickerId === PlanAnswer.STICKER;

/** Going, maybe or not: one answer each; choosing the same again takes it back. */
export async function answerPlan(message: Entity, answer: number) {
  if (![PlanAnswer.GOING, PlanAnswer.MAYBE, PlanAnswer.NO].includes(answer as 0 | 1 | 2)) throw new Error('Not an answer');
  const me = myId();
  const mine = index().children[message.dto.id]?.find((e) => isPlanVote(e) && e.dto.authorId === me);
  if (mine) await deleteObject(mine.dto.id);
  if ((mine?.payload as VotePayload | undefined)?.option === answer) return;
  return publish<VotePayload>({
    kind: Kind.VOTE, audience: Audience.PARENT, payload: { stickerId: PlanAnswer.STICKER, option: answer },
    parentId: message.dto.id, threadId: message.dto.threadId,
  });
}

/** Who answered a plan, and how: each person's latest answer. */
export function planAnswers(idx: Index, messageId: string): Record<string, number> {
  const out: Record<string, number> = {};
  for (const e of [...(idx.children[messageId] ?? [])].filter(isPlanVote).sort((a, b) => a.dto.createdAt - b.dto.createdAt)) {
    const option = (e.payload as VotePayload).option;
    if (option === 0 || option === 1 || option === 2) out[e.dto.authorId] = option;
    else delete out[e.dto.authorId];
  }
  return out;
}

// ------------------------------------------------------------------------------------------------ live location

/**
 * Starts sharing where you are with a chat for [durationMs] (5 minutes to 8 hours). The message lasts exactly that long;
 * moveLocation replaces it with each new position, so the server never holds a trail.
 */
export async function shareLocation(threadId: string, lat: number, lng: number, accuracy: number | null, durationMs: number) {
  const now = Date.now();
  const until = now + clamp(durationMs, LOCATION_MIN, LOCATION_MAX);
  const location: LiveLocation = { lat: clamp(lat, -90, 90), lng: clamp(lng, -180, 180), accuracy, until, updatedAt: now };
  return sendMessage(threadId, { type: MessageType.LOCATION, location }, { expiresAt: until });
}

export async function moveLocation(message: Entity<MessagePayload>, lat: number, lng: number, accuracy: number | null) {
  const current = message.payload.location;
  const now = Date.now();
  if (!current || current.until <= now) return null;
  return edit(message, {
    ...message.payload,
    location: { ...current, lat: clamp(lat, -90, 90), lng: clamp(lng, -180, 180), accuracy, updatedAt: now },
  });
}

export const stopLocation = (message: Entity) => deleteObject(message.dto.id);

/** People sharing where they are in a chat right now. */
export function liveLocations(idx: Index, threadId: string, now = Date.now()): Entity<MessagePayload>[] {
  return (idx.messages[threadId] ?? []).filter((m) => (safeLocation(m.payload.location)?.until ?? 0) > now);
}

/** A link that opens the spot in the person's own maps app; nothing is fetched from a map service here. */
export function mapsLink(l: Pick<LiveLocation, 'lat' | 'lng'>): string {
  return `https://www.openstreetmap.org/?mlat=${l.lat.toFixed(5)}&mlon=${l.lng.toFixed(5)}#map=16/${l.lat.toFixed(5)}/${l.lng.toFixed(5)}`;
}

// ------------------------------------------------------------------------------------------------- on this day

/** Your own posts and reels, and archived stories, from this date in earlier years, newest year first. On the device. */
export function onThisDay(idx: Index, me: string, now = Date.now()): Entity[] {
  const today = new Date(now);
  const sameDayEarlierYear = (at: number) => {
    const d = new Date(at);
    return d.getFullYear() < today.getFullYear() && d.getMonth() === today.getMonth() && d.getDate() === today.getDate();
  };
  return [...idx.posts.filter((p) => p.dto.authorId === me), ...idx.myStoryArchive]
    .filter((e) => sameDayEarlierYear(e.dto.createdAt))
    .sort((a, b) => b.dto.createdAt - a.dto.createdAt);
}

// ------------------------------------------------------------------------------------------------------- search

/** Messages containing [query] in every chat, newest first: text, quotes, plans and polls. Searched on the device. */
export function searchMessages(idx: Index, query: string, limit = 100): Entity<MessagePayload>[] {
  const needle = query.trim().toLowerCase();
  if (needle.length < 2) return [];
  const hit = (s: string | null | undefined) => !!s && s.toLowerCase().includes(needle);
  return Object.values(idx.messages)
    .flat()
    .filter(({ payload: p }) => {
      const plan = safePlan(p.plan);
      const poll = safePoll(p.poll);
      return [safeText(p.text), safeText(p.quote), plan?.title, plan?.place, poll?.question].some(hit) || (poll?.options ?? []).some(hit);
    })
    .sort((a, b) => b.dto.receivedAt - a.dto.receivedAt)
    .slice(0, limit);
}
