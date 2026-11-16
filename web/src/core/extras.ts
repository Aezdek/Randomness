// The smaller things a chat can hold: files, shared lists, the day's question, a countdown, arrivals. All of it
// arrives from other people's apps, so everything here cuts what it is given down to what an honest app would send.
// Mirrors shared/client/.../Extras.kt; the tests on both sides use the same cases.
import type { DailyAnswer, ListChange, SharedFile, SharedList } from './payloads';
import { displayText, isBlobId } from './safe';

export const plausibleTime = (at: unknown): at is number => typeof at === 'number' && Number.isFinite(at) && at >= 0 && at <= 253_402_300_799_999;

// ----------------------------------------------------------------------------------------------------------- files

/** The largest file either app sends: it is decrypted in one piece in a browser. */
export const MAX_FILE_BYTES = 250 * 1024 * 1024;
export const MAX_FILE_NAME = 120;

/**
 * A file's name as it may be shown and saved: no folders, no control characters, and none of the invisible marks that
 * can make "photo\u202Egpj.exe" read as "photoexe.jpg". Never empty, never only dots, at most MAX_FILE_NAME characters
 * with the ending kept.
 */
export function safeFileName(name: unknown): string {
  let clean = (typeof name === 'string' ? name : '')
    .split('/').pop()!.split('\\').pop()!
    .replace(/[\p{Cc}\p{Cf}\u2028\u2029]/gu, '')
    .replace(/[<>:"|?*]/g, '')
    .trim().replace(/^\.+|\.+$/g, '').trim();
  if (!clean) return 'file';
  if (clean.length > MAX_FILE_NAME) {
    const dot = clean.lastIndexOf('.');
    const ending = dot >= 0 ? clean.slice(dot + 1) : '';
    clean = ending && ending.length <= 10
      ? `${clean.slice(0, MAX_FILE_NAME - ending.length - 1).replace(/[. ]+$/, '')}.${ending}`
      : clean.slice(0, MAX_FILE_NAME);
  }
  return clean;
}

/** Kinds of file that can run or act when opened: the card says to open them only from people you trust. */
const RISKY = new Set(['apk', 'exe', 'msi', 'bat', 'cmd', 'com', 'scr', 'ps1', 'vbs', 'js', 'jar', 'sh', 'app', 'dmg', 'pkg', 'lnk', 'html', 'htm', 'svg', 'docm', 'xlsm']);
export const riskyFile = (name: string) => name.includes('.') && RISKY.has(name.slice(name.lastIndexOf('.') + 1).toLowerCase());

export interface SafeFile { name: string; blobId: string; key: string; size: number }
export function safeFile(raw: unknown): SafeFile | null {
  if (!raw || typeof raw !== 'object') return null;
  const { name, blobId, key, size } = raw as Partial<SharedFile>;
  if (!isBlobId(blobId) || typeof key !== 'string' || !key || key.length > 200) return null;
  if (typeof size !== 'number' || !Number.isFinite(size) || size < 0 || size > MAX_FILE_BYTES) return null;
  return { name: safeFileName(name), blobId, key, size };
}

export function sizeLabel(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${Math.floor(bytes / 1024)} KB`;
  if (bytes < 1024 ** 3) return `${(bytes / 1024 ** 2).toFixed(1)} MB`;
  return `${(bytes / 1024 ** 3).toFixed(1)} GB`;
}

// ----------------------------------------------------------------------------------------------------------- lists

export const MAX_LIST_ITEMS = 100;
export const MAX_LIST_ITEM = 200;
export const MAX_LIST_TITLE = 80;

export interface ListItem { id: string; text: string; done: boolean; by: string | null }
export interface ListState { title: string; items: ListItem[] }
export interface ListEdit { id: string; by: string; change: ListChange }

const item = (text: unknown) => displayText(typeof text === 'string' ? text.replace(/\n/g, ' ') : '', MAX_LIST_ITEM).trim();

export const listTitle = (list: SharedList | null | undefined) => item(list?.title).slice(0, MAX_LIST_TITLE) || 'List';

/** The list as it was sent, with everyone's changes applied in order. */
export function foldList(list: SharedList, author: string, edits: ListEdit[]): ListState {
  const items = new Map<string, ListItem>();
  (Array.isArray(list.items) ? list.items : []).slice(0, MAX_LIST_ITEMS).forEach((text, i) => {
    const t = item(text);
    if (t) items.set(String(i), { id: String(i), text: t, done: false, by: author });
  });
  for (const edit of edits) {
    const c = edit.change ?? {};
    if (typeof c.add === 'string') {
      const t = item(c.add);
      if (t && items.size < MAX_LIST_ITEMS && !items.has(edit.id)) items.set(edit.id, { id: edit.id, text: t, done: false, by: edit.by });
    }
    for (const [id, done] of [[c.done, true], [c.undone, false]] as const) {
      const it = typeof id === 'string' ? items.get(id) : undefined;
      if (it) items.set(it.id, { ...it, done, by: edit.by });
    }
    if (typeof c.remove === 'string') items.delete(c.remove);
  }
  return { title: listTitle(list), items: [...items.values()] };
}

// ------------------------------------------------------------------------------------------------ the day's question

export const MAX_ANSWER = 500;
export const MAX_QUESTION = 200;

/** The same list, in the same order, as Daily.QUESTIONS in Extras.kt. */
export const QUESTIONS = [
  'What made you smile today?',
  'What is a small thing you are looking forward to?',
  'What song has been stuck in your head lately?',
  'What is a meal you could eat every week?',
  'Where would you go if you could leave tomorrow?',
  'What is something you changed your mind about?',
  'What is your favourite memory of us?',
  'What did you want to be when you were ten?',
  'What is a film you can watch again and again?',
  'What is one thing you would like to learn this year?',
  'What does a perfect lazy day look like?',
  'What is the best gift you ever got?',
  'What smell takes you straight back somewhere?',
  'What are you proud of that you rarely mention?',
  'What is something you find beautiful that others overlook?',
  'Which season suits you best, and why?',
  'What is the kindest thing someone did for you lately?',
  'What would you do with a free afternoon and no phone?',
  'What is a habit you would like to pick up?',
  "What is a place that feels like home that isn't home?",
  'What was the last thing that made you laugh out loud?',
  'What food do you miss from when you were little?',
  'What is a question you wish people asked you more?',
  'What would you name a boat?',
  'What is your idea of a good morning?',
  'What book or show changed how you see things?',
  'What is something you are getting better at?',
  'Who do you admire, and what for?',
  'What is a tradition you would like us to start?',
  'What is one thing on your list for this month?',
  'What is the best advice you were ever given?',
  'What sound do you find calming?',
  'What would you cook to impress someone?',
  'What is a tiny luxury you love?',
  'What is something you kept from childhood?',
  'What do you wish you had more time for?',
  'What is the best thing about this week so far?',
  'If today had a title, what would it be?',
  'What is a word you love the sound of?',
  'What would you like to do together soon?',
];

/** Today's date where this device is ("2026-11-15"). */
export function dayOf(now = Date.now()): string {
  const d = new Date(now);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

export const isDay = (day: unknown): day is string => typeof day === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(day);

/** The question for a chat on a day: the same on every device, whatever its platform. */
export function questionFor(threadId: string, day: string): string {
  let h = 0;
  const s = threadId + day;
  for (let i = 0; i < s.length; i++) h = (Math.imul(h, 31) + s.charCodeAt(i)) & 0x7fffffff;
  return QUESTIONS[h % QUESTIONS.length];
}

export interface DailyState { question: string; answers: Record<string, string>; revealed: boolean }

/**
 * The day's question for a chat and who has answered. Each person's first answer of the day counts, and the first
 * answer's question is the question (so two apps with different lists still talk about the same one).
 */
export function dailyState(threadId: string, day: string, members: string[], answers: { author: string; daily: DailyAnswer }[]): DailyState {
  const today = answers.filter((a) => a.daily?.day === day && members.includes(a.author));
  const byUser: Record<string, string> = {};
  for (const a of today) {
    const text = displayText(a.daily.answer, MAX_ANSWER).trim();
    if (!(a.author in byUser) && text) byUser[a.author] = text;
  }
  const first = today[0]?.daily.question;
  const asked = typeof first === 'string' ? first.replace(/\n/g, ' ').trim().slice(0, MAX_QUESTION) : '';
  return { question: asked || questionFor(threadId, day), answers: byUser, revealed: members.length > 0 && members.every((m) => m in byUser) };
}

// ------------------------------------------------------------------------------------------------------ countdowns

export const MAX_COUNTDOWN_TITLE = 40;
export const countdownTitle = (raw: unknown) => (typeof raw === 'string' ? raw.replace(/\n/g, ' ').trim().slice(0, MAX_COUNTDOWN_TITLE) : '');

const localDay = (ms: number) => {
  const d = new Date(ms);
  return Date.UTC(d.getFullYear(), d.getMonth(), d.getDate()) / 86_400_000;
};

/** "12 days until Trip", "Trip is tomorrow", "Trip is today", "412 days since Trip"; null if there is none. */
export function countdownLabel(title: string, at: number, now = Date.now()): string | null {
  if (!title || !plausibleTime(at)) return null;
  const days = localDay(at) - localDay(now);
  if (days === 0) return `${title} is today`;
  if (days === 1) return `${title} is tomorrow`;
  if (days > 1) return `${days} days until ${title}`;
  if (days === -1) return `1 day since ${title}`;
  return `${-days} days since ${title}`;
}

// ---------------------------------------------------------------------------------------------------------- places

/** How near counts as there, in metres. */
export const ARRIVED_WITHIN = 150;
export const placeName = (raw: unknown) => (typeof raw === 'string' ? raw.replace(/\n/g, ' ').trim().slice(0, 40) : '') || 'their destination';

/** Metres between two points on Earth (haversine). */
export function distance(lat1: number, lng1: number, lat2: number, lng2: number): number {
  const rad = (d: number) => (d * Math.PI) / 180;
  const a = Math.sin(rad(lat2 - lat1) / 2) ** 2 + Math.cos(rad(lat1)) * Math.cos(rad(lat2)) * Math.sin(rad(lng2 - lng1) / 2) ** 2;
  return 2 * 6_371_000 * Math.asin(Math.sqrt(a));
}
