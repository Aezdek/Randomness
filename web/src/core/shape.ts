// What other people's apps send is encrypted, so the server can't check it, and this app is the first thing to see it
// in the clear. A member with a modified app can put any JSON there: null, a number where a list goes, a list where
// text goes. The Android app refuses those when it decodes (its payloads are typed); this is the same refusal for the
// web, done once as each object arrives, so no screen ever has to wonder what it was handed.
//
// The rule, for every kind this app knows (mirroring the fields and their required/optional split in Payloads.kt):
//   - the payload must be an object, each required field present and of the right type;
//   - an optional field may be missing or null; if it is there it must be of the right type;
//   - anything that doesn't fit drops the whole object, as it does on Android;
//   - fields this app doesn't know are left out (a newer app may send more).
import { Kind } from './payloads';
import { isBlobId } from './safe';

/** Returns the value, cleaned, or undefined when it isn't what it should be. */
type Check = (value: unknown) => unknown;

const MAX_LIST = 2000;

const str: Check = (v) => (typeof v === 'string' ? v : undefined);
const num: Check = (v) => (typeof v === 'number' && Number.isFinite(v) ? v : undefined);
const bool: Check = (v) => (typeof v === 'boolean' ? v : undefined);

const list = (item: Check): Check => (v) => {
  if (!Array.isArray(v) || v.length > MAX_LIST) return undefined;
  const out: unknown[] = [];
  for (const x of v) {
    const clean = item(x);
    if (clean === undefined) return undefined;
    out.push(clean);
  }
  return out;
};

/** Text to text, as in the settings' record of verified keys. */
const strMap: Check = (v) => {
  if (!v || typeof v !== 'object' || Array.isArray(v)) return undefined;
  const out: Record<string, string> = {};
  for (const [k, x] of Object.entries(v)) {
    if (typeof x !== 'string') return undefined;
    out[k] = x;
  }
  return out;
};

const obj = (required: Record<string, Check>, optional: Record<string, Check> = {}): Check => (v) => {
  if (!v || typeof v !== 'object' || Array.isArray(v)) return undefined;
  const raw = v as Record<string, unknown>;
  const out: Record<string, unknown> = {};
  for (const [field, check] of Object.entries(required)) {
    const clean = check(raw[field]);
    if (clean === undefined) return undefined;
    out[field] = clean;
  }
  for (const [field, check] of Object.entries(optional)) {
    if (raw[field] === undefined || raw[field] === null) continue;
    const clean = check(raw[field]);
    if (clean === undefined) return undefined;
    out[field] = clean;
  }
  return out;
};

const blobId: Check = (v) => (isBlobId(v) ? v : undefined);
const mediaFields = { width: num, height: num, durationMs: num, blurhash: str, alt: str };
/** A poster is a picture of a video; it has no poster of its own. */
const poster = obj({ blobId, key: str, mime: str, size: num }, mediaFields);
const media = obj({ blobId, key: str, mime: str, size: num }, { ...mediaFields, poster });

const sticker = obj(
  { id: str, type: str, x: num, y: num },
  { scale: num, rotation: num, text: str, userId: str, options: list(str), correct: num, emoji: str, endsAt: num, url: str, color: str },
);

const chatEvent = obj({}, {
  theme: str, nicknameFor: str, nickname: str, quickReaction: str, pin: str, unpin: str, disappearAfter: num, screenshot: bool,
  countdownTitle: str, countdownAt: num,
});

const SHAPES: Record<string, Check> = {
  [Kind.PROFILE]: obj({}, { displayName: str, bio: str, link: str, pronouns: str, avatar: media, pinned: list(str) }),
  [Kind.POST]: obj({ media: list(media) }, { caption: str, location: str, taggedUserIds: list(str), hideLikes: bool, commentsOff: bool }),
  [Kind.REEL]: obj({ video: media }, { caption: str, cover: media, taggedUserIds: list(str), hideLikes: bool, commentsOff: bool }),
  [Kind.STORY]: obj({}, {
    media,
    text: obj({ text: str }, { background: str, font: str, align: str }),
    stickers: list(sticker),
    drawing: media,
    durationMs: num,
  }),
  [Kind.HIGHLIGHT]: obj({ title: str, stories: list(obj({ id: str, key: str })) }, { cover: media }),
  [Kind.NOTE]: obj({ text: str }, { emoji: str }),
  [Kind.COMMENT]: obj({ text: str }),
  [Kind.LIKE]: obj({}, { emoji: str }),
  [Kind.VOTE]: obj({ stickerId: str }, { option: num, value: num }),
  [Kind.ANSWER]: obj({ storyId: str, stickerId: str, text: str }),
  [Kind.COLLECTION]: obj({ name: str, postIds: list(str) }, { isDefault: bool }),
  [Kind.SETTINGS]: obj({}, {
    closeFriends: list(str), mutedPosts: list(str), mutedStories: list(str), pinnedThreads: list(str), mutedThreads: list(str),
    verifiedKeys: strMap, notifyLikes: bool, notifyComments: bool, notifyMessages: bool, notifyFollows: bool, notifyStories: bool,
    notifyPreview: str,
  }),
  [Kind.ACTIVITY]: obj({ type: str, objectId: str }, { text: str }),
  [Kind.THREAD]: obj({}, { name: str, avatar: media, theme: str, album: bool }),
  [Kind.EPOCH]: obj({ memberIds: list(str) }),
  [Kind.MESSAGE]: obj({ type: str }, {
    text: str, media: list(media), replyTo: str, sharedObjectId: str, sharedAuthorId: str, storyId: str,
    call: obj({ callId: str, video: bool, durationMs: num, missed: bool }, { participants: list(str) }),
    edited: bool, event: chatEvent, quote: str, forwarded: bool,
    poll: obj({ question: str, options: list(str) }),
    plan: obj({ title: str, at: num }, { place: str }),
    location: obj({ lat: num, lng: num, until: num, updatedAt: num }, { accuracy: num }),
    file: obj({ name: str, blobId, key: str, size: num }, { mime: str }),
    list: obj({ title: str }, { items: list(str) }),
    listChange: obj({}, { add: str, done: str, undone: str, remove: str }),
    daily: obj({ day: str, question: str, answer: str }),
  }),
  [Kind.REACTION]: obj({ emoji: str }),
  [Kind.LIVE]: obj({ startedAt: num }, { title: str, callId: str }),
};

/**
 * The payload of an object of [kind] as this app may use it, or undefined if it isn't well-formed (the object is then
 * dropped). A kind this app doesn't know (from a newer app) is kept as it came, as long as it is an object: nothing
 * here reads it.
 */
export function shapeOf(kind: string, raw: unknown): unknown {
  const shape = SHAPES[kind];
  if (shape) return shape(raw);
  return raw && typeof raw === 'object' && !Array.isArray(raw) ? raw : undefined;
}
