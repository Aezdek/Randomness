import { Camera } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import { saveProfile } from '../../core/compose';
import { messageOf } from '../../core/errors';
import { prepareImage, upload } from '../../core/media';
import type { MediaRef } from '../../core/payloads';
import { useIndex } from '../../core/selectors';
import { toast, useApp } from '../../core/store';
import { Avatar } from '../../ui/Avatar';
import { Spinner } from '../../ui/bits';
import { Sheet } from '../../ui/Sheet';

export function EditProfile({ open, onClose }: { open: boolean; onClose: () => void }) {
  const me = useApp((s) => s.me!);
  const index = useIndex();
  const current = index.profiles[me.id]?.payload;
  const [displayName, setDisplayName] = useState('');
  const [pronouns, setPronouns] = useState('');
  const [bio, setBio] = useState('');
  const [link, setLink] = useState('');
  const [avatar, setAvatar] = useState<MediaRef | null>(null);
  const [preview, setPreview] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const file = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (!open) return;
    setDisplayName(current?.displayName ?? '');
    setPronouns(current?.pronouns ?? '');
    setBio(current?.bio ?? '');
    setLink(current?.link ?? '');
    setAvatar(current?.avatar ?? null);
    setPreview(null);
  }, [open]); // eslint-disable-line react-hooks/exhaustive-deps

  const pick = async (f: File) => {
    setBusy(true);
    try {
      const bitmap = await createImageBitmap(f, { imageOrientation: 'from-image' });
      const side = Math.min(bitmap.width, bitmap.height);
      const prepared = await prepareImage(f, 'avatar', { crop: { x: (bitmap.width - side) / 2, y: (bitmap.height - side) / 2, width: side, height: side } });
      bitmap.close();
      setPreview(URL.createObjectURL(prepared.blob));
      setAvatar(await upload(prepared));
    } catch (e) {
      toast(messageOf(e), 'error');
    } finally {
      setBusy(false);
    }
  };

  const save = async () => {
    setBusy(true);
    try {
      await saveProfile({ displayName: displayName.trim(), pronouns: pronouns.trim(), bio: bio.trim(), link: link.trim(), avatar });
      toast('Profile saved', 'success');
      onClose();
    } catch (e) {
      toast(messageOf(e), 'error');
    } finally {
      setBusy(false);
    }
  };

  return (
    <Sheet
      open={open}
      onClose={onClose}
      title="Edit profile"
      left={<button className="btn btn-ghost btn-sm" onClick={onClose}>Cancel</button>}
      right={<button className="btn btn-link" onClick={save} disabled={busy}>{busy ? <Spinner size={16} /> : 'Done'}</button>}
    >
      <div className="edit-profile">
        <button className="edit-avatar" onClick={() => file.current?.click()} disabled={busy}>
          {preview ? <img src={preview} alt="" className="avatar" style={{ ['--size' as string]: '96px' }} /> : <Avatar userId={me.id} size={96} />}
          <span className="edit-avatar-badge">
            <Camera size={16} />
          </span>
        </button>
        <button className="btn btn-link" onClick={() => file.current?.click()}>
          Change photo
        </button>
        <input ref={file} type="file" accept="image/*" hidden onChange={(e) => e.target.files?.[0] && pick(e.target.files[0])} />
        <label className="field">
          <span>Name</span>
          <input className="input" value={displayName} onChange={(e) => setDisplayName(e.target.value)} maxLength={60} placeholder="Your name" />
        </label>
        <label className="field">
          <span>Pronouns</span>
          <input className="input" value={pronouns} onChange={(e) => setPronouns(e.target.value)} maxLength={30} placeholder="they/them" />
        </label>
        <label className="field">
          <span>Bio</span>
          <textarea className="textarea" rows={3} value={bio} onChange={(e) => setBio(e.target.value)} maxLength={150} />
          <small className="faint" style={{ textAlign: 'right' }}>{bio.length} / 150</small>
        </label>
        <label className="field">
          <span>Link</span>
          <input className="input" value={link} onChange={(e) => setLink(e.target.value)} maxLength={120} placeholder="aezdek.xyz" inputMode="url" />
        </label>
      </div>
    </Sheet>
  );
}
