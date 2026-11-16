// Sending and reading the smaller things a chat can hold: files, nudges, shared lists, the day's question, countdowns
// and arrivals. The rules for what is shown live in extras.ts; this is the part that talks to the server.
import { sendChatEvent, sendMessage } from './compose';
import {
  countdownTitle, dailyState, dayOf, foldList, isDay, MAX_ANSWER, MAX_FILE_BYTES, MAX_LIST_ITEM, MAX_LIST_ITEMS, MAX_LIST_TITLE, placeName,
  questionFor, safeFile, safeFileName, sizeLabel, type DailyState, type ListEdit, type ListState,
} from './extras';
import { downloadBytes, uploadEncrypted } from './media';
import { Audience, MessageType, type MessagePayload } from './payloads';
import type { Index } from './selectors';
import { b64 } from '../crypto/sodium';
import type { Entity } from './store';
import type { ThreadDto } from './types';

// ----------------------------------------------------------------------------------------------------------- files

export async function sendFile(threadId: string, file: File, onProgress?: (fraction: number) => void) {
  if (file.size > MAX_FILE_BYTES) throw new Error(`Files can be up to ${sizeLabel(MAX_FILE_BYTES)}`);
  if (file.size === 0) throw new Error('That file is empty');
  const { blobId, key } = await uploadEncrypted(file, onProgress);
  const name = safeFileName(file.name);
  return sendMessage(threadId, { type: MessageType.FILE, text: name, file: { name, blobId, key: b64(key), size: file.size } }, { blobIds: [blobId] });
}

/** Decrypts a file and hands it to the browser to save under its cleaned name. It is never opened in this page. */
export async function saveFile(raw: unknown) {
  const file = safeFile(raw);
  if (!file) throw new Error('That file can’t be opened');
  const url = URL.createObjectURL(await downloadBytes(file.blobId, file.key));
  const a = document.createElement('a');
  a.href = url;
  a.download = file.name;
  a.rel = 'noopener';
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 60_000);
}

// ---------------------------------------------------------------------------------------------------------- nudges

/** How long before the same person can nudge the same chat again. */
export const NUDGE_EVERY = 30_000;
const lastNudge = new Map<string, number>();

export async function sendNudge(threadId: string) {
  const now = Date.now();
  if (now - (lastNudge.get(threadId) ?? 0) < NUDGE_EVERY) throw new Error('Give it a moment before nudging again');
  lastNudge.set(threadId, now);
  return sendMessage(threadId, { type: MessageType.NUDGE });
}

// ----------------------------------------------------------------------------------------------------------- lists

export async function sendList(threadId: string, title: string, items: string[]) {
  const t = title.trim().slice(0, MAX_LIST_TITLE);
  if (!t) throw new Error('Give the list a name');
  const clean = items.map((i) => i.trim().slice(0, MAX_LIST_ITEM)).filter(Boolean).slice(0, MAX_LIST_ITEMS);
  return sendMessage(threadId, { type: MessageType.LIST, text: t, list: { title: t, items: clean } }, { audience: Audience.THREAD });
}

const listEdits = (idx: Index, list: Entity<MessagePayload>): ListEdit[] =>
  (idx.messages[list.dto.threadId ?? ''] ?? [])
    .filter((m) => m.payload.type === MessageType.LIST_CHANGE && m.payload.replyTo === list.dto.id && m.payload.listChange)
    .map((m) => ({ id: m.dto.id, by: m.dto.authorId, change: m.payload.listChange! }));

export function listState(idx: Index, list: Entity<MessagePayload>): ListState {
  return foldList(list.payload.list ?? { title: '' }, list.dto.authorId, listEdits(idx, list));
}

export async function changeList(list: Entity<MessagePayload>, change: { add?: string; done?: string; undone?: string; remove?: string }) {
  if (change.add !== undefined) {
    change = { add: change.add.trim().slice(0, MAX_LIST_ITEM) };
    if (!change.add) return null;
  }
  return sendMessage(list.dto.threadId!, { type: MessageType.LIST_CHANGE, replyTo: list.dto.id, listChange: change }, { audience: Audience.THREAD });
}

// ------------------------------------------------------------------------------------------------ the day's question

const dailyAnswers = (idx: Index, threadId: string) =>
  (idx.messages[threadId] ?? []).filter((m) => m.payload.type === MessageType.DAILY && m.payload.daily && isDay(m.payload.daily.day)).map((m) => ({ author: m.dto.authorId, daily: m.payload.daily! }));

export function daily(idx: Index, thread: ThreadDto, day = dayOf()): DailyState {
  return dailyState(thread.id, day, thread.members.map((m) => m.userId), dailyAnswers(idx, thread.id));
}

export async function answerDaily(idx: Index, thread: ThreadDto, me: string, answer: string) {
  const day = dayOf();
  const state = daily(idx, thread, day);
  const text = answer.trim().slice(0, MAX_ANSWER);
  if (!text) throw new Error('Write an answer');
  if (me in state.answers) throw new Error('You already answered today');
  const question = state.question || questionFor(thread.id, day);
  return sendMessage(thread.id, { type: MessageType.DAILY, daily: { day, question, answer: text } }, { audience: Audience.THREAD });
}

// ------------------------------------------------------------------------------------------------------ countdowns

export const setCountdown = (threadId: string, title: string, at: number) => sendChatEvent(threadId, { countdownTitle: countdownTitle(title), countdownAt: at });
export const clearCountdown = (threadId: string) => sendChatEvent(threadId, { countdownTitle: '', countdownAt: 0 });

// -------------------------------------------------------------------------------------------------------- arrivals

export interface Place { name: string; lat: number; lng: number }
export interface ArrivalWatch { threadId: string; until: number; place: Place }

const PLACES = 'hush.places';
const WATCHES = 'hush.arrivals';
const read = <T,>(key: string): T[] => {
  try {
    const v = JSON.parse(localStorage.getItem(key) ?? '[]');
    return Array.isArray(v) ? v : [];
  } catch {
    return [];
  }
};
const write = (key: string, value: unknown[]) => {
  try {
    localStorage.setItem(key, JSON.stringify(value));
  } catch {
    // Private windows may refuse storage; arrivals then only last until the page closes.
  }
};

/** Places saved on this device to arrive at (home, work). They never leave it. */
export const savedPlaces = (): Place[] => read<Place>(PLACES).filter((p) => p && typeof p.name === 'string' && Number.isFinite(p.lat) && Number.isFinite(p.lng)).slice(0, 10);
export function savePlace(place: Place) {
  const name = place.name.trim().slice(0, 40);
  if (!name) throw new Error('Name the place');
  write(PLACES, [...savedPlaces().filter((p) => p.name !== name), { name, lat: place.lat, lng: place.lng }].slice(-10));
}
export const removePlace = (name: string) => write(PLACES, savedPlaces().filter((p) => p.name !== name));

export const arrivalWatches = (now = Date.now()): ArrivalWatch[] => read<ArrivalWatch>(WATCHES).filter((w) => w && w.until > now && w.place);
export const watchArrival = (watch: ArrivalWatch) => write(WATCHES, [...arrivalWatches().filter((w) => w.threadId !== watch.threadId), watch]);
export const dropArrival = (threadId: string) => write(WATCHES, arrivalWatches().filter((w) => w.threadId !== threadId));

export const sendArrived = (threadId: string, place: string) => sendMessage(threadId, { type: MessageType.ARRIVED, text: placeName(place) });
