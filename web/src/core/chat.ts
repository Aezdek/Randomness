import { MessageType, type ChatEvent, type MessagePayload } from './payloads';
import type { Entity } from './store';
import type { UserDto } from './types';

/**
 * A chat's look: its theme, nicknames, quick reaction and pinned messages, which anyone in the chat can change by
 * sending a ChatEvent (an ordinary, end-to-end encrypted system message). Mirrors shared/client/.../ChatCustom.kt.
 */
export interface ChatCustom {
  theme: string;
  nicknames: Record<string, string>;
  quickReaction: string;
  /** Oldest pin first. */
  pinned: string[];
}

export const DEFAULT_REACTION = '❤️';
export const MAX_NICKNAME = 40;

export interface ChatTheme {
  id: string;
  name: string;
  colors: [string, string, string];
}

/** The gradient behind your own messages, as on Instagram. Same ids and colours as ChatThemes in the Android app. */
export const THEMES: ChatTheme[] = [
  { id: 'default', name: 'Hush', colors: ['#7c5cff', '#b34fe0', '#e84393'] },
  { id: 'love', name: 'Love', colors: ['#ff0844', '#ff5e7e', '#ffb199'] },
  { id: 'sunset', name: 'Sunset', colors: ['#f83600', '#ff6a3d', '#f9d423'] },
  { id: 'ocean', name: 'Ocean', colors: ['#0061ff', '#2f9bff', '#60efff'] },
  { id: 'mint', name: 'Mint', colors: ['#0ba360', '#1fb97a', '#3cba92'] },
  { id: 'forest', name: 'Forest', colors: ['#134e5e', '#3d7f6e', '#71b280'] },
  { id: 'lavender', name: 'Lavender', colors: ['#8e7cf0', '#b894e8', '#e8a6d9'] },
  { id: 'berry', name: 'Berry', colors: ['#8e2de2', '#6a1bd1', '#4a00e0'] },
  { id: 'midnight', name: 'Midnight', colors: ['#1e3c72', '#24467f', '#2a5298'] },
  { id: 'citrus', name: 'Citrus', colors: ['#f7971e', '#f9a825', '#e8a200'] },
  { id: 'mono', name: 'Mono', colors: ['#4a4a52', '#3c3c43', '#2e2e34'] },
];

export const themeOf = (id: string | null | undefined) => THEMES.find((t) => t.id === id) ?? THEMES[0];
export const themeGradient = (t: ChatTheme) => `linear-gradient(135deg, ${t.colors[0]}, ${t.colors[1]} 55%, ${t.colors[2]})`;

const EMPTY: ChatCustom = { theme: 'default', nicknames: {}, quickReaction: DEFAULT_REACTION, pinned: [] };

export function applyEvent(c: ChatCustom, e: ChatEvent): ChatCustom {
  let next = c;
  if (e.theme != null) next = { ...next, theme: THEMES.some((t) => t.id === e.theme) ? e.theme : 'default' };
  if (e.nicknameFor != null) {
    const name = e.nickname?.trim().slice(0, MAX_NICKNAME);
    const nicknames = { ...next.nicknames };
    if (name) nicknames[e.nicknameFor] = name;
    else delete nicknames[e.nicknameFor];
    next = { ...next, nicknames };
  }
  if (e.quickReaction?.trim()) next = { ...next, quickReaction: e.quickReaction.slice(0, 16) };
  if (e.pin != null) next = { ...next, pinned: [...next.pinned.filter((id) => id !== e.pin), e.pin] };
  if (e.unpin != null) next = { ...next, pinned: next.pinned.filter((id) => id !== e.unpin) };
  return next;
}

const cache = new WeakMap<Entity[], ChatCustom>();

/** Folds a chat's events in order; pins of messages that are gone (unsent, vanished) are dropped. */
export function chatCustom(messages: Entity[] | undefined): ChatCustom {
  if (!messages) return EMPTY;
  const hit = cache.get(messages);
  if (hit) return hit;
  let custom = EMPTY;
  const ids = new Set<string>();
  for (const m of messages) {
    ids.add(m.dto.id);
    const p = m.payload as MessagePayload;
    if (p?.type === MessageType.SYSTEM && p.event) custom = applyEvent(custom, p.event);
  }
  custom = { ...custom, pinned: custom.pinned.filter((id) => ids.has(id)) };
  cache.set(messages, custom);
  return custom;
}

/** The line a chat event shows in the conversation and the inbox: "You changed the theme to Ocean". */
export function eventText(e: ChatEvent, authorId: string, me: string, users: Record<string, UserDto>, nameOf: (userId: string) => string): string {
  const who = authorId === me ? 'You' : nameOf(authorId);
  const whom = (id: string) => (id === me ? 'you' : (users[id]?.username ?? 'someone'));
  if (e.theme != null) return `${who} changed the theme to ${themeOf(e.theme).name}`;
  if (e.nicknameFor != null && !e.nickname?.trim()) return `${who} removed the nickname for ${whom(e.nicknameFor)}`;
  if (e.nicknameFor != null) {
    return e.nicknameFor === authorId
      ? `${who} set ${authorId === me ? 'your' : 'their'} nickname to ${e.nickname!.trim()}`
      : `${who} set the nickname for ${whom(e.nicknameFor)} to ${e.nickname!.trim()}`;
  }
  if (e.quickReaction != null) return `${who} set the quick reaction to ${e.quickReaction}`;
  if (e.pin != null) return `${who} pinned a message`;
  if (e.unpin != null) return `${who} unpinned a message`;
  return `${who} changed the chat`;
}
