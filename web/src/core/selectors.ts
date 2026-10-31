import { useMemo } from 'react';
import {
  Kind,
  type ActivityPayload,
  type AnswerPayload,
  type CollectionPayload,
  type EpochPayload,
  type HighlightPayload,
  type LivePayload,
  type MessagePayload,
  type NotePayload,
  type ProfilePayload,
  type SettingsPayload,
  type StoryPayload,
  type ThreadPayload,
} from './payloads';
import { useApp, type AppState, type Entity } from './store';
import type { ThreadDto, UserDto } from './types';
import { chatCustom } from './chat';

/** Everything the UI looks things up by, rebuilt only when the entity map changes. */
export interface Index {
  profiles: Record<string, Entity<ProfilePayload>>;
  /** Posts and reels, newest first. */
  posts: Entity[];
  stories: Record<string, Entity<StoryPayload>[]>;
  myStoryArchive: Entity<StoryPayload>[];
  highlights: Record<string, Entity<HighlightPayload>[]>;
  notes: Record<string, Entity<NotePayload>>;
  children: Record<string, Entity[]>;
  messages: Record<string, Entity<MessagePayload>[]>;
  threadMeta: Record<string, Entity<ThreadPayload>>;
  epochs: Record<string, Entity<EpochPayload>[]>;
  settings: Entity<SettingsPayload> | null;
  collections: Entity<CollectionPayload>[];
  activity: Entity<ActivityPayload>[];
  answers: Entity<AnswerPayload>[];
  lives: Entity<LivePayload>[];
}

const cache = new WeakMap<object, Index>();

export function buildIndex(state: Pick<AppState, 'entities' | 'me'>): Index {
  const hit = cache.get(state.entities);
  if (hit) return hit;
  const now = Date.now();
  const me = state.me?.id;
  const out: Index = {
    profiles: {},
    posts: [],
    stories: {},
    myStoryArchive: [],
    highlights: {},
    notes: {},
    children: {},
    messages: {},
    threadMeta: {},
    epochs: {},
    settings: null,
    collections: [],
    activity: [],
    answers: [],
    lives: [],
  };
  const byReceived = (a: Entity, b: Entity) => a.dto.receivedAt - b.dto.receivedAt;
  for (const entity of Object.values(state.entities)) {
    const { dto } = entity;
    if (dto.parentId && dto.kind !== Kind.MESSAGE) (out.children[dto.parentId] ??= []).push(entity);
    switch (dto.kind) {
      case Kind.PROFILE: {
        const current = out.profiles[dto.authorId];
        if (!current || current.dto.version < dto.version) out.profiles[dto.authorId] = entity as Entity<ProfilePayload>;
        break;
      }
      case Kind.POST:
      case Kind.REEL:
        out.posts.push(entity);
        break;
      case Kind.STORY:
        if (dto.authorId === me) out.myStoryArchive.push(entity as Entity<StoryPayload>);
        if ((dto.expiresAt ?? 0) > now && !dto.archived) (out.stories[dto.authorId] ??= []).push(entity as Entity<StoryPayload>);
        break;
      case Kind.HIGHLIGHT:
        (out.highlights[dto.authorId] ??= []).push(entity as Entity<HighlightPayload>);
        break;
      case Kind.NOTE:
        if ((dto.expiresAt ?? 0) > now) {
          const current = out.notes[dto.authorId];
          if (!current || current.dto.createdAt < dto.createdAt) out.notes[dto.authorId] = entity as Entity<NotePayload>;
        }
        break;
      case Kind.MESSAGE:
        if (dto.threadId) (out.messages[dto.threadId] ??= []).push(entity as Entity<MessagePayload>);
        break;
      case Kind.THREAD:
        if (dto.threadId) out.threadMeta[dto.threadId] = entity as Entity<ThreadPayload>;
        break;
      case Kind.EPOCH:
        if (dto.threadId) (out.epochs[dto.threadId] ??= []).push(entity as Entity<EpochPayload>);
        break;
      case Kind.SETTINGS:
        if (dto.authorId === me && (!out.settings || out.settings.dto.receivedAt < dto.receivedAt)) out.settings = entity as Entity<SettingsPayload>;
        break;
      case Kind.COLLECTION:
        if (dto.authorId === me) out.collections.push(entity as Entity<CollectionPayload>);
        break;
      case Kind.ACTIVITY:
        if (dto.authorId !== me) out.activity.push(entity as Entity<ActivityPayload>);
        break;
      case Kind.ANSWER:
        out.answers.push(entity as Entity<AnswerPayload>);
        break;
      case Kind.LIVE:
        if ((dto.expiresAt ?? 0) > now) out.lives.push(entity as Entity<LivePayload>);
        break;
    }
  }
  out.posts.sort((a, b) => b.dto.createdAt - a.dto.createdAt);
  for (const list of Object.values(out.stories)) list.sort((a, b) => a.dto.createdAt - b.dto.createdAt);
  out.myStoryArchive.sort((a, b) => b.dto.createdAt - a.dto.createdAt);
  for (const list of Object.values(out.highlights)) list.sort((a, b) => a.dto.createdAt - b.dto.createdAt);
  for (const list of Object.values(out.children)) list.sort((a, b) => a.dto.createdAt - b.dto.createdAt);
  for (const list of Object.values(out.messages)) list.sort(byReceived);
  for (const list of Object.values(out.epochs)) list.sort(byReceived);
  out.activity.sort((a, b) => b.dto.receivedAt - a.dto.receivedAt);
  cache.set(state.entities, out);
  return out;
}

