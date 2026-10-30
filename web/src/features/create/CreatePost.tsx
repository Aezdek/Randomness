import { ArrowLeft, Check, ImagePlus, MapPin, Tag, UserPlus, X } from 'lucide-react';
import { AnimatePresence, motion } from 'motion/react';
import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { createPost } from '../../core/compose';
import { messageOf } from '../../core/errors';
import { prepareImage, prepareVideo, upload, UnsupportedMediaError } from '../../core/media';
import type { MediaRef } from '../../core/payloads';
import { useApp } from '../../core/store';
import { Avatar } from '../../ui/Avatar';
import { Segmented, Switch } from '../../ui/bits';
import { Sheet } from '../../ui/Sheet';
import { ASPECTS, cropRect, filterString, LOOKS, NO_ADJUST, type Adjustments, type Aspect } from './editing';

export interface Item {
  id: string;
  file: File;
  url: string;
  video: boolean;
  width: number;
  height: number;
  duration: number;
  look: string;
  adjust: Adjustments;
  zoom: number;
  panX: number;
  panY: number;
  trim: [number, number];
  alt: string;
}

export async function load(file: File): Promise<Item> {
  const url = URL.createObjectURL(file);
  const video = file.type.startsWith('video/');
  const dims = await new Promise<{ w: number; h: number; d: number }>((resolve, reject) => {
    if (video) {
      const el = document.createElement('video');
      el.preload = 'metadata';
      el.onloadedmetadata = () => resolve({ w: el.videoWidth, h: el.videoHeight, d: el.duration });
      el.onerror = () => reject(new UnsupportedMediaError('This browser cannot open that video.'));
      el.src = url;
    } else {
      const img = new Image();
      img.onload = () => resolve({ w: img.naturalWidth, h: img.naturalHeight, d: 0 });
      img.onerror = () => reject(new UnsupportedMediaError('This browser cannot open that photo. Try JPEG or PNG.'));
      img.src = url;
    }
  });
  return {
    id: crypto.randomUUID(),
    file,
    url,
    video,
    width: dims.w,
    height: dims.h,
    duration: dims.d,
    look: 'original',
    adjust: { ...NO_ADJUST },
    zoom: 1,
    panX: 0,
    panY: 0,
    trim: [0, Math.min(dims.d, 600)],
    alt: '',
  };
}

/** Shows an item cropped exactly as it will be exported; drag to pan. */
export function CropPreview({ item, ratio, onPan, interactive = true }: { item: Item; ratio: number | null; onPan?: (x: number, y: number) => void; interactive?: boolean }) {
  const box = useRef<HTMLDivElement>(null);
  const [w, setW] = useState(0);
  const drag = useRef<{ x: number; y: number; px: number; py: number } | null>(null);
  useEffect(() => {
    const el = box.current;
    if (!el) return;
    const observer = new ResizeObserver(() => setW(el.clientWidth));
    observer.observe(el);
    return () => observer.disconnect();
  }, []);
  const crop = cropRect(item.width, item.height, ratio, item.zoom, item.panX, item.panY);
  const scale = w / crop.width;
  const style: React.CSSProperties = {
    position: 'absolute',
    width: item.width * scale,
    height: item.height * scale,
    left: -crop.x * scale,
    top: -crop.y * scale,
    filter: filterString(item.look, item.adjust),
    maxWidth: 'none',
  };
  return (
    <div
      ref={box}
      className="crop-preview"
      style={{ aspectRatio: String(crop.width / crop.height) }}
      onPointerDown={(e) => {
        if (!interactive) return;
        (e.target as HTMLElement).setPointerCapture?.(e.pointerId);
        drag.current = { x: e.clientX, y: e.clientY, px: item.panX, py: item.panY };
      }}
      onPointerMove={(e) => {
        const d = drag.current;
        if (!d || !onPan) return;
        const rangeX = ((item.width - crop.width) / 2) * scale || 1;
        const rangeY = ((item.height - crop.height) / 2) * scale || 1;
        onPan(Math.max(-1, Math.min(1, d.px - (e.clientX - d.x) / rangeX)), Math.max(-1, Math.min(1, d.py - (e.clientY - d.y) / rangeY)));
      }}
      onPointerUp={() => (drag.current = null)}
    >
      {item.video ? <video src={item.url} style={style} muted autoPlay loop playsInline /> : <img src={item.url} style={style} alt="" draggable={false} />}
      {item.adjust.vignette > 0 && <div className="vignette" style={{ opacity: item.adjust.vignette }} />}
      {interactive && <div className="crop-grid" />}
    </div>
  );
}

