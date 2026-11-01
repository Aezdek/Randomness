import { AtSign, BarChart3, Brush, Camera, Clock, Hash, HelpCircle, Image as ImageIcon, Link2, MapPin, Smile, SlidersHorizontal, Sparkles, Star, Trash2, Type, Undo2, X } from 'lucide-react';
import { AnimatePresence, motion } from 'motion/react';
import { useEffect, useRef, useState } from 'react';
import { createStory, newObjectId } from '../../core/compose';
import { messageOf } from '../../core/errors';
import { openablePhoto, prepare, type Prepared, upload } from '../../core/media';
import { StickerType, type MediaRef, type Sticker, type StoryText } from '../../core/payloads';
import { toast, useApp } from '../../core/store';
import { Avatar } from '../../ui/Avatar';
import { Spinner } from '../../ui/bits';
import { Sheet } from '../../ui/Sheet';
import { BACKGROUNDS, FONTS } from '../stories/backgrounds';
import { ShareProgress } from './CreatePost';

type Source = { kind: 'file'; file: Blob; url: string; video: boolean } | { kind: 'text' };

const COLORS = ['#ffffff', '#0b0b0e', '#ff3b5c', '#ff8a3d', '#ffd23f', '#2fd38a', '#4f8bff', '#b36bff'];
const EMOJI = ['😍', '😂', '🔥', '🥹', '✨', '🎉', '💜', '🙌', '😎', '🌙', '☕', '🎧'];

function useCamera(active: boolean) {
  const video = useRef<HTMLVideoElement>(null);
  const [stream, setStream] = useState<MediaStream | null>(null);
  const [facing, setFacing] = useState<'user' | 'environment'>('user');
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    if (!active) return;
    let current: MediaStream | null = null;
    navigator.mediaDevices
      ?.getUserMedia({ video: { facingMode: facing, width: { ideal: 1080 }, height: { ideal: 1920 } }, audio: true })
      .then((s) => {
        current = s;
        setStream(s);
        if (video.current) video.current.srcObject = s;
      })
      .catch(() => setError('Camera unavailable. Allow camera access, or upload from your device.'));
    return () => current?.getTracks().forEach((t) => t.stop());
  }, [active, facing]);
  return { video, stream, error, flip: () => setFacing((f) => (f === 'user' ? 'environment' : 'user')), facing };
}

