import {
  Bookmark,
  Clapperboard,
  Compass,
  Heart,
  Home,
  LockKeyhole,
  Menu,
  MessageCircle,
  Mic,
  MicOff,
  MoreHorizontal,
  PhoneOff,
  Play,
  PlusSquare,
  Search,
  Send,
  Video,
} from 'lucide-react';
import { useId, useMemo } from 'react';
import { Wordmark } from '../../ui/Brand';

/*
 * The landing page's illustrations, drawn with HTML and CSS rather than screenshots: they stay sharp at any size,
 * cost nothing to load, and the made-up people in them never need real photos.
 */

/** A seeded shuffle, so the stars are where they were last time. */
function seeded(seed: number) {
  let s = seed;
  return () => {
    s = (s * 1664525 + 1013904223) % 4294967296;
    return s / 4294967296;
  };
}

/** Faint specks drifting up behind the hero. */
export function Starfield({ count = 56 }: { count?: number }) {
  const stars = useMemo(() => {
    const rand = seeded(7);
    return Array.from({ length: count }, () => ({
      left: rand() * 100,
      size: 0.6 + rand() * 1.6,
      duration: 16 + rand() * 26,
      delay: -rand() * 40,
      opacity: 0.25 + rand() * 0.55,
    }));
  }, [count]);
  return (
    <div className="starfield" aria-hidden>
      {stars.map((star, i) => (
        <span
          key={i}
          style={{
            left: `${star.left}%`,
            width: star.size,
            height: star.size,
            animationDuration: `${star.duration}s`,
            animationDelay: `${star.delay}s`,
            opacity: star.opacity,
          }}
        />
      ))}
    </div>
  );
}

const PEOPLE = [
  { name: 'You', initial: 'Y', tone: 'a' },
  { name: 'maya', initial: 'M', tone: 'b' },
  { name: 'leo', initial: 'L', tone: 'c' },
  { name: 'noor', initial: 'N', tone: 'd' },
  { name: 'sam', initial: 'S', tone: 'e' },
];

function Face({ initial, tone, size = 32 }: { initial: string; tone: string; size?: number }) {
  return (
    <span className={`la-face tone-${tone}`} style={{ width: size, height: size, fontSize: size * 0.42 }}>
      {initial}
    </span>
  );
}

/** The Hush home feed as a phone shows it: the screen only, the device around it is drawn by the page. */
export function PhoneScreen() {
  return (
    <div className="la-screen">
      <div className="la-status">
        <span>9:41</span>
        <span className="la-island" />
        <span className="la-bars">
          <i />
          <i />
          <i />
        </span>
      </div>
      <div className="la-top">
        <Wordmark />
        <span className="la-top-icons">
          <Heart size={20} />
          <Send size={20} />
        </span>
      </div>
      <div className="la-stories">
        {PEOPLE.map((p, i) => (
          <span key={p.name} className="la-story">
            <span className={`la-ring ${i === 0 ? 'seen' : i === 3 ? 'close' : ''}`}>
              <Face initial={p.initial} tone={p.tone} size={46} />
            </span>
            <small>{p.name}</small>
          </span>
        ))}
      </div>
      <div className="la-post">
        <div className="la-post-head">
          <Face initial="M" tone="b" size={26} />
          <span>
            <b>maya</b>
            <small>Lisbon</small>
          </span>
        </div>
        <div className="la-photo sunset">
          <span className="la-count">1/3</span>
        </div>
        <div className="la-actions">
          <Heart size={21} className="liked" fill="currentColor" />
          <MessageCircle size={21} />
          <Send size={21} />
          <Bookmark size={21} className="end" />
        </div>
        <p className="la-caption">
          <b>8 likes</b>
          <br />
          <b>maya</b> golden hour with the best people
        </p>
      </div>
      <div className="la-tabbar">
        <Home size={22} />
        <Search size={22} />
        <PlusSquare size={22} />
        <Play size={22} />
        <Face initial="Y" tone="a" size={22} />
      </div>
    </div>
  );
}

const DESK_NAV = [
  { icon: Home, label: 'Home', on: true },
  { icon: Search, label: 'Search' },
  { icon: Compass, label: 'Explore' },
  { icon: Clapperboard, label: 'Reels' },
  { icon: MessageCircle, label: 'Messages', badge: 2 },
  { icon: Heart, label: 'Notifications' },
  { icon: PlusSquare, label: 'Create' },
];

const DESK_CHATS = [
  { name: 'leo', initial: 'L', tone: 'c', text: 'dinner saturday? 🍝', time: '2m', unread: true },
  { name: 'noor', initial: 'N', tone: 'd', text: 'Sent a voice note', time: '1h', unread: true },
  { name: 'Lisbon crew', initial: 'L', tone: 'e', text: 'maya: photos are up!', time: '3h' },
  { name: 'sam', initial: 'S', tone: 'e', text: 'Liked your story', time: '5h' },
];

