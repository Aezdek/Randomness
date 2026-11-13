/*
 * What makes a password good enough for Hush. Anyone holding a copy of the server's data can guess at a password
 * offline for as long as they like, so the ones attackers try first are refused. The Android app has the same rules
 * (shared/client PasswordRules.kt); crypto-vectors/password-rules.json keeps the two giving identical answers.
 *
 * The list of common passwords is SecLists' 10,000 most common (MIT licence, Daniel Miessler), each written the way
 * core() reads a password. It loads on first use, so it costs nothing until a password form opens.
 */

export const MIN_LENGTH = 12;

export type PasswordProblem = 'too_short' | 'has_username' | 'repetitive' | 'only_digits' | 'predictable' | 'common';

export const PROBLEM_MESSAGES: Record<PasswordProblem, string> = {
  too_short: `Use at least ${MIN_LENGTH} characters. A few unrelated words make a good one.`,
  has_username: 'Leave your username out of your password.',
  repetitive: 'Use more different characters. Repeats are easy to guess.',
  only_digits: 'Mix in some letters. Numbers alone are easy to guess.',
  predictable: 'Avoid runs like abcd, 1234 or qwerty.',
  common: 'That password, or one very like it, is on the lists attackers try first.',
};

const LEET: Record<string, string> = {
  '0': 'o', '1': 'i', '3': 'e', '4': 'a', '5': 's', '7': 't', '8': 'b', '9': 'g', '@': 'a', $: 's', '!': 'i', '|': 'i', '+': 't',
};
const ROWS = ['1234567890', 'qwertyuiop', 'asdfghjkl', 'zxcvbnm'];

let common: Set<string> | null = null;

/** Loads the list of common passwords (once). check() needs it; password forms call this when they open. */
export async function loadPasswordRules(): Promise<void> {
  if (common) return;
  const { default: text } = await import('./common-passwords.txt?raw');
  common = new Set(text.split('\n').filter(Boolean));
}

const isLetter = (c: string) => c >= 'a' && c <= 'z';

/** Lower case, without the digits and symbols people tack on at either end. */
function trimmed(password: string): string {
  const s = password.toLowerCase();
  let i = 0;
  let j = s.length;
  while (i < j && !isLetter(s[i])) i++;
  while (j > i && !isLetter(s[j - 1])) j--;
  return s.slice(i, j);
}

/** The trimmed password with look-alike characters read as letters (p@ssw0rd is password). */
const core = (password: string) => [...trimmed(password)].map((c) => LEET[c] ?? c).join('');

/** Just its letters a to z (letmein!letmein is letmeinletmein). */
const letters = (password: string) => [...trimmed(password)].filter(isLetter).join('');

/** On the list, or two words from it run together. */
function isCommon(word: string, list: Set<string>): boolean {
  if (!word) return false;
  if (list.has(word)) return true;
  const points = [...word];
  for (let i = 3; i < points.length - 2; i++) {
    if (list.has(points.slice(0, i).join('')) && list.has(points.slice(i).join(''))) return true;
  }
  return false;
}

function adjacent(a: string, b: string): boolean {
  if (a === b || Math.abs(a.codePointAt(0)! - b.codePointAt(0)!) === 1) return true;
  return ROWS.some((row) => {
    const ia = row.indexOf(a);
    const ib = row.indexOf(b);
    return ia >= 0 && ib >= 0 && Math.abs(ia - ib) === 1;
  });
}

/** How many characters sit in runs of three or more: repeats, alphabet or number order, or keyboard rows. */
function predictable(points: string[]): number {
  let covered = 0;
  let start = 0;
  for (let k = 1; k <= points.length; k++) {
    if (k === points.length || !adjacent(points[k - 1], points[k])) {
      if (k - start >= 3) covered += k - start;
      start = k;
    }
  }
  return covered;
}

/** What is wrong with the password, or null if it will do. Call loadPasswordRules() first. */
export function checkPassword(password: string, username = ''): PasswordProblem | null {
  const points = [...password];
  const lower = password.toLowerCase();
  if (points.length < MIN_LENGTH) return 'too_short';
  const name = username.trim().toLowerCase();
  if (name.length >= 3 && lower.includes(name)) return 'has_username';
  if (new Set(points).size < 6) return 'repetitive';
  if (points.every((c) => c >= '0' && c <= '9')) return 'only_digits';
  const lowerPoints = [...lower];
  if (predictable(lowerPoints) * 2 >= lowerPoints.length) return 'predictable';
  const list = common ?? new Set<string>();
  if (isCommon(core(password), list) || isCommon(letters(password), list)) return 'common';
  return null;
}

/** The sentence to show for what's wrong, or null. */
export function passwordAdvice(password: string, username = ''): string | null {
  const problem = checkPassword(password, username);
  return problem ? PROBLEM_MESSAGES[problem] : null;
}
