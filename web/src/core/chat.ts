import { DISAPPEARING, disappearingLabel, MessageType, type ChatEvent, type MessagePayload } from './payloads';
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
  /** Disappearing messages: how long new messages last, in milliseconds; 0 if they stay. */
  disappearAfter: number;
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

/** A line of hills, dunes, waves, peaks or rooftops along the bottom of the chat. */
export interface Ridge {
  shape: 'smooth' | 'peaks' | 'steps';
  /** Heights across the width, each 0 to 1 of [height]. */
  h: number[];
  /** How tall the ridge stands, in pixels. */
  height: number;
  /** 0 is the farthest ridge (hazy), 1 the nearest (strongest). */
  depth: number;
}

/**
 * A theme's scenery, behind the messages. Everything hangs off an edge and is sized in pixels, so the same scene
 * suits a phone and a wide desktop window: ridges stretch along the bottom, a sun or moon keeps its shape, and the
 * small marks repeat on a tile too large to notice. Same numbers as ChatThemes in shared/client (ChatCustom.kt), which
 * the Android app draws from.
 */
export interface ChatScene {
  ridges?: Ridge[];
  /** Ridges repeat every this many pixels instead of stretching across (rooftops would turn into slabs). */
  span?: number;
  /** A sun or moon: across (0 to 1), and down from the top or up from the bottom in pixels. */
  orb?: { x: number; top?: number; bottom?: number; r: number };
  /** Small marks scattered over the chat. `alpha` is their strength in the dark and the light look (0 leaves them out). */
  motes?: { kind: 'star' | 'sparkle' | 'petal' | 'dot' | 'ring'; count: number; seed: number; size: [number, number]; alpha: [number, number] };
  /** Wide washes of the theme's own colours near the top (an aurora). `rx` is a share of the width. */
  glows?: { x: number; top: number; rx: number; ry: number; color: 0 | 1; alpha: [number, number] }[];
}

/**
 * A chat theme: your messages in its gradient, and (except for the default, which keeps the app's own look) a
 * background, a tint for their messages and an accent, in a dark and a light version that follow the app's
 * appearance. Most have a scene of their own. Same ids and colours as ChatThemes in shared/client (ChatCustom.kt).
 */
export interface ChatTheme {
  id: string;
  name: string;
  /** Your messages: a gradient across the screen, and the text on it. */
  mine: [string, string];
  mineText: string;
  dark?: ChatThemeLook;
  light?: ChatThemeLook;
  scene?: ChatScene;
}

const WHITE = '#ffffff';
const INK = '#14141a';