/** Hush on a big screen: sidebar, feed and messages, the way the web app lays them out. Drawn at 1200px wide. */
export function DesktopScreen() {
  return (
    <div className="la-desk">
      <aside className="la-side">
        <span className="la-side-brand">
          <Wordmark />
        </span>
        {DESK_NAV.map(({ icon: Icon, label, on, badge }) => (
          <span key={label} className={`la-side-item ${on ? 'on' : ''}`}>
            <span className="la-side-icon">
              <Icon size={24} strokeWidth={on ? 2.6 : 1.8} />
              {badge && <i>{badge}</i>}
            </span>
            {label}
          </span>
        ))}
        <span className="la-side-item">
          <Face initial="Y" tone="a" size={24} />
          Profile
        </span>
        <span className="la-side-item end">
          <Menu size={24} strokeWidth={1.8} />
          More
        </span>
      </aside>
      <div className="la-desk-feed">
        <div className="la-desk-stories">
          {[...PEOPLE, { name: 'ava', initial: 'A', tone: 'c' }, { name: 'kai', initial: 'K', tone: 'd' }].map((p, i) => (
            <span key={p.name} className="la-story">
              <span className={`la-ring ${i === 0 ? 'seen' : i === 3 ? 'close' : ''}`}>
                <Face initial={p.initial} tone={p.tone} size={62} />
              </span>
              <small>{p.name === 'You' ? 'Your story' : p.name}</small>
            </span>
          ))}
        </div>
        <article className="la-desk-post">
          <div className="la-post-head">
            <Face initial="M" tone="b" size={34} />
            <span>
              <b>maya</b> <small className="inline">· 2h</small>
              <small>Lisbon</small>
            </span>
            <MoreHorizontal size={20} className="end" />
          </div>
          <div className="la-photo sunset">
            <span className="la-count">1/3</span>
          </div>
          <div className="la-actions">
            <Heart size={25} className="liked" fill="currentColor" />
            <MessageCircle size={25} />
            <Send size={25} />
            <Bookmark size={25} className="end" />
          </div>
          <p className="la-caption">
            <b>8 likes</b>
          </p>
        </article>
      </div>
      <aside className="la-desk-right">
        <div className="la-me">
          <Face initial="Y" tone="a" size={46} />
          <span>
            <b>you</b>
            <small>Your profile</small>
          </span>
        </div>
        <b className="la-right-title">Messages</b>
        {DESK_CHATS.map((c) => (
          <div key={c.name} className={`la-chat-row ${c.unread ? 'unread' : ''}`}>
            <Face initial={c.initial} tone={c.tone} size={46} />
            <span>
              <b>{c.name}</b>
              <small>
                {c.text} · {c.time}
              </small>
            </span>
            {c.unread && <i className="la-dot" />}
          </div>
        ))}
        <div className="la-e2ee">
          <LockKeyhole size={18} />
          <span>
            <b>End-to-end encrypted</b>
            <small>Only the people you share with can see your posts, stories and messages.</small>
          </span>
        </div>
      </aside>
    </div>
  );
}

/*
 * One of your hands, holding the screen by its lower corner: the palm and fingers behind it, the thumb in front.
 * Drawn for the left; the right one is the same, mirrored. The screen's corner sits at (110, 200), and `corner` is
 * the screen's corner radius in these units, so the palm tucks behind it exactly.
 */
const PALM =
  'M 36 396 C 46 350, 38 280, 40 220 C 42 160, 52 110, 78 74 C 90 58, 104 48, 120 42 L 240 50 L 240 186 L 207 186 C 206 226, 194 266, 184 290 C 174 316, 168 356, 170 396 Z';
const THUMB = 'M 118 248 C 142 208, 178 158, 204 117 C 210 107, 222 102, 232 106 C 243 111, 245 128, 240 139 C 216 178, 190 226, 172 272 C 162 296, 114 290, 118 248 Z';

