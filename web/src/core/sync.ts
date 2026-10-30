import { isSelfSigned } from '../crypto/account';
import { childKey, openObject, unwrapKey } from '../crypto/object';
import { CryptoError, b64, fromUtf8, unb64 } from '../crypto/sodium';
import { wrapKey } from '../crypto/object';
import { api } from './api';
import { LocalDb, type Pin } from './db';
import { CHILD_KEYED, Kind, type HighlightPayload } from './payloads';
import { getState, useApp, type Entity } from './store';
import type { ObjectDto, SyncResponse, ThreadDto, UserDto, WrapTaskDto, WrapUpload } from './types';

/**
 * Keeps the local copy in step with the server: replays the change log from our cursor, verifies and decrypts what
 * arrives, pins identity keys, cascades deletions, and seals our content to newcomers (wrap tasks).
 */
class SyncEngine {
  private db: LocalDb | null = null;
  private cursor = 0;
  private running: Promise<void> | null = null;
  private again = false;
  /** Objects waiting for a parent, an author or a highlight key. */
  private pending = new Map<string, ObjectDto>();
  /** Keys for stories reachable through highlights (no wrap of their own after expiry). */
  private refKeys = new Map<string, Uint8Array>();

  async open(userId: string) {
    this.db = await LocalDb.open(userId);
    this.cursor = (await this.db.get<number>('cursor')) ?? 0;
    const [users, threads, pins, objects, seen] = await Promise.all([
      this.db.allUsers(),
      this.db.allThreads(),
      this.db.allPins(),
      this.db.allObjects(),
      this.db.get<Record<string, number>>('seenStories'),
    ]);
    useApp.setState({
      users: Object.fromEntries(users.map((u) => [u.id, u])),
      threads: Object.fromEntries(threads.map((t) => [t.id, t])),
      pins,
      seenStories: seen ?? {},
      initialSyncDone: objects.length > 0,
    });
    this.ingest(objects);
  }

  close() {
    this.db?.close();
    this.db = null;
    this.cursor = 0;
    this.pending.clear();
    this.refKeys.clear();
  }

  get local() {
    return this.db;
  }

  /** Coalesces concurrent requests: one sync runs at a time, and one more runs after it if anything asked. */
  sync(): Promise<void> {
    if (this.running) {
      this.again = true;
      return this.running;
    }
    this.running = (async () => {
      try {
        do {
          this.again = false;
          await this.syncOnce();
        } while (this.again);
      } finally {
        this.running = null;
      }
    })();
    return this.running;
  }

  private async syncOnce() {
    if (!this.db) return;
    useApp.setState({ syncing: true });
    try {
      let response: SyncResponse;
      do {
        response = await api.get<SyncResponse>(`/sync?cursor=${this.cursor}&limit=300`);
        await this.apply(response);
        this.cursor = response.cursor;
        await this.db.set('cursor', this.cursor);
      } while (response.hasMore);
      useApp.setState({ followRequests: response.followRequests, initialSyncDone: true });
      if (response.pendingWrapTasks > 0) await this.processWrapTasks();
    } finally {
      useApp.setState({ syncing: false });
    }
  }

  private async apply(response: SyncResponse) {
    if (!this.db) return;
    await this.db.apply(response.objects, response.deletedIds, response.users, response.threads, response.removedThreadIds);
    this.applyUsers(response.users);
    if (response.threads.length || response.removedThreadIds.length) {
      useApp.setState((s) => {
        const threads = { ...s.threads };
        for (const thread of response.threads) threads[thread.id] = thread;
        for (const id of response.removedThreadIds) delete threads[id];
        return { threads };
      });
      for (const id of response.removedThreadIds) this.removeWhere((e) => e.dto.threadId === id);
    }
    for (const id of response.deletedIds) this.remove(id);
    this.ingest(response.objects);
  }

