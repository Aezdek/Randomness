import { api } from './api';

// A small in-memory cache for GET requests whose answers change rarely (follower lists and the like), the way
// Instagram's app keeps a profile around: show what we have at once, ask the server again only when it is old or
// something we did changed it. Requests already on their way are shared rather than sent twice.

type Entry = { at: number; value: unknown };

const entries = new Map<string, Entry>();
const pending = new Map<string, Promise<unknown>>();

/** How long an answer is used without asking again. */
export const FRESH_MS = 2 * 60_000;

/** The cached answer for [path], however old, or undefined. */
export function peekCache<T>(path: string): T | undefined {
  return entries.get(path)?.value as T | undefined;
}

/** [path]'s answer: the cached one while fresh, otherwise (or with [force]) a new one from the server. */
export function cachedGet<T>(path: string, force = false): Promise<T> {
  const inFlight = pending.get(path);
  if (inFlight) return inFlight as Promise<T>;
  const entry = entries.get(path);
  if (!force && entry && Date.now() - entry.at < FRESH_MS) return Promise.resolve(entry.value as T);
  const request = api
    .get<T>(path)
    .then((value) => {
      entries.set(path, { at: Date.now(), value });
      return value;
    })
    .finally(() => pending.delete(path));
  pending.set(path, request);
  return request;
}

/** Marks every cached answer whose path starts with [prefix] as old, so the next ask goes to the server. */
export function invalidateCache(prefix = '') {
  for (const [path, entry] of entries) if (path.startsWith(prefix)) entries.set(path, { ...entry, at: 0 });
}

/** Forgets everything (on sign-out). */
export function clearCache() {
  entries.clear();
  pending.clear();
}
