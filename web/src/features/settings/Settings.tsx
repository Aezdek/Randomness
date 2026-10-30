import {
  Archive,
  Bell,
  ChevronRight,
  Download,
  HardDrive,
  KeyRound,
  Laptop,
  LogOut,
  Moon,
  Palette,
  ShieldCheck,
  Smartphone,
  Star,
  Ticket,
  UserX,
  Users,
} from 'lucide-react';
import { useEffect, useState, type ReactNode } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { api } from '../../core/api';
import { saveSettings, unblock } from '../../core/compose';
import { messageOf } from '../../core/errors';
import { bytesLabel } from '../../core/media';
import { displayName, useIndex } from '../../core/selectors';
import { changePassword, deleteAccount, enablePush, pushSupported, resetKeys, signOut, updateMe } from '../../core/session';
import { toast, useApp } from '../../core/store';
import { applyTheme, useUi } from '../../core/ui';
import type { AdminStatus, IdList, InviteDto, SessionDto } from '../../core/types';
import { Avatar } from '../../ui/Avatar';
import { Ago, Center, Segmented, Spinner, Switch, Verified } from '../../ui/bits';
import { Confirm } from '../../ui/Sheet';
import { PostGrid } from '../profile/PostGrid';
import { StoryThumb } from '../stories/StoryThumb';
import { MyQr } from './Verify';
import { safetyCode, groupDigits } from '../../crypto/safety';

const SECTIONS = [
  { id: 'account', label: 'Account', icon: KeyRound },
  { id: 'privacy', label: 'Privacy', icon: ShieldCheck },
  { id: 'close-friends', label: 'Close Friends', icon: Star },
  { id: 'blocked', label: 'Blocked', icon: UserX },
  { id: 'notifications', label: 'Notifications and calls', icon: Bell },
  { id: 'security', label: 'Encryption and devices', icon: Laptop },
  { id: 'archive', label: 'Archive', icon: Archive },
  { id: 'appearance', label: 'Appearance', icon: Palette },
  { id: 'data', label: 'Your data', icon: Download },
] as const;

function Row({ title, sub, children }: { title: ReactNode; sub?: ReactNode; children?: ReactNode }) {
  return (
    <div className="setting-line">
      <span>
        <b>{title}</b>
        {sub && <span className="faint">{sub}</span>}
      </span>
      {children}
    </div>
  );
}

function Card({ title, children }: { title?: string; children: ReactNode }) {
  return (
    <section className="settings-card">
      {title && <h3>{title}</h3>}
      {children}
    </section>
  );
}

const run = (p: Promise<unknown>, done?: string) => p.then(() => done && toast(done, 'success')).catch((e) => toast(messageOf(e), 'error'));

function AccountSection() {
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [busy, setBusy] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [deletePw, setDeletePw] = useState('');
  return (
    <>
      <Card title="Change password">
        <p className="faint small">Your keys are re-sealed under the new password and your other devices are signed out. If an old copy of the server’s data leaked, your old password still opens the old copy — after a suspected leak, reset your keys instead.</p>
        <input className="input" type="password" placeholder="Current password" value={current} onChange={(e) => setCurrent(e.target.value)} autoComplete="current-password" />
        <input className="input" type="password" placeholder="New password (10+ characters)" value={next} onChange={(e) => setNext(e.target.value)} autoComplete="new-password" />
        <button
          className="btn btn-primary"
          disabled={busy || next.length < 10 || !current}
          onClick={() => {
            setBusy(true);
            void run(changePassword(current, next), 'Password changed').finally(() => (setBusy(false), setCurrent(''), setNext('')));
          }}
        >
          {busy ? <Spinner size={16} /> : 'Change password'}
        </button>
      </Card>
      <Card title="Delete account">
        <p className="faint small">Deletes your account and everything you posted, for everyone. It may survive in encrypted backups for up to 30 days.</p>
        <input className="input" type="password" placeholder="Password" value={deletePw} onChange={(e) => setDeletePw(e.target.value)} />
        <button className="btn btn-danger" disabled={!deletePw} onClick={() => setDeleting(true)}>
          Delete my account
        </button>
        <Confirm open={deleting} title="Delete your account?" message="This cannot be undone." confirm="Delete forever" danger onConfirm={() => void run(deleteAccount(deletePw))} onClose={() => setDeleting(false)} />
      </Card>
    </>
  );
}