  /** Pins identity keys on first sight and flags changes; refuses keys that are not self-signed. */
  private applyUsers(users: UserDto[]) {
    if (!users.length) return;
    const { pins } = getState();
    const nextPins = { ...pins };
    const accepted: UserDto[] = [];
    for (const user of users) {
      if (!user.deleted && !isSelfSigned({ signPk: unb64(user.signPk), boxPk: unb64(user.boxPk), boxPkSig: unb64(user.boxPkSig) })) {
        console.warn(`Ignoring ${user.username}: box key not signed by identity key`);
        continue;
      }
      accepted.push(user);
      const pin = nextPins[user.id];
      if (!pin) {
        nextPins[user.id] = { signPk: user.signPk, keyVersion: user.keyVersion, verified: false, history: [] };
      } else if (pin.signPk !== user.signPk) {
        const history = [...(pin.history ?? []), pin.signPk];
        nextPins[user.id] = { signPk: user.signPk, keyVersion: user.keyVersion, verified: false, changedFrom: pin.signPk, history };
      }
      if (nextPins[user.id] !== pin) void this.db?.setPin(user.id, nextPins[user.id]);
    }
    useApp.setState((s) => {
      const next = { ...s.users };
      for (const user of accepted) next[user.id] = user;
      const presence = { ...s.presence };
      for (const user of accepted) presence[user.id] = !!user.online;
      return { users: next, pins: nextPins, presence };
    });
  }

  /** Every identity key we have seen for a user, newest first: old content stays verifiable after a key reset. */
  private signKeysOf(userId: string): Uint8Array[] {
    const { users, pins, me, keys } = getState();
    if (userId === me?.id && keys) return [keys.signPk];
    const out: string[] = [];
    const user = users[userId];
    if (user) out.push(user.signPk);
    const pin: Pin | undefined = pins[userId];
    if (pin) out.push(pin.signPk, ...(pin.history ?? []));
    return [...new Set(out)].map(unb64);
  }

  /** Decrypts what it can, retrying the rest as their parents arrive. */
  ingest(dtos: ObjectDto[]) {
    for (const dto of dtos) this.pending.set(dto.id, dto);
    const decoded: Record<string, Entity> = {};
    let progress = true;
    while (progress && this.pending.size) {
      progress = false;
      for (const [id, dto] of [...this.pending]) {
        const result = this.decode(dto, decoded);
        if (result === 'wait') continue;
        this.pending.delete(id);
        progress = true;
        if (result !== 'fail') decoded[id] = result;
      }
    }
    if (Object.keys(decoded).length) useApp.setState((s) => ({ entities: { ...s.entities, ...decoded } }));
  }

  private decode(dto: ObjectDto, batch: Record<string, Entity>): Entity | 'wait' | 'fail' {
    const { keys, entities } = getState();
    if (!keys) return 'wait';
    let key: Uint8Array | undefined;
    try {
      if (dto.wrap) {
        key = unwrapKey(unb64(dto.wrap), keys, unb64(dto.commitment));
      } else if (CHILD_KEYED.has(dto.kind) && dto.parentId) {
        const parent = batch[dto.parentId] ?? entities[dto.parentId];
        if (!parent) return 'wait';
        key = childKey(parent.key, dto.id);
      } else {
        key = this.refKeys.get(dto.id);
        if (!key) return 'wait';
      }
      const signKeys = this.signKeysOf(dto.authorId);
      if (!signKeys.length) return 'wait';
      let plain: Uint8Array | null = null;
      let lastError: unknown = null;
      for (const signPk of signKeys) {
        try {
          plain = openObject(headerOf(dto), unb64(dto.payload), unb64(dto.commitment), unb64(dto.signature), signPk, key);
          break;
        } catch (e) {
          lastError = e;
        }
      }
      if (!plain) throw lastError;
      const payload = JSON.parse(fromUtf8(plain));
      if (dto.kind === Kind.HIGHLIGHT) {
        for (const story of (payload as HighlightPayload).stories ?? []) {
          this.refKeys.set(story.id, unb64(story.key));
          void this.fetchReferenced(story.id);
        }
      }
      return { dto, key, payload };
    } catch (e) {
      if (e instanceof CryptoError || e instanceof SyntaxError) {
        console.warn(`Dropping ${dto.kind} ${dto.id}: ${(e as Error).message}`);
        return 'fail';
      }
      throw e;
    }
  }

