// Plans and live location inside a chat: the cards in the conversation, the sheets that make them, and the sharer that
// keeps your own live location up to date while this tab is open.
import { CalendarDays, Check, HelpCircle, ListChecks, MapPin, Navigation, X } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import {
  answerPlan,
  LOCATION_CHOICES,
  liveLocations,
  mapsLink,
  moveLocation,
  planAnswers,
  shareLocation,
  stopLocation,
} from '../../core/circle';
import { messageOf } from '../../core/errors';
import { PlanAnswer, type MessagePayload, type Plan } from '../../core/payloads';
import { buildIndex, nameIn, useIndex } from '../../core/selectors';
import { safeLocation, safePlan } from '../../core/safe';
import { getState, toast, useApp, type Entity } from '../../core/store';
import { Avatar } from '../../ui/Avatar';
import { Ago } from '../../ui/bits';
import { Sheet } from '../../ui/Sheet';

const fail = (e: unknown) => toast(messageOf(e), 'error');

// ------------------------------------------------------------------------------------------------------------ plans

const ANSWERS = [
  { id: PlanAnswer.GOING, label: 'Going', icon: Check },
  { id: PlanAnswer.MAYBE, label: 'Maybe', icon: HelpCircle },
  { id: PlanAnswer.NO, label: 'Can’t go', icon: X },
] as const;

function planWhen(at: number) {
  const d = new Date(at);
  const sameYear = d.getFullYear() === new Date().getFullYear();
  return d.toLocaleString(undefined, { weekday: 'short', day: 'numeric', month: 'short', year: sameYear ? undefined : 'numeric', hour: 'numeric', minute: '2-digit' });
}

