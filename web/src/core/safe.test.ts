import { describe, expect, it } from 'vitest';
import { isBlobId, safeLocation, safeMediaType, safePlan, safePoll, safeText, webLink } from './safe';

describe('content from other people', () => {
  it('only ever calls a decrypted file a photo, a video or sound', () => {
    expect(safeMediaType('video/mp4')).toBe('video/mp4');
    expect(safeMediaType('audio/webm;codecs=opus')).toBe('audio/webm');
    expect(safeMediaType('IMAGE/JPEG')).toBe('image/jpeg');
    for (const bad of ['text/html', 'image/svg+xml', 'application/xhtml+xml', 'text/javascript', 'video/mp4, text/html', '', undefined, 42]) {
      expect(safeMediaType(bad)).toBe('application/octet-stream');
    }
  });

  it('knows a media id from a path', () => {
    expect(isBlobId('a'.repeat(64))).toBe(true);
    for (const bad of ['../auth/logout', 'a'.repeat(63), 'A'.repeat(64), `${'a'.repeat(64)}/x`, null]) expect(isBlobId(bad)).toBe(false);
  });

  it('opens web addresses and nothing else', () => {
    expect(webLink('example.com/page')).toBe('https://example.com/page');
    expect(webLink('http://example.com')).toBe('http://example.com/');
    expect(webLink(' https://example.com/a?b=1 ')).toBe('https://example.com/a?b=1');
    for (const bad of ['javascript:alert(1)', 'JaVaScript:alert(1)', 'data:text/html,<script>1</script>', 'file:///etc/passwd', 'intent://x#Intent;end', 'tel:123', '', null]) {
      expect(webLink(bad)).toBeNull();
    }
  });
});

describe('chat payload shapes from other members', () => {
  it('keeps a well-formed plan, location and poll', () => {
    expect(safePlan({ title: ' Dinner ', at: 5, place: ' Leo’s ' })).toEqual({ title: 'Dinner', at: 5, place: 'Leo’s' });
    expect(safeLocation({ lat: 38.7, lng: -9.1, until: 9, updatedAt: 1, accuracy: 20 })).toEqual({ lat: 38.7, lng: -9.1, until: 9, updatedAt: 1, accuracy: 20 });
    expect(safePoll({ question: 'Where?', options: ['A', 'B'] })).toEqual({ question: 'Where?', options: ['A', 'B'] });
  });
  it('refuses what a modified client could send instead', () => {
    expect(safePlan({ title: { evil: 1 }, at: 5 })).toBeNull();
    expect(safePlan({ title: 'x', at: '5' })).toBeNull();
    expect(safePlan('plan')).toBeNull();
    expect(safeLocation({ lat: '38', lng: 1, until: 1, updatedAt: 1 })).toBeNull();
    expect(safeLocation({ lat: 91, lng: 1, until: 1, updatedAt: 1 })).toBeNull();
    expect(safeLocation({ lat: 1, lng: 1, until: Infinity, updatedAt: 1 })).toBeNull();
    expect(safeLocation({ lat: 1, lng: 1, until: 2, updatedAt: 1, accuracy: -4 })?.accuracy).toBeNull();
    expect(safePoll({ question: 'q', options: 5 })).toBeNull();
    expect(safePoll({ question: 'q', options: [1, 2, 'only one string'] })).toBeNull();
    expect(safeText({ not: 'text' })).toBe('');
    expect(safePlan({ title: 'x'.repeat(500), at: 1 })?.title).toHaveLength(100);
  });
});