function PrivacySection() {
  const me = useApp((s) => s.me!);
  return (
    <Card>
      <Row title="Private account" sub="Only approved followers see your posts, reels and stories. Your name and photo stay visible to members.">
        <Switch checked={me.isPrivate} onChange={(v) => void run(updateMe({ isPrivate: v }))} label="Private account" />
      </Row>
      <Row title="Show activity status" sub="People you can see will know when you’re online. Turning this off hides theirs from you too.">
        <Switch checked={me.showActivity} onChange={(v) => void run(updateMe({ showActivity: v }))} label="Show activity status" />
      </Row>
    </Card>
  );
}

function CloseFriendsSection() {
  const users = useApp((s) => s.users);
  const index = useIndex();
  const close = index.settings?.payload.closeFriends ?? [];
  const followers = Object.values(users).filter((u) => u.followsMe === 'following' && !u.deleted);
  return (
    <Card>
      <p className="faint small">Close Friends stories have a green ring. Only people on this list can see them — and they won’t be told they’re on it.</p>
      {followers.map((u) => {
        const on = close.includes(u.id);
        return (
          <div className="user-row" key={u.id}>
            <Avatar userId={u.id} size={44} />
            <div className="user-row-text">
              <b>{u.username}</b>
              <span className="faint">{displayName(u, index.profiles[u.id]?.payload)}</span>
            </div>
            <button className={`star ${on ? 'on' : ''}`} onClick={() => void run(saveSettings({ closeFriends: on ? close.filter((x) => x !== u.id) : [...close, u.id] }))} aria-label={on ? 'Remove from Close Friends' : 'Add to Close Friends'}>
              <Star size={22} fill={on ? 'currentColor' : 'none'} />
            </button>
          </div>
        );
      })}
      {!followers.length && <p className="faint">People who follow you can be added here.</p>}
    </Card>
  );
}

function BlockedSection() {
  const [ids, setIds] = useState<string[] | null>(null);
  const users = useApp((s) => s.users);
  useEffect(() => {
    void api.get<IdList>('/blocks').then((r) => setIds(r.ids));
  }, [users]);
  if (!ids) return <Center><Spinner /></Center>;
  return (
    <Card>
      {ids.map((id) => (
        <div className="user-row" key={id}>
          <Avatar userId={id} size={44} />
          <div className="user-row-text">
            <b>{users[id]?.username}</b>
          </div>
          <button className="btn btn-secondary btn-sm" onClick={() => void run(unblock(id))}>
            Unblock
          </button>
        </div>
      ))}
      {!ids.length && <p className="faint">You haven’t blocked anyone.</p>}
    </Card>
  );
}

function NotificationsSection() {
  const index = useIndex();
  const s = index.settings?.payload ?? {};
  const [permission, setPermission] = useState(typeof Notification !== 'undefined' ? Notification.permission : 'denied');
  const toggles: [keyof typeof s, string][] = [
    ['notifyMessages', 'Messages and calls'],
    ['notifyComments', 'Comments'],
    ['notifyLikes', 'Likes'],
    ['notifyFollows', 'New followers and requests'],
    ['notifyStories', 'Story replies and reactions'],
  ];
  return (
    <>
      <Card title="This browser">
        <Row title="Push notifications" sub={permission === 'granted' ? 'On — sent by your own server, with no content in them.' : 'Get notified when this tab is closed.'}>
          {permission === 'granted' ? (
            <span className="chip">On</span>
          ) : (
            <button className="btn btn-primary btn-sm" disabled={!pushSupported()} onClick={() => void enablePush().then((ok) => setPermission(ok ? 'granted' : Notification.permission))}>
              Turn on
            </button>
          )}
        </Row>
        <Row title="Test ring" sub="Rings this device in 20 seconds. Close or background the tab to check it works.">
          <button className="btn btn-secondary btn-sm" onClick={() => void run(api.post('/push/test-ring', { delaySeconds: 20 }), 'Ringing in 20 seconds')}>
            Test
          </button>
        </Row>
      </Card>
      <Card title="Notify me about">
        {toggles.map(([key, label]) => (
          <Row key={key} title={label}>
            <Switch checked={s[key] !== false} onChange={(v) => void run(saveSettings({ [key]: v }))} label={label} />
          </Row>
        ))}
      </Card>
    </>
  );
}

