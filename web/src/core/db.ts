import { openDB, type DBSchema, type IDBPDatabase } from 'idb';
import type { ObjectDto, ThreadDto, UserDto } from './types';

/** A pinned identity key (trust on first use) and whether we checked it in person (QR / safety number). */
export interface Pin {
  signPk: string;
  keyVersion: number;
  verified: boolean;
  /** Set when the server starts serving a different key; cleared when the user acknowledges it. */
  changedFrom?: string | null;
  /** Earlier identity keys, so content signed before a key reset still verifies. */
  history?: string[];
}

interface AccountDb extends DBSchema {
  kv: { key: string; value: unknown };
  objects: { key: string; value: ObjectDto };
  users: { key: string; value: UserDto };
  threads: { key: string; value: ThreadDto };
  pins: { key: string; value: Pin };
}

/**
 * Per-account local cache: the ciphertext objects the server synced to us (decrypted only in memory), users, chats,
 * pinned keys. Deleted on sign-out.
 */
export class LocalDb {
  private constructor(private readonly db: IDBPDatabase<AccountDb>) {}

  static async open(userId: string): Promise<LocalDb> {
    const db = await openDB<AccountDb>(`aezgram-${userId}`, 1, {
      upgrade(database) {
        database.createObjectStore('kv');
        database.createObjectStore('objects', { keyPath: 'id' });
        database.createObjectStore('users', { keyPath: 'id' });
        database.createObjectStore('threads', { keyPath: 'id' });
        database.createObjectStore('pins');
      },
    });
    return new LocalDb(db);
  }

  static async destroy(userId: string) {
    const { deleteDB } = await import('idb');
    await deleteDB(`aezgram-${userId}`);
  }

  get<T>(key: string): Promise<T | undefined> {
    return this.db.get('kv', key) as Promise<T | undefined>;
  }

  set(key: string, value: unknown) {
    return this.db.put('kv', value, key);
  }

  allObjects() {
    return this.db.getAll('objects');
  }

  allUsers() {
    return this.db.getAll('users');
  }

  allThreads() {
    return this.db.getAll('threads');
  }

  async allPins(): Promise<Record<string, Pin>> {
    const tx = this.db.transaction('pins');
    const out: Record<string, Pin> = {};
    let cursor = await tx.store.openCursor();
    while (cursor) {
      out[cursor.key as string] = cursor.value;
      cursor = await cursor.continue();
    }
    return out;
  }

  setPin(userId: string, pin: Pin) {
    return this.db.put('pins', pin, userId);
  }

  async apply(objects: ObjectDto[], deleted: string[], users: UserDto[], threads: ThreadDto[], removedThreads: string[]) {
    const tx = this.db.transaction(['objects', 'users', 'threads'], 'readwrite');
    const os = tx.objectStore('objects');
    for (const object of objects) os.put(object);
    for (const id of deleted) os.delete(id);
    for (const user of users) tx.objectStore('users').put(user);
    for (const thread of threads) tx.objectStore('threads').put(thread);
    for (const id of removedThreads) tx.objectStore('threads').delete(id);
    await tx.done;
  }

  putObject(object: ObjectDto) {
    return this.db.put('objects', object);
  }

  deleteObject(id: string) {
    return this.db.delete('objects', id);
  }

  close() {
    this.db.close();
  }
}
