import { AlertCircle, Check, ChevronDown, Copy, Download, HardDrive, KeyRound, Lock, Power, RefreshCw, RotateCw, Server, ShieldOff, Unlock } from 'lucide-react';
import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react';
import { api } from '../../core/api';
import { messageOf } from '../../core/errors';
import { toast } from '../../core/store';
import { SERVER_LIMITS, type AdminServerDto, type AdminStatus, type ControlStatusDto, type ServerActionId, type ServerSettingsDto } from '../../core/types';
import { Ago, Spinner, Switch } from '../../ui/bits';
import { Sheet } from '../../ui/Sheet';

/*
 * The admin panel's Server tab: install updates, restart, back up and check for updates (run on the server by its
 * control helper), the server's settings (data/config.json, as a form), and the switch that turns this web admin
 * off. Changing anything needs the server password, which unlocks these controls for ten minutes.
 */

const ACTIONS: Record<ServerActionId, { verb: string; running: string; done: string; failed: string }> = {
  update: { verb: 'Install update', running: 'Installing the update', done: 'Updated', failed: "The update didn't go through" },
  restart: { verb: 'Restart', running: 'Restarting Hush', done: 'Restarted', failed: "Hush didn't restart cleanly" },
  backup: { verb: 'Back up now', running: 'Backing up', done: 'Backed up', failed: 'The backup failed' },
  check: { verb: 'Check for updates', running: 'Checking for updates', done: 'Checked for updates', failed: "Couldn't check for updates" },
};

function copy(text: string) {
  void navigator.clipboard.writeText(text).then(
    () => toast('Command copied', 'success'),
    () => toast('Copying was blocked; select the text instead', 'error'),
  );
}

function Card({ title, icon, children, footer }: { title: string; icon: ReactNode; children: ReactNode; footer?: ReactNode }) {
  return (
    <section className="server-card">
      <header>
        <span className="server-card-icon">{icon}</span>
        <h2>{title}</h2>
      </header>
      {children}
      {footer && <p className="admin-foot">{footer}</p>}
    </section>
  );
}

function Stepper({ label, value, min, max, unit, onChange, hint }: { label: string; value: number; min: number; max: number; unit: string; onChange: (v: number) => void; hint?: string }) {
  const clamp = (v: number) => Math.min(max, Math.max(min, Math.round(v || min)));
  return (
    <label className="server-field">
      <span>
        <b>{label}</b>
        {hint && <span className="faint">{hint}</span>}
      </span>
      <span className="stepper">
        <button type="button" onClick={() => onChange(clamp(value - 1))} disabled={value <= min} aria-label={`Fewer: ${label}`}>
          −
        </button>
        <input type="number" inputMode="numeric" min={min} max={max} value={value} onChange={(e) => onChange(clamp(Number(e.target.value)))} aria-label={label} />
        <button type="button" onClick={() => onChange(clamp(value + 1))} disabled={value >= max} aria-label={`More: ${label}`}>
          +
        </button>
        <em>{unit}</em>
      </span>
    </label>
  );
}

function JobStatus({ job, log }: { job: ControlStatusDto; log?: string | null }) {
  const [open, setOpen] = useState(false);
  const words = ACTIONS[job.action as ServerActionId] ?? { running: job.action, done: job.action, failed: job.action };
  const busy = job.state === 'queued' || job.state === 'running';
  const at = job.finishedAt ?? job.startedAt ?? job.requestedAt;
  return (
    <div className={`server-job ${job.state}`}>
      <span className="server-job-icon">{busy ? <Spinner size={16} /> : job.state === 'done' ? <Check size={16} /> : <AlertCircle size={16} />}</span>
      <span className="server-job-text">
        <b>{busy ? `${words.running}…` : job.state === 'done' ? words.done : words.failed}</b>
        <span className="faint">
          {job.state === 'queued' ? 'Waiting for the server to pick it up' : job.message || ''}
          {!busy && at ? (
            <>
              {job.message ? ' · ' : ''}
              <Ago at={at} className="" suffix />
            </>
          ) : null}
        </span>
      </span>
      {log && (
        <button className="btn btn-secondary btn-sm" onClick={() => setOpen((o) => !o)} aria-expanded={open}>
          Output <ChevronDown size={14} className={open ? 'flip' : ''} />
        </button>
      )}
      {open && log && <pre className="server-log">{log}</pre>}
    </div>
  );
}

