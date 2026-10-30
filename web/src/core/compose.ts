import { childKey, newContentKey, sealObject, wrapKey } from '../crypto/object';
import { b64, random, unb64, utf8 } from '../crypto/sodium';
import { api } from './api';
import {
  Audience,
  CHILD_KEYED,
  Kind,
  type AnswerPayload,
  type CollectionPayload,
  type CommentPayload,
  type EpochPayload,
  type HighlightPayload,
  type LikePayload,
  type MessagePayload,
  type NotePayload,
  type PostPayload,
  type ProfilePayload,
  type ReactionPayload,
  type ReelPayload,
  type SettingsPayload,
  type StoryPayload,
  type ThreadPayload,
  type VotePayload,
} from './payloads';
import { buildIndex } from './selectors';
import { getState, useApp, type Entity } from './store';
import { engine } from './sync';
import type { ObjectDto, ObjectUpload, ThreadDto, WrapDto } from './types';

const DAY = 24 * 60 * 60 * 1000;

export const newObjectId = (): string => b64(random(16));

function me() {
  const { me, keys } = getState();
  if (!me || !keys) throw new Error('Not signed in');
  return { me, keys };
}

/** Everyone who may read something with [audience], always including us. */
export function recipientsFor(audience: string, explicit: string[] = []): string[] {
  const { me: self } = me();
  const { users } = getState();
  const live = Object.values(users).filter((u) => !u.deleted && !u.blockedByMe);
  let out: string[];
  switch (audience) {
    case Audience.MEMBERS:
      out = live.map((u) => u.id);
      break;
    case Audience.FOLLOWERS:
      out = live.filter((u) => u.followsMe === 'following').map((u) => u.id);
      break;
    case Audience.CLOSE_FRIENDS: {
      const close = new Set(settings().closeFriends ?? []);
      out = live.filter((u) => u.followsMe === 'following' && close.has(u.id)).map((u) => u.id);
      break;
    }
    case Audience.SELF:
      out = [];
      break;
    default:
      out = explicit;
  }
  return [...new Set([self.id, ...out])];
}

/** The audience for something we publish to our profile: everyone if public, followers if private. */
export const profileAudience = () => (getState().me?.isPrivate ? Audience.FOLLOWERS : Audience.MEMBERS);

function wrapsFor(key: Uint8Array, recipients: string[]): WrapDto[] {
  const { users, keys } = getState();
  return recipients.flatMap((id) => {
    const boxPk = id === getState().me?.id ? keys!.boxPk : users[id] ? unb64(users[id].boxPk) : null;
    return boxPk ? [{ recipientId: id, sealed: b64(wrapKey(key, boxPk)) }] : [];
  });
}

interface PublishOptions<P> {
  kind: string;
  audience: string;
  payload: P;
  parentId?: string | null;
  threadId?: string | null;
  expiresAt?: number | null;
  blobIds?: string[];
  refIds?: string[];
  recipients?: string[];
  id?: string;
  key?: Uint8Array;
}

function seal<P>(opts: PublishOptions<P>, version = 1, createdAt = Date.now()): { upload: ObjectUpload; key: Uint8Array } {
  const { me: self, keys } = me();
  const id = opts.id ?? newObjectId();
  let key = opts.key;
  if (!key) {
    if (CHILD_KEYED.has(opts.kind)) {
      const parent = opts.parentId ? getState().entities[opts.parentId] : undefined;
      if (!parent) throw new Error('That is no longer available');
      key = childKey(parent.key, id);
    } else {
      key = newContentKey();
    }
  }
  const header = {
    id,
    kind: opts.kind,
    authorId: self.id,
    parentId: opts.parentId ?? null,
    threadId: opts.threadId ?? null,
    audience: opts.audience,
    createdAt,
    expiresAt: opts.expiresAt ?? null,
    version,
    blobIds: opts.blobIds ?? [],
    refIds: opts.refIds ?? [],
  };
  const sealed = sealObject(header, utf8(JSON.stringify(opts.payload)), key, keys);
  const childKeyed = opts.audience === Audience.PARENT || opts.kind === Kind.MESSAGE;
  const wraps = childKeyed ? [] : wrapsFor(key, opts.recipients ?? recipientsFor(opts.audience));
  return {
    key,
    upload: {
      ...header,
      payload: b64(sealed.payload),
      commitment: b64(sealed.commitment),
      signature: b64(sealed.signature),
      wraps,
    },
  };
}

export async function publish<P>(opts: PublishOptions<P>): Promise<Entity<P>> {
  const { upload, key } = seal(opts);
  const dto = await api.post<ObjectDto>('/objects', upload);
  engine.add(dto, key, opts.payload);
  return { dto, key, payload: opts.payload };
}