const ADJUST_LABELS: { key: keyof Adjustments; label: string; min: number }[] = [
  { key: 'brightness', label: 'Brightness', min: -1 },
  { key: 'contrast', label: 'Contrast', min: -1 },
  { key: 'saturation', label: 'Saturation', min: -1 },
  { key: 'warmth', label: 'Warmth', min: -1 },
  { key: 'fade', label: 'Fade', min: 0 },
  { key: 'vignette', label: 'Vignette', min: 0 },
];

function TagPeople({ value, onChange }: { value: string[]; onChange: (ids: string[]) => void }) {
  const users = useApp((s) => s.users);
  const me = useApp((s) => s.me!);
  const people = Object.values(users).filter((u) => u.id !== me.id && !u.deleted && !u.blockedByMe);
  return (
    <div className="tag-people">
      {people.map((u) => {
        const on = value.includes(u.id);
        return (
          <button key={u.id} className={`chip ${on ? 'on' : ''}`} onClick={() => onChange(on ? value.filter((x) => x !== u.id) : [...value, u.id])}>
            <Avatar userId={u.id} size={20} /> {u.username} {on && <Check size={14} />}
          </button>
        );
      })}
      {!people.length && <span className="faint">Nobody to tag yet.</span>}
    </div>
  );
}

export function CreatePost({ onClose }: { onClose: () => void }) {
  const me = useApp((s) => s.me!);
  const navigate = useNavigate();
  const [step, setStep] = useState<'pick' | 'edit' | 'details' | 'share'>('pick');
  const [items, setItems] = useState<Item[]>([]);
  const [current, setCurrent] = useState(0);
  const [aspect, setAspect] = useState<Aspect>('original');
  const [panel, setPanel] = useState<'looks' | 'adjust'>('looks');
  const [caption, setCaption] = useState('');
  const [location, setLocation] = useState('');
  const [tagged, setTagged] = useState<string[]>([]);
  const [hideLikes, setHideLikes] = useState(false);
  const [commentsOff, setCommentsOff] = useState(false);
  const [progress, setProgress] = useState<{ label: string; value: number }>({ label: '', value: 0 });
  const [error, setError] = useState<string | null>(null);
  const [dragging, setDragging] = useState(false);
  const input = useRef<HTMLInputElement>(null);
  const item = items[current];
  const ratio = ASPECTS.find((a) => a.id === aspect)!.ratio ?? (items[0] ? Math.min(1.91, Math.max(0.8, items[0].width / items[0].height)) : 1);

  useEffect(() => () => items.forEach((i) => URL.revokeObjectURL(i.url)), []); // eslint-disable-line react-hooks/exhaustive-deps

  const add = async (files: FileList | File[]) => {
    setError(null);
    try {
      const loaded = await Promise.all([...files].slice(0, 20 - items.length).map(load));
      setItems((prev) => [...prev, ...loaded]);
      setStep('edit');
    } catch (e) {
      setError(messageOf(e));
    }
  };

  const update = (patch: Partial<Item>) => setItems((prev) => prev.map((x, i) => (i === current ? { ...x, ...patch } : x)));

  const share = async () => {
    setStep('share');
    try {
      const refs: MediaRef[] = [];
      for (const [i, it] of items.entries()) {
        const label = items.length > 1 ? `${i + 1} of ${items.length}` : '';
        const crop = cropRect(it.width, it.height, ratio, it.zoom, it.panX, it.panY);
        setProgress({ label: it.video ? `Optimising video ${label}` : `Preparing photo ${label}`, value: i / items.length });
        const prepared = it.video
          ? await prepareVideo(it.file, 'post', (f) => setProgress({ label: `Optimising video ${label}`, value: (i + f * 0.6) / items.length }), {
              trim: { start: it.trim[0], end: it.trim[1] },
              crop: { left: crop.x, top: crop.y, width: crop.width, height: crop.height },
            })
          : await prepareImage(it.file, 'post', { crop, filter: filterString(it.look, it.adjust), vignette: it.adjust.vignette });
        setProgress({ label: `Encrypting and uploading ${label}`, value: (i + 0.6) / items.length });
        refs.push(await upload(prepared, (f) => setProgress({ label: `Encrypting and uploading ${label}`, value: (i + 0.6 + f * 0.4) / items.length }), it.alt));
      }
      const post = await createPost({ caption, media: refs, location: location || null, taggedUserIds: tagged, hideLikes, commentsOff });
      setProgress({ label: 'Shared', value: 1 });
      setTimeout(() => {
        onClose();
        navigate(`/p/${post.dto.id}`);
      }, 900);
    } catch (e) {
      setError(messageOf(e));
      setStep('details');
    }
  };

  const title = { pick: 'Create new post', edit: 'Edit', details: 'New post', share: progress.value >= 1 ? 'Post shared' : 'Sharing' }[step];
  const back = step === 'edit' ? () => (setItems([]), setStep('pick')) : step === 'details' ? () => setStep('edit') : null;

  return (
    <Sheet
      open
      onClose={step === 'share' ? () => undefined : onClose}
      size={step === 'pick' ? 'normal' : 'full'}
      title={title}
      left={
        back ? (
          <button className="icon-btn sm" onClick={back} aria-label="Back">
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
        <div
          className={`dropzone ${dragging ? 'over' : ''}`}
          onDragOver={(e) => (e.preventDefault(), setDragging(true))}
          onDragLeave={() => setDragging(false)}
          onDrop={(e) => {
            e.preventDefault();
            setDragging(false);
            void add(e.dataTransfer.files);
          }}
        >
          <motion.div className="dropzone-art" animate={{ y: dragging ? -6 : 0, scale: dragging ? 1.05 : 1 }}>
            <ImagePlus size={64} strokeWidth={1.2} />
          </motion.div>
          <p>Drag photos and videos here</p>
          <button className="btn btn-brand" onClick={() => input.current?.click()}>
            Select from device
          </button>
          {error && <p className="form-error">{error}</p>}
          <input ref={input} type="file" accept="image/*,video/*" multiple hidden onChange={(e) => e.target.files && add(e.target.files)} />
        </div>
      )}

      {step === 'edit' && item && (
        <div className="composer">
          <div className="composer-stage">
            <div className="composer-canvas">
              <CropPreview item={item} ratio={ratio} onPan={(x, y) => update({ panX: x, panY: y })} />
            </div>
            <div className="composer-tools">
              <Segmented id="aspect" value={aspect} onChange={setAspect} options={ASPECTS.map((a) => ({ id: a.id, label: a.label }))} />
              <label className="zoom">
                Zoom
                <input type="range" min={1} max={3} step={0.01} value={item.zoom} onChange={(e) => update({ zoom: Number(e.target.value) })} />
              </label>
            </div>
            <div className="thumbs">
              {items.map((it, i) => (
                <button key={it.id} className={`thumb ${i === current ? 'on' : ''}`} onClick={() => setCurrent(i)}>
                  {it.video ? <video src={it.url} muted /> : <img src={it.url} alt="" />}
                  <span
                    className="thumb-remove"
                    role="button"
                    aria-label="Remove"
                    onClick={(e) => {
                      e.stopPropagation();
                      const next = items.filter((x) => x.id !== it.id);
                      setItems(next);
                      setCurrent(Math.max(0, Math.min(current, next.length - 1)));
                      if (!next.length) setStep('pick');
                    }}
                  >
                    <X size={12} />
                  </span>
                </button>
              ))}
              {items.length < 20 && (
                <button className="thumb add" onClick={() => input.current?.click()} aria-label="Add more">
                  <ImagePlus size={20} />
                </button>
              )}
              <input ref={input} type="file" accept="image/*,video/*" multiple hidden onChange={(e) => e.target.files && add(e.target.files)} />
            </div>
          </div>
          <aside className="composer-side">
            {item.video ? (
              <div className="stack" style={{ padding: 16 }}>
                <b>Trim</b>
                <label className="field">
                  <span>Start · {item.trim[0].toFixed(1)}s</span>
                  <input type="range" min={0} max={item.duration} step={0.1} value={item.trim[0]} onChange={(e) => update({ trim: [Math.min(Number(e.target.value), item.trim[1] - 1), item.trim[1]] })} />
                </label>
                <label className="field">
                  <span>End · {item.trim[1].toFixed(1)}s</span>
                  <input type="range" min={0} max={item.duration} step={0.1} value={item.trim[1]} onChange={(e) => update({ trim: [item.trim[0], Math.max(Number(e.target.value), item.trim[0] + 1)] })} />
                </label>
                <p className="faint" style={{ fontSize: 13 }}>Videos are converted on this device to 1080p H.264 so every phone and browser can play them.</p>
              </div>
            ) : (
              <>
                <div className="side-tabs">
                  <button aria-selected={panel === 'looks'} onClick={() => setPanel('looks')}>
                    Filters
                  </button>
                  <button aria-selected={panel === 'adjust'} onClick={() => setPanel('adjust')}>
                    Adjustments
                  </button>
                </div>
                {panel === 'looks' ? (
                  <div className="looks">
                    {LOOKS.map((look) => (
                      <button key={look.id} className={`look ${item.look === look.id ? 'on' : ''}`} onClick={() => update({ look: look.id })}>
                        <span className="look-img">
                          <img src={item.url} alt="" style={{ filter: look.filter }} />
                        </span>
                        <span>{look.name}</span>
                      </button>
                    ))}
                  </div>
                ) : (
                  <div className="adjust">
                    {ADJUST_LABELS.map(({ key, label, min }) => (
                      <label key={key} className="adjust-row">
                        <span className="row" style={{ justifyContent: 'space-between' }}>
                          {label}
                          <span className="faint tabular">{Math.round(item.adjust[key] * 100)}</span>
                        </span>
                        <input type="range" min={min} max={1} step={0.01} value={item.adjust[key]} onChange={(e) => update({ adjust: { ...item.adjust, [key]: Number(e.target.value) } })} onDoubleClick={() => update({ adjust: { ...item.adjust, [key]: 0 } })} />
                      </label>
                    ))}
                  </div>
                )}
              </>
            )}
          </aside>
        </div>
      )}

      {step === 'details' && item && (
        <div className="composer">
          <div className="composer-stage">
            <div className="composer-canvas">
              <CropPreview item={items[0]} ratio={ratio} interactive={false} />
            </div>
          </div>
          <aside className="composer-side details">
            <div className="user-row" style={{ padding: '14px 16px 4px' }}>
              <Avatar userId={me.id} size={30} />
              <b>{me.username}</b>
            </div>
            <textarea className="textarea bare caption-input" placeholder="Write a caption… Use @ to mention and # for hashtags" value={caption} onChange={(e) => setCaption(e.target.value)} maxLength={2200} rows={7} autoFocus />
            <div className="faint caption-count tabular">{caption.length.toLocaleString()}/2,200</div>
            <label className="detail-row">
              <MapPin size={20} />
              <input className="input bare" placeholder="Add location" value={location} onChange={(e) => setLocation(e.target.value)} maxLength={80} />
            </label>
            <div className="detail-row column">
              <span className="row" style={{ gap: 12 }}>
                <UserPlus size={20} /> Tag people
              </span>
              <TagPeople value={tagged} onChange={setTagged} />
            </div>
            <details className="detail-row column">
              <summary className="row" style={{ gap: 12 }}>
                <Tag size={20} /> Accessibility
              </summary>
              <p className="faint" style={{ fontSize: 13, margin: '8px 0' }}>
                Alt text describes your photos for people who can’t see them.
              </p>
              {items.map((it, i) => (
                <div key={it.id} className="row" style={{ marginTop: 8 }}>
                  {it.video ? <video src={it.url} className="alt-thumb" muted /> : <img src={it.url} className="alt-thumb" alt="" />}
                  <input
                    className="input"
                    placeholder="Write alt text…"
                    value={it.alt}
                    onChange={(e) => setItems((prev) => prev.map((x, j) => (j === i ? { ...x, alt: e.target.value } : x)))}
                  />
                </div>
              ))}
            </details>
            <details className="detail-row column" open>
              <summary>Advanced settings</summary>
              <div className="setting-line">
                <span>
                  <b>Hide like count</b>
                  <span className="faint">Only you will see the total number of likes.</span>
                </span>
                <Switch checked={hideLikes} onChange={setHideLikes} label="Hide like count" />
              </div>
              <div className="setting-line">
                <span>
                  <b>Turn off commenting</b>
                  <span className="faint">You can change this later from the ⋯ menu.</span>
                </span>
                <Switch checked={commentsOff} onChange={setCommentsOff} label="Turn off commenting" />
              </div>
            </details>
            {error && <p className="form-error" style={{ margin: 16 }}>{error}</p>}
          </aside>
        </div>
      )}

      {step === 'share' && <ShareProgress progress={progress} />}
    </Sheet>
  );
}

