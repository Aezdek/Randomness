import { Check, Copy, HardDrive, LogOut, MoreHorizontal, Server, Ticket, UserMinus, Users } from 'lucide-react';
import { useCallback, useEffect, useState, type ReactNode } from 'react';
import { Navigate } from 'react-router-dom';
import { api } from '../../core/api';
import { messageOf } from '../../core/errors';
import { bytesLabel } from '../../core/media';
import { useIndex } from '../../core/selectors';
import { toast, useApp } from '../../core/store';
import type { AdminStatus, AdminUserDto, InviteDto } from '../../core/types';
import { Avatar } from '../../ui/Avatar';
import { Ago, Center, Spinner } from '../../ui/bits';
import { Sheet } from '../../ui/Sheet';

const DAY = 86_400_000;
const run = (p: Promise<unknown>, done?: string) => p.then(() => done && toast(done, 'success')).catch((e) => toast(messageOf(e), 'error'));

function copy(text: string, done: string) {
  void navigator.clipboard.writeText(text).then(
    () => toast(done, 'success'),
    () => toast('Copying was blocked; select the text instead', 'error'),
  );
}

function Stat({ label, value, sub, tone }: { label: string; value: ReactNode; sub?: ReactNode; tone?: 'ok' | 'warn' }) {
  return (
    <div className={`admin-stat ${tone ?? ''}`}>
      <span className="admin-stat-label">{label}</span>
      <span className="admin-stat-value">{value}</span>
      {sub && <span className="admin-stat-sub">{sub}</span>}
    </div>
  );
}

function Group({ title, action, footer, children }: { title: string; action?: ReactNode; footer?: ReactNode; children: ReactNode }) {
  return (
    <section className="admin-group">
      <header>
        <h2>{title}</h2>
        {action}
      </header>
      <div className="admin-list">{children}</div>
      {footer && <p className="admin-foot">{footer}</p>}
    </section>
  );
}

function Command({ label, command }: { label: string; command: string }) {
  return (
    <div className="admin-row">
      <span className="admin-row-main">
        <b>{label}</b>
        <code>{command}</code>
      </span>
      <button className="btn btn-secondary btn-sm" onClick={() => copy(command, 'Command copied')}>
        <Copy size={14} /> Copy
      </button>
    </div>
  );
}

