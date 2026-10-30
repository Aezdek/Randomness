import { Bookmark, Clapperboard, Grid3x3, Lock, MoreHorizontal, Settings as SettingsIcon, ShieldCheck, ShieldAlert, SquareUser } from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { api } from '../../core/api';
import { block, openDirect, removeFollower, unblock } from '../../core/compose';
import { messageOf } from '../../core/errors';
import { Kind, type PostPayload } from '../../core/payloads';
import { savedPostIds, useIndex, useUserByName } from '../../core/selectors';
import { toast, useApp } from '../../core/store';
import type { IdList } from '../../core/types';
import { RingAvatar } from '../../ui/Avatar';
import { compact, Empty, RichText, Tabs, Verified } from '../../ui/bits';
import { Confirm, Sheet } from '../../ui/Sheet';
import { useRing } from '../stories/ring';
import { EditProfile } from './EditProfile';
import { FollowButton } from './FollowButton';
import { FollowList } from './FollowList';
import { Highlights } from './Highlights';
import { PostGrid } from './PostGrid';

type Tab = 'posts' | 'reels' | 'tagged' | 'saved';

function useCounts(userId: string | undefined, visible: boolean) {
  const [counts, setCounts] = useState<{ followers: number; following: number } | null>(null);
  const users = useApp((s) => s.users);
  useEffect(() => {
    if (!userId || !visible) return;
    let alive = true;
    Promise.all([api.get<IdList>(`/users/${userId}/followers`), api.get<IdList>(`/users/${userId}/following`)])
      .then(([a, b]) => alive && setCounts({ followers: a.ids.length, following: b.ids.length }))
      .catch(() => undefined);
    return () => {
      alive = false;
    };
  }, [userId, visible, users]);
  return counts;
}

