import { ArrowLeft, Clapperboard, X } from 'lucide-react';
import { useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { createReel } from '../../core/compose';
import { messageOf } from '../../core/errors';
import { prepareImage, prepareVideo, upload } from '../../core/media';
import { useApp } from '../../core/store';
import { Avatar } from '../../ui/Avatar';
import { Switch } from '../../ui/bits';
import { Sheet } from '../../ui/Sheet';
import { load, ShareProgress, type Item } from './CreatePost';

/** A reel: one vertical video, trimmed, with a cover frame you pick. */
export function CreateReel({ onClose }: { onClose: () => void }) {
  const me = useApp((s) => s.me!);
  const navigate = useNavigate();
  const [item, setItem] = useState<Item | null>(null);
  const [step, setStep] = useState<'pick' | 'edit' | 'details' | 'share'>('pick');
  const [cover, setCover] = useState(0);
  const [caption, setCaption] = useState('');
  const [hideLikes, setHideLikes] = useState(false);
  const [commentsOff, setCommentsOff] = useState(false);
  const [progress, setProgress] = useState({ label: '', value: 0 });
  const [error, setError] = useState<string | null>(null);
  const video = useRef<HTMLVideoElement>(null);
  const input = useRef<HTMLInputElement>(null);

  const pick = async (file: File) => {
    setError(null);
    if (!file.type.startsWith('video/')) return setError('Reels are videos. Choose a video file.');
    try {
      const loaded = await load(file);
      loaded.trim = [0, Math.min(loaded.duration, 180)];
      setItem(loaded);
      setStep('edit');
    } catch (e) {
      setError(messageOf(e));
    }
  };

  const grabCover = async (): Promise<Blob | null> => {
    const el = video.current;
    if (!el) return null;
    el.currentTime = cover;
    await new Promise((r) => (el.onseeked = r));
    const canvas = document.createElement('canvas');
    canvas.width = el.videoWidth;
    canvas.height = el.videoHeight;
    canvas.getContext('2d')!.drawImage(el, 0, 0);
    return new Promise((r) => canvas.toBlob(r, 'image/jpeg', 0.9));
  };

  const share = async () => {
    if (!item) return;
    const coverBlob = await grabCover();
    setStep('share');
    try {
      setProgress({ label: 'Optimising video', value: 0.02 });
      const prepared = await prepareVideo(item.file, 'reel', (f) => setProgress({ label: 'Optimising video', value: 0.02 + f * 0.6 }), { trim: { start: item.trim[0], end: item.trim[1] } });
      setProgress({ label: 'Encrypting and uploading', value: 0.62 });
      const videoRef = await upload(prepared, (f) => setProgress({ label: 'Encrypting and uploading', value: 0.62 + f * 0.33 }));
      const coverRef = coverBlob ? await upload(await prepareImage(coverBlob, 'reel')) : null;
      const reel = await createReel({ caption, video: videoRef, cover: coverRef, hideLikes, commentsOff });
      setProgress({ label: 'Shared', value: 1 });
      setTimeout(() => {
        onClose();
        navigate(`/reels/${reel.dto.id}`);
      }, 900);
    } catch (e) {
      setError(messageOf(e));
      setStep('details');
    }
  };

  return (
    <Sheet
      open
      onClose={step === 'share' ? () => undefined : onClose}
      size={step === 'pick' ? 'normal' : 'wide'}
      title={step === 'pick' ? 'New reel' : step === 'edit' ? 'Trim and cover' : step === 'details' ? 'New reel' : 'Sharing'}
      left={
        step === 'details' ? (
          <button className="icon-btn sm" onClick={() => setStep('edit')} aria-label="Back">
            <ArrowLeft size={20} />
          </button>
        ) : (
          <button className="icon-btn sm" onClick={onClose} aria-label="Close" disabled={step === 'share'}>
            <X size={20} />
          </button>
        )
      }
      right={
        step === 'edit' ? (
          <button className="btn btn-link" onClick={() => setStep('details')}>
            Next
          </button>
        ) : step === 'details' ? (
          <button className="btn btn-link" onClick={share}>
            Share
          </button>
        ) : undefined
      }
      bodyClass="flush"
    >
      {step === 'pick' && (
        <div className="dropzone" onDragOver={(e) => e.preventDefault()} onDrop={(e) => (e.preventDefault(), e.dataTransfer.files[0] && pick(e.dataTransfer.files[0]))}>
          <div className="dropzone-art">
            <Clapperboard size={64} strokeWidth={1.2} />
          </div>
          <p>Choose a vertical video</p>
          <button className="btn btn-brand" onClick={() => input.current?.click()}>
            Select video
          </button>
          {error && <p className="form-error">{error}</p>}
          <input ref={input} type="file" accept="video/*" hidden onChange={(e) => e.target.files?.[0] && pick(e.target.files[0])} />
        </div>
      )}
      {(step === 'edit' || step === 'details') && item && (
        <div className="reel-composer">
          <div className="reel-preview">
            <video ref={video} src={item.url} autoPlay muted loop playsInline onTimeUpdate={(e) => e.currentTarget.currentTime > item.trim[1] && (e.currentTarget.currentTime = item.trim[0])} />
          </div>
          <div className="reel-side">
            {step === 'edit' ? (
              <div className="stack">
                <label className="field">
                  <span>Start · {item.trim[0].toFixed(1)}s</span>
                  <input type="range" min={0} max={item.duration} step={0.1} value={item.trim[0]} onChange={(e) => setItem({ ...item, trim: [Math.min(Number(e.target.value), item.trim[1] - 1), item.trim[1]] })} />
                </label>
                <label className="field">
                  <span>End · {item.trim[1].toFixed(1)}s (up to 3 minutes)</span>
                  <input type="range" min={0} max={item.duration} step={0.1} value={item.trim[1]} onChange={(e) => setItem({ ...item, trim: [item.trim[0], Math.min(item.trim[0] + 180, Math.max(Number(e.target.value), item.trim[0] + 1))] })} />
                </label>
                <label className="field">
                  <span>Cover frame · {cover.toFixed(1)}s</span>
                  <input
                    type="range"
                    min={item.trim[0]}
                    max={item.trim[1]}
                    step={0.1}
                    value={cover}
                    onChange={(e) => {
                      setCover(Number(e.target.value));
                      if (video.current) {
                        video.current.pause();
                        video.current.currentTime = Number(e.target.value);
                      }
                    }}
                  />
                </label>
              </div>
            ) : (
              <div className="stack">
                <div className="user-row">
                  <Avatar userId={me.id} size={30} />
                  <b>{me.username}</b>
                </div>
                <textarea className="textarea" rows={5} placeholder="Write a caption…" value={caption} onChange={(e) => setCaption(e.target.value)} maxLength={2200} />
                <div className="setting-line">
                  <span>
                    <b>Hide like count</b>
                  </span>
                  <Switch checked={hideLikes} onChange={setHideLikes} label="Hide like count" />
                </div>
                <div className="setting-line">
                  <span>
                    <b>Turn off commenting</b>
                  </span>
                  <Switch checked={commentsOff} onChange={setCommentsOff} label="Turn off commenting" />
                </div>
                {error && <p className="form-error">{error}</p>}
              </div>
            )}
          </div>
        </div>
      )}
      {step === 'share' && <ShareProgress progress={progress} />}
    </Sheet>
  );
}
