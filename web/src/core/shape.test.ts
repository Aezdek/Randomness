import { describe, expect, it } from 'vitest';
import { Kind } from './payloads';
import { shapeOf } from './shape';

const blob = 'a'.repeat(64);
const ref = { blobId: blob, key: 'k', mime: 'image/jpeg', size: 10 };

describe('what a payload from another member must look like', () => {
  it('keeps well-formed payloads, leaving out fields this app does not know', () => {
    expect(shapeOf(Kind.MESSAGE, { type: 'text', text: 'hi', replyTo: null, somethingNew: { x: 1 } })).toEqual({ type: 'text', text: 'hi' });
    expect(shapeOf(Kind.POST, { media: [{ ...ref, poster: null, width: 4 }], caption: 'c' })).toEqual({ media: [{ ...ref, width: 4 }], caption: 'c' });
    expect(shapeOf(Kind.MESSAGE, { type: 'plan', plan: { title: 'Dinner', at: 5, place: null } })).toEqual({ type: 'plan', plan: { title: 'Dinner', at: 5 } });
    expect(shapeOf(Kind.SETTINGS, { verifiedKeys: { a: 'b' }, notifyPreview: 'none' })).toEqual({ verifiedKeys: { a: 'b' }, notifyPreview: 'none' });
    expect(shapeOf(Kind.PROFILE, {})).toEqual({});
  });

  it('refuses anything that is not an object', () => {
    for (const kind of Object.values(Kind)) {
      for (const bad of [null, 5, 'text', true, [], [{ type: 'text' }]]) expect(shapeOf(kind, bad), `${kind} ${JSON.stringify(bad)}`).toBeUndefined();
    }
    expect(shapeOf('a-kind-from-a-newer-app', null)).toBeUndefined();
    expect(shapeOf('a-kind-from-a-newer-app', { anything: 1 })).toEqual({ anything: 1 });
  });

  it('refuses the wrong type in any field, required or not', () => {
    const bad: [string, unknown][] = [
      [Kind.MESSAGE, {}],
      [Kind.MESSAGE, { type: 5 }],
      [Kind.MESSAGE, { type: 'text', text: { toString: 'x' } }],
      [Kind.MESSAGE, { type: 'text', text: ['a'] }],
      [Kind.MESSAGE, { type: 'media', media: 'not a list' }],
      [Kind.MESSAGE, { type: 'media', media: [{ ...ref, blobId: '../../etc/passwd' }] }],
      [Kind.MESSAGE, { type: 'media', media: [null] }],
      [Kind.MESSAGE, { type: 'poll', poll: { question: 'q', options: 'ab' } }],
      [Kind.MESSAGE, { type: 'poll', poll: { question: 'q', options: ['a', 2] } }],
      [Kind.MESSAGE, { type: 'plan', plan: { title: 'x', at: '5' } }],
      [Kind.MESSAGE, { type: 'location', location: { lat: 1, lng: 2, until: 3 } }],
      [Kind.MESSAGE, { type: 'system', event: 'theme' }],
      [Kind.MESSAGE, { type: 'call', call: { callId: 'c', video: 'yes', durationMs: 1, missed: false } }],
      [Kind.POST, { caption: 'no media' }],
      [Kind.POST, { media: {} }],
      [Kind.REEL, { video: 'x' }],
      [Kind.STORY, { stickers: [{ id: 's', type: 'poll', x: 'left', y: 0 }] }],
      [Kind.STORY, { text: 'just text' }],
      [Kind.HIGHLIGHT, { title: 't', stories: 5 }],
      [Kind.HIGHLIGHT, { title: 't', stories: [{ id: 's' }] }],
      [Kind.NOTE, { text: 7 }],
      [Kind.COMMENT, { text: null }],
      [Kind.VOTE, { stickerId: 'poll', option: 'first' }],
      [Kind.EPOCH, { memberIds: 'everyone' }],
      [Kind.THREAD, { name: ['x'] }],
      [Kind.PROFILE, { displayName: {}, avatar: ref }],
      [Kind.PROFILE, { pinned: 'post' }],
      [Kind.REACTION, {}],
      [Kind.LIVE, { startedAt: 'now' }],
      [Kind.SETTINGS, { verifiedKeys: { a: 1 } }],
      [Kind.COLLECTION, { name: 'Saved', postIds: [1] }],
    ];
    for (const [kind, payload] of bad) expect(shapeOf(kind, payload), `${kind} ${JSON.stringify(payload)}`).toBeUndefined();
  });

  it('refuses numbers that are not numbers, and lists too long to be real', () => {
    expect(shapeOf(Kind.MESSAGE, JSON.parse('{"type":"plan","plan":{"title":"x","at":1e999}}'))).toBeUndefined();
    expect(shapeOf(Kind.MESSAGE, { type: 'poll', poll: { question: 'q', options: Array(5000).fill('x') } })).toBeUndefined();
  });
});
