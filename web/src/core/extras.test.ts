import { describe, expect, it } from 'vitest';
import { applyEvent, chatCustom } from './chat';
import {
  ARRIVED_WITHIN, countdownLabel, dailyState, distance, foldList, isDay, MAX_COUNTDOWN_TITLE, MAX_FILE_BYTES, MAX_FILE_NAME, MAX_LIST_ITEM,
  MAX_LIST_ITEMS, MAX_LIST_TITLE, placeName, questionFor, QUESTIONS, riskyFile, safeFile, safeFileName, sizeLabel,
} from './extras';
import { shapeOf } from './shape';
import { Kind } from './payloads';

// The same cases as ExtrasTest.kt.
describe('files', () => {
  it('cleans names of folders, control and direction marks', () => {
    expect(safeFileName('notes.pdf')).toBe('notes.pdf');
    expect(safeFileName('../../etc/passwd')).toBe('passwd');
    expect(safeFileName('C:\\Users\\me\\evil.exe')).toBe('evil.exe');
    expect(safeFileName('photo\u202Egpj.exe')).toBe('photogpj.exe');
    expect(safeFileName('a\u0000b\n.txt')).toBe('ab.txt');
    expect(safeFileName('  ..  ')).toBe('file');
    expect(safeFileName(null)).toBe('file');
    expect(safeFileName('a<b>.txt')).toBe('ab.txt');
    const long = safeFileName('x'.repeat(500) + '.tar');
    expect(long.length).toBe(MAX_FILE_NAME);
    expect(long.endsWith('.tar')).toBe(true);
  });

  it('only fetches real-looking files', () => {
    const ok = { name: 'a.txt', blobId: 'ab'.repeat(32), key: 'key', size: 10 };
    expect(safeFile(ok)).toEqual(ok);
    expect(safeFile({ ...ok, blobId: '../../etc/passwd' })).toBeNull();
    expect(safeFile({ ...ok, size: -1 })).toBeNull();
    expect(safeFile({ ...ok, size: MAX_FILE_BYTES + 1 })).toBeNull();
    expect(safeFile(null)).toBeNull();
    expect(sizeLabel(1_572_864)).toBe('1.5 MB');
    expect(sizeLabel(12_800)).toBe('12 KB');
    expect(riskyFile('game.APK') && riskyFile('page.html') && riskyFile('a.tar.sh')).toBe(true);
    expect(riskyFile('photo.jpg') || riskyFile('notes') || riskyFile('apk')).toBe(false);
  });

  it('drops file, list and daily messages of the wrong shape as they arrive', () => {
    expect(shapeOf(Kind.MESSAGE, { type: 'file', file: { name: 'a', blobId: 'nope', key: 'k', size: 1 } })).toBeUndefined();
    expect(shapeOf(Kind.MESSAGE, { type: 'file', file: 'a.txt' })).toBeUndefined();
    expect(shapeOf(Kind.MESSAGE, { type: 'list', list: { title: 5 } })).toBeUndefined();
    expect(shapeOf(Kind.MESSAGE, { type: 'list_change', listChange: { add: ['x'] } })).toBeUndefined();
    expect(shapeOf(Kind.MESSAGE, { type: 'daily', daily: { day: 1, question: 'q', answer: 'a' } })).toBeUndefined();
    expect(shapeOf(Kind.MESSAGE, { type: 'list', list: { title: 'L', items: ['a'] } })).toBeTruthy();
  });
});

describe('lists', () => {
  it("is what was sent with everyone's changes applied in order", () => {
    const state = foldList({ title: 'Groceries', items: ['Milk', 'Eggs', '  '] }, 'ana', [
      { id: 'm1', by: 'bo', change: { add: 'Bread' } },
      { id: 'm2', by: 'bo', change: { done: '0' } },
      { id: 'm3', by: 'ana', change: { remove: '1' } },
      { id: 'm4', by: 'ana', change: { done: 'm1' } },
      { id: 'm5', by: 'bo', change: { undone: 'm1' } },
      { id: 'm6', by: 'bo', change: { done: 'no-such-item' } },
    ]);
    expect(state.title).toBe('Groceries');
    expect(state.items.map((i) => [i.id, i.done])).toEqual([['0', true], ['m1', false]]);
    expect(state.items.map((i) => i.text)).toEqual(['Milk', 'Bread']);
  });

  it("can't be flooded", () => {
    const edits = Array.from({ length: 500 }, (_, i) => ({ id: `e${i}`, by: 'bo', change: { add: 'y'.repeat(10_000) } }));
    const state = foldList({ title: 'x'.repeat(5_000), items: Array.from({ length: 1_000 }, (_, i) => `item ${i}`) }, 'ana', edits);
    expect(state.items.length).toBe(MAX_LIST_ITEMS);
    expect(state.title.length).toBe(MAX_LIST_TITLE);
    const one = foldList({ title: 'L' }, 'ana', [{ id: 'e', by: 'bo', change: { add: 'y'.repeat(10_000) } }]);
    expect(one.items[0].text.length).toBeLessThanOrEqual(MAX_LIST_ITEM + 1);
  });
});