function SecuritySection() {
  const keys = useApp((s) => s.keys!);
  const users = useApp((s) => s.users);
  const pins = useApp((s) => s.pins);
  const [sessions, setSessions] = useState<SessionDto[] | null>(null);
  const [password, setPassword] = useState('');
  const [resetting, setResetting] = useState(false);
  const [status, setStatus] = useState<string | null>(null);
  const load = () => void api.get<SessionDto[]>('/auth/sessions').then(setSessions);
  useEffect(load, []);
  return (
    <>
      <Card title="Your security code">
        <div className="my-code">
          <MyQr size={164} />
          <div className="safety-grid small">
            {groupDigits(safetyCode(keys.signPk)).map((g, i) => (
              <span key={i} className="tabular">{g}</span>
            ))}
          </div>
        </div>
        <p className="faint small">Everything you post and send is encrypted on your device for exactly the people who may see it. The server stores only ciphertext; it can see who follows and messages whom, and when.</p>
      </Card>
      <Card title="Contacts">
        {Object.values(users)
          .filter((u) => !u.deleted)
          .map((u) => (
            <Link key={u.id} to={`/verify/${u.id}`} className="user-row result">
              <Avatar userId={u.id} size={40} />
              <span className="user-row-text">
                <b className="row" style={{ gap: 4 }}>
                  {u.username} {pins[u.id]?.verified && <Verified />}
                </b>
                <span className={pins[u.id]?.changedFrom ? 'warn-text' : 'faint'}>{pins[u.id]?.changedFrom ? 'Security code changed' : pins[u.id]?.verified ? 'Verified' : 'Not verified'}</span>
              </span>
              <ChevronRight size={18} className="faint" />
            </Link>
          ))}
      </Card>
      <Card title="Where you’re signed in">
        {!sessions ? (
          <Spinner />
        ) : (
          sessions.map((s) => (
            <div className="user-row" key={s.id}>
              <span className="device-icon">{s.platform === 'android' ? <Smartphone size={20} /> : <Laptop size={20} />}</span>
              <span className="user-row-text">
                <b>{s.deviceName}</b>
                <span className="faint">{s.current ? 'This device' : <>Active <Ago at={s.lastSeenAt} className="" /> ago</>}</span>
              </span>
              {!s.current && (
                <button className="btn btn-secondary btn-sm" onClick={() => void run(api.delete(`/auth/sessions/${s.id}`)).then(load)}>
                  Sign out
                </button>
              )}
            </div>
          ))
        )}
      </Card>
      <Card title="Reset encryption keys">
        <p className="faint small">Only if you think your keys or password leaked. You get a new identity; everything you can read is re-sealed to it, and your contacts are told your code changed.</p>
        <input className="input" type="password" placeholder="Password" value={password} onChange={(e) => setPassword(e.target.value)} />
        <button className="btn btn-danger" disabled={!password || !!status} onClick={() => setResetting(true)}>
          {status ?? 'Reset keys'}
        </button>
        <Confirm
          open={resetting}
          title="Reset your keys?"
          message="Contacts will see a security code change and should verify you again."
          confirm="Reset keys"
          danger
          onConfirm={() => void run(resetKeys(password, setStatus), 'New keys in place').finally(() => (setStatus(null), setPassword('')))}
          onClose={() => setResetting(false)}
        />
      </Card>
    </>
  );
}