export const THEMES: ChatTheme[] = [
  { id: 'default', name: 'Hush', mine: ['#7c5cff', '#e84393'], mineText: WHITE },
  {
    id: 'dusk', name: 'Dusk', mine: ['#ff7a59', '#f2497c'], mineText: WHITE,
    dark: { bg: ['#33193f', '#150a1c'], received: '#ffffff21', receivedText: WHITE, accent: '#ff9a7a' },
    light: { bg: ['#fff1e6', '#ffdfe6'], received: WHITE, receivedText: INK, accent: '#d9502f' },
    scene: {
      orb: { x: 0.68, bottom: 132, r: 46 },
      ridges: [
        { shape: 'smooth', h: [0.55, 0.7, 0.5, 0.8, 0.62, 0.9, 0.7, 0.6, 0.78], height: 172, depth: 0 },
        { shape: 'smooth', h: [0.8, 0.55, 0.7, 0.45, 0.62, 0.5, 0.75, 0.6, 0.5], height: 120, depth: 0.5 },
        { shape: 'smooth', h: [0.4, 0.6, 0.5, 0.7, 0.45, 0.55, 0.8, 0.6, 0.7], height: 70, depth: 1 },
      ],
      motes: { kind: 'star', count: 39, seed: 7, size: [0.6, 1.5], alpha: [0.5, 0] },
    },
  },
  {
    id: 'lagoon', name: 'Lagoon', mine: ['#12b5a5', '#1192e8'], mineText: WHITE,
    dark: { bg: ['#06343f', '#03161c'], received: '#ffffff1e', receivedText: WHITE, accent: '#5fdccd' },
    light: { bg: ['#e4faf7', '#dceeff'], received: WHITE, receivedText: INK, accent: '#0b8579' },
    scene: {
      ridges: [
        { shape: 'smooth', h: [0.72, 0.92, 0.72, 0.92, 0.72, 0.92, 0.72, 0.92, 0.72, 0.92, 0.72, 0.92, 0.72], height: 112, depth: 0 },
        { shape: 'smooth', h: [0.92, 0.7, 0.92, 0.7, 0.92, 0.7, 0.92, 0.7, 0.92, 0.7, 0.92, 0.7, 0.92], height: 80, depth: 0.5 },
        { shape: 'smooth', h: [0.68, 0.94, 0.68, 0.94, 0.68, 0.94, 0.68, 0.94, 0.68, 0.94, 0.68, 0.94, 0.68], height: 46, depth: 1 },
      ],
      motes: { kind: 'ring', count: 21, seed: 3, size: [3, 9], alpha: [0.16, 0.2] },
    },
  },
  {
    id: 'moss', name: 'Moss', mine: ['#7fb85e', '#4c9a6a'], mineText: WHITE,
    dark: { bg: ['#1b2b1e', '#0a120c'], received: '#ffffff1c', receivedText: WHITE, accent: '#a9dc8c' },
    light: { bg: ['#f1f8ea', '#e2edd8'], received: WHITE, receivedText: INK, accent: '#437f34' },
    scene: {
      ridges: [
        { shape: 'smooth', h: [0.6, 0.85, 0.7, 0.5, 0.65, 0.9, 0.75], height: 180, depth: 0 },
        { shape: 'smooth', h: [0.9, 0.6, 0.5, 0.75, 0.85, 0.55, 0.6], height: 125, depth: 0.5 },
        { shape: 'smooth', h: [0.55, 0.7, 0.8, 0.62, 0.5, 0.62, 0.78], height: 72, depth: 1 },
      ],
      motes: { kind: 'dot', count: 24, seed: 11, size: [1.2, 2.6], alpha: [0.5, 0.22] },
    },
  },
  {
    id: 'ember', name: 'Ember', mine: ['#ffa246', '#ec5a2a'], mineText: WHITE,
    dark: { bg: ['#321811', '#130807'], received: '#ffffff1e', receivedText: WHITE, accent: '#ffad70' },
    light: { bg: ['#fff4e8', '#ffe4d1'], received: WHITE, receivedText: INK, accent: '#c9561a' },
    scene: {
      ridges: [
        { shape: 'peaks', h: [0.3, 0.75, 0.5, 0.95, 0.55, 0.8, 0.4, 0.7, 0.35], height: 190, depth: 0 },
        { shape: 'peaks', h: [0.6, 0.4, 0.8, 0.5, 0.65, 0.45, 0.9, 0.5, 0.6], height: 120, depth: 0.6 },
        { shape: 'smooth', h: [0.6, 0.7, 0.5, 0.65, 0.55, 0.75], height: 54, depth: 1 },
      ],
      motes: { kind: 'dot', count: 33, seed: 5, size: [1, 3], alpha: [0.55, 0.3] },
    },
  },
  {
    id: 'lilac', name: 'Lilac', mine: ['#9f86ff', '#c77dff'], mineText: WHITE,
    dark: { bg: ['#221b40', '#0c0a19'], received: '#ffffff1e', receivedText: WHITE, accent: '#c8b8ff' },
    light: { bg: ['#f5f0ff', '#ebe2ff'], received: WHITE, receivedText: INK, accent: '#6b4fe0' },
    scene: {
      orb: { x: 0.2, top: 96, r: 26 },
      ridges: [
        { shape: 'smooth', h: [0.5, 0.8, 0.55, 0.9, 0.6, 0.85, 0.5, 0.75, 0.6, 0.9, 0.55], height: 120, depth: 0 },
        { shape: 'smooth', h: [0.8, 0.5, 0.85, 0.55, 0.75, 0.5, 0.9, 0.6, 0.8, 0.5, 0.7], height: 78, depth: 0.7 },
      ],
      motes: { kind: 'sparkle', count: 21, seed: 9, size: [3, 7], alpha: [0.32, 0.28] },
    },
  },
  {
    id: 'blossom', name: 'Blossom', mine: ['#f472b6', '#fb6f84'], mineText: WHITE,
    dark: { bg: ['#301326', '#12070e'], received: '#ffffff1e', receivedText: WHITE, accent: '#f9a8d4' },
    light: { bg: ['#fff0f6', '#ffe1ec'], received: WHITE, receivedText: INK, accent: '#c8256a' },
    scene: {
      ridges: [
        { shape: 'smooth', h: [0.5, 0.7, 0.9, 0.75, 0.55, 0.6], height: 110, depth: 0.3 },
        { shape: 'smooth', h: [0.8, 0.6, 0.5, 0.65, 0.85, 0.7], height: 64, depth: 1 },
      ],
      motes: { kind: 'petal', count: 42, seed: 4, size: [4, 8], alpha: [0.3, 0.32] },
    },
  },
  {
    id: 'aurora', name: 'Aurora', mine: ['#2fd3a0', '#5b6cf0'], mineText: WHITE,
    dark: { bg: ['#0b1736', '#050914'], received: '#ffffff1e', receivedText: WHITE, accent: '#6ff0cf' },
    light: { bg: ['#ecfcf5', '#eceeff'], received: WHITE, receivedText: INK, accent: '#4148cc' },
    scene: {
      glows: [
        { x: 0.3, top: 70, rx: 0.62, ry: 120, color: 0, alpha: [0.34, 0.22] },
        { x: 0.72, top: 150, rx: 0.55, ry: 110, color: 1, alpha: [0.3, 0.2] },
        { x: 0.5, top: 250, rx: 0.7, ry: 90, color: 0, alpha: [0.14, 0.1] },
      ],
      ridges: [
        { shape: 'peaks', h: [0.4, 0.8, 0.55, 0.95, 0.6, 0.75, 0.45, 0.85, 0.5], height: 150, depth: 0.4 },
        { shape: 'peaks', h: [0.7, 0.45, 0.85, 0.5, 0.7, 0.4, 0.8, 0.55, 0.65], height: 88, depth: 1 },
      ],
      motes: { kind: 'star', count: 51, seed: 21, size: [0.6, 1.6], alpha: [0.6, 0] },
    },
  },
  {
    id: 'dune', name: 'Dune', mine: ['#cf8048', '#a85a30'], mineText: WHITE,
    dark: { bg: ['#2b1f14', '#110c08'], received: '#ffffff1c', receivedText: WHITE, accent: '#e9b07e' },
    light: { bg: ['#fbf3e6', '#f1e2c9'], received: '#fffdf8', receivedText: INK, accent: '#8f4c26' },
    scene: {
      orb: { x: 0.78, top: 120, r: 24 },
      ridges: [
        { shape: 'smooth', h: [0.35, 0.5, 0.8, 0.95, 0.7, 0.45, 0.4], height: 170, depth: 0 },
        { shape: 'smooth', h: [0.9, 0.7, 0.45, 0.4, 0.6, 0.85, 0.95], height: 120, depth: 0.5 },
        { shape: 'smooth', h: [0.4, 0.55, 0.75, 0.6, 0.4, 0.5, 0.7], height: 64, depth: 1 },
      ],
    },
  },
  {
    id: 'graphite', name: 'Graphite', mine: ['#5a5a63', '#34343b'], mineText: WHITE,
    dark: { bg: ['#1c1c20', '#0b0b0d'], received: '#ffffff1a', receivedText: WHITE, accent: '#d8d8de' },
    light: { bg: ['#f5f5f6', '#e9e9ec'], received: WHITE, receivedText: INK, accent: '#3d3d45' },
  },
  {
    id: 'citron', name: 'Citron', mine: ['#d9f24f', '#9ee03a'], mineText: '#18200a',
    dark: { bg: ['#181f08', '#080b02'], received: '#ffffff1c', receivedText: WHITE, accent: '#dcfa8f' },
    light: { bg: ['#fbfde6', '#f0f7cb'], received: WHITE, receivedText: INK, accent: '#557f10' },
    scene: { motes: { kind: 'dot', count: 14, seed: 13, size: [16, 44], alpha: [0.07, 0.11] } },
  },
  {
    id: 'cobalt', name: 'Cobalt', mine: ['#3f7ff6', '#6a5cf0'], mineText: WHITE,
    dark: { bg: ['#0f1b40', '#060a18'], received: '#ffffff1e', receivedText: WHITE, accent: '#9cc3ff' },
    light: { bg: ['#edf3ff', '#e0e9ff'], received: WHITE, receivedText: INK, accent: '#2453d0' },
    scene: {
      orb: { x: 0.82, top: 92, r: 18 },
      span: 420,
      ridges: [
        { shape: 'steps', h: [0.5, 0.8, 0.6, 0.95, 0.4, 0.7, 0.85, 0.55, 0.75, 0.45, 0.9, 0.6, 0.7, 0.5], height: 130, depth: 0.6 },
        { shape: 'steps', h: [0.7, 0.4, 0.85, 0.5, 0.65, 0.9, 0.45, 0.6, 0.8, 0.5, 0.7, 0.4, 0.6, 0.75], height: 80, depth: 1 },
      ],
      motes: { kind: 'star', count: 45, seed: 17, size: [0.6, 1.5], alpha: [0.6, 0] },
    },
  },
];