export function Profile() {
  const { username, tab: tabParam } = useParams();
  const me = useApp((s) => s.me!);
  const user = useUserByName(username);
  const isMe = username === me.username;
  const userId = isMe ? me.id : user?.id;
  const index = useIndex();
  const profile = userId ? index.profiles[userId]?.payload : undefined;
  const ring = useRing(userId);
  const verified = useApp((s) => (userId ? s.pins[userId]?.verified : false));
  const keyChanged = useApp((s) => (userId ? !!s.pins[userId]?.changedFrom : false));
  const navigate = useNavigate();
  const [tab, setTab] = useState<Tab>((tabParam as Tab) || 'posts');
  const [editing, setEditing] = useState(false);
  const [list, setList] = useState<'followers' | 'following' | null>(null);
  const [menu, setMenu] = useState(false);
  const [confirm, setConfirm] = useState<'block' | 'remove' | null>(null);
  const isPrivate = isMe ? me.isPrivate : user?.isPrivate;
  const canSee = isMe || !isPrivate || user?.followState === 'following';
  const counts = useCounts(userId, canSee);

  const posts = useMemo(() => index.posts.filter((p) => p.dto.authorId === userId && (!p.dto.archived || isMe)), [index.posts, userId, isMe]);
  const visiblePosts = posts.filter((p) => !p.dto.archived);
  const grid = useMemo(() => {
    switch (tab) {
      case 'reels':
        return visiblePosts.filter((p) => p.dto.kind === Kind.REEL);
      case 'tagged':
        return index.posts.filter((p) => ((p.payload as PostPayload).taggedUserIds ?? []).includes(userId ?? '') && !p.dto.archived);
      case 'saved': {
        const saved = savedPostIds(index);
        return index.posts.filter((p) => saved.has(p.dto.id));
      }
      default:
        return visiblePosts.filter((p) => p.dto.kind === Kind.POST || p.dto.kind === Kind.REEL);
    }
  }, [tab, visiblePosts, index, userId]);

  if (!userId) {
    return (
      <div className="page">
        <Empty icon={<SquareUser size={36} />} title="Profile not found">
          This account may have been deleted.
        </Empty>
      </div>
    );
  }

  const message = async () => {
    try {
      navigate(`/direct/${await openDirect(userId)}`);
    } catch (e) {
      toast(messageOf(e), 'error');
    }
  };

  return (
    <div className="page profile">
      {keyChanged && !isMe && (
        <Link to={`/verify/${userId}`} className="key-warning">
          <ShieldAlert size={18} /> {user?.username}’s security code changed. Tap to verify it’s really them.
        </Link>
      )}
      <header className="profile-head">
        <div className="profile-avatar">
          <RingAvatar userId={userId} size={156} ring={ring} onClick={ring !== 'none' ? () => navigate(`/stories/${userId}`) : undefined} />
        </div>
        <section className="profile-info">
          <div className="profile-title">
            <h1>{isMe ? me.username : user?.username}</h1>
            {verified && <Verified size={20} />}
            <div className="profile-buttons">
              {isMe ? (
                <>
                  <button className="btn btn-secondary btn-sm" onClick={() => setEditing(true)}>
                    Edit profile
                  </button>
                  <Link className="btn btn-secondary btn-sm" to="/settings/archive">
                    View archive
                  </Link>
                  <Link className="icon-btn sm" to="/settings" aria-label="Settings">
                    <SettingsIcon size={22} />
                  </Link>
                </>
              ) : user && !user.deleted ? (
                <>
                  {user.blockedByMe ? (
                    <button className="btn btn-secondary btn-sm" onClick={() => void unblock(userId).catch((e) => toast(messageOf(e), 'error'))}>
                      Unblock
                    </button>
                  ) : (
                    <>
                      <FollowButton user={user} />
                      <button className="btn btn-secondary btn-sm" onClick={message}>
                        Message
                      </button>
                    </>
                  )}
                  <button className="icon-btn sm" onClick={() => setMenu(true)} aria-label="Options">
                    <MoreHorizontal size={22} />
                  </button>
                </>
              ) : null}
            </div>
          </div>
          <ul className="profile-stats">
            <li>
              <b>{compact(visiblePosts.length)}</b> {visiblePosts.length === 1 ? 'post' : 'posts'}
            </li>
            <li>
              <button onClick={() => canSee && setList('followers')}>
                <b>{counts ? compact(counts.followers) : '–'}</b> {counts?.followers === 1 ? 'follower' : 'followers'}
              </button>
            </li>
            <li>
              <button onClick={() => canSee && setList('following')}>
                <b>{counts ? compact(counts.following) : '–'}</b> following
              </button>
            </li>
          </ul>
          <div className="profile-bio">
            {profile?.displayName && <b>{profile.displayName}</b>}
            {profile?.pronouns && <span className="faint"> {profile.pronouns}</span>}
            {profile?.bio && (
              <p>
                <RichText text={profile.bio} />
              </p>
            )}
            {profile?.link && (
              <a href={/^https?:\/\//.test(profile.link) ? profile.link : `https://${profile.link}`} target="_blank" rel="noreferrer noopener" className="profile-link">
                {profile.link.replace(/^https?:\/\//, '')}
              </a>
            )}
            {!isMe && user?.followsMe === 'following' && <span className="chip" style={{ marginTop: 8 }}>Follows you</span>}
          </div>
        </section>
      </header>

      {canSee && <Highlights userId={userId} isMe={isMe} />}

      {canSee ? (
        <>
          <Tabs
            value={tab}
            onChange={(t) => {
              setTab(t);
              navigate(t === 'posts' ? `/u/${username}` : `/u/${username}/${t}`, { replace: true });
            }}
            tabs={[
              { id: 'posts', label: 'Posts', icon: <Grid3x3 size={14} strokeWidth={2.2} /> },
              { id: 'reels', label: 'Reels', icon: <Clapperboard size={14} strokeWidth={2.2} /> },
              ...(isMe ? [{ id: 'saved' as Tab, label: 'Saved', icon: <Bookmark size={14} strokeWidth={2.2} /> }] : []),
              { id: 'tagged', label: 'Tagged', icon: <SquareUser size={14} strokeWidth={2.2} /> },
            ]}
          />
          {grid.length ? (
            <PostGrid posts={grid} reels={tab === 'reels'} />
          ) : (
            <Empty
              icon={tab === 'reels' ? <Clapperboard size={34} /> : tab === 'saved' ? <Bookmark size={34} /> : tab === 'tagged' ? <SquareUser size={34} /> : <Grid3x3 size={34} />}
              title={tab === 'saved' ? 'Save posts for later' : tab === 'tagged' ? 'Photos of you' : tab === 'reels' ? 'No reels yet' : isMe ? 'Share your first photo' : 'No posts yet'}
            >
              {tab === 'saved' ? 'Only you can see what you’ve saved.' : tab === 'tagged' ? 'When people tag you in posts, they’ll appear here.' : isMe ? 'When you share photos and videos, they’ll appear on your profile.' : undefined}
            </Empty>
          )}
        </>
      ) : (
        <div className="private-gate">
          <Lock size={28} />
          <div>
            <b>This account is private</b>
            <p className="muted">Follow to see their photos and videos.</p>
          </div>
        </div>
      )}

      {isMe && <EditProfile open={editing} onClose={() => setEditing(false)} />}
      {list && <FollowList userId={userId} kind={list} onClose={() => setList(null)} />}
      {user && (
        <Sheet open={menu} onClose={() => setMenu(false)} hideHeader bottom bodyClass="flush">
          <div className="menu centered">
            <button onClick={() => (setMenu(false), navigate(`/verify/${userId}`))}>
              <ShieldCheck size={18} /> Verify security code
            </button>
            {user.followsMe === 'following' && (
              <button className="danger" onClick={() => (setMenu(false), setConfirm('remove'))}>
                Remove follower
              </button>
            )}
            <button className="danger" onClick={() => (setMenu(false), user.blockedByMe ? void unblock(userId) : setConfirm('block'))}>
              {user.blockedByMe ? 'Unblock' : 'Block'}
            </button>
            <button onClick={() => setMenu(false)}>Cancel</button>
          </div>
        </Sheet>
      )}
      <Confirm
        open={confirm === 'block'}
        title={`Block ${user?.username}?`}
        message="They won’t be able to find your profile, posts or stories, or message you. They won’t be told."
        confirm="Block"
        danger
        onConfirm={() => void block(userId).catch((e) => toast(messageOf(e), 'error'))}
        onClose={() => setConfirm(null)}
      />
      <Confirm
        open={confirm === 'remove'}
        title="Remove follower?"
        message={`${user?.username} won’t be told. Their app may still show what it already downloaded.`}
        confirm="Remove"
        danger
        onConfirm={() => void removeFollower(userId).catch((e) => toast(messageOf(e), 'error'))}
        onClose={() => setConfirm(null)}
      />
    </div>
  );
}