export function useIndex(): Index {
  const entities = useApp((s) => s.entities);
  const me = useApp((s) => s.me);
  return useMemo(() => buildIndex({ entities, me }), [entities, me]);
}

export function useUser(id: string | undefined | null): UserDto | undefined {
  return useApp((s) => (id ? s.users[id] : undefined));
}

export function useProfile(id: string | undefined | null): ProfilePayload | undefined {
  const index = useIndex();
  return id ? index.profiles[id]?.payload : undefined;
}

export function displayName(user: UserDto | undefined, profile: ProfilePayload | undefined): string {
  return profile?.displayName?.trim() || user?.username || 'Someone';
}

export function useUserByName(username: string | undefined): UserDto | undefined {
  return useApp((s) => Object.values(s.users).find((u) => u.username === username?.toLowerCase()));
}

/** What a chat calls someone: their nickname there, else their display name, else their username. */
export function nameIn(threadId: string | null | undefined, userId: string, index: Index, users: Record<string, UserDto>): string {
  const nickname = threadId ? chatCustom(index.messages[threadId]).nicknames[userId] : undefined;
  return nickname || index.profiles[userId]?.payload.displayName || users[userId]?.username || 'someone';
}

export function threadTitle(thread: ThreadDto, index: Index, users: Record<string, UserDto>, me: string): string {
  const name = index.threadMeta[thread.id]?.payload.name;
  if (name) return name;
  const others = thread.members.filter((m) => m.userId !== me).map((m) => nameIn(thread.id, m.userId, index, users));
  return others.join(', ') || 'Just you';
}

export function unreadCount(thread: ThreadDto, index: Index, me: string): number {
  const mine = thread.members.find((m) => m.userId === me);
  if (!mine) return 0;
  return (index.messages[thread.id] ?? []).filter((m) => m.dto.authorId !== me && m.dto.receivedAt > mine.lastReadAt).length;
}

export const isLiked = (index: Index, parentId: string, me: string) =>
  (index.children[parentId] ?? []).some((e) => e.dto.kind === Kind.LIKE && e.dto.authorId === me);

export const likesOf = (index: Index, parentId: string) => (index.children[parentId] ?? []).filter((e) => e.dto.kind === Kind.LIKE);

export const commentsOf = (index: Index, parentId: string) => (index.children[parentId] ?? []).filter((e) => e.dto.kind === Kind.COMMENT);

export function savedPostIds(index: Index): Set<string> {
  return new Set(index.collections.flatMap((c) => (c.payload.isDefault ? c.payload.postIds : [])));
}