export function ServerPanel({ status, onChanged }: { status: AdminStatus; onChanged: () => void }) {
  const [server, setServer] = useState<AdminServerDto | null>(null);
  const [draft, setDraft] = useState<ServerSettingsDto | null>(null);
  const [unlockFor, setUnlockFor] = useState<null | { reason: string; then: () => void }>(null);
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [offline, setOffline] = useState(false);
  const [confirmOff, setConfirmOff] = useState(false);
  const [now, setNow] = useState(Date.now());
  const restarting = useRef(false);

  const load = useCallback(() => {
    return api
      .get<AdminServerDto>('/admin/server')
      .then((s) => {
        setServer(s);
        setDraft((d) => d ?? s.settings);
        setOffline(false);
        if (restarting.current && s.control?.state !== 'running' && s.control?.state !== 'queued') {
          restarting.current = false;
          onChanged();
        }
      })
      .catch((e) => {
        // During an update or a restart the server goes away for a moment; keep asking.
        if (restarting.current) setOffline(true);
        else toast(messageOf(e), 'error');
      });
  }, [onChanged]);
  useEffect(() => void load(), [load]);

  const job = server?.control;
  const working = !!job && (job.state === 'queued' || job.state === 'running');
  useEffect(() => {
    if (!working && !offline) return;
    const timer = setInterval(() => void load(), 2500);
    return () => clearInterval(timer);
  }, [working, offline, load]);
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 15_000);
    return () => clearInterval(timer);
  }, []);

  if (!server || !draft) {
    return (
      <div className="server-loading">
        <Spinner />
      </div>
    );
  }

  const unlocked = !!server.unlockedUntil && server.unlockedUntil > now;
  const minutesLeft = unlocked ? Math.max(1, Math.round((server.unlockedUntil! - now) / 60_000)) : 0;
  const changed = JSON.stringify(draft) !== JSON.stringify(server.settings);

  /** Runs [fn] now if the controls are unlocked, else after the server password unlocks them. */
  const guard = (reason: string, fn: () => void) => (unlocked ? fn() : (setPassword(''), setUnlockFor({ reason, then: fn })));

  const unlock = async () => {
    if (!unlockFor) return;
    setBusy(true);
    try {
      const { unlockedUntil } = await api.post<{ unlockedUntil: number }>('/admin/server/unlock', { password });
      setServer((s) => (s ? { ...s, unlockedUntil } : s));
      setNow(Date.now());
      const next = unlockFor.then;
      setUnlockFor(null);
      setPassword('');
      next();
    } catch (e) {
      toast(messageOf(e), 'error');
    } finally {
      setBusy(false);
    }
  };

  const act = (action: ServerActionId) =>
    guard(ACTIONS[action].verb, () => {
      if (action === 'update' || action === 'restart') restarting.current = true;
      void api
        .post<ControlStatusDto>('/admin/server/actions', { action })
        .then((control) => setServer((s) => (s ? { ...s, control } : s)))
        .catch((e) => toast(messageOf(e), 'error'));
    });

  const save = () =>
    guard('Save the settings', () => {
      void api
        .put<ServerSettingsDto>('/admin/server/settings', draft)
        .then((settings) => {
          setServer((s) => (s ? { ...s, settings } : s));
          setDraft(settings);
          toast('Settings saved', 'success');
          onChanged();
        })
        .catch((e) => toast(messageOf(e), 'error'));
    });

  const turnOff = () =>
    guard('Turn off the web admin', () => {
      setConfirmOff(false);
      void api
        .post('/admin/server/web-admin', { enabled: false })
        .then(() => (toast('The web admin is off', 'success'), onChanged()))
        .catch((e) => toast(messageOf(e), 'error'));
    });

  const lock = () => void api.post('/admin/server/lock').then(() => setServer((s) => (s ? { ...s, unlockedUntil: null } : s)));

  const noAgent = !server.agent;
  const folder = server.folder ?? '/opt/hush';

  return (
    <div className="server-panel">
      <div className={`server-lock ${unlocked ? 'open' : ''}`}>
        <span className="server-lock-icon">{unlocked ? <Unlock size={18} /> : <Lock size={18} />}</span>
        <span>
          <b>{unlocked ? 'Server controls are unlocked' : 'Server controls are locked'}</b>
          <span className="faint">
            {unlocked ? `They lock again in ${minutesLeft} min.` : 'Changing anything here asks for the server password, the one set when Hush was installed.'}
          </span>
        </span>
        {unlocked ? (
          <button className="btn btn-secondary btn-sm" onClick={lock}>
            <Lock size={14} /> Lock now
          </button>
        ) : (
          <button className="btn btn-primary btn-sm" onClick={() => guard('Unlock the server controls', () => toast('Unlocked for 10 minutes', 'success'))}>
            <KeyRound size={14} /> Unlock
          </button>
        )}
      </div>

      {noAgent && (
        <div className="server-callout">
          <AlertCircle size={18} />
          <span>
            <b>One step on the server first</b>
            <span className="faint">The buttons below need Hush's helper on the server. The next update installs it; run this once over SSH:</span>
            <code>sudo {folder}/update.sh</code>
          </span>
          <button className="btn btn-secondary btn-sm" onClick={() => copy(`sudo ${folder}/update.sh`)}>
            <Copy size={14} /> Copy
          </button>
        </div>
      )}

      {offline && (
        <div className="server-callout">
          <Spinner size={16} />
          <span>
            <b>Hush is restarting</b>
            <span className="faint">This page carries on when it's back, usually within a minute.</span>
          </span>
        </div>
      )}

      <Card title="Software" icon={<Server size={16} />}>
        <div className="server-version">
          <span>
            <b>{server.updateAvailable && server.latestVersion ? `Hush ${server.latestVersion} is ready to install` : 'Hush is up to date'}</b>
            <span className="faint">
              Running {server.version}
              {server.commit ? ` (${server.commit.slice(0, 7)})` : ''}
            </span>
          </span>
          {server.updateAvailable && <span className="admin-pill">Update</span>}
        </div>
        <div className="server-actions">
          <button className={`btn ${server.updateAvailable ? 'btn-primary' : 'btn-secondary'}`} disabled={noAgent || working} onClick={() => act('update')}>
            <Download size={16} /> Install update
          </button>
          <button className="btn btn-secondary" disabled={noAgent || working} onClick={() => act('check')}>
            <RefreshCw size={16} /> Check for updates
          </button>
          <button className="btn btn-secondary" disabled={noAgent || working} onClick={() => act('restart')}>
            <RotateCw size={16} /> Restart
          </button>
        </div>
        {job && <JobStatus job={job} log={server.log} />}
      </Card>

      <Card title="Backups" icon={<HardDrive size={16} />} footer="Nightly at 03:30 to your encrypted remote, kept for 30 days. Everything in them is already encrypted.">
        <div className="server-version">
          <span>
            <b>{status.lastBackupAt ? <>Last backed up <Ago at={status.lastBackupAt} className="" suffix /></> : 'No backups yet'}</b>
            <span className="faint">{status.backupMessage || (status.lastBackupOk === false ? 'The last run failed' : 'Off-site, encrypted')}</span>
          </span>
          <button className="btn btn-secondary" disabled={noAgent || working} onClick={() => act('backup')}>
            <HardDrive size={16} /> Back up now
          </button>
        </div>
      </Card>

      <Card title="Settings" icon={<Power size={16} />} footer="Saved on the server in data/config.json.">
        <div className="server-fields">
          <Stepper
            label="Members"
            hint={`Hush takes up to this many people (${status.users} now).`}
            value={draft.maxMembers}
            min={Math.max(SERVER_LIMITS.members[0], status.users)}
            max={SERVER_LIMITS.members[1]}
            unit="people"
            onChange={(maxMembers) => setDraft({ ...draft, maxMembers })}
          />
          <Stepper
            label="Invites last"
            hint="How long a new invite code works."
            value={draft.inviteDays}
            min={SERVER_LIMITS.inviteDays[0]}
            max={SERVER_LIMITS.inviteDays[1]}
            unit={draft.inviteDays === 1 ? 'day' : 'days'}
            onChange={(inviteDays) => setDraft({ ...draft, inviteDays })}
          />
          <Stepper
            label="Keep free"
            hint="Uploads pause when the disk has less than this left."
            value={draft.minFreeGb}
            min={SERVER_LIMITS.minFreeGb[0]}
            max={SERVER_LIMITS.minFreeGb[1]}
            unit="GB"
            onChange={(minFreeGb) => setDraft({ ...draft, minFreeGb })}
          />
        </div>
        <div className="server-actions end">
          {changed && (
            <button className="btn btn-secondary" onClick={() => setDraft(server.settings)}>
              Undo
            </button>
          )}
          <button className="btn btn-primary" disabled={!changed} onClick={save}>
            Save settings
          </button>
        </div>
      </Card>

      <Card title="Web admin" icon={<ShieldOff size={16} />}>
        <div className="server-version">
          <span>
            <b>Admin in the browser and the app</b>
            <span className="faint">Turn it off and this panel closes for every admin. Only the server itself can turn it back on.</span>
          </span>
          <Switch checked label="Web admin" onChange={(on) => !on && setConfirmOff(true)} />
        </div>
      </Card>

      <Sheet open={!!unlockFor} onClose={() => setUnlockFor(null)} title="Server password">
        <form
          className="server-unlock"
          onSubmit={(e) => {
            e.preventDefault();
            void unlock();
          }}
        >
          <p className="faint">{unlockFor?.reason}: enter the server password. The controls then stay unlocked for 10 minutes.</p>
          <label className="field">
            <span>Server password</span>
            <input className="input" type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="off" autoFocus />
          </label>
          <button className="btn btn-primary btn-block" disabled={!password || busy}>
            {busy ? <Spinner size={16} /> : <KeyRound size={16} />} Unlock
          </button>
        </form>
      </Sheet>

      <Sheet open={confirmOff} onClose={() => setConfirmOff(false)} title="Turn off the web admin?">
        <div className="server-unlock">
          <p>
            The admin panel closes for every admin, here and in the app. Members, invites and the server keep working. To turn it back on, run
            this on the server:
          </p>
          <code className="server-code">sudo {folder}/control.sh web-admin on</code>
          <p className="faint">While it's off, that script also makes invites: sudo {folder}/control.sh invite</p>
          <button className="btn btn-danger btn-block" onClick={turnOff}>
            <ShieldOff size={16} /> Turn off the web admin
          </button>
        </div>
      </Sheet>
    </div>
  );
}

/** What admins see while the web admin is off. [command] is what the server says turns it back on. */
export function WebAdminOff({ command }: { command?: string | null }) {
  const on = command || 'sudo ./control.sh web-admin on';
  return (
    <div className="admin page">
      <div className="web-admin-off">
        <ShieldOff size={30} />
        <h1>The web admin is off</h1>
        <p className="faint">It was turned off from this panel. Hush itself keeps working; to manage it here again, turn the web admin back on from the server:</p>
        <div className="server-callout">
          <code>{on}</code>
          <button className="btn btn-secondary btn-sm" onClick={() => copy(on)}>
            <Copy size={14} /> Copy
          </button>
        </div>
        <p className="faint">Invites, while it's off: {on.replace(/web-admin on$/, 'invite')}</p>
      </div>
    </div>
  );
}
