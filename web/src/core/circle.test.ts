import { beforeAll, describe, expect, it } from 'vitest';
import { initSodium } from '../crypto/sodium';
import { albumMedia, liveLocations, mapsLink, notesToSelfId, onThisDay, planAnswers, searchMessages } from './circle';
import { passkeyKeys } from './passkeys';
import { Kind, MessageType, PlanAnswer, type MessagePayload, type VotePayload } from './payloads';
import { buildIndex } from './selectors';
import type { Entity } from './store';
import type { ThreadDto } from './types';

let clock = 1_000;
function entity(kind: string, authorId: string, payload: unknown, extra: Record<string, unknown> = {}): Entity {
  clock += 1;
  return {
    dto: { id: `o${clock}`, kind, authorId, createdAt: clock, receivedAt: clock, version: 1, blobIds: [], refIds: [], archived: false, audience: 'thread', ...extra },
    key: new Uint8Array(32),
    payload,
  } as unknown as Entity;
}
const msg = (authorId: string, threadId: string, payload: MessagePayload, extra: Record<string, unknown> = {}) => entity(Kind.MESSAGE, authorId, payload, { threadId, ...extra });
const index = (...list: Entity[]) => buildIndex({ entities: Object.fromEntries(list.map((e) => [e.dto.id, e])), me: { id: 'me' } as never });

describe('plans (mirror Index.planAnswers in Kotlin)', () => {
  it('keeps each person’s latest answer and ignores poll votes and nonsense', () => {
    const plan = msg('ana', 't', { type: MessageType.PLAN, plan: { title: 'Dinner', at: 5 } });
    const vote = (who: string, option: number, stickerId: string = PlanAnswer.STICKER) =>
      entity(Kind.VOTE, who, { stickerId, option } satisfies VotePayload, { parentId: plan.dto.id, threadId: 't' });
    const idx = index(plan, vote('bo', PlanAnswer.GOING), vote('cy', PlanAnswer.MAYBE), vote('bo', PlanAnswer.NO), vote('di', 1, 'poll'), vote('ed', 7));
    expect(planAnswers(idx, plan.dto.id)).toEqual({ bo: PlanAnswer.NO, cy: PlanAnswer.MAYBE });
  });
});

describe('live location', () => {
  it('lists only locations still running, and links to a map without fetching anything', () => {
    const now = 10_000;
    const running = msg('ana', 't', { type: MessageType.LOCATION, location: { lat: 1, lng: 2, until: now + 60_000, updatedAt: now } });
    const over = msg('bo', 't', { type: MessageType.LOCATION, location: { lat: 1, lng: 2, until: now - 1, updatedAt: now - 100 } });
    const text = msg('bo', 't', { type: MessageType.TEXT, text: 'hi' });
    const idx = index(running, over, text);
    expect(liveLocations(idx, 't', now).map((m) => m.dto.id)).toEqual([running.dto.id]);
    expect(mapsLink({ lat: 38.71391, lng: -9.13942 })).toBe('https://www.openstreetmap.org/?mlat=38.71391&mlon=-9.13942#map=16/38.71391/-9.13942');
  });
});

describe('search across chats (on the device)', () => {
  it('finds text, quotes, plans and polls, newest first, case-insensitively', () => {
    const a = msg('ana', 't1', { type: MessageType.TEXT, text: 'Meet at Leo’s?' });
    const b = msg('bo', 't2', { type: MessageType.PLAN, text: 'x', plan: { title: 'Dinner', at: 1, place: 'LEO’S place' } });
    const c = msg('cy', 't2', { type: MessageType.POLL, poll: { question: 'Where?', options: ['Home', 'leo’s'] } });
    const d = msg('cy', 't3', { type: MessageType.TEXT, text: 'nothing here' });
    const idx = index(a, b, c, d);
    expect(searchMessages(idx, 'leo').map((m) => m.dto.id)).toEqual([c.dto.id, b.dto.id, a.dto.id]);
    expect(searchMessages(idx, ' l ')).toEqual([]);
  });
});

describe('albums and notes to self', () => {
  it('lists every photo newest first, and finds the chat with only you', () => {
    const ref = (blobId: string) => ({ blobId, key: 'k', mime: 'image/jpeg', size: 1 });
    const one = msg('ana', 'al', { type: MessageType.MEDIA, media: [ref('b1'), ref('b2')] });
    const two = msg('bo', 'al', { type: MessageType.MEDIA, media: [ref('b3')] });
    expect(albumMedia(index(one, two), 'al').map((x) => x.media.blobId)).toEqual(['b3', 'b2', 'b1']);
    const thread = (id: string, isGroup: boolean, members: string[]) =>
      ({ id, isGroup, createdBy: 'me', createdAt: 0, members: members.map((userId) => ({ userId, role: 'member', joinedAt: 0, lastReadAt: 0 })) }) as ThreadDto;
    const threads = { a: thread('a', false, ['me', 'bo']), b: thread('b', true, ['me']), c: thread('c', false, ['me']) };
    expect(notesToSelfId({ threads, me: { id: 'me' } as never })).toBe('c');
    expect(notesToSelfId({ threads: { a: threads.a }, me: { id: 'me' } as never })).toBeNull();
  });
});

describe('on this day', () => {
  it('shows your own posts from this date in earlier years only', () => {
    const now = new Date(2026, 10, 14, 12).getTime();
    const at = (y: number, m: number, d: number) => new Date(y, m, d, 9).getTime();
    const mine2024 = entity(Kind.POST, 'me', { media: [] }, { createdAt: at(2024, 10, 14) });
    const mine2025 = entity(Kind.POST, 'me', { media: [] }, { createdAt: at(2025, 10, 14) });
    const mineToday = entity(Kind.POST, 'me', { media: [] }, { createdAt: at(2026, 10, 14) });
    const mineOtherDay = entity(Kind.POST, 'me', { media: [] }, { createdAt: at(2025, 10, 13) });
    const theirs = entity(Kind.POST, 'bo', { media: [] }, { createdAt: at(2025, 10, 14) });
    const idx = index(mine2024, mine2025, mineToday, mineOtherDay, theirs);
    expect(onThisDay(idx, 'me', now).map((e) => e.dto.id)).toEqual([mine2025.dto.id, mine2024.dto.id]);
  });
});

describe('passkey keys', () => {
  beforeAll(() => initSodium());
  it('derives two different, stable keys from the passkey’s secret, and refuses a short one', () => {
    const secret = new Uint8Array(32).map((_, i) => i);
    const a = passkeyKeys(secret);
    const b = passkeyKeys(secret.slice());
    expect(a.authKey).toEqual(b.authKey);
    expect(a.vaultKey).toEqual(b.vaultKey);
    expect(a.authKey).not.toEqual(a.vaultKey);
    expect(a.authKey).not.toEqual(secret);
    expect(() => passkeyKeys(new Uint8Array(16))).toThrow();
  });
});
