import { describe, expect, it } from 'vitest';
import { displayText, foldText, FOLD_CHARS, FOLD_LINES, heartOf, isBlobId, MAX_PREVIEW, MAX_TEXT, previewText, safeLocation, safeMediaType, safePlan, safePoll, safeText, shownDuration, webLink } from './safe';

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

describe('times no calendar holds', () => {
  it('are refused in plans and locations, so formatting a date never throws', () => {
    expect(safePlan({ title: 'x', at: 1e300 })).toBeNull();
    expect(safePlan({ title: 'x', at: -5 })).toBeNull();
    expect(safeLocation({ lat: 1, lng: 1, until: 9e18, updatedAt: 1 })).toBeNull();
    expect(safeLocation({ lat: 1, lng: 1, until: 2, updatedAt: -1 })).toBeNull();
    expect(safePlan({ title: 'x', at: Date.UTC(2030, 0, 1) })).not.toBeNull();
  });
});

describe('display limits (mirror Display.kt)', () => {
  it('cut texts, collapse blank lines, keep hearts to one emoji and durations to a day', () => {
    expect(displayText('W'.repeat(200_000))).toHaveLength(MAX_TEXT + 1);
    expect(displayText(`a${'\n'.repeat(400)}b`)).toBe('a\n\nb');
    expect(displayText({ not: 'text' })).toBe('');
    expect(previewText(`one\ntwo ${'x'.repeat(10_000)}`)).toHaveLength(MAX_PREVIEW + 1);
    expect(previewText('one\ntwo')).toBe('one two');
    expect(heartOf('🔥', '❤️')).toBe('🔥');
    expect(heartOf('👩‍👩‍👧‍👦', '❤️')).toBe('👩‍👩‍👧‍👦');
    expect(heartOf('❤️'.repeat(2_000), '❤️')).toBe('❤️');
    expect(heartOf('🔥'.repeat(500), '❤️')).toBe('🔥');
    expect(heartOf('   ', '❤️')).toBe('❤️');
    expect(shownDuration(-9e18)).toBe(0);
    expect(shownDuration(9e18)).toBe(86_400_000);
    expect(shownDuration('5')).toBe(0);
  });
});

describe('long messages fold (mirror Display.fold)', () => {
  it('at a thousand characters or twenty lines', () => {
    expect(foldText('short')).toBe('short');
    expect(foldText('W'.repeat(8_000))).toHaveLength(FOLD_CHARS + 1);
    const tall = Array.from({ length: 100 }, (_, i) => `line ${i + 1}`).join('\n');
    expect(foldText(tall).split('\n')).toHaveLength(FOLD_LINES);
    expect(foldText('x'.repeat(FOLD_CHARS))).toBe('x'.repeat(FOLD_CHARS));
  });
});
