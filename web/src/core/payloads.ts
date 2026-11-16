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

export interface ProfilePayload {
  displayName?: string; bio?: string; link?: string; pronouns?: string; avatar?: MediaRef | null;
  /** Up to three posts or reels shown first on the grid. */
  pinned?: string[];
}

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
  /** What notifications show: everything, only who it is from, or nothing but "New message". One of NotifyPreview. */
  notifyPreview?: string;
}

export const NotifyPreview = { FULL: 'full', NAME: 'name', NONE: 'none' } as const;

export interface ActivityPayload { type: 'mention' | 'tag'; objectId: string; text?: string | null }
export interface ThreadPayload {
  name?: string | null; avatar?: MediaRef | null; theme?: string | null;
  /** A shared album: a group whose photos and videos are shown as a gallery rather than a conversation. */
  album?: boolean;
}
export interface EpochPayload { memberIds: string[] }
export interface CallSummary { callId: string; video: boolean; durationMs: number; missed: boolean; participants?: string[] }

export const MessageType = {
  TEXT: 'text', MEDIA: 'media', VOICE: 'voice', SHARE: 'share', STORY_REPLY: 'story_reply', STORY_REACTION: 'story_reaction',
  CALL: 'call', HEART: 'heart', SYSTEM: 'system', NOTE_REPLY: 'note_reply', POLL: 'poll', PLAN: 'plan', LOCATION: 'location',
  FILE: 'file', NUDGE: 'nudge', LIST: 'list', LIST_CHANGE: 'list_change', DAILY: 'daily', ARRIVED: 'arrived',
} as const;

export interface MessagePayload {
  type: string; text?: string | null; media?: MediaRef[]; replyTo?: string | null; sharedObjectId?: string | null;
  sharedAuthorId?: string | null; storyId?: string | null; call?: CallSummary | null; edited?: boolean;
  /** For SYSTEM messages: a change to the chat every member sees (see core/chat.ts). */
  event?: ChatEvent | null;
  /** For NOTE_REPLY: the note's words, kept with the reply because notes vanish after a day. */
  quote?: string | null;
  /** Sent on from another chat (shows "Forwarded"). */
  forwarded?: boolean;
  /** For POLL: the question and options; votes are VOTE children ({ stickerId: 'poll', option }). */
  poll?: Poll | null;
  /** For PLAN: what, when and where. Answers are VOTE children ({ stickerId: 'plan', option: PlanAnswer }). */
  plan?: Plan | null;
  /** For LOCATION: where the sender is, kept up to date by editing this one message until it expires. */
  location?: LiveLocation | null;
  /** For FILE: any file, sent as it is (encrypted like a photo, never opened by the app). */
  file?: SharedFile | null;
  /** For LIST: a list the whole chat keeps; changes are LIST_CHANGE replies to it. */
  list?: SharedList | null;
  /** For LIST_CHANGE: one change to the list in replyTo. */
  listChange?: ListChange | null;
  /** For DAILY: an answer to the day's question. */
  daily?: DailyAnswer | null;
}

/** See the same types in Payloads.kt. */
export interface SharedFile { name: string; blobId: string; key: string; size: number; mime?: string }
export interface SharedList { title: string; items?: string[] }
export interface ListChange { add?: string | null; done?: string | null; undone?: string | null; remove?: string | null }
export interface DailyAnswer { day: string; question: string; answer: string }

export interface Poll { question: string; options: string[] }

/** A plan in a chat: "Dinner, Saturday 19:30, at Leo's". */
export interface Plan { title: string; at: number; place?: string | null }
export const PlanAnswer = { GOING: 0, MAYBE: 1, NO: 2, STICKER: 'plan' } as const;

/** Where someone is, shared with a chat until [until]; replaced with each move, so no trail is kept. */
export interface LiveLocation { lat: number; lng: number; accuracy?: number | null; until: number; updatedAt: number }

/** One change to a chat's look; see ChatEvent in Payloads.kt. */
export interface ChatEvent {
  theme?: string | null; nicknameFor?: string | null; nickname?: string | null; quickReaction?: string | null;
  pin?: string | null; unpin?: string | null;
  /** Disappearing messages: new messages last this many milliseconds; 0 turns it off. One of DISAPPEARING. */
  disappearAfter?: number | null;
  /** Someone's phone saw a screenshot of this chat while it was protected (see ChatEvent in Payloads.kt). */
  screenshot?: boolean | null;
  /** A date the chat counts down to or up from, shown at the top; a blank title takes it away. */
  countdownTitle?: string | null;
  countdownAt?: number | null;
}

const DAY = 24 * 60 * 60 * 1000;
/** How long messages can last in a chat with disappearing messages on (Disappearing in Payloads.kt). */
export const DISAPPEARING = [0, DAY, 7 * DAY, 90 * DAY] as const;

export function disappearingLabel(after: number): string {
  if (after <= 0) return 'Off';
  if (after === DAY) return '24 hours';
  if (after % DAY === 0) return `${after / DAY} days`;
  return `${after / (60 * 60 * 1000)} hours`;
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
  /** For 'state': the sender's video is their screen, not their camera (shown whole, not cropped). */
  screen?: boolean | null;
}
