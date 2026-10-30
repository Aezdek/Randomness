import { useState } from 'react';
import { follow, unfollow } from '../../core/compose';
import { messageOf } from '../../core/errors';
import { toast } from '../../core/store';
import type { UserDto } from '../../core/types';
import { Spinner } from '../../ui/bits';
import { Confirm } from '../../ui/Sheet';

export function FollowButton({ user, compact }: { user: UserDto; compact?: boolean }) {
  const [busy, setBusy] = useState(false);
  const [confirm, setConfirm] = useState(false);
  const run = async (action: () => Promise<void>) => {
    setBusy(true);
    try {
      await action();
    } catch (e) {
      toast(messageOf(e), 'error');
    } finally {
      setBusy(false);
    }
  };
  if (user.blockedByMe) return null;
  const following = user.followState === 'following';
  const requested = user.followState === 'requested';
  const label = following ? 'Following' : requested ? 'Requested' : user.followsMe === 'following' ? 'Follow back' : 'Follow';
  if (compact) {
    return (
      <button className={`btn btn-sm ${following || requested ? 'btn-ghost faint' : 'btn-link'}`} disabled={busy} onClick={() => (following || requested ? setConfirm(true) : run(() => follow(user.id)))}>
        {busy ? <Spinner size={14} /> : label}
        <Confirm open={confirm} title={`${requested ? 'Cancel request to' : 'Unfollow'} ${user.username}?`} message={user.isPrivate ? 'Their account is private: you’ll need to request again to see their posts.' : undefined} confirm={requested ? 'Cancel request' : 'Unfollow'} danger onConfirm={() => run(() => unfollow(user.id))} onClose={() => setConfirm(false)} />
      </button>
    );
  }
  return (
    <>
      <button className={`btn btn-sm ${following || requested ? 'btn-secondary' : 'btn-brand'}`} disabled={busy} onClick={() => (following || requested ? setConfirm(true) : run(() => follow(user.id)))} style={{ minWidth: 104 }}>
        {busy ? <Spinner size={14} /> : label}
      </button>
      <Confirm open={confirm} title={`${requested ? 'Cancel request to' : 'Unfollow'} ${user.username}?`} message={user.isPrivate ? 'Their account is private: you’ll need to request again to see their posts.' : undefined} confirm={requested ? 'Cancel request' : 'Unfollow'} danger onConfirm={() => run(() => unfollow(user.id))} onClose={() => setConfirm(false)} />
    </>
  );
}