export function PlanCard({ message }: { message: Entity<MessagePayload> }) {
  const index = useIndex();
  const me = useApp((s) => s.me!);
  const users = useApp((s) => s.users);
  const plan = safePlan(message.payload.plan);
  const answers = planAnswers(index, message.dto.id);
  const mine = answers[me.id];
  if (!plan) return <span className="faint small">A plan that couldn’t be shown</span>;
  const past = plan.at < Date.now() - 3 * 60 * 60_000;
  const name = (id: string) => (id === me.id ? 'You' : nameIn(message.dto.threadId ?? null, id, index, users));
  const going = Object.entries(answers).filter(([, a]) => a === PlanAnswer.GOING).map(([id]) => id);
  const maybe = Object.entries(answers).filter(([, a]) => a === PlanAnswer.MAYBE).map(([id]) => id);
  return (
    <div className={`plan-card ${past ? 'past' : ''}`}>
      <span className="plan-date" aria-hidden>
        <span>{new Date(plan.at).toLocaleString(undefined, { month: 'short' })}</span>
        <b>{new Date(plan.at).getDate()}</b>
      </span>
      <div className="plan-body">
        <b className="plan-title">{plan.title}</b>
        <span className="plan-meta">
          <CalendarDays size={13} /> {planWhen(plan.at)}
        </span>
        {plan.place && (
          <span className="plan-meta">
            <MapPin size={13} /> {plan.place}
          </span>
        )}
        {(going.length > 0 || maybe.length > 0) && (
          <span className="plan-people" title={[...going.map((id) => `${name(id)}: going`), ...maybe.map((id) => `${name(id)}: maybe`)].join('\n')}>
            <span className="plan-avatars">
              {going.slice(0, 4).map((id) => (
                <Avatar key={id} userId={id} size={20} />
              ))}
            </span>
            {going.length > 0 && <span>{going.length} going</span>}
            {maybe.length > 0 && <span className="faint">{maybe.length} maybe</span>}
          </span>
        )}
      </div>
      {!past && (
        <div className="plan-answers" role="group" aria-label="Your answer">
          {ANSWERS.map(({ id, label, icon: Icon }) => (
            <button key={id} className={`plan-answer ${mine === id ? 'on' : ''}`} aria-pressed={mine === id} onClick={() => void answerPlan(message, id).catch(fail)}>
              <Icon size={14} /> {label}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

/** "Saturday 19:30" as the value of a datetime-local input, in the viewer's own time zone. */
function localInput(at: number) {
  const d = new Date(at - new Date(at).getTimezoneOffset() * 60_000);
  return d.toISOString().slice(0, 16);
}

export function PlanSheet({ open, onClose, onSend }: { open: boolean; onClose: () => void; onSend: (plan: Plan) => void }) {
  const tomorrowEvening = () => {
    const d = new Date();
    d.setDate(d.getDate() + 1);
    d.setHours(19, 0, 0, 0);
    return d.getTime();
  };
  const [title, setTitle] = useState('');
  const [when, setWhen] = useState(() => localInput(tomorrowEvening()));
  const [place, setPlace] = useState('');
  const at = new Date(when).getTime();
  const ready = title.trim().length > 0 && Number.isFinite(at);
  const close = () => (setTitle(''), setPlace(''), setWhen(localInput(tomorrowEvening())), onClose());
  return (
    <Sheet open={open} onClose={close} title="Plan" bottom>
      <form
        className="chat-poll-form"
        onSubmit={(e) => {
          e.preventDefault();
          if (!ready) return;
          onSend({ title: title.trim(), at, place: place.trim() || null });
          close();
        }}
      >
        <input className="input" placeholder="What’s the plan?" value={title} maxLength={100} onChange={(e) => setTitle(e.target.value)} autoFocus aria-label="What’s the plan?" />
        <label className="field">
          <span>When</span>
          <input className="input" type="datetime-local" value={when} onChange={(e) => setWhen(e.target.value)} required />
        </label>
        <input className="input" placeholder="Where (optional)" value={place} maxLength={100} onChange={(e) => setPlace(e.target.value)} aria-label="Where" />
        <button className="btn btn-primary btn-block" disabled={!ready}>
          Send plan
        </button>
        <p className="faint small">Everyone in the chat can say if they’re going. Like everything here, the plan is end-to-end encrypted.</p>
      </form>
    </Sheet>
  );
}

// ------------------------------------------------------------------------------------------------------- location

function minutesLeft(until: number) {
  // Nobody can share for more than 8 hours; a longer claim shows as 8.
  const m = Math.round(Math.min(8 * 60 * 60_000, Math.max(0, until - Date.now())) / 60_000);
  return m >= 60 ? `${Math.floor(m / 60)} h ${m % 60 ? `${m % 60} min` : ''}`.trim() : `${m} min`;
}

export function LocationCard({ message, mine }: { message: Entity<MessagePayload>; mine: boolean }) {
  const location = safeLocation(message.payload.location);
  const [, tick] = useState(0);
  useEffect(() => {
    const timer = setInterval(() => tick((n) => n + 1), 30_000);
    return () => clearInterval(timer);
  }, []);
  if (!location) return <span className="faint small">A location that couldn’t be shown</span>;
  const live = location.until > Date.now();
  return (
    <div className={`location-card ${live ? 'live' : ''}`}>
      <a className="location-map" href={mapsLink(location)} target="_blank" rel="noopener noreferrer" aria-label="Open in maps">
        <span className="location-grid" aria-hidden />
        <span className="location-dot" aria-hidden>
          <Navigation size={14} />
        </span>
        <span className="location-coords tabular">
          {location.lat.toFixed(4)}, {location.lng.toFixed(4)}
        </span>
      </a>
      <div className="location-body">
        <b>{live ? 'Live location' : 'Location sharing ended'}</b>
        <span className="faint">
          {live ? <>Updated <Ago at={location.updatedAt} className="" suffix /> · {minutesLeft(location.until)} left</> : 'No longer shared'}
          {location.accuracy && location.accuracy <= 100_000 ? ` · within ${Math.round(location.accuracy)} m` : ''}
        </span>
        <span className="location-actions">
          <a className="btn btn-secondary btn-sm" href={mapsLink(location)} target="_blank" rel="noopener noreferrer">
            Open in maps
          </a>
          {mine && live && (
            <button className="btn btn-danger btn-sm" onClick={() => void stopLocation(message).catch(fail)}>
              Stop sharing
            </button>
          )}
        </span>
      </div>
    </div>
  );
}

function currentPosition(): Promise<GeolocationPosition> {
  return new Promise((resolve, reject) => {
    if (!navigator.geolocation) return reject(new Error('This browser can’t share where you are'));
    navigator.geolocation.getCurrentPosition(resolve, (e) => reject(new Error(e.code === e.PERMISSION_DENIED ? 'Allow location for Hush in your browser to share where you are' : 'Couldn’t find where you are')), {
      enableHighAccuracy: true,
      timeout: 20_000,
    });
  });
}

const DURATION_LABELS = ['15 minutes', '1 hour', '8 hours'];

export function LocationSheet({ open, onClose, threadId }: { open: boolean; onClose: () => void; threadId: string }) {
  const [choice, setChoice] = useState(1);
  const [busy, setBusy] = useState(false);
  const share = async () => {
    setBusy(true);
    try {
      const pos = await currentPosition();
      await shareLocation(threadId, pos.coords.latitude, pos.coords.longitude, pos.coords.accuracy ?? null, LOCATION_CHOICES[choice]);
      onClose();
    } catch (e) {
      fail(e);
    } finally {
      setBusy(false);
    }
  };
  return (
    <Sheet open={open} onClose={onClose} title="Share live location" bottom>
      <div className="chat-poll-form">
        <p className="faint small">The chat sees where you are, updated as you move while Hush is open, until the time is up. Then it’s deleted from the server. Only the latest spot is ever stored, never a trail.</p>
        <div className="duration-choices" role="radiogroup" aria-label="For how long">
          {DURATION_LABELS.map((label, i) => (
            <button key={label} role="radio" aria-checked={choice === i} className={`duration-choice ${choice === i ? 'on' : ''}`} onClick={() => setChoice(i)}>
              {label}
            </button>
          ))}
        </div>
        <button className="btn btn-primary btn-block" disabled={busy} onClick={() => void share()}>
          <Navigation size={16} /> {busy ? 'Finding you…' : 'Share'}
        </button>
      </div>
    </Sheet>
  );
}

/**
 * While any of your own live locations is running, follows the device's position and replaces the message with each
 * real move (at most every 20 seconds, or 25 metres). Mounted once in the shell, so it works from any page.
 */
export function LocationSharer() {
  const me = useApp((s) => s.me?.id);
  const entities = useApp((s) => s.entities);
  const threads = useApp((s) => s.threads);
  const active = (() => {
    if (!me) return [] as Entity<MessagePayload>[];
    const idx = buildIndex({ entities, me: getState().me });
    return Object.keys(threads).flatMap((t) => liveLocations(idx, t)).filter((m) => m.dto.authorId === me);
  })();
  const key = active.map((m) => m.dto.id).join(',');
  const last = useRef<{ at: number; lat: number; lng: number } | null>(null);

  useEffect(() => {
    if (!key || !navigator.geolocation) return;
    const ids = key.split(',');
    const watch = navigator.geolocation.watchPosition(
      (pos) => {
        const { latitude: lat, longitude: lng, accuracy } = pos.coords;
        const prev = last.current;
        const moved = prev ? distance(prev.lat, prev.lng, lat, lng) : Infinity;
        if (prev && Date.now() - prev.at < 20_000 && moved < 25) return;
        if (prev && moved < 10 && Date.now() - prev.at < 5 * 60_000) return;
        last.current = { at: Date.now(), lat, lng };
        const idx = buildIndex(getState());
        for (const id of ids) {
          const message = Object.values(idx.messages).flat().find((m) => m.dto.id === id);
          if (message) void moveLocation(message, lat, lng, accuracy ?? null).catch(() => undefined);
        }
      },
      () => undefined,
      { enableHighAccuracy: true, maximumAge: 15_000 },
    );
    // When the last one runs out, stop following (the list changes and this effect ends).
    const soonest = Math.min(...active.map((m) => safeLocation(m.payload.location)?.until ?? 0));
    const timer = setTimeout(() => useApp.setState((s) => ({ entities: { ...s.entities } })), Math.max(1000, soonest - Date.now() + 500));
    return () => {
      navigator.geolocation.clearWatch(watch);
      clearTimeout(timer);
    };
  }, [key]); // eslint-disable-line react-hooks/exhaustive-deps
  return null;
}

/** Metres between two points (haversine). */
function distance(lat1: number, lng1: number, lat2: number, lng2: number) {
  const r = 6_371_000;
  const rad = Math.PI / 180;
  const a = Math.sin(((lat2 - lat1) * rad) / 2) ** 2 + Math.cos(lat1 * rad) * Math.cos(lat2 * rad) * Math.sin(((lng2 - lng1) * rad) / 2) ** 2;
  return 2 * r * Math.asin(Math.sqrt(a));
}

// -------------------------------------------------------------------------------------------------------- the menu

export type ChatExtra = 'plan' | 'location' | 'poll';

/** The composer's "+": plans, live location and (in groups) polls. */
export function ExtrasSheet({ open, onClose, onPick, group }: { open: boolean; onClose: () => void; onPick: (what: ChatExtra) => void; group: boolean }) {
  const items: { id: ChatExtra; label: string; sub: string; icon: typeof MapPin }[] = [
    { id: 'plan', label: 'Plan', sub: 'A time and place, and who’s going', icon: CalendarDays },
    { id: 'location', label: 'Live location', sub: 'Where you are, for a set time', icon: Navigation },
    ...(group ? [{ id: 'poll' as const, label: 'Create poll', sub: 'Let everyone vote', icon: ListChecks }] : []),
  ];
  return (
    <Sheet open={open} onClose={onClose} title="Add to chat" bottom>
      <div className="extras-list">
        {items.map(({ id, label, sub, icon: Icon }) => (
          <button key={id} className="extras-item" onClick={() => (onClose(), onPick(id))}>
            <span className="extras-icon">
              <Icon size={20} />
            </span>
            <span className="user-row-text">
              <b>{label}</b>
              <span className="faint">{sub}</span>
            </span>
          </button>
        ))}
      </div>
    </Sheet>
  );
}
