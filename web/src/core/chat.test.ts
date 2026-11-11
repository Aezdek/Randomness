import { describe, expect, it } from 'vitest';
import { chatCustom, eventText, mix, ridgePath, scatter, themeOf, themeStyle, THEMES } from './chat';
import { MessageType, type ChatEvent, type MessagePayload } from './payloads';
import type { Entity } from './store';
import type { UserDto } from './types';

let clock = 0;
const message = (authorId: string, payload: MessagePayload, id = `m${++clock}`) =>
  ({ dto: { id, authorId, receivedAt: clock }, payload }) as unknown as Entity;
const event = (authorId: string, e: ChatEvent) => message(authorId, { type: MessageType.SYSTEM, event: e });

describe('chatCustom (mirrors ChatCustomTest.kt)', () => {
  it('has the defaults without events', () => {
    expect(chatCustom([message('ana', { type: MessageType.TEXT, text: 'hi' })])).toEqual({ theme: 'default', nicknames: {}, quickReaction: '❤️', pinned: [] });
  });

  it('lets later events win, each changing only its own setting', () => {
    const text = message('bo', { type: MessageType.TEXT, text: 'pin me' });
    const c = chatCustom([
      event('ana', { theme: 'ocean' }),
      event('bo', { nicknameFor: 'ana', nickname: '  Captain  ' }),
      text,
      event('ana', { quickReaction: '🔥' }),
      event('bo', { theme: 'love' }),
      event('ana', { pin: text.dto.id }),
    ]);
    expect(c).toEqual({ theme: 'love', nicknames: { ana: 'Captain' }, quickReaction: '🔥', pinned: [text.dto.id] });
  });

  it('undoes with a blank nickname, an unpin, and an unknown theme', () => {
    const text = message('bo', { type: MessageType.TEXT, text: 'x' });
    const c = chatCustom([
      text,
      event('ana', { theme: 'ocean' }),
      event('ana', { nicknameFor: 'bo', nickname: 'B' }),
      event('ana', { pin: text.dto.id }),
      event('bo', { nicknameFor: 'bo', nickname: ' ' }),
      event('bo', { unpin: text.dto.id }),
      event('bo', { theme: 'from-a-newer-app' }),
    ]);
    expect(c).toEqual({ theme: 'default', nicknames: {}, quickReaction: '❤️', pinned: [] });
  });

  it('drops pins of messages that are gone', () => {
    expect(chatCustom([event('ana', { pin: 'unsent' })]).pinned).toEqual([]);
  });

  it('words events like the Android app', () => {
    const users = { ana: { username: 'ana.p' } } as unknown as Record<string, UserDto>;
    const nameOf = (id: string) => (id === 'ana' ? 'Nana' : id);
    expect(eventText({ nicknameFor: 'ana', nickname: 'Nana' }, 'me', 'me', users, nameOf)).toBe('You set the nickname for ana.p to Nana');
    expect(eventText({ theme: 'lagoon' }, 'ana', 'me', users, nameOf)).toBe('Nana changed the theme to Lagoon');
    // A theme set before the redesign shows as its closest new one.
    expect(eventText({ theme: 'ocean' }, 'ana', 'me', users, nameOf)).toBe('Nana changed the theme to Lagoon');
  });

  it('has the same theme ids as the Android app', () => {
    expect(THEMES.map((t) => t.id)).toEqual(['default', 'dusk', 'lagoon', 'moss', 'ember', 'lilac', 'blossom', 'aurora', 'dune', 'graphite', 'citron', 'cobalt']);
  });

  it('gives every theme but the default both a dark and a light look', () => {
    for (const t of THEMES.slice(1)) {
      expect(t.dark && t.light).toBeTruthy();
      expect(themeStyle(t)['--chat-bg-l']).toContain(t.light!.bg[1]);
    }
    expect(themeOf('love').id).toBe('blossom');
    expect(themeOf('no-such-theme').id).toBe('default');
  });

  it('draws scenery the Android app can draw the same way', () => {
    // The same generator on both sides (ChatScenes.scatter in the Android app): these are its first values.
    const [a, b] = scatter(7, 2);
    expect([a.x, a.y, a.s, a.t, b.x].map((v) => v.toFixed(6))).toEqual(['0.238781', '0.913493', '0.612492', '0.926981', '0.049341']);
    expect(ridgePath({ shape: 'peaks', h: [0, 1, 0.5], height: 80, depth: 0 }, 100)).toBe('M0 100L0 100L50 20L100 60L100 100Z');
    expect(ridgePath({ shape: 'steps', h: [1, 0.5], height: 80, depth: 0 }, 100)).toBe('M0 100L0 20L50 20L50 60L100 60L100 100Z');
    expect(ridgePath({ shape: 'smooth', h: [0, 1, 0], height: 100, depth: 0 }, 100)).toBe('M0 100L0 100Q0 100 25 50Q50 0 75 50L100 100L100 100Z');
    expect(mix('#000000', '#ffffff', 0.5)).toBe('#808080');
    for (const t of THEMES) {
      for (const r of t.scene?.ridges ?? []) expect(r.h.every((v) => v >= 0 && v <= 1)).toBe(true);
    }
  });
});
