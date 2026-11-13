import { ArrowRight, Eye, EyeOff } from 'lucide-react';
import { AnimatePresence, motion } from 'motion/react';
import { useEffect, useState, type FormEvent } from 'react';
import { messageOf } from '../../core/errors';
import { checkPassword, loadPasswordRules, passwordAdvice } from '../../core/passwordRules';
import { signIn, signUp } from '../../core/session';
import { setWeakPassword } from '../../core/ui';
import { Segmented, Spinner } from '../../ui/bits';

export type AuthMode = 'signin' | 'join';

/** Sign in, or join with an invite. Passwords never leave the device: they are stretched with Argon2id here. */
export function AuthForm({ mode, onMode, initialInvite = '' }: { mode: AuthMode; onMode: (mode: AuthMode) => void; initialInvite?: string }) {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [invite, setInvite] = useState(initialInvite);
  const [show, setShow] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // The list of common passwords arrives a moment after the form; show the hint again once it has.
  const [, setRulesReady] = useState(false);
  useEffect(() => void loadPasswordRules().then(() => setRulesReady(true)), []);
  const advice = mode === 'join' && password ? passwordAdvice(password, username) : null;

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    if (mode === 'join') {
      if (!/^[a-z0-9._]{2,30}$/.test(username.trim().toLowerCase())) return setError('Usernames are 2–30 characters: letters, numbers, dots and underscores.');
      await loadPasswordRules();
      const weak = passwordAdvice(password, username);
      if (weak) return setError(weak);
      if (password !== confirm) return setError('The passwords don’t match.');
    }
    setBusy(true);
    try {
      if (mode === 'join') await signUp(invite, username, password);
      else {
        await signIn(username, password);
        // An older password the rules would refuse now: ask for a better one, once signed in.
        await loadPasswordRules();
        setWeakPassword(checkPassword(password, username) !== null);
      }
      history.replaceState(null, '', '/');
    } catch (err) {
      setError(messageOf(err));
      setBusy(false);
    }
  };

  return (
    <form className="auth-card" onSubmit={submit}>
      <Segmented
        id="auth"
        value={mode}
        onChange={(m) => {
          onMode(m);
          setError(null);
        }}
        options={[
          { id: 'signin', label: 'Sign in' },
          { id: 'join', label: 'Join with invite' },
        ]}
      />
      <AnimatePresence initial={false}>
        {mode === 'join' && (
          <motion.label className="field" initial={{ opacity: 0, height: 0 }} animate={{ opacity: 1, height: 'auto' }} exit={{ opacity: 0, height: 0 }}>
            <span>Invite code</span>
            <input className="input" value={invite} onChange={(e) => setInvite(e.target.value.toUpperCase())} placeholder="ABCDE-FGHJK" autoComplete="off" required />
          </motion.label>
        )}
      </AnimatePresence>
      <label className="field">
        <span>Username</span>
        <input
          className="input"
          value={username}
          onChange={(e) => setUsername(e.target.value)}
          autoComplete="username"
          autoCapitalize="none"
          spellCheck={false}
          placeholder="yourname"
          autoFocus={mode === 'signin'}
          required
        />
      </label>
      <label className="field">
        <span>Password</span>
        <div className="password">
          <input
            className="input"
            type={show ? 'text' : 'password'}
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete={mode === 'join' ? 'new-password' : 'current-password'}
            required
          />
          <button type="button" className="icon-btn sm" onClick={() => setShow((s) => !s)} aria-label={show ? 'Hide password' : 'Show password'}>
            {show ? <EyeOff size={18} /> : <Eye size={18} />}
          </button>
        </div>
      </label>
      {mode === 'join' && password && <PasswordHint advice={advice} />}
      <AnimatePresence initial={false}>
        {mode === 'join' && (
          <motion.label className="field" initial={{ opacity: 0, height: 0 }} animate={{ opacity: 1, height: 'auto' }} exit={{ opacity: 0, height: 0 }}>
            <span>Confirm password</span>
            <input className="input" type={show ? 'text' : 'password'} value={confirm} onChange={(e) => setConfirm(e.target.value)} autoComplete="new-password" required />
          </motion.label>
        )}
      </AnimatePresence>
      {error && (
        <motion.p className="form-error" initial={{ opacity: 0, y: -4 }} animate={{ opacity: 1, y: 0 }}>
          {error}
        </motion.p>
      )}
      <button className="btn btn-bold btn-lg btn-block" disabled={busy}>
        {busy ? (
          <>
            <Spinner size={18} /> {mode === 'join' ? 'Creating your keys…' : 'Unlocking your keys…'}
          </>
        ) : (
          <>
            {mode === 'join' ? 'Create account' : 'Sign in'} <ArrowRight size={18} />
          </>
        )}
      </button>
      <p className="fine">
        {mode === 'join'
          ? 'Your password encrypts your keys. If you forget it, nobody can recover your messages, not even the server.'
          : 'Signing in on a new device restores everything, decrypted right here.'}
      </p>
    </form>
  );
}

/** Under a new password: what's wrong with it, or that it will do. */
export function PasswordHint({ advice }: { advice: string | null }) {
  return <span className={`password-hint ${advice ? '' : 'good'}`}>{advice ?? 'Strong enough.'}</span>;
}
