// Plaintext inside encrypted objects, mirroring shared/protocol/.../Payloads.kt. The server never sees these.

export const Kind = {
  PROFILE: 'profile', POST: 'post', REEL: 'reel', STORY: 'story', HIGHLIGHT: 'highlight', NOTE: 'note',
  COMMENT: 'comment', LIKE: 'like', VOTE: 'vote', ANSWER: 'answer', COLLECTION: 'collection', SETTINGS: 'settings',
  ACTIVITY: 'activity', THREAD: 'thread', EPOCH: 'epoch', MESSAGE: 'message', REACTION: 'reaction', LIVE: 'live',
} as const;

export const Audience = {
  MEMBERS: 'members', FOLLOWERS: 'followers', CLOSE_FRIENDS: 'close_friends', SELF: 'self', DIRECT: 'direct',
  THREAD: 'thread', VANISH: 'vanish', ONCE: 'once', PARENT: 'parent',
} as const;

/** Kinds whose key derives from the parent's (no wraps of their own). */
export const CHILD_KEYED = new Set<string>([Kind.COMMENT, Kind.LIKE, Kind.VOTE, Kind.REACTION, Kind.MESSAGE]);

export interface MediaRef {
  blobId: string; key: string; mime: string; size: number; width?: number; height?: number; durationMs?: number | null;
  blurhash?: string | null; poster?: MediaRef | null; alt?: string | null;
}

export interface ProfilePayload { displayName?: string; bio?: string; link?: string; pronouns?: string; avatar?: MediaRef | null }

export interface PostPayload {
  caption?: string; media: MediaRef[]; location?: string | null; taggedUserIds?: string[]; hideLikes?: boolean; commentsOff?: boolean;
}

export interface ReelPayload {
  caption?: string; video: MediaRef; cover?: MediaRef | null; taggedUserIds?: string[]; hideLikes?: boolean; commentsOff?: boolean;
}

export interface StoryText { text: string; background?: string; font?: string; align?: string }

export const StickerType = {
  TEXT: 'text', EMOJI: 'emoji', MENTION: 'mention', HASHTAG: 'hashtag', LOCATION: 'location', LINK: 'link', POLL: 'poll',
  QUIZ: 'quiz', QUESTION: 'question', SLIDER: 'slider', COUNTDOWN: 'countdown', DRAWING: 'drawing',
} as const;

export interface Sticker {
  id: string; type: string; x: number; y: number; scale?: number; rotation?: number; text?: string | null; userId?: string | null;
  options?: string[]; correct?: number | null; emoji?: string | null; endsAt?: number | null; url?: string | null; color?: string | null;
}

export interface StoryPayload { media?: MediaRef | null; text?: StoryText | null; stickers?: Sticker[]; drawing?: MediaRef | null; durationMs?: number }
export interface HighlightStory { id: string; key: string }
export interface HighlightPayload { title: string; cover?: MediaRef | null; stories: HighlightStory[] }
export interface NotePayload { text: string; emoji?: string | null }
export interface CommentPayload { text: string }
export interface LikePayload { emoji?: string }
export interface VotePayload { stickerId: string; option?: number | null; value?: number | null }
export interface AnswerPayload { storyId: string; stickerId: string; text: string }
export interface CollectionPayload { name: string; postIds: string[]; isDefault?: boolean }

export interface SettingsPayload {
  closeFriends?: string[]; mutedPosts?: string[]; mutedStories?: string[]; pinnedThreads?: string[]; mutedThreads?: string[];
  verifiedKeys?: Record<string, string>; notifyLikes?: boolean; notifyComments?: boolean; notifyMessages?: boolean;
  notifyFollows?: boolean; notifyStories?: boolean;
}

export interface ActivityPayload { type: 'mention' | 'tag'; objectId: string; text?: string | null }
export interface ThreadPayload { name?: string | null; avatar?: MediaRef | null; theme?: string | null }
export interface EpochPayload { memberIds: string[] }
export interface CallSummary { callId: string; video: boolean; durationMs: number; missed: boolean; participants?: string[] }

export const MessageType = {
  TEXT: 'text', MEDIA: 'media', VOICE: 'voice', SHARE: 'share', STORY_REPLY: 'story_reply', STORY_REACTION: 'story_reaction',
  CALL: 'call', HEART: 'heart', SYSTEM: 'system',
} as const;

export interface MessagePayload {
  type: string; text?: string | null; media?: MediaRef[]; replyTo?: string | null; sharedObjectId?: string | null;
  sharedAuthorId?: string | null; storyId?: string | null; call?: CallSummary | null; edited?: boolean;
  /** For SYSTEM messages: a change to the chat every member sees (see core/chat.ts). */
  event?: ChatEvent | null;
}

/** One change to a chat's look; see ChatEvent in Payloads.kt. */
export interface ChatEvent {
  theme?: string | null; nicknameFor?: string | null; nickname?: string | null; quickReaction?: string | null;
  pin?: string | null; unpin?: string | null;
}

export interface ReactionPayload { emoji: string }
export interface LivePayload { title?: string; startedAt: number; callId?: string }

export interface CallSignal {
  type: 'invite' | 'offer' | 'answer' | 'ice' | 'accept' | 'decline' | 'busy' | 'hangup' | 'join' | 'state' | 'chat' | 'heart';
  callId: string; threadId?: string | null; video?: boolean; sdp?: string | null; candidate?: string | null;
  sdpMid?: string | null; sdpMLineIndex?: number | null; participants?: string[]; text?: string | null; live?: boolean;
  /** The sending device, and the one device a signal is for once someone has answered (see CallSignal in Payloads.kt). */
  device?: string | null; toDevice?: string | null;
  /** For 'state': whether the sender's camera and microphone are on. */
  camera?: boolean | null; mic?: boolean | null;
}