export function Hand({ corner }: { corner: number }) {
  const id = useId().replace(/[^a-zA-Z0-9]/g, '');
  const screen = `M 110 -400 H 900 V 200 H ${110 + corner} A ${corner} ${corner} 0 0 1 110 ${200 - corner} Z`;
  return (
    <svg viewBox="0 0 260 700" aria-hidden>
      <defs>
        <clipPath id={`${id}off`}>
          <path clipRule="evenodd" d={`M -400 -400 H 900 V 900 H -400 Z ${screen}`} />
        </clipPath>
        <clipPath id={`${id}on`}>
          <path d={screen} />
        </clipPath>
        <clipPath id={`${id}palm`}>
          <path d={PALM} />
        </clipPath>
      </defs>
      <path d="M -50 700 L 0 390 C 50 372, 140 370, 196 390 L 232 700 Z" fill="#2b2440" />
      <path d="M 0 390 C 50 372, 140 370, 196 390 L 199 410 C 140 392, 50 394, -3 412 Z" fill="#3b3356" />
      <g clipPath={`url(#${id}off)`}>
        <path d={PALM} fill="#e9d9cf" />
        <g clipPath={`url(#${id}palm)`}>
          <path d="M 40 220 C 42 160, 52 110, 78 74 C 66 100, 62 140, 62 190 C 62 240, 68 300, 64 340 C 62 364, 56 384, 50 396 L 36 396 C 46 350, 38 280, 40 220 Z" fill="#d7c2b5" />
          <path d={screen} transform="translate(-5 15)" fill="#c9ae9e" />
        </g>
      </g>
      <g clipPath={`url(#${id}on)`}>
        <path d={THUMB} transform="translate(-9 11)" fill="rgba(0,0,0,0.45)" />
      </g>
      <path d={THUMB} fill="#e9d9cf" />
      <path d="M 240 139 C 228 160, 216 180, 206 200 C 202 208, 198 216, 194 226 C 200 206, 214 180, 230 150 C 236 139, 241 127, 242 118 C 244 125, 243 133, 240 139 Z" fill="#d6bdaf" />
      <ellipse cx="224" cy="124" rx="12" ry="15" transform="rotate(31.5 224 124)" fill="#f6ede8" />
      <path d="M 173 190 C 179 193, 184 196, 189 200" stroke="#d2b7a8" strokeWidth="2.6" fill="none" strokeLinecap="round" />
      <path d="M 104 306 C 116 322, 134 330, 152 326" stroke="#dcc6b9" strokeWidth="2.6" fill="none" strokeLinecap="round" />
    </svg>
  );
}

/** Posts, reels and stories: a profile grid. */
export function SharePanel() {
  const tiles = ['sunset', 'dusk', 'bloom', 'tide', 'ember', 'haze', 'grape', 'peach', 'night'];
  return (
    <div className="la-panel la-share" aria-hidden>
      <div className="la-share-head">
        <Face initial="M" tone="b" size={58} />
        <div>
          <b>maya</b>
          <span className="la-counts">
            <span>
              <b>42</b> posts
            </span>
            <span>
              <b>9</b> followers
            </span>
          </span>
        </div>
      </div>
      <div className="la-grid">
        {tiles.map((t, i) => (
          <span key={t} className={`la-photo ${t}`}>
            {i === 1 && <Play size={14} className="la-tile-icon" fill="currentColor" />}
          </span>
        ))}
      </div>
    </div>
  );
}

/** Direct messages: reactions, a voice note, Seen. */
export function TalkPanel() {
  return (
    <div className="la-panel la-talk" aria-hidden>
      <div className="la-chat-head">
        <Face initial="L" tone="c" size={34} />
        <span>
          <b>leo</b>
          <small>Active now</small>
        </span>
      </div>
      <div className="la-bubbles">
        <span className="la-time">Today 19:02</span>
        <span className="la-bubble them">dinner saturday? 🍝</span>
        <span className="la-bubble me">yes!! I’ll bring dessert</span>
        <span className="la-bubble me reacted">
          and the good playlist
          <i className="la-reaction">❤️</i>
        </span>
        <span className="la-bubble them voice">
          <Play size={14} fill="currentColor" />
          <span className="la-wave">
            {[6, 12, 18, 10, 16, 22, 14, 8, 16, 20, 12, 7, 14, 18, 9].map((h, i) => (
              <i key={i} style={{ height: h }} />
            ))}
          </span>
          <small>0:07</small>
        </span>
        <span className="la-seen">Seen</span>
      </div>
      <div className="la-composer">
        <span>Message…</span>
        <Mic size={18} />
      </div>
    </div>
  );
}

/** A group video call. */
export function CallPanel() {
  return (
    <div className="la-panel la-call" aria-hidden>
      <div className="la-tiles">
        {PEOPLE.slice(1).map((p, i) => (
          <span key={p.name} className={`la-tile tone-${p.tone} ${i === 0 ? 'speaking' : ''}`}>
            <Face initial={p.initial} tone={p.tone} size={54} />
            <small>{p.name}</small>
          </span>
        ))}
      </div>
      <div className="la-call-bar">
        <span>
          <MicOff size={18} />
        </span>
        <span>
          <Video size={18} />
        </span>
        <span className="end">
          <PhoneOff size={18} />
        </span>
      </div>
    </div>
  );
}