/** A new version with the same key; only the content changes. */
export async function edit<P>(entity: Entity<P>, payload: P, extra: { blobIds?: string[]; refIds?: string[] } = {}): Promise<Entity<P>> {
  const { dto } = entity;
  const { upload } = seal(
    {
      kind: dto.kind,
      audience: dto.audience,
      payload,
      parentId: dto.parentId,
      threadId: dto.threadId,
      expiresAt: dto.expiresAt,
      blobIds: extra.blobIds ?? dto.blobIds,
      refIds: extra.refIds ?? dto.refIds,
      id: dto.id,
      key: entity.key,
      recipients: [],
    },
    dto.version + 1,
    dto.createdAt,
  );
  const updated = await api.put<ObjectDto>(`/objects/${dto.id}`, { ...upload, wraps: [] });
  engine.add(updated, entity.key, payload);
  return { dto: updated, key: entity.key, payload };
}

export async function deleteObject(id: string) {
  await api.delete(`/objects/${id}`);
  engine.remove(id);
}

export async function setArchived(id: string, archived: boolean) {
  await api.post(`/objects/${id}/archive`, { archived });
  const entity = getState().entities[id];
  if (entity) engine.add({ ...entity.dto, archived }, entity.key, entity.payload);
}

// ------------------------------------------------------------------------------------------------ profile & settings

const index = () => buildIndex(getState());

export function settings(): SettingsPayload {
  return (index().settings?.payload as SettingsPayload | undefined) ?? {};
}

export async function saveProfile(payload: ProfilePayload) {
  const existing = index().profiles[me().me.id];
  const blobIds = [payload.avatar?.blobId].filter(Boolean) as string[];
  if (existing) return edit(existing as Entity<ProfilePayload>, payload, { blobIds });
  return publish({ kind: Kind.PROFILE, audience: Audience.MEMBERS, payload, blobIds });
}

export async function saveSettings(patch: Partial<SettingsPayload>) {
  const existing = index().settings as Entity<SettingsPayload> | null;
  const payload = { ...(existing?.payload ?? {}), ...patch };
  if (existing) return edit(existing, payload);
  return publish({ kind: Kind.SETTINGS, audience: Audience.SELF, payload });
}

// ------------------------------------------------------------------------------------------------------- content

export const mediaBlobIds = (media: { blobId: string; poster?: { blobId: string } | null }[]) =>
  media.flatMap((m) => [m.blobId, ...(m.poster ? [m.poster.blobId] : [])]);

export async function createPost(payload: PostPayload) {
  const entity = await publish({ kind: Kind.POST, audience: profileAudience(), payload, blobIds: mediaBlobIds(payload.media) });
  await notifyMentions(entity.dto.id, payload.caption ?? '', payload.taggedUserIds ?? []);
  return entity;
}

export async function createReel(payload: ReelPayload) {
  const media = [payload.video, ...(payload.cover ? [payload.cover] : [])];
  const entity = await publish({ kind: Kind.REEL, audience: profileAudience(), payload, blobIds: mediaBlobIds(media) });
  await notifyMentions(entity.dto.id, payload.caption ?? '', payload.taggedUserIds ?? []);
  return entity;
}

export async function createStory(payload: StoryPayload, closeFriends: boolean) {
  const media = [payload.media, payload.drawing].filter(Boolean) as { blobId: string }[];
  return publish({
    kind: Kind.STORY,
    audience: closeFriends ? Audience.CLOSE_FRIENDS : profileAudience(),
    payload,
    blobIds: mediaBlobIds(media),
    expiresAt: Date.now() + DAY,
  });
}

export async function createHighlight(payload: HighlightPayload) {
  return publish({
    kind: Kind.HIGHLIGHT,
    audience: profileAudience(),
    payload,
    refIds: payload.stories.map((s) => s.id),
    blobIds: payload.cover ? mediaBlobIds([payload.cover]) : [],
  });
}

export async function updateHighlight(entity: Entity<HighlightPayload>, payload: HighlightPayload) {
  return edit(entity, payload, { refIds: payload.stories.map((s) => s.id), blobIds: payload.cover ? mediaBlobIds([payload.cover]) : [] });
}

export async function createNote(payload: NotePayload) {
  const existing = index().notes[me().me.id];
  if (existing) await deleteObject(existing.dto.id).catch(() => undefined);
  return publish({ kind: Kind.NOTE, audience: profileAudience(), payload, expiresAt: Date.now() + DAY });
}

