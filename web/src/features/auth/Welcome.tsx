import { ArrowRight, Eye, EyeOff, LockKeyhole, ShieldCheck } from 'lucide-react';
import { AnimatePresence, motion } from 'motion/react';
import { useState, type FormEvent } from 'react';
import { messageOf } from '../../core/errors';
import { signIn, signUp } from '../../core/session';
import { BrandMark, Wordmark } from '../../ui/Brand';
import { Segmented, Spinner } from '../../ui/bits';

type Mode = 'signin' | 'join';

/** Sign in, or join with an invite. Passwords never leave the device: they are stretched with Argon2id here. */
export function Welcome() {
  const [mode, setMode] = useState<Mode>(() => (new URLSearchParams(location.search).get('invite') ? 'join' : 'signin'));
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [invite, setInvite] = useState(() => new URLSearchParams(location.search).get('invite') ?? '');
  const [show, setShow] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    if (mode === 'join') {
      if (!/^[a-z0-9._]{2,30}$/.test(username.trim().toLowerCase())) return setError('Usernames are 2–30 characters: letters, numbers, dots and underscores.');
      if (password.length < 10) return setError('Use at least 10 characters. Your password also protects your encryption keys.');
      if (password !== confirm) return setError('The passwords don’t match.');
    }
    setBusy(true);
    try {
      if (mode === 'join') await signUp(invite, username, password);
      else await signIn(username, password);
      history.replaceState(null, '', '/');
    } catch (err) {
      setError(messageOf(err));
      setBusy(false);
    }
  };

  return (
    <div className="welcome">
      <div className="aurora" aria-hidden>
        <span />
        <span />
        <span />
      </div>
      <main className="welcome-inner">
        <motion.section className="welcome-hero" initial={{ opacity: 0, y: 12 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.6, ease: [0.16, 1, 0.3, 1] }}>
          <BrandMark size={56} />
          <h1>
            <Wordmark className="hero-word" />
          </h1>
          <p className="tagline">
            Your people, your moments.
            <br />
            <span className="grad-text">Encrypted end to end.</span>
          </p>
          <ul className="promises">
            <li>
              <LockKeyhole size={18} /> Posts, stories, messages and calls are encrypted on your device.
            </li>
            <li>
              <ShieldCheck size={18} /> The server only ever stores ciphertext it cannot read.
            </li>
          </ul>
        </motion.section>

        <motion.form
          className="auth-card"
          onSubmit={submit}
          initial={{ opacity: 0, y: 20, scale: 0.98 }}
          animate={{ opacity: 1, y: 0, scale: 1 }}
          transition={{ duration: 0.6, delay: 0.08, ease: [0.16, 1, 0.3, 1] }}
        >
          <Segmented
            id="auth"
            value={mode}
            onChange={(m) => {
              setMode(m);
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
          <button className="btn btn-brand btn-lg btn-block" disabled={busy}>
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
              ? 'Your password encrypts your keys. If you forget it, nobody — not even the server — can recover your messages.'
              : 'Signing in on a new device restores everything, decrypted right here.'}
          </p>
        </motion.form>
      </main>
    </div>
  );
}