  /** A story inside a highlight may have no wrap for us any more; fetch it through the highlight. */
  private async fetchReferenced(id: string) {
    if (getState().entities[id] || this.pending.has(id)) return;
    try {
      const dto = await api.get<ObjectDto>(`/objects/${id}`);
      await this.db?.putObject(dto);
      this.ingest([dto]);
    } catch {
      /* gone */
    }
  }

  /** Adds an object we just created (we already know its key). */
  add(dto: ObjectDto, key: Uint8Array, payload: unknown) {
    void this.db?.putObject(dto);
    this.pending.delete(dto.id);
    useApp.setState((s) => ({ entities: { ...s.entities, [dto.id]: { dto, key, payload } } }));
    this.ingest([]);
  }

  remove(id: string) {
    const doomed = new Set<string>([id]);
    const { entities } = getState();
    let grew = true;
    while (grew) {
      grew = false;
      for (const e of Object.values(entities)) {
        if (e.dto.parentId && doomed.has(e.dto.parentId) && !doomed.has(e.dto.id)) {
          doomed.add(e.dto.id);
          grew = true;
        }
      }
    }
    for (const doomedId of doomed) {
      void this.db?.deleteObject(doomedId);
      this.pending.delete(doomedId);
    }
    useApp.setState((s) => {
      const next = { ...s.entities };
      for (const doomedId of doomed) delete next[doomedId];
      return { entities: next };
    });
  }

  private removeWhere(predicate: (e: Entity) => boolean) {
    for (const e of Object.values(getState().entities)) if (predicate(e)) this.remove(e.dto.id);
  }

  markStorySeen(id: string) {
    const seen = { ...getState().seenStories, [id]: Date.now() };
    useApp.setState({ seenStories: seen });
    void this.db?.set('seenStories', seen);
  }

  async acknowledgeKeyChange(userId: string, verified = false) {
    const pin = getState().pins[userId];
    if (!pin) return;
    const next: Pin = { ...pin, changedFrom: null, verified };
    useApp.setState((s) => ({ pins: { ...s.pins, [userId]: next } }));
    await this.db?.setPin(userId, next);
  }

  /** Our clients' half of the "new follower / new member" handshake: seal our content keys to them. */
  async processWrapTasks() {
    const { keys, users } = getState();
    if (!keys) return;
    const tasks = await api.get<WrapTaskDto[]>('/wrap-tasks');
    for (const task of tasks) {
      const recipient = users[task.recipientId];
      if (!recipient || recipient.deleted) {
        await api.post(`/wrap-tasks/${task.id}/done`);
        continue;
      }
      const boxPk = unb64(recipient.boxPk);
      const wraps: WrapUpload[] = [];
      for (const obj of task.objects) {
        if (!obj.wrap) continue;
        try {
          const key = unwrapKey(unb64(obj.wrap), keys, unb64(obj.commitment));
          wraps.push({ objectId: obj.id, recipientId: recipient.id, sealed: b64(wrapKey(key, boxPk)) });
        } catch {
          /* not ours to share */
        }
      }
      for (let i = 0; i < wraps.length; i += 500) await api.post('/wraps', { wraps: wraps.slice(i, i + 500) });
      await api.post(`/wrap-tasks/${task.id}/done`);
    }
  }

  upsertThread(thread: ThreadDto) {
    useApp.setState((s) => ({ threads: { ...s.threads, [thread.id]: thread } }));
    void this.db?.apply([], [], [], [thread], []);
  }
}

export function headerOf(dto: ObjectDto) {
  return {
    id: dto.id,
    kind: dto.kind,
    authorId: dto.authorId,
    parentId: dto.parentId ?? null,
    threadId: dto.threadId ?? null,
    audience: dto.audience,
    createdAt: dto.createdAt,
    expiresAt: dto.expiresAt ?? null,
    version: dto.version,
    blobIds: dto.blobIds,
    refIds: dto.refIds,
  };
}

export const engine = new SyncEngine();