function ArchiveSection() {
  const index = useIndex();
  const me = useApp((s) => s.me!);
  const navigate = useNavigate();
  const [tab, setTab] = useState<'stories' | 'posts'>('stories');
  const archived = index.posts.filter((p) => p.dto.authorId === me.id && p.dto.archived);
  return (
    <>
      <div style={{ marginBottom: 16 }}>
        <Segmented id="archive" value={tab} onChange={setTab} options={[{ id: 'stories', label: 'Stories' }, { id: 'posts', label: 'Posts' }]} />
      </div>
      {tab === 'stories' ? (
        index.myStoryArchive.length ? (
          <div className="story-pick">
            {index.myStoryArchive.map((s) => (
              <button key={s.dto.id} className="story-pick-item" onClick={() => navigate(`/stories/${me.id}`)}>
                <StoryThumb story={s} />
                <span className="story-pick-date">{new Date(s.dto.createdAt).toLocaleDateString(undefined, { day: 'numeric', month: 'short' })}</span>
              </button>
            ))}
          </div>
        ) : (
          <p className="faint">Your stories are kept here after they disappear from your profile.</p>
        )
      ) : archived.length ? (
        <PostGrid posts={archived} />
      ) : (
        <p className="faint">Posts you archive are hidden from everyone but you, and appear here.</p>
      )}
    </>
  );
}

function AppearanceSection() {
  const theme = useUi((s) => s.theme);
  return (
    <Card>
      <Row title="Theme">
        <Segmented id="theme" value={theme} onChange={applyTheme} options={[{ id: 'system', label: 'Auto' }, { id: 'dark', label: 'Dark' }, { id: 'light', label: 'Light' }]} />
      </Row>
    </Card>
  );
}

