import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { beforeAll, describe, expect, it } from 'vitest';
import { checkPassword, loadPasswordRules } from './passwordRules';

const root = resolve(__dirname, '../../..');

describe('password rules', () => {
  beforeAll(loadPasswordRules);

  it('give the same answers as the Android app (crypto-vectors/password-rules.json)', () => {
    const { cases } = JSON.parse(readFileSync(resolve(root, 'crypto-vectors/password-rules.json'), 'utf8')) as {
      cases: { password: string; username: string; result: string }[];
    };
    for (const c of cases) expect(checkPassword(c.password, c.username) ?? 'ok', c.password).toBe(c.result);
  });

  it('use the same list of common passwords as the Android app', () => {
    const shared = readFileSync(resolve(root, 'shared/client/src/main/resources/xyz/aezdek/hush/client/common-passwords.txt'), 'utf8');
    expect(readFileSync(resolve(__dirname, 'common-passwords.txt'), 'utf8')).toBe(shared);
  });
});
