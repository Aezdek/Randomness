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

/** One side of a theme: how it looks while the app is dark, or while it is light. */
export interface ChatThemeLook {
  /** The chat's background, top to bottom. */
  bg: [string, string];
  /** Their messages, and the text on them. */
  received: string;
  receivedText: string;
  /** The chat's buttons. */
  accent: string;
}

export type ChatPattern = 'dots' | 'petals' | 'sparkles' | 'waves' | 'grid';

/**
 * A chat theme: your messages in its gradient, and (except for the default, which keeps the app's own look) a
 * background, a tint for their messages and an accent, in a dark and a light version that follow the app's
 * appearance. Some add a faint pattern. Same ids and colours as ChatThemes in shared/client (ChatCustom.kt).
 */
export interface ChatTheme {
  id: string;
  name: string;
  /** Your messages: a gradient across the screen, and the text on it. */
  mine: [string, string];
  mineText: string;
  dark?: ChatThemeLook;
  light?: ChatThemeLook;
  pattern?: ChatPattern;
}

export const THEMES: ChatTheme[] = [
  { id: 'default', name: 'Hush', mine: ['#7c5cff', '#e84393'], mineText: '#ffffff' },
  { id: 'dusk', name: 'Dusk', mine: ['#ff7a59', '#f2497c'], mineText: '#ffffff', dark: { bg: ['#2a1636', '#120a19'], received: '#ffffff1f', receivedText: '#ffffff', accent: '#ff9a7a' }, light: { bg: ['#fff3ec', '#ffe6ee'], received: '#ffffff', receivedText: '#14141a', accent: '#d9502f' } },
  { id: 'lagoon', name: 'Lagoon', mine: ['#12b5a5', '#1192e8'], mineText: '#ffffff', dark: { bg: ['#05303a', '#03161c'], received: '#ffffff1c', receivedText: '#ffffff', accent: '#5fdccd' }, light: { bg: ['#e7fbf8', '#e2f2ff'], received: '#ffffff', receivedText: '#14141a', accent: '#0b8579' }, pattern: 'waves' },
  { id: 'moss', name: 'Moss', mine: ['#7fb85e', '#4c9a6a'], mineText: '#ffffff', dark: { bg: ['#18261b', '#0a120c'], received: '#ffffff1a', receivedText: '#ffffff', accent: '#a9dc8c' }, light: { bg: ['#f2f8ec', '#e5efdc'], received: '#ffffff', receivedText: '#14141a', accent: '#437f34' }, pattern: 'dots' },
  { id: 'ember', name: 'Ember', mine: ['#ffa246', '#ec5a2a'], mineText: '#ffffff', dark: { bg: ['#2c1510', '#130807'], received: '#ffffff1c', receivedText: '#ffffff', accent: '#ffad70' }, light: { bg: ['#fff5ea', '#ffe8d7'], received: '#ffffff', receivedText: '#14141a', accent: '#c9561a' } },
  { id: 'lilac', name: 'Lilac', mine: ['#9f86ff', '#c77dff'], mineText: '#ffffff', dark: { bg: ['#1e1838', '#0c0a19'], received: '#ffffff1c', receivedText: '#ffffff', accent: '#c8b8ff' }, light: { bg: ['#f6f2ff', '#eee6ff'], received: '#ffffff', receivedText: '#14141a', accent: '#6b4fe0' }, pattern: 'sparkles' },
  { id: 'blossom', name: 'Blossom', mine: ['#f472b6', '#fb6f84'], mineText: '#ffffff', dark: { bg: ['#2b1121', '#12070e'], received: '#ffffff1c', receivedText: '#ffffff', accent: '#f9a8d4' }, light: { bg: ['#fff1f7', '#ffe5ef'], received: '#ffffff', receivedText: '#14141a', accent: '#c8256a' }, pattern: 'petals' },
  { id: 'aurora', name: 'Aurora', mine: ['#2fd3a0', '#5b6cf0'], mineText: '#ffffff', dark: { bg: ['#0a1631', '#050914'], received: '#ffffff1c', receivedText: '#ffffff', accent: '#6ff0cf' }, light: { bg: ['#edfcf6', '#eef0ff'], received: '#ffffff', receivedText: '#14141a', accent: '#4148cc' }, pattern: 'sparkles' },
  { id: 'dune', name: 'Dune', mine: ['#cf8048', '#a85a30'], mineText: '#ffffff', dark: { bg: ['#251b12', '#110c08'], received: '#ffffff1a', receivedText: '#ffffff', accent: '#e9b07e' }, light: { bg: ['#fbf4e9', '#f3e6d1'], received: '#fffdf8', receivedText: '#14141a', accent: '#8f4c26' }, pattern: 'grid' },
  { id: 'graphite', name: 'Graphite', mine: ['#5a5a63', '#34343b'], mineText: '#ffffff', dark: { bg: ['#1b1b1f', '#0b0b0d'], received: '#ffffff1a', receivedText: '#ffffff', accent: '#d8d8de' }, light: { bg: ['#f5f5f6', '#eaeaec'], received: '#ffffff', receivedText: '#14141a', accent: '#3d3d45' } },
  { id: 'citron', name: 'Citron', mine: ['#d9f24f', '#9ee03a'], mineText: '#18200a', dark: { bg: ['#151b07', '#080b02'], received: '#ffffff1a', receivedText: '#ffffff', accent: '#dcfa8f' }, light: { bg: ['#fbfde8', '#f2f8d0'], received: '#ffffff', receivedText: '#14141a', accent: '#557f10' }, pattern: 'dots' },
  { id: 'cobalt', name: 'Cobalt', mine: ['#3f7ff6', '#6a5cf0'], mineText: '#ffffff', dark: { bg: ['#0d1736', '#060a18'], received: '#ffffff1c', receivedText: '#ffffff', accent: '#9cc3ff' }, light: { bg: ['#eef4ff', '#e5ecff'], received: '#ffffff', receivedText: '#14141a', accent: '#2453d0' }, pattern: 'grid' },
];

