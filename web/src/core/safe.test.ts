import { describe, expect, it } from 'vitest';
import { isBlobId, safeMediaType, webLink } from './safe';

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