/** Themes from before the redesign map to the closest new one, so chats that used them keep a similar look. */
const RENAMED: Record<string, string> = { love: 'blossom', sunset: 'dusk', ocean: 'lagoon', mint: 'aurora', forest: 'moss', lavender: 'lilac', berry: 'lilac', midnight: 'cobalt', citrus: 'ember', mono: 'graphite' };

export const isThemeId = (id: string) => THEMES.some((t) => t.id === id) || id in RENAMED;
export const themeOf = (id: string | null | undefined) => {
  const key = id && RENAMED[id] ? RENAMED[id] : id;
  return THEMES.find((t) => t.id === key) ?? THEMES[0];
};
export const themeGradient = (t: ChatTheme) => `linear-gradient(135deg, ${t.mine[0]}, ${t.mine[1]})`;

// ------------------------------------------------------------------------------------------------------- scenery

const channels = (hex: string) => [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16));
const hex2 = (n: number) => Math.round(Math.min(255, Math.max(0, n))).toString(16).padStart(2, '0');
/** [a] with [k] (0 to 1) of [b] mixed in. */
export const mix = (a: string, b: string, k: number) => {
  const [x, y] = [channels(a), channels(b)];
  return `#${x.map((v, i) => hex2(v + (y[i] - v) * k)).join('')}`;
};
const alpha = (hex: string, a: number) => `${hex.slice(0, 7)}${hex2(a * 255)}`;
const n2 = (v: number) => Number(v.toFixed(2));
const svgUrl = (body: string, w: number, h: number, extra = '') =>
  `url("data:image/svg+xml,${encodeURIComponent(`<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${w} ${h}" ${extra}>${body}</svg>`)}")`;

/** The colour of a ridge: hazier the farther back it stands, like hills at a distance. */
export function ridgeColor(look: ChatThemeLook, depth: number, dark: boolean): string {
  return dark ? mix(mix(look.bg[0], look.bg[1], depth), look.accent, 0.17 - 0.08 * depth) : mix(look.bg[1], look.accent, 0.08 + 0.16 * depth);
}

/** One ridge as a path in a box 100 wide and [boxHeight] tall, standing on the bottom edge. */
export function ridgePath(ridge: Ridge, boxHeight: number): string {
  const last = ridge.h.length - 1;
  const pts = ridge.h.map((v, i) => [n2((i / last) * 100), n2(boxHeight - v * ridge.height)] as const);
  let d = `M0 ${boxHeight}L${pts[0][0]} ${pts[0][1]}`;
  if (ridge.shape === 'peaks') for (const [x, y] of pts.slice(1)) d += `L${x} ${y}`;
  else if (ridge.shape === 'steps') {
    // Flat roofs: each height holds until the next one starts.
    const w = 100 / ridge.h.length;
    d = `M0 ${boxHeight}`;
    ridge.h.forEach((v, i) => {
      const y = n2(boxHeight - v * ridge.height);
      d += `L${n2(i * w)} ${y}L${n2((i + 1) * w)} ${y}`;
    });
  } else {
    // A curve through the midpoints, bending at each height.
    for (let i = 0; i < last; i++) d += `Q${pts[i][0]} ${pts[i][1]} ${n2((pts[i][0] + pts[i + 1][0]) / 2)} ${n2((pts[i][1] + pts[i + 1][1]) / 2)}`;
    d += `L${pts[last][0]} ${pts[last][1]}`;
  }
  return `${d}L100 ${boxHeight}Z`;
}

/** The same sequence in the web app and the Android app, so both scatter a theme's marks identically. */
export function scatter(seed: number, count: number): { x: number; y: number; s: number; t: number }[] {
  let state = seed >>> 0;
  const next = () => {
    state = (Math.imul(state, 1664525) + 1013904223) >>> 0;
    return state / 4294967296;
  };
  return Array.from({ length: count }, () => ({ x: next(), y: next(), s: next(), t: next() }));
}

export const MOTE_TILE = 600;

function motesTile(scene: ChatScene, look: ChatThemeLook, dark: boolean): string | null {
  const m = scene.motes;
  const strength = m ? m.alpha[dark ? 0 : 1] : 0;
  if (!m || strength <= 0) return null;
  const ink = m.kind === 'star' && dark ? WHITE : look.accent;
  const mark = (cx: number, cy: number, size: number, t: number) => {
    const a = n2(strength * (0.45 + 0.55 * t));
    switch (m.kind) {
      case 'sparkle':
        return `<path d="M${cx} ${n2(cy - size)}Q${cx} ${cy} ${n2(cx + size)} ${cy}Q${cx} ${cy} ${cx} ${n2(cy + size)}Q${cx} ${cy} ${n2(cx - size)} ${cy}Q${cx} ${cy} ${cx} ${n2(cy - size)}Z" fill="${ink}" fill-opacity="${a}"/>`;
      case 'petal':
        return `<ellipse cx="${cx}" cy="${cy}" rx="${size}" ry="${n2(size * 0.55)}" transform="rotate(${Math.round(t * 360)} ${cx} ${cy})" fill="${ink}" fill-opacity="${a}"/>`;
      case 'ring':
        return `<circle cx="${cx}" cy="${cy}" r="${size}" fill="none" stroke="${ink}" stroke-opacity="${a}" stroke-width="1.2"/>`;
      default:
        return `<circle cx="${cx}" cy="${cy}" r="${size}" fill="${ink}" fill-opacity="${a}"/>`;
    }
  };
  const marks = scatter(m.seed, m.count).flatMap(({ x, y, s, t }) => {
    const [cx, cy, size] = [n2(x * MOTE_TILE), n2(y * MOTE_TILE), n2(m.size[0] + (m.size[1] - m.size[0]) * s)];
    // A mark that crosses the tile's edge is drawn again on the far side, so it isn't cut where the tile repeats.
    const wrap = (c: number) => (c < size ? [c, c + MOTE_TILE] : c > MOTE_TILE - size ? [c, c - MOTE_TILE] : [c]);
    return wrap(cx).flatMap((px) => wrap(cy).map((py) => mark(px, py, size, t)));
  });
  return svgUrl(marks.join(''), MOTE_TILE, MOTE_TILE);
}

/**
 * The CSS for one side of a theme's background, front to back: its ridges, the sun or moon behind them, the
 * scattered marks, any washes of colour, a soft glow of the accent at the top, and the gradient. [scale] shrinks the
 * whole scene for the small previews in the theme picker.
 */
export function themeBackground(t: ChatTheme, dark: boolean, scale = 1): string | undefined {
  const look = dark ? t.dark : t.light;
  if (!look) return undefined;
  const px = (v: number) => `${n2(v * scale)}px`;
  const scene = t.scene ?? {};
  const layers: string[] = [];
  if (scene.ridges?.length) {
    const box = Math.max(...scene.ridges.map((r) => r.height));
    const paths = scene.ridges.map((r) => `<path d="${ridgePath(r, box)}" fill="${ridgeColor(look, r.depth, dark)}"/>`).join('');
    const across = scene.span ? `${px(scene.span)} ${px(box)} repeat-x` : `100% ${px(box)} no-repeat`;
    layers.push(`${svgUrl(paths, 100, box, 'preserveAspectRatio="none"')} left bottom / ${across}`);
  }
  if (scene.orb) {
    const { x, top, bottom, r } = scene.orb;
    const at = `${n2(x * 100)}% ${top != null ? px(top) : `calc(100% - ${px(bottom ?? 0)})`}`;
    const colour = dark ? look.accent : mix(t.mine[0], WHITE, 0.25);
    layers.push(`radial-gradient(circle at ${at}, ${alpha(colour, dark ? 0.92 : 0.8)} 0 ${px(r)}, transparent ${px(r + 0.8)})`);
    layers.push(`radial-gradient(circle at ${at}, ${alpha(colour, dark ? 0.3 : 0.26)} 0, transparent ${px(r * 3.4)})`);
  }
  const motes = motesTile(scene, look, dark);
  if (motes) layers.push(`${motes} 0 0 / ${px(MOTE_TILE)} ${px(MOTE_TILE)} repeat`);
  for (const g of scene.glows ?? []) {
    layers.push(`radial-gradient(ellipse ${n2(g.rx * 100)}% ${px(g.ry)} at ${n2(g.x * 100)}% ${px(g.top)}, ${alpha(t.mine[g.color], g.alpha[dark ? 0 : 1])}, transparent 72%)`);
  }
  layers.push(`radial-gradient(120% 55% at 50% 0%, ${alpha(look.accent, dark ? 0.18 : 0.12)}, transparent 70%)`);
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

const EMPTY: ChatCustom = { theme: 'default', nicknames: {}, quickReaction: DEFAULT_REACTION, pinned: [], disappearAfter: 0 };

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
  if (e.disappearAfter != null) {
    next = { ...next, disappearAfter: (DISAPPEARING as readonly number[]).includes(e.disappearAfter) ? e.disappearAfter : 0 };
  }
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
  if (e.disappearAfter != null) {
    return e.disappearAfter > 0
      ? `${who} turned on disappearing messages. New messages disappear after ${disappearingLabel(e.disappearAfter)}.`
      : `${who} turned off disappearing messages`;
  }
  return `${who} changed the chat`;
}