describe("the day's question", () => {
  it('is the same on every device', () => {
    expect(questionFor('thread-1', '2026-11-15')).toBe(QUESTIONS[28]);
    expect(questionFor('thread-1', '2026-11-16')).toBe(QUESTIONS[29]);
    expect(QUESTIONS.length).toBe(40);
  });

  it('keeps answers hidden until everyone in the chat has answered', () => {
    const day = '2026-11-15';
    const members = ['ana', 'bo'];
    const one = dailyState('t', day, members, [{ author: 'ana', daily: { day, question: 'Asked?', answer: 'Tea' } }]);
    expect(one.revealed).toBe(false);
    expect(one.question).toBe('Asked?');
    const both = dailyState('t', day, members, [
      { author: 'ana', daily: { day, question: 'Asked?', answer: 'Tea' } },
      { author: 'ana', daily: { day, question: 'Asked?', answer: 'Changed my mind' } },
      { author: 'bo', daily: { day, question: 'Another question', answer: 'Coffee' } },
      { author: 'mallory', daily: { day, question: 'x', answer: 'not in this chat' } },
      { author: 'bo', daily: { day: '2026-11-14', question: 'x', answer: 'yesterday' } },
    ]);
    expect(both.revealed).toBe(true);
    expect(both.answers).toEqual({ ana: 'Tea', bo: 'Coffee' });
    const none = dailyState('t', day, members, [{ author: 'ana', daily: { day, question: 'Asked?', answer: '   ' } }]);
    expect(none.revealed).toBe(false);
    expect(none.answers).toEqual({});
    expect(isDay(day)).toBe(true);
    expect(isDay('tomorrow')).toBe(false);
  });
});

describe('countdowns', () => {
  it('counts calendar days, both ways', () => {
    const now = new Date(2026, 10, 15, 10).getTime();
    const day = 86_400_000;
    expect(countdownLabel('Trip', now + 3 * 3_600_000, now)).toBe('Trip is today');
    expect(countdownLabel('Trip', now + day, now)).toBe('Trip is tomorrow');
    expect(countdownLabel('Trip', now + 12 * day, now)).toBe('12 days until Trip');
    expect(countdownLabel('Trip', now - day, now)).toBe('1 day since Trip');
    expect(countdownLabel('Us', now - 412 * day, now)).toBe('412 days since Us');
    expect(countdownLabel('', now, now)).toBeNull();
    expect(countdownLabel('Trip', Number.MAX_SAFE_INTEGER, now)).toBeNull();
  });

  it('is set, capped and cleared by events', () => {
    let custom = applyEvent(chatCustom(undefined), { countdownTitle: '  ' + 'T'.repeat(300), countdownAt: 1_800_000_000_000 });
    expect(custom.countdownTitle.length).toBe(MAX_COUNTDOWN_TITLE);
    expect(custom.countdownAt).toBe(1_800_000_000_000);
    custom = applyEvent(custom, { countdownTitle: 'Trip', countdownAt: Number.MAX_SAFE_INTEGER });
    expect(custom.countdownTitle).toBe('');
    custom = applyEvent(applyEvent(custom, { countdownTitle: 'Trip', countdownAt: 5 }), { countdownTitle: '' });
    expect(custom.countdownTitle).toBe('');
    expect(custom.countdownAt).toBe(0);
  });
});

describe('places', () => {
  it('measures distance', () => {
    expect(Math.abs(distance(0, 0, 1, 0) - 111_195)).toBeLessThan(100);
    expect(distance(52.52, 13.405, 52.5205, 13.4055)).toBeLessThan(ARRIVED_WITHIN);
    expect(placeName('  ')).toBe('their destination');
  });
});