export async function comment(parentId: string, text: string) {
  const entity = await publish<CommentPayload>({ kind: Kind.COMMENT, audience: Audience.PARENT, payload: { text }, parentId });
  await notifyMentions(rootOf(parentId), text, []);
  return entity;
}

export async function like(parentId: string, emoji?: string) {
  return publish<LikePayload>({ kind: Kind.LIKE, audience: Audience.PARENT, payload: emoji ? { emoji } : {}, parentId });
}

export async function unlike(parentId: string) {
  const mine = index().children[parentId]?.find((e) => e.dto.kind === Kind.LIKE && e.dto.authorId === me().me.id);
  if (mine) await deleteObject(mine.dto.id);
}

export async function vote(storyId: string, payload: VotePayload) {
  return publish({ kind: Kind.VOTE, audience: Audience.PARENT, payload, parentId: storyId });
}

/** Question-sticker answers go to the story's author only. */
export async function answer(story: Entity, payload: AnswerPayload) {
  return publish({
    kind: Kind.ANSWER,
    audience: Audience.DIRECT,
    payload,
    refIds: [story.dto.id],
    recipients: [me().me.id, story.dto.authorId],
  });
}

function rootOf(id: string): string {
  const { entities } = getState();
  let current = entities[id];
  while (current?.dto.parentId && entities[current.dto.parentId]) current = entities[current.dto.parentId];
  return current?.dto.id ?? id;
}

/** Tells @mentioned and tagged people, as a direct activity item only they can read. */
async function notifyMentions(objectId: string, text: string, tagged: string[]) {
  const { users } = getState();
  const self = me().me.id;
  const byName = new Map(Object.values(users).map((u) => [u.username.toLowerCase(), u.id]));
  const mentioned = [...text.matchAll(/@([a-z0-9._]{2,30})/gi)].map((m) => byName.get(m[1].toLowerCase())).filter(Boolean) as string[];
  const notify = new Map<string, 'mention' | 'tag'>();
  for (const id of mentioned) notify.set(id, 'mention');
  for (const id of tagged) notify.set(id, 'tag');
  notify.delete(self);
  for (const [userId, type] of notify) {
    await publish({
      kind: Kind.ACTIVITY,
      audience: Audience.DIRECT,
      payload: { type, objectId, text: text.slice(0, 140) },
      recipients: [self, userId],
      refIds: [],
    }).catch(() => undefined);
  }
}

// ---------------------------------------------------------------------------------------------------- collections

export async function toggleSaved(postId: string) {
  const collections = index().collections;
  const saved = (collections.find((c) => (c.payload as CollectionPayload).isDefault) as Entity<CollectionPayload> | undefined) ?? null;
  if (!saved) {
    return publish<CollectionPayload>({ kind: Kind.COLLECTION, audience: Audience.SELF, payload: { name: 'Saved', postIds: [postId], isDefault: true } });
  }
  const ids = saved.payload.postIds.includes(postId) ? saved.payload.postIds.filter((id) => id !== postId) : [postId, ...saved.payload.postIds];
  return edit(saved, { ...saved.payload, postIds: ids });
}

// ------------------------------------------------------------------------------------------------------- social

async function refreshUser(id: string) {
  const user = await api.get<import('./types').UserDto>(`/users/${id}`);
  // Through the engine, so the box key's signature is checked and identity changes are pinned, as in a sync.
  await engine.upsertUser(user);
  return user;
}

export async function follow(id: string) {
  await api.post(`/follows/${id}`);
  await refreshUser(id);
}

export async function unfollow(id: string) {
  await api.delete(`/follows/${id}`);
  await refreshUser(id);
}

export async function acceptFollow(id: string) {
  await api.post(`/follows/requests/${id}/accept`);
  useApp.setState((s) => ({ followRequests: s.followRequests.filter((r) => r !== id) }));
  await refreshUser(id);
  await engine.processWrapTasks();
}

export async function declineFollow(id: string) {
  await api.post(`/follows/requests/${id}/decline`);
  useApp.setState((s) => ({ followRequests: s.followRequests.filter((r) => r !== id) }));
}

export async function removeFollower(id: string) {
  await api.delete(`/followers/${id}`);
  await refreshUser(id);
}

export async function block(id: string) {
  await api.post(`/blocks/${id}`);
  await refreshUser(id);
}

export async function unblock(id: string) {
  await api.delete(`/blocks/${id}`);
  await refreshUser(id);
}

// --------------------------------------------------------------------------------------------------------- chats

function latestEpoch(threadId: string) {
  return index().epochs[threadId]?.at(-1) as Entity<EpochPayload> | undefined;
}