export function ShareProgress({ progress }: { progress: { label: string; value: number } }) {
  const done = progress.value >= 1;
  const circumference = 2 * Math.PI * 42;
  return (
    <div className="share-progress">
      <div className="progress-ring">
        <svg viewBox="0 0 100 100">
          <defs>
            <linearGradient id="pr-grad" x1="0" y1="0" x2="1" y2="1">
              <stop offset="0" stopColor="#7c5cff" />
              <stop offset="0.5" stopColor="#e84393" />
              <stop offset="1" stopColor="#ff8a3d" />
            </linearGradient>
          </defs>
          <circle cx="50" cy="50" r="42" fill="none" stroke="var(--bg-elev-3)" strokeWidth="5" />
          <motion.circle
            cx="50"
            cy="50"
            r="42"
            fill="none"
            stroke="url(#pr-grad)"
            strokeWidth="5"
            strokeLinecap="round"
            strokeDasharray={circumference}
            animate={{ strokeDashoffset: circumference * (1 - Math.max(0.03, progress.value)) }}
            transform="rotate(-90 50 50)"
          />
        </svg>
        <AnimatePresence>
          {done && (
            <motion.span className="progress-check" initial={{ scale: 0 }} animate={{ scale: 1 }} transition={{ type: 'spring', stiffness: 500, damping: 18 }}>
              <Check size={40} strokeWidth={2.5} />
            </motion.span>
          )}
        </AnimatePresence>
      </div>
      <b>{done ? 'Your post has been shared.' : progress.label}</b>
      <span className="faint">{done ? 'Encrypted for the people who can see it.' : 'Everything is encrypted on this device before it leaves.'}</span>
    </div>
  );
}