function StickerPrompt({ type, onDone, onClose }: { type: string; onDone: (s: Sticker) => void; onClose: () => void }) {
  const users = useApp((s) => s.users);
  const me = useApp((s) => s.me!);
  const [text, setText] = useState('');
  const [options, setOptions] = useState(['Yes', 'No']);
  const [correct, setCorrect] = useState(0);
  const [emoji, setEmoji] = useState('😍');
  const [when, setWhen] = useState(() => new Date(Date.now() + 86_400_000).toISOString().slice(0, 16));
  const base = { id: newObjectId(), type, x: 0.5, y: 0.45, scale: 1, rotation: 0 };
  const done = (extra: Partial<Sticker>) => {
    onDone({ ...base, ...extra });
    onClose();
  };
  const titles: Record<string, string> = {
    poll: 'Poll', quiz: 'Quiz', slider: 'Emoji slider', question: 'Questions', countdown: 'Countdown', link: 'Link',
    mention: 'Mention', location: 'Location', hashtag: 'Hashtag', emoji: 'Emoji', text: 'Text',
  };
  return (
    <Sheet open onClose={onClose} title={titles[type] ?? 'Sticker'} bottom>
      <div className="stack">
        {type === StickerType.MENTION ? (
          <div className="tag-people">
            {Object.values(users)
              .filter((u) => u.id !== me.id && !u.deleted)
              .map((u) => (
                <button key={u.id} className="chip" onClick={() => done({ userId: u.id, text: u.username })}>
                  <Avatar userId={u.id} size={20} /> @{u.username}
                </button>
              ))}
          </div>
        ) : type === StickerType.EMOJI ? (
          <div className="emoji-grid">
            {EMOJI.map((e) => (
              <button key={e} onClick={() => done({ emoji: e })}>
                {e}
              </button>
            ))}
          </div>
        ) : (
          <>
            <input
              className="input"
              autoFocus
              placeholder={type === 'link' ? 'https://' : type === 'question' ? 'Ask me a question' : type === 'countdown' ? 'Countdown name' : type === 'slider' ? 'Ask a question…' : type === 'location' ? 'Where?' : type === 'hashtag' ? '#hashtag' : 'Ask a question…'}
              value={text}
              onChange={(e) => setText(e.target.value)}
              inputMode={type === 'link' ? 'url' : undefined}
            />
            {(type === 'poll' || type === 'quiz') && (
              <div className="stack" style={{ gap: 8 }}>
                {options.map((option, i) => (
                  <div className="row" key={i} style={{ gap: 8 }}>
                    {type === 'quiz' && (
                      <button className={`quiz-pick ${correct === i ? 'on' : ''}`} onClick={() => setCorrect(i)} aria-label="Correct answer">
                        {String.fromCharCode(65 + i)}
                      </button>
                    )}
                    <input className="input" value={option} placeholder={`Option ${i + 1}`} onChange={(e) => setOptions((o) => o.map((x, j) => (j === i ? e.target.value : x)))} />
                  </div>
                ))}
                {options.length < 4 && (
                  <button className="btn btn-secondary btn-sm" onClick={() => setOptions((o) => [...o, ''])}>
                    Add option
                  </button>
                )}
              </div>
            )}
            {type === 'slider' && (
              <div className="emoji-grid small">
                {EMOJI.map((e) => (
                  <button key={e} className={emoji === e ? 'on' : ''} onClick={() => setEmoji(e)}>
                    {e}
                  </button>
                ))}
              </div>
            )}
            {type === 'countdown' && <input className="input" type="datetime-local" value={when} onChange={(e) => setWhen(e.target.value)} />}
            <button
              className="btn btn-primary btn-lg"
              onClick={() => {
                if (type === 'poll' || type === 'quiz') return done({ text, options: options.filter((o) => o.trim()), correct: type === 'quiz' ? correct : null });
                if (type === 'slider') return done({ text, emoji });
                if (type === 'countdown') return done({ text, endsAt: new Date(when).getTime() });
                if (type === 'link') return done({ url: /^https?:\/\//.test(text) ? text : `https://${text}`, text: text.replace(/^https?:\/\//, '') });
                if (type === 'hashtag') return done({ text: text.replace(/^#*/, '#') });
                return done({ text });
              }}
              disabled={type !== 'question' && !text.trim()}
            >
              Add
            </button>
          </>
        )}
      </div>
    </Sheet>
  );
}

/** Freehand drawing layer, exported as a transparent PNG. */
function DrawLayer({ active, color, canvasRef, onStroke }: { active: boolean; color: string; canvasRef: React.RefObject<HTMLCanvasElement | null>; onStroke: () => void }) {
  const drawing = useRef(false);
  const point = (e: React.PointerEvent) => {
    const c = canvasRef.current!;
    const r = c.getBoundingClientRect();
    return { x: ((e.clientX - r.left) / r.width) * c.width, y: ((e.clientY - r.top) / r.height) * c.height };
  };
  return (
    <canvas
      ref={canvasRef}
      width={1080}
      height={1920}
      className={`draw-layer ${active ? 'active' : ''}`}
      onPointerDown={(e) => {
        if (!active) return;
        drawing.current = true;
        const ctx = canvasRef.current!.getContext('2d')!;
        const p = point(e);
        ctx.strokeStyle = color;
        ctx.lineWidth = 14;
        ctx.lineCap = 'round';
        ctx.lineJoin = 'round';
        ctx.shadowColor = color;
        ctx.shadowBlur = 6;
        ctx.beginPath();
        ctx.moveTo(p.x, p.y);
        (e.target as HTMLElement).setPointerCapture(e.pointerId);
      }}
      onPointerMove={(e) => {
        if (!drawing.current) return;
        const ctx = canvasRef.current!.getContext('2d')!;
        const p = point(e);
        ctx.lineTo(p.x, p.y);
        ctx.stroke();
      }}
      onPointerUp={() => {
        if (drawing.current) onStroke();
        drawing.current = false;
      }}
    />
  );
}

export function CreateStory({ onClose }: { onClose: () => void }) {
  const [mode, setMode] = useState<'camera' | 'edit'>('camera');
  const [source, setSource] = useState<Source | null>(null);
  const [text, setText] = useState<StoryText>({ text: '', background: 'sunset', font: 'modern', align: 'center' });
  const [stickers, setStickers] = useState<Sticker[]>([]);
  const [prompt, setPrompt] = useState<string | null>(null);
  const [tray, setTray] = useState(false);
  const [drawMode, setDrawMode] = useState(false);
  const [drawColor, setDrawColor] = useState(COLORS[0]);
  const [strokes, setStrokes] = useState(0);
  const [closeFriends, setCloseFriends] = useState(false);
  const [recording, setRecording] = useState(false);
  const [progress, setProgress] = useState<{ label: string; value: number } | null>(null);
  const drawCanvas = useRef<HTMLCanvasElement | null>(null);
  const canvas = useRef<HTMLDivElement>(null);
  const file = useRef<HTMLInputElement>(null);
  const recorder = useRef<MediaRecorder | null>(null);
  const pressTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const camera = useCamera(mode === 'camera' && source === null);

  const useFile = (blob: Blob) => {
    setSource({ kind: 'file', file: blob, url: URL.createObjectURL(blob), video: blob.type.startsWith('video/') });
    setMode('edit');
  };

  const snap = () => {
    const v = camera.video.current;
    if (!v || !v.videoWidth) return;
    const c = document.createElement('canvas');
    c.width = v.videoWidth;
    c.height = v.videoHeight;
    const ctx = c.getContext('2d')!;
    if (camera.facing === 'user') {
      ctx.translate(c.width, 0);
      ctx.scale(-1, 1);
    }
    ctx.drawImage(v, 0, 0);
    c.toBlob((b) => b && useFile(b), 'image/jpeg', 0.92);
  };

  const startRecording = () => {
    if (!camera.stream) return;
    const chunks: Blob[] = [];
    const rec = new MediaRecorder(camera.stream);
    rec.ondataavailable = (e) => chunks.push(e.data);
    rec.onstop = () => useFile(new Blob(chunks, { type: rec.mimeType || 'video/webm' }));
    rec.start();
    recorder.current = rec;
    setRecording(true);
    setTimeout(() => recorder.current?.state === 'recording' && recorder.current.stop(), 60_000);
  };

  const onShutterDown = () => {
    pressTimer.current = setTimeout(startRecording, 350);
  };
  const onShutterUp = () => {
    if (pressTimer.current) clearTimeout(pressTimer.current);
    if (recorder.current?.state === 'recording') {
      recorder.current.stop();
      setRecording(false);
    } else if (!recording) snap();
  };

  const drag = useRef<{ id: string; dx: number; dy: number } | null>(null);
  const moveSticker = (e: React.PointerEvent) => {
    const d = drag.current;
    const box = canvas.current?.getBoundingClientRect();
    if (!d || !box) return;
    const x = Math.max(0.05, Math.min(0.95, (e.clientX - box.left) / box.width - d.dx));
    const y = Math.max(0.05, Math.min(0.95, (e.clientY - box.top) / box.height - d.dy));
    setStickers((list) => list.map((s) => (s.id === d.id ? { ...s, x, y } : s)));
  };

  const share = async () => {
    setProgress({ label: 'Preparing', value: 0.05 });
    try {
      let media: MediaRef | null = null;
      if (source?.kind === 'file') {
        const prepared: Prepared = await prepare(source.file, 'story', (f) => setProgress({ label: 'Optimising video', value: 0.05 + f * 0.55 }));
        setProgress({ label: 'Encrypting and uploading', value: 0.6 });
        media = await upload(prepared, (f) => setProgress({ label: 'Encrypting and uploading', value: 0.6 + f * 0.35 }));
      }
      let drawing: MediaRef | null = null;
      if (strokes > 0 && drawCanvas.current) {
        const blob = await new Promise<Blob>((r) => drawCanvas.current!.toBlob((b) => r(b!), 'image/png'));
        drawing = await upload({ blob, mime: 'image/png', width: 1080, height: 1920 });
      }
      await createStory(
        {
          media,
          text: source?.kind === 'text' ? text : null,
          stickers,
          drawing,
          durationMs: media?.durationMs ?? 5000,
        },
        closeFriends,
      );
      setProgress({ label: 'Shared', value: 1 });
      setTimeout(onClose, 800);
    } catch (e) {
      toast(messageOf(e), 'error');
      setProgress(null);
    }
  };

  const font = FONTS[text.font ?? 'modern'];
  const fontNames = Object.keys(FONTS);

  return (
    <div className="story-composer" role="dialog" aria-label="New story">
      <button className="icon-btn on-media composer-close" onClick={onClose} aria-label="Close">
        <X size={22} />
      </button>
      <div className="story-composer-card" ref={canvas} onPointerMove={moveSticker} onPointerUp={() => (drag.current = null)}>
        {mode === 'camera' && !source && (
          <>
            {camera.error ? (
              <div className="camera-error">
                <Camera size={40} strokeWidth={1.4} />
                <p>{camera.error}</p>
              </div>
            ) : (
              <video ref={camera.video} autoPlay muted playsInline className={`camera ${camera.facing === 'user' ? 'mirror' : ''}`} />
            )}
            <div className="camera-modes">
              <button onClick={() => file.current?.click()} className="icon-btn on-media" aria-label="Upload from device">
                <ImageIcon size={22} />
              </button>
              <motion.button
                className={`shutter ${recording ? 'recording' : ''}`}
                onPointerDown={onShutterDown}
                onPointerUp={onShutterUp}
                whileTap={{ scale: 0.9 }}
                aria-label="Tap for photo, hold for video"
                disabled={!!camera.error}
              />
              <button
                onClick={() => {
                  setSource({ kind: 'text' });
                  setMode('edit');
                }}
                className="icon-btn on-media"
                aria-label="Text story"
              >
                <Type size={22} />
              </button>
            </div>
            <input ref={file} type="file" accept="image/*,video/*,.heic,.heif" hidden onChange={(e) => e.target.files?.[0] && void openablePhoto(e.target.files[0]).then(useFile, (err) => toast(messageOf(err), 'error'))} />
          </>
        )}

        {mode === 'edit' && source && (
          <>
            {source.kind === 'file' ? (
              <>
                {source.video ? <video src={source.url} className="story-backdrop-media" autoPlay muted loop playsInline /> : <img src={source.url} className="story-backdrop-media" alt="" />}
                {source.video ? <video src={source.url} className="story-edit-media" autoPlay loop playsInline /> : <img src={source.url} className="story-edit-media" alt="" />}
              </>
            ) : (
              <div className="story-text" style={{ background: BACKGROUNDS[text.background ?? 'sunset'] }}>
                <textarea
                  className="story-text-input"
                  placeholder="Start typing…"
                  value={text.text}
                  onChange={(e) => setText((t) => ({ ...t, text: e.target.value }))}
                  maxLength={300}
                  autoFocus
                  style={{ fontFamily: font.family, fontStyle: font.style, fontWeight: font.weight, textTransform: font.transform as React.CSSProperties['textTransform'], letterSpacing: font.tracking, textAlign: (text.align ?? 'center') as React.CSSProperties['textAlign'] }}
                />
              </div>
            )}
            <DrawLayer active={drawMode} color={drawColor} canvasRef={drawCanvas} onStroke={() => setStrokes((n) => n + 1)} />
            <div className="stickers editing">
              {stickers.map((s) => (
                <div
                  key={s.id}
                  className="sticker draggable"
                  style={{ left: `${s.x * 100}%`, top: `${s.y * 100}%`, transform: `translate(-50%, -50%) rotate(${s.rotation ?? 0}deg) scale(${s.scale ?? 1})` }}
                  onPointerDown={(e) => {
                    const box = canvas.current!.getBoundingClientRect();
                    drag.current = { id: s.id, dx: (e.clientX - box.left) / box.width - s.x, dy: (e.clientY - box.top) / box.height - s.y };
                  }}
                  onWheel={(e) => setStickers((list) => list.map((x) => (x.id === s.id ? { ...x, scale: Math.max(0.5, Math.min(2.5, (x.scale ?? 1) - e.deltaY * 0.001)) } : x)))}
                  onDoubleClick={() => setStickers((list) => list.filter((x) => x.id !== s.id))}
                >
                  <StickerPreview sticker={s} />
                </div>
              ))}
            </div>

            <div className="story-tools">
              {source.kind === 'text' && (
                <>
                  <button className="icon-btn on-media" onClick={() => setText((t) => ({ ...t, font: fontNames[(fontNames.indexOf(t.font ?? 'modern') + 1) % fontNames.length] }))} aria-label="Font">
                    <span style={{ fontWeight: 800 }}>Aa</span>
                  </button>
                  <button
                    className="icon-btn on-media"
                    onClick={() => setText((t) => ({ ...t, background: Object.keys(BACKGROUNDS)[(Object.keys(BACKGROUNDS).indexOf(t.background ?? 'sunset') + 1) % Object.keys(BACKGROUNDS).length] }))}
                    aria-label="Background"
                  >
                    <span className="swatch" style={{ background: BACKGROUNDS[text.background ?? 'sunset'] }} />
                  </button>
                </>
              )}
              <button className="icon-btn on-media" onClick={() => setTray(true)} aria-label="Stickers">
                <Smile size={22} />
              </button>
              <button className={`icon-btn on-media ${drawMode ? 'on' : ''}`} onClick={() => setDrawMode((d) => !d)} aria-label="Draw">
                <Brush size={22} />
              </button>
              {drawMode && strokes > 0 && (
                <button
                  className="icon-btn on-media"
                  onClick={() => {
                    drawCanvas.current?.getContext('2d')?.clearRect(0, 0, 1080, 1920);
                    setStrokes(0);
                  }}
                  aria-label="Clear drawing"
                >
                  <Undo2 size={20} />
                </button>
              )}
              <button
                className="icon-btn on-media"
                onClick={() => {
                  setSource(null);
                  setStickers([]);
                  setMode('camera');
                }}
                aria-label="Discard"
              >
                <Trash2 size={20} />
              </button>
            </div>
            {drawMode && (
              <div className="color-row">
                {COLORS.map((c) => (
                  <button key={c} className={`color ${drawColor === c ? 'on' : ''}`} style={{ background: c }} onClick={() => setDrawColor(c)} aria-label={`Colour ${c}`} />
                ))}
              </div>
            )}

            <div className="story-share">
              <button className={`share-pill ${!closeFriends ? 'on' : ''}`} onClick={() => setCloseFriends(false)}>
                <Sparkles size={16} /> Your story
              </button>
              <button className={`share-pill close ${closeFriends ? 'on' : ''}`} onClick={() => setCloseFriends(true)}>
                <Star size={16} fill="currentColor" /> Close Friends
              </button>
              <button className="btn btn-primary" onClick={share} disabled={!!progress || (source.kind === 'text' && !text.text.trim() && !stickers.length)}>
                {progress ? <Spinner size={16} /> : 'Share'}
              </button>
            </div>
          </>
        )}
      </div>

      <AnimatePresence>
        {tray && (
          <motion.div className="sticker-tray" initial={{ y: '100%' }} animate={{ y: 0 }} exit={{ y: '100%' }} transition={{ type: 'spring', stiffness: 380, damping: 36 }}>
            <div className="sticker-tray-head">
              <b>Stickers</b>
              <button className="icon-btn sm" onClick={() => setTray(false)} aria-label="Close">
                <X size={18} />
              </button>
            </div>
            <div className="sticker-tray-grid">
              {[
                { type: 'mention', icon: <AtSign size={18} />, label: 'Mention' },
                { type: 'poll', icon: <BarChart3 size={18} />, label: 'Poll' },
                { type: 'question', icon: <HelpCircle size={18} />, label: 'Questions' },
                { type: 'quiz', icon: <Sparkles size={18} />, label: 'Quiz' },
                { type: 'slider', icon: <SlidersHorizontal size={18} />, label: 'Slider' },
                { type: 'countdown', icon: <Clock size={18} />, label: 'Countdown' },
                { type: 'link', icon: <Link2 size={18} />, label: 'Link' },
                { type: 'location', icon: <MapPin size={18} />, label: 'Location' },
                { type: 'hashtag', icon: <Hash size={18} />, label: 'Hashtag' },
                { type: 'emoji', icon: <Smile size={18} />, label: 'Emoji' },
                { type: 'text', icon: <Type size={18} />, label: 'Text' },
              ].map((option) => (
                <button key={option.type} className="sticker-option" onClick={() => (setTray(false), setPrompt(option.type))}>
                  {option.icon} {option.label}
                </button>
              ))}
            </div>
          </motion.div>
        )}
      </AnimatePresence>
      {prompt && <StickerPrompt type={prompt} onDone={(s) => setStickers((list) => [...list, s])} onClose={() => setPrompt(null)} />}
      {progress && (
        <div className="story-progress-overlay">
          <ShareProgress progress={progress} />
        </div>
      )}
    </div>
  );
}

/** Static rendering of a sticker while composing (the interactive version lives in the viewer). */
function StickerPreview({ sticker }: { sticker: Sticker }) {
  const users = useApp((s) => s.users);
  switch (sticker.type) {
    case 'poll':
    case 'quiz':
      return (
        <div className="sticker-card poll">
          {sticker.text && <div className="poll-q">{sticker.text}</div>}
          {(sticker.options ?? []).map((o, i) => (
            <div key={i} className={`poll-option ${sticker.type === 'quiz' && sticker.correct === i ? 'correct' : ''}`}>
              <span className="poll-label">{o}</span>
            </div>
          ))}
        </div>
      );
    case 'slider':
      return (
        <div className="sticker-card slider">
          {sticker.text && <div className="poll-q">{sticker.text}</div>}
          <div className="slider-track static">
            <span className="slider-emoji" style={{ left: 'calc(50% - 16px)' }}>
              {sticker.emoji}
            </span>
          </div>
        </div>
      );
    case 'question':
      return (
        <div className="sticker-card question">
          <div className="poll-q">{sticker.text || 'Ask me a question'}</div>
          <div className="question-input faint">Type something…</div>
        </div>
      );
    case 'countdown':
      return (
        <div className="sticker-card countdown">
          <div className="poll-q">
            <Clock size={14} /> {sticker.text}
          </div>
          <div className="countdown-digits">{new Date(sticker.endsAt ?? 0).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' })}</div>
        </div>
      );
    case 'mention':
      return <span className="sticker-pill"><AtSign size={16} /> {sticker.userId ? users[sticker.userId]?.username : sticker.text}</span>;
    case 'link':
      return <span className="sticker-pill"><Link2 size={16} /> {sticker.text}</span>;
    case 'location':
      return <span className="sticker-pill"><MapPin size={16} /> {sticker.text}</span>;
    case 'hashtag':
      return <span className="sticker-pill"><Hash size={16} /> {(sticker.text ?? '').replace(/^#/, '')}</span>;
    case 'emoji':
      return <span className="sticker-emoji">{sticker.emoji}</span>;
    default:
      return <span className="sticker-text">{sticker.text}</span>;
  }
}