/** Themes from before the redesign map to the closest new one, so chats that used them keep a similar look. */
const RENAMED: Record<string, string> = { love: 'blossom', sunset: 'dusk', ocean: 'lagoon', mint: 'aurora', forest: 'moss', lavender: 'lilac', berry: 'lilac', midnight: 'cobalt', citrus: 'ember', mono: 'graphite' };

export const isThemeId = (id: string) => THEMES.some((t) => t.id === id) || id in RENAMED;
export const themeOf = (id: string | null | undefined) => {
  const key = id && RENAMED[id] ? RENAMED[id] : id;
  return THEMES.find((t) => t.id === key) ?? THEMES[0];
};
export const themeGradient = (t: ChatTheme) => `linear-gradient(135deg, ${t.mine[0]}, ${t.mine[1]})`;

/** A theme's faint pattern as one tile of SVG, in marks light enough to sit behind text. */
function patternTile(pattern: ChatPattern, onDark: boolean): string {
  const c = onDark ? 'rgba(255,255,255,0.075)' : 'rgba(0,0,0,0.055)';
  const flower = (x: number, y: number, k: number) =>
    [0, 72, 144, 216, 288].map((a) => {
      const r = (a * Math.PI) / 180;
      return `<circle cx="${(x + Math.sin(r) * 3.4 * k).toFixed(2)}" cy="${(y - Math.cos(r) * 3.4 * k).toFixed(2)}" r="${(2.3 * k).toFixed(2)}" fill="${c}"/>`;
    }).join('');
  const star = (x: number, y: number, s: number) => `<path d="M${x} ${y - s}Q${x} ${y} ${x + s} ${y}Q${x} ${y} ${x} ${y + s}Q${x} ${y} ${x - s} ${y}Q${x} ${y} ${x} ${y - s}Z" fill="${c}"/>`;
  const tiles: Record<ChatPattern, [number, string]> = {
    dots: [44, `<circle cx="11" cy="11" r="1.8" fill="${c}"/><circle cx="33" cy="33" r="1.8" fill="${c}"/>`],
    petals: [64, flower(16, 18, 1) + flower(46, 46, 0.7)],
    sparkles: [60, star(15, 18, 5) + star(44, 42, 3.2) + `<circle cx="46" cy="12" r="1.1" fill="${c}"/>`],
    waves: [48, `<path d="M0 14q12-7 24 0t24 0M0 38q12-7 24 0t24 0" stroke="${c}" stroke-width="1.4" fill="none"/>`],
    grid: [32, `<path d="M32 0H0V32" stroke="${c}" stroke-width="1" fill="none"/>`],
  };
  const [size, marks] = tiles[pattern];
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}">${marks}</svg>`;
  return `url("data:image/svg+xml,${encodeURIComponent(svg)}")`;
}

/** The CSS for one side of a theme's background: a soft glow of its accent at the top, the pattern, the gradient. */
export function themeBackground(t: ChatTheme, dark: boolean): string | undefined {
  const look = dark ? t.dark : t.light;
  if (!look) return undefined;
  const layers = [`radial-gradient(120% 55% at 50% 0%, ${look.accent}${dark ? '2e' : '1f'}, transparent 70%)`];
  if (t.pattern) layers.push(patternTile(t.pattern, dark));
  layers.push(`linear-gradient(180deg, ${look.bg[0]}, ${look.bg[1]})`);
  return layers.join(', ');
}

/**
 * The CSS variables a chat sets for its theme. A themed chat gets both versions, and the stylesheet picks the one for
 * the app's current appearance; the default theme only colours your messages and the chat's buttons.
 */
export function themeStyle(t: ChatTheme): Record<string, string> {
  const vars: Record<string, string> = { '--chat-bubble': themeGradient(t), '--chat-bubble-text': t.mineText };
  if (!t.dark || !t.light) return { ...vars, '--chat-accent': t.mine[0] };
  for (const [side, look, dark] of [
    ['d', t.dark, true],
    ['l', t.light, false],
  ] as const) {
    vars[`--chat-bg-${side}`] = themeBackground(t, dark)!;
    vars[`--chat-received-${side}`] = look.received;
    vars[`--chat-received-text-${side}`] = look.receivedText;
    vars[`--chat-accent-${side}`] = look.accent;
  }
  return vars;
}

const EMPTY: ChatCustom = { theme: 'default', nicknames: {}, quickReaction: DEFAULT_REACTION, pinned: [] };

export function applyEvent(c: ChatCustom, e: ChatEvent): ChatCustom {
  let next = c;
  if (e.theme != null) next = { ...next, theme: isThemeId(e.theme) ? e.theme : 'default' };
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