function sealEpoch(threadId: string, memberIds: string[]) {
  return seal<EpochPayload>({
    kind: Kind.EPOCH,
    audience: Audience.THREAD,
    payload: { memberIds },
    threadId,
    recipients: memberIds,
  });
}

/** An existing one-to-one chat with [userId], or a new one. */
export async function openDirect(userId: string): Promise<string> {
  const self = me().me.id;
  const existing = Object.values(getState().threads).find(
    (t) => !t.isGroup && t.members.length === 2 && t.members.some((m) => m.userId === userId) && t.members.some((m) => m.userId === self),
  );
  if (existing) return existing.id;
  return createThread([userId], false, {});
}

export async function createThread(memberIds: string[], isGroup: boolean, meta: ThreadPayload): Promise<string> {
  const self = me().me.id;
  const all = [...new Set([self, ...memberIds])];
  const threadId = newObjectId();
  const thread = seal<ThreadPayload>({ kind: Kind.THREAD, audience: Audience.THREAD, payload: meta, id: threadId, threadId, recipients: all });
  const epoch = sealEpoch(threadId, all);
  try {
    const created = await api.post<ThreadDto>('/threads', { isGroup, memberIds, thread: thread.upload, epoch: epoch.upload });
    engine.upsertThread(created);
    await engine.sync();
    return created.id;
  } catch (error) {
    // Someone else started our one-to-one chat first.
    if (error instanceof Error && 'code' in error && (error as { code: string }).code === 'thread_exists') {
      await engine.sync();
      return error.message;
    }
    throw error;
  }
}

/** If people left since the newest epoch, start a new one so they cannot read what comes next. */
async function epochForSending(threadId: string): Promise<Entity<EpochPayload>> {
  const thread = getState().threads[threadId];
  const latest = latestEpoch(threadId);
  const members = thread.members.map((m) => m.userId).sort();
  const epochMembers = [...(latest?.payload.memberIds ?? [])].sort();
  if (latest && members.length === epochMembers.length && members.every((m, i) => m === epochMembers[i])) return latest;
  const epoch = sealEpoch(threadId, members);
  const dto = await api.post<ObjectDto>(`/threads/${threadId}/epochs`, epoch.upload);
  engine.add(dto, epoch.key, { memberIds: members });
  return { dto, key: epoch.key, payload: { memberIds: members } };
}

export async function sendMessage(threadId: string, payload: MessagePayload, opts: { audience?: string; blobIds?: string[]; expiresAt?: number } = {}) {
  for (let attempt = 0; attempt < 2; attempt++) {
    const epoch = await epochForSending(threadId);
    try {
      return await publish({
        kind: Kind.MESSAGE,
        audience: opts.audience ?? Audience.THREAD,
        payload,
        threadId,
        parentId: epoch.dto.id,
        blobIds: opts.blobIds ?? mediaBlobIds(payload.media ?? []),
        expiresAt: opts.expiresAt,
      });
    } catch (error) {
      if ((error as { code?: string }).code !== 'stale_epoch' || attempt > 0) throw error;
      await engine.sync();
    }
  }
  throw new Error('Could not send');
}

export async function react(message: Entity, emoji: string) {
  const mine = index().children[message.dto.id]?.find((e) => e.dto.kind === Kind.REACTION && e.dto.authorId === me().me.id);
  if (mine) await deleteObject(mine.dto.id);
  if ((mine?.payload as ReactionPayload | undefined)?.emoji === emoji) return;
  return publish<ReactionPayload>({ kind: Kind.REACTION, audience: Audience.PARENT, payload: { emoji }, parentId: message.dto.id, threadId: message.dto.threadId });
}

export async function addMembers(threadId: string, userIds: string[]) {
  const thread = getState().threads[threadId];
  const meta = index().threadMeta[threadId];
  const all = [...new Set([...thread.members.map((m) => m.userId), ...userIds])];
  const epoch = sealEpoch(threadId, all);
  const threadWraps = meta ? wrapsFor(meta.key, userIds) : [];
  const updated = await api.post<ThreadDto>(`/threads/${threadId}/members`, { userIds, epoch: epoch.upload, threadWraps });
  engine.upsertThread(updated);
  await engine.sync();
}

export async function leaveThread(threadId: string, userId = me().me.id) {
  await api.delete(`/threads/${threadId}/members/${userId}`);
  await engine.sync();
}

export async function markRead(threadId: string) {
  await api.post(`/threads/${threadId}/read`, { at: Date.now() });
}

export async function recordView(id: string) {
  await api.post(`/objects/${id}/views`).catch(() => undefined);
}