/** The admin panel: how the server is doing, who's on it, invites, backups and the commands that run it. */
export function Admin() {
  const me = useApp((s) => s.me);
  const index = useIndex();
  const [status, setStatus] = useState<AdminStatus | null>(null);
  const [users, setUsers] = useState<AdminUserDto[]>([]);
  const [invites, setInvites] = useState<InviteDto[]>([]);
  const [menu, setMenu] = useState<AdminUserDto | null>(null);
  const [removing, setRemoving] = useState<AdminUserDto | null>(null);
  const [typed, setTyped] = useState('');

  const load = useCallback(() => {
    void api.get<AdminStatus>('/admin/status').then(setStatus).catch((e) => toast(messageOf(e), 'error'));
    void api.get<AdminUserDto[]>('/admin/users').then(setUsers).catch(() => {});
    void api.get<InviteDto[]>('/admin/invites').then(setInvites).catch(() => {});
  }, []);
  useEffect(load, [load]);

  if (me && !me.isAdmin) return <Navigate to="/settings" replace />;
  if (!status) return <Center style={{ minHeight: '60vh' }}><Spinner /></Center>;

  const now = Date.now();
  const backupAge = status.lastBackupAt ? now - status.lastBackupAt : null;
  const backupOk = backupAge !== null && backupAge < 2 * DAY && status.lastBackupOk !== false;

  return (
    <div className="admin page">
      <header className="admin-head">
        <div>
          <h1>Admin</h1>
          <p className="faint">
            Hush {status.version}
            {status.startedAt ? <> · up since <Ago at={status.startedAt} className="" /> ago</> : null}
          </p>
        </div>
        {status.updateAvailable && status.latestVersion && <span className="admin-pill">Update {status.latestVersion} available</span>}
      </header>

      <div className="admin-stats">
        <Stat label="Members" value={`${status.users} of ${status.maxUsers}`} sub={`${status.maxUsers - status.users} places left`} />
        <Stat label="Stored" value={bytesLabel(status.dataBytes)} sub="encrypted media and data" />
        <Stat
          label="Free disk"
          value={bytesLabel(status.diskFreeBytes)}
          sub={status.uploadsBlocked ? 'Uploads paused until there is more room' : 'on the server'}
          tone={status.uploadsBlocked ? 'warn' : undefined}
        />
        <Stat
          label="Last backup"
          value={status.lastBackupAt ? <Ago at={status.lastBackupAt} className="" /> : 'Never'}
          sub={backupOk ? 'Encrypted, off-site' : status.backupMessage || 'Not set up yet'}
          tone={backupOk ? 'ok' : 'warn'}
        />
      </div>

      <Group title="Members" action={<span className="faint">{users.length}</span>}>
        {users.map((u) => {
          const profile = index.profiles[u.id]?.payload;
          return (
            <div className="admin-row member" key={u.id}>
              <Avatar userId={u.id} size={40} />
              <span className="admin-row-main">
                <b>
                  {profile?.displayName?.trim() || u.username}
                  {u.isAdmin && <span className="admin-tag">Admin</span>}
                </b>
                <span className="faint">
                  @{u.username} · joined {new Date(u.createdAt).toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' })}
                </span>
              </span>
              <span className="admin-cell hide-mobile">
                <b>{u.posts}</b>
                <span className="faint">posts</span>
              </span>
              <span className="admin-cell hide-mobile">
                <b>{bytesLabel(u.storageBytes)}</b>
                <span className="faint">media</span>
              </span>
              <span className="admin-cell hide-mobile">
                <b>{u.sessions}</b>
                <span className="faint">{u.sessions === 1 ? 'device' : 'devices'}</span>
              </span>
              <span className="admin-cell">
                <b>{u.lastActiveAt ? <Ago at={u.lastActiveAt} className="" /> : '—'}</b>
                <span className="faint">active</span>
              </span>
              {u.id !== me?.id && !u.isAdmin ? (
                <button className="icon-btn sm" aria-label={`Manage ${u.username}`} onClick={() => setMenu(u)}>
                  <MoreHorizontal size={18} />
                </button>
              ) : (
                <span className="icon-btn sm" aria-hidden />
              )}
            </div>
          );
        })}
      </Group>

      <Group
        title="Invites"
        action={
          <button className="btn btn-primary btn-sm" disabled={status.users >= status.maxUsers} onClick={() => void run(api.post('/admin/invites'), 'Invite created').then(load)}>
            <Ticket size={14} /> New invite
          </button>
        }
        footer="Each code lets one person join, within 7 days."
      >
        {invites.length === 0 && (
          <div className="admin-row">
            <span className="faint">No invites yet.</span>
          </div>
        )}
        {invites.map((i) => {
          const open = !i.usedBy && i.expiresAt > now;
          const used = i.usedBy ? users.find((u) => u.id === i.usedBy)?.username : null;
          return (
            <div className="admin-row" key={i.code}>
              <span className={`admin-dot ${open ? 'ok' : ''}`} />
              <span className="admin-row-main">
                <b className="tabular">{i.code}</b>
                <span className="faint">
                  {i.usedBy ? `Used${used ? ` by @${used}` : ''}` : i.expiresAt < now ? 'Expired' : `Expires ${new Date(i.expiresAt).toLocaleDateString(undefined, { month: 'short', day: 'numeric' })}`}
                </span>
              </span>
              {open && (
                <>
                  <button className="btn btn-secondary btn-sm" onClick={() => copy(`${location.origin}/?invite=${i.code}`, 'Invite link copied')}>
                    <Copy size={14} /> Copy link
                  </button>
                  <button className="btn btn-danger btn-sm" onClick={() => void run(api.delete(`/admin/invites/${i.code}`), 'Invite withdrawn').then(load)}>
                    Revoke
                  </button>
                </>
              )}
            </div>
          );
        })}
      </Group>

      <Group
        title="Backups"
        footer="Snapshots, media and the app's signing key go to your encrypted Google Drive remote every night at 03:30, kept for 30 days."
      >
        <div className="admin-row">
          <span className={`admin-icon ${backupOk ? 'ok' : 'warn'}`}>{backupOk ? <Check size={16} /> : <HardDrive size={16} />}</span>
          <span className="admin-row-main">
            <b>{backupOk ? 'Backed up' : status.lastBackupAt ? 'Backup is overdue' : 'Backups are off'}</b>
            <span className="faint">
              {status.lastBackupAt ? `Last success ${new Date(status.lastBackupAt).toLocaleString()}` : 'Set up the rclone remote, then run install.sh again.'}
              {status.backupMessage ? ` · ${status.backupMessage}` : ''}
            </span>
          </span>
        </div>
        <Command label="Back up now" command="sudo systemctl start hush-backup" />
      </Group>

      <Group title="Server" footer="Run these on Resolute over SSH. Updates only happen when you run update.sh.">
        <div className="admin-row">
          <span className="admin-icon"><Server size={16} /></span>
          <span className="admin-row-main">
            <b>{status.updateAvailable && status.latestVersion ? `Hush ${status.latestVersion} is ready to install` : 'Up to date'}</b>
            <span className="faint">
              Running {status.version}
              {status.commit ? ` (${status.commit.slice(0, 12)})` : ''}
            </span>
          </span>
        </div>
        <Command label="Update" command="sudo /opt/hl/hush/update.sh" />
        <Command label="Go back a version" command="sudo /opt/hl/hush/update.sh --rollback" />
        <Command label="Logs" command="docker compose -f /opt/hl/hush/compose.yml logs -f" />
      </Group>

      <Sheet open={!!menu} onClose={() => setMenu(null)} hideHeader bottom bodyClass="flush">
        {menu && (
          <>
            <div className="admin-sheet-head">
              <Avatar userId={menu.id} size={56} />
              <b>@{menu.username}</b>
            </div>
            <div className="menu">
              <button
                onClick={() => {
                  const target = menu;
                  setMenu(null);
                  void run(api.post(`/admin/users/${target.id}/sign-out`), `@${target.username} is signed out everywhere`).then(load);
                }}
              >
                <LogOut size={18} /> Sign out on all devices
              </button>
              <button
                className="danger"
                onClick={() => {
                  setRemoving(menu);
                  setTyped('');
                  setMenu(null);
                }}
              >
                <UserMinus size={18} /> Remove from Hush…
              </button>
              <button style={{ justifyContent: 'center' }} onClick={() => setMenu(null)}>
                Cancel
              </button>
            </div>
          </>
        )}
      </Sheet>

      <Sheet open={!!removing} onClose={() => setRemoving(null)} title="Remove member">
        {removing && (
          <div className="admin-remove">
            <p>
              This deletes <b>@{removing.username}</b>'s account and everything they posted, for everyone. It can't be undone, though it stays in
              the encrypted backups for 30 days.
            </p>
            <label className="field">
              <span>Type {removing.username} to confirm</span>
              <input className="input" value={typed} onChange={(e) => setTyped(e.target.value)} autoComplete="off" autoCapitalize="off" />
            </label>
            <button
              className="btn btn-danger btn-block"
              disabled={typed.trim() !== removing.username}
              onClick={() => {
                const target = removing;
                setRemoving(null);
                void run(api.delete(`/admin/users/${target.id}`), `@${target.username} was removed`).then(load);
              }}
            >
              <Users size={16} /> Remove @{removing.username}
            </button>
          </div>
        )}
      </Sheet>
    </div>
  );
}