function DataSection() {
  const download = () => {
    const { entities, me, users } = useApp.getState();
    const mine = Object.values(entities).filter((e) => e.dto.authorId === me!.id);
    const data = {
      exportedAt: new Date().toISOString(),
      account: me,
      people: Object.values(users).map((u) => ({ id: u.id, username: u.username })),
      content: mine.map((e) => ({ id: e.dto.id, kind: e.dto.kind, createdAt: new Date(e.dto.createdAt).toISOString(), audience: e.dto.audience, threadId: e.dto.threadId, content: e.payload })),
    };
    const url = URL.createObjectURL(new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' }));
    const a = document.createElement('a');
    a.href = url;
    a.download = `aezgram-${me!.username}-${new Date().toISOString().slice(0, 10)}.json`;
    a.click();
    URL.revokeObjectURL(url);
  };
  return (
    <Card title="Download your data">
      <p className="faint small">A decrypted copy of everything you’ve posted and sent (text and media references), made right here on this device. Keep it safe: it’s not encrypted.</p>
      <button className="btn btn-primary" onClick={download}>
        <Download size={16} /> Download
      </button>
    </Card>
  );
}

function AdminSection() {
  const [status, setStatus] = useState<AdminStatus | null>(null);
  const [invites, setInvites] = useState<InviteDto[]>([]);
  const load = () => {
    void api.get<AdminStatus>('/admin/status').then(setStatus);
    void api.get<InviteDto[]>('/admin/invites').then(setInvites);
  };
  useEffect(load, []);
  if (!status) return <Center><Spinner /></Center>;
  const backupAge = status.lastBackupAt ? Date.now() - status.lastBackupAt : null;
  const backupStale = backupAge === null || backupAge > 48 * 3_600_000 || status.lastBackupOk === false;
  return (
    <>
      {backupStale && <div className="key-warning"><HardDrive size={18} /> {status.lastBackupAt ? 'The last successful backup is more than 48 hours old.' : 'No backup has run yet.'} Check aezgram-backup.timer on the server.</div>}
      <Card title="Server">
        <Row title="Members" sub={`${status.users} of ${status.maxUsers}`}><Users size={18} /></Row>
        <Row title="Free disk" sub={`${bytesLabel(status.diskFreeBytes)} free · AezGram uses ${bytesLabel(status.dataBytes)}`}>{status.uploadsBlocked && <span className="chip warn">Uploads paused</span>}</Row>
        <Row title="Last backup" sub={status.lastBackupAt ? `${new Date(status.lastBackupAt).toLocaleString()}${status.backupMessage ? ' · ' + status.backupMessage : ''}` : 'Never'}>
          <span className={`chip ${backupStale ? 'warn' : 'ok'}`}>{backupStale ? 'Attention' : 'OK'}</span>
        </Row>
        <Row title="Version" sub={status.version} />
      </Card>
      <Card title="Invites">
        <button className="btn btn-brand" disabled={status.users >= status.maxUsers} onClick={() => void run(api.post('/admin/invites')).then(load)}>
          <Ticket size={16} /> New invite
        </button>
        {invites.map((i) => (
          <div className="setting-line" key={i.code}>
            <span>
              <b className="tabular">{i.code}</b>
              <span className="faint">{i.usedBy ? 'Used' : i.expiresAt < Date.now() ? 'Expired' : `Expires ${new Date(i.expiresAt).toLocaleDateString()}`}</span>
            </span>
            {!i.usedBy && i.expiresAt > Date.now() && (
              <button className="btn btn-secondary btn-sm" onClick={() => void run(navigator.clipboard.writeText(`${location.origin}/?invite=${i.code}`), 'Invite link copied')}>
                Copy link
              </button>
            )}
          </div>
        ))}
      </Card>
    </>
  );
}

export function Settings() {
  const { section } = useParams();
  const me = useApp((s) => s.me!);
  const sections = me.isAdmin ? [...SECTIONS, { id: 'admin', label: 'Server', icon: HardDrive } as const] : SECTIONS;
  const active = sections.find((s) => s.id === section);
  const body = (() => {
    switch (section) {
      case 'account': return <AccountSection />;
      case 'privacy': return <PrivacySection />;
      case 'close-friends': return <CloseFriendsSection />;
      case 'blocked': return <BlockedSection />;
      case 'notifications': return <NotificationsSection />;
      case 'security': return <SecuritySection />;
      case 'archive': return <ArchiveSection />;
      case 'appearance': return <AppearanceSection />;
      case 'data': return <DataSection />;
      case 'admin': return me.isAdmin ? <AdminSection /> : null;
      default: return null;
    }
  })();
  return (
    <div className={`settings ${section ? 'has-section' : ''}`}>
      <nav className="settings-nav">
        <h1 className="page-title" style={{ display: 'block', padding: '0 12px' }}>Settings</h1>
        {sections.map((s) => (
          <Link key={s.id} to={`/settings/${s.id}`} className={`settings-link ${section === s.id ? 'active' : ''}`}>
            <s.icon size={20} /> {s.label}
            <ChevronRight size={16} className="faint only-mobile" style={{ marginLeft: 'auto' }} />
          </Link>
        ))}
        <button className="settings-link danger" onClick={() => void signOut()}>
          <LogOut size={20} /> Log out
        </button>
        <p className="settings-foot faint">
          <Moon size={12} /> AezGram · end-to-end encrypted
        </p>
      </nav>
      <section className="settings-body">
        {active ? (
          <>
            <h2 className="settings-title">
              <Link to="/settings" className="only-mobile" style={{ marginRight: 8 }}>‹</Link>
              {active.label}
            </h2>
            {body}
          </>
        ) : (
          <div className="hide-mobile" style={{ padding: 40 }}>
            <p className="faint">Choose a setting.</p>
          </div>
        )}
      </section>
    </div>
  );
}
