import { HardDrive, LogIn, RefreshCw, ShieldAlert, X } from 'lucide-react';
import { useCallback, useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { api } from '../../core/api';
import { messageOf } from '../../core/errors';
import { toast, useApp } from '../../core/store';
import type { AdminAlertDto, SessionDto } from '../../core/types';
import { realtime } from '../../core/ws';
import { Ago } from '../../ui/bits';
import { Sheet } from '../../ui/Sheet';
import { useCall } from '../calls/calls';

const store = {
  get(key: string): string | null {
    try {
      return localStorage.getItem(key);
    } catch {
      return null;
    }
  },
  set(key: string, value: string) {
    try {
      localStorage.setItem(key, value);
    } catch {
      /* private mode: the alert may show again, which is the safe side */
    }
  },
};

/**
 * "Was this you?" whenever another device signs in to the account. The server tells every signed-in device the moment
 * it happens (a 'session' frame, and an unmutable push); on start the app also looks for sign-ins it hasn't asked about
 * yet. Answering "Not me" signs that device out at once and takes you to change your password.
 */
export function SignInAlert() {
  const me = useApp((s) => s.me?.id);
  const navigate = useNavigate();
  const [pending, setPending] = useState<SessionDto[]>([]);
  // Not in the middle of a call: it waits until the call ends.
  const inCall = useCall((s) => s.status !== 'idle');
  const slot = `hush.signInsSeen.${me}`;

  const check = useCallback(async () => {
    if (!me) return;
    const sessions = await api.get<SessionDto[]>('/auth/sessions').catch(() => null);
    if (!sessions) return;
    const current = sessions.find((s) => s.current);
    // The first time on this device, sign-ins before this one are not news.
    const seen = Number(store.get(slot) ?? current?.createdAt ?? Date.now());
    if (store.get(slot) == null) store.set(slot, String(seen));
    setPending(sessions.filter((s) => !s.current && s.createdAt > seen).sort((a, b) => b.createdAt - a.createdAt));
  }, [me, slot]);

  useEffect(() => {
    void check();
    return realtime.on((frame) => frame.t === 'session' && void check());
  }, [check]);

  const latest = pending[0];
  const answered = () => {
    store.set(slot, String(Math.max(...pending.map((s) => s.createdAt))));
    setPending([]);
  };
  const notMe = async () => {
    try {
      for (const s of pending) await api.delete(`/auth/sessions/${s.id}`);
      answered();
      toast(pending.length > 1 ? 'Those devices were signed out' : 'That device was signed out', 'success');
      navigate('/settings/account');
    } catch (e) {
      toast(messageOf(e), 'error');
    }
  };
  if (!latest || inCall) return null;
  return (
    <Sheet open onClose={answered} title="New sign-in" bottom>
      <div className="signin-alert">
        <span className="signin-alert-icon">
          <LogIn size={26} />
        </span>
        <p>
          <b>{latest.deviceName}</b> signed in to your account <Ago at={latest.createdAt} className="" suffix />.
          {pending.length > 1 && <> {pending.length - 1} more device{pending.length > 2 ? 's' : ''} signed in too.</>}
        </p>
        <p className="faint small">If this wasn’t you, sign {pending.length > 1 ? 'them' : 'it'} out now and change your password. Your messages stay encrypted either way: a new device only gets in with your password.</p>
        <div className="signin-alert-actions">
          <button className="btn btn-danger" onClick={() => void notMe()}>
            Not me
          </button>
          <button className="btn btn-secondary" onClick={answered}>
            That was me
          </button>
        </div>
      </div>
    </Sheet>
  );
}

const ALERT_ICONS: Record<string, typeof HardDrive> = { disk: HardDrive, backup: ShieldAlert, update: RefreshCw };

/**
 * For admins: what's wrong with the server (backups, disk, an update), as soon as it happens, until it is fixed. Shown at
 * the top of the home feed (it never covers anything); the Android app also raises it as a notification.
 */
export function AdminAlertBanner() {
  const isAdmin = useApp((s) => !!s.me?.isAdmin);
  const [alerts, setAlerts] = useState<AdminAlertDto[]>([]);
  const [dismissed, setDismissed] = useState<string[]>(() => (store.get('hush.adminAlertsDismissed') ?? '').split(',').filter(Boolean));

  useEffect(() => {
    if (!isAdmin) return;
    const load = () => void api.get<AdminAlertDto[]>('/admin/alerts').then(setAlerts).catch(() => undefined);
    load();
    const every = setInterval(load, 30 * 60_000);
    const off = realtime.on((frame) => frame.t === 'admin' && load());
    return () => {
      clearInterval(every);
      off();
    };
  }, [isAdmin]);

  const shown = alerts.find((a) => !dismissed.includes(a.id));
  if (!isAdmin || !shown) return null;
  const Icon = ALERT_ICONS[shown.kind] ?? ShieldAlert;
  const dismiss = () => {
    const next = [...dismissed, shown.id].slice(-20);
    store.set('hush.adminAlertsDismissed', next.join(','));
    setDismissed(next);
  };
  return (
    <div className={`admin-alert ${shown.kind}`} role="status">
      <Icon size={17} />
      <span className="admin-alert-text">
        <b>{shown.title}</b>
        <span>{shown.text}</span>
      </span>
      <Link to="/admin" onClick={dismiss}>
        Open
      </Link>
      <button className="icon-btn sm" onClick={dismiss} aria-label="Hide this alert">
        <X size={16} />
      </button>
    </div>
  );
}
