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

/**
 * A chat theme, as on Instagram: a background behind the whole conversation, a gradient for your messages, a translucent
 * tint for theirs and an accent for the chat's buttons. The default keeps the app's own look. Same ids and colours as
 * ChatThemes in the Android app.
 */
export interface ChatTheme {
  id: string;
  name: string;
  /** Your messages, one gradient across the screen. */
  colors: [string, string, string];
  /** Top to bottom; none keeps the app's background. */
  background?: [string, string, string];
  /** Their messages. */
  received?: string;
  accent?: string;
}

const RECEIVED = 'rgba(255, 255, 255, 0.14)';

export const THEMES: ChatTheme[] = [
  { id: 'default', name: 'Hush', colors: ['#7c5cff', '#b34fe0', '#e84393'] },
  { id: 'love', name: 'Love', colors: ['#ff2d55', '#ff5e7e', '#ff8fa3'], background: ['#8a1240', '#4a0820', '#1e030c'], accent: '#ff7a9a' },
  { id: 'sunset', name: 'Sunset', colors: ['#f83600', '#ff6a3d', '#f9a23d'], background: ['#8c2f4e', '#4a1838', '#1c0a18'], accent: '#ff8a4d' },
  { id: 'ocean', name: 'Ocean', colors: ['#0061ff', '#2f9bff', '#60efff'], background: ['#0b4a86', '#07284e', '#020c1c'], accent: '#5cc8ff' },
  { id: 'mint', name: 'Mint', colors: ['#0ba360', '#1fb97a', '#3cba92'], background: ['#0e5c46', '#06302a', '#02120f'], accent: '#4fe0a8' },
  { id: 'forest', name: 'Forest', colors: ['#2e7d5b', '#3d8f6e', '#71b280'], background: ['#24432f', '#12241a', '#060d09'], accent: '#8fd3a0' },
  { id: 'lavender', name: 'Lavender', colors: ['#8e7cf0', '#b894e8', '#e8a6d9'], background: ['#574394', '#2c2152', '#110c22'], accent: '#c9b2ff' },
  { id: 'berry', name: 'Berry', colors: ['#a43cf0', '#7b2be0', '#5a1de0'], background: ['#5a1878', '#2c0a3c', '#10031a'], accent: '#c77dff' },
  { id: 'midnight', name: 'Midnight', colors: ['#2d5bd8', '#3b6fe8', '#4f86f7'], background: ['#16234d', '#0a1028', '#03050e'], accent: '#7fa6ff' },
  { id: 'citrus', name: 'Citrus', colors: ['#f76b1c', '#f7931e', '#f9a825'], background: ['#7a4300', '#3a2000', '#140b00'], accent: '#ffb84d' },
  { id: 'mono', name: 'Mono', colors: ['#6a6a73', '#55555d', '#45454c'], background: ['#2e2e33', '#161619', '#050506'], accent: '#d0d0d6' },
].map((t) => ({ received: RECEIVED, ...t }) as ChatTheme);

/** The CSS for a theme's chat background (a soft glow at the top over the gradient). */
export const themeBackground = (t: ChatTheme) =>
  t.background ? `radial-gradient(120% 60% at 50% 0%, ${t.background[0]}cc, transparent 70%), linear-gradient(180deg, ${t.background[0]}, ${t.background[1]} 45%, ${t.background[2]})` : undefined;

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
