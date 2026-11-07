import {
  Bookmark,
  Clapperboard,
  Compass,
  Heart,
  Home,
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
  Smile,
  Video,
} from 'lucide-react';
import { useId, useMemo } from 'react';
import { Wordmark } from '../../ui/Brand';
import { Photo } from './DemoPhotos';
import ava from './demo/ava.svg';
import jules from './demo/jules.svg';
import kai from './demo/kai.svg';
import leo from './demo/leo.svg';
import maya from './demo/maya.svg';
import noor from './demo/noor.svg';
import sam from './demo/sam.svg';
import theo from './demo/theo.svg';

/*
 * The landing page's illustrations, drawn with HTML, CSS and SVG rather than screenshots: they stay sharp at any
 * size and cost little to load. The people in them are made up; their portraits are DiceBear's "Notionists" by
 * Zoish (CC0), and the photos are drawings (DemoPhotos.tsx).
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

/** The made-up circle in the demo screens. */
const P = {
  jules: { user: 'jules.moreau', name: 'Jules Moreau', src: jules },
  maya: { user: 'maya.lin', name: 'Maya Lin', src: maya },
  leo: { user: 'leo.martins', name: 'Leo Martins', src: leo },
  noor: { user: 'noor.haddad', name: 'Noor Haddad', src: noor },
  sam: { user: 'sam.okafor', name: 'Sam Okafor', src: sam },
  ava: { user: 'ava.rossi', name: 'Ava Rossi', src: ava },
  kai: { user: 'kai.tanaka', name: 'Kai Tanaka', src: kai },
  theo: { user: 'theo.grant', name: 'Theo Grant', src: theo },
};
type Person = (typeof P)[keyof typeof P];

const STORIES: { who: Person; ring: '' | 'is-seen' | 'is-close' }[] = [
  { who: P.maya, ring: '' },
  { who: P.leo, ring: 'is-close' },
  { who: P.noor, ring: '' },
  { who: P.sam, ring: '' },
  { who: P.ava, ring: 'is-seen' },
  { who: P.kai, ring: 'is-seen' },
  { who: P.theo, ring: 'is-seen' },
];

function Face({ who, size = 32 }: { who: Person; size?: number }) {
  return <img className="la-face" src={who.src} alt="" width={size} height={size} draggable={false} />;
}

/** "Liked by leo.martins and 23 others", with the first few likers' faces. */
function LikedBy({ size = 18 }: { size?: number }) {
  return (
    <p className="la-liked">
      <span className="la-stack">
        {[P.leo, P.noor, P.sam].map((who) => (
          <Face key={who.user} who={who} size={size} />
        ))}
      </span>
      <span>
        Liked by <b>{P.leo.user}</b> and <b>23 others</b>
      </span>
    </p>
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
        <span className="la-story">
          <span className="la-ring is-seen">
            <Face who={P.jules} size={50} />
          </span>
          <small>Your story</small>
        </span>
        {STORIES.slice(0, 4).map(({ who, ring }) => (
          <span key={who.user} className="la-story">
            <span className={`la-ring ${ring}`}>
              <Face who={who} size={50} />
            </span>
            <small>{who.user}</small>
          </span>
        ))}
      </div>
      <div className="la-post">
        <div className="la-post-head">
          <Face who={P.maya} size={28} />
          <span>
            <b>{P.maya.user}</b>
            <small>Lisbon, Portugal</small>
          </span>
          <MoreHorizontal size={16} className="end" />
        </div>
        <Photo scene="lisbon">
          <span className="la-count">1/4</span>
        </Photo>
        <div className="la-actions">
          <Heart size={21} className="liked" fill="currentColor" />
          <MessageCircle size={21} />
          <Send size={21} />
          <span className="la-dots">
            <i className="on" />
            <i />
            <i />
            <i />
          </span>
          <Bookmark size={21} className="end" />
        </div>
        <LikedBy size={16} />
        <p className="la-caption">
          <b>{P.maya.user}</b> golden hour from the miradouro, with the best people 🧡
        </p>
        <p className="la-more">View all 6 comments</p>
        <p className="la-when">2 hours ago</p>
      </div>
      <div className="la-tabbar">
        <Home size={22} strokeWidth={2.4} />
        <Search size={22} />
        <PlusSquare size={22} />
        <Clapperboard size={22} />
        <Face who={P.jules} size={23} />
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

const SUGGESTED = [
  { who: P.kai, why: `Followed by ${P.maya.user} + 2 more` },
  { who: P.theo, why: `Followed by ${P.leo.user}` },
  { who: P.ava, why: `Followed by ${P.noor.user} + 1 more` },
  { who: P.sam, why: 'Follows you' },
];

/** Hush on a big screen: sidebar, feed and suggestions, the way the web app lays them out. Drawn at 1200px wide. */
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
          <Face who={P.jules} size={26} />
          Profile
        </span>
        <span className="la-side-item end">
          <Menu size={24} strokeWidth={1.8} />
          More
        </span>
      </aside>
      <div className="la-desk-feed">
        <div className="la-desk-stories">
          {STORIES.map(({ who, ring }) => (
            <span key={who.user} className="la-story">
              <span className={`la-ring ${ring}`}>
                <Face who={who} size={62} />
              </span>
              <small>{who.user}</small>
            </span>
          ))}
        </div>
        <article className="la-desk-post">
          <div className="la-post-head">
            <Face who={P.maya} size={34} />
            <span>
              <b>{P.maya.user}</b> <small className="inline">· 2h</small>
              <small>Lisbon, Portugal</small>
            </span>
            <MoreHorizontal size={20} className="end" />
          </div>
          <Photo scene="lisbon">
            <span className="la-count">1/4</span>
          </Photo>
          <div className="la-actions">
            <Heart size={25} className="liked" fill="currentColor" />
            <MessageCircle size={25} />
            <Send size={25} />
            <span className="la-dots">
              <i className="on" />
              <i />
              <i />
              <i />
            </span>
            <Bookmark size={25} className="end" />
          </div>
          <LikedBy size={20} />
          <p className="la-caption">
            <b>{P.maya.user}</b> golden hour from the miradouro, with the best people 🧡
          </p>
          <p className="la-more">View all 6 comments</p>
        </article>
      </div>
      <aside className="la-desk-right">
        <div className="la-me">
          <Face who={P.jules} size={48} />
          <span>
            <b>{P.jules.user}</b>
            <small>{P.jules.name}</small>
          </span>
          <em>Switch</em>
        </div>
        <div className="la-right-title">
          <b>Suggested for you</b>
          <em>See all</em>
        </div>
        {SUGGESTED.map(({ who, why }) => (
          <div key={who.user} className="la-suggest">
            <Face who={who} size={46} />
            <span>
              <b>{who.user}</b>
              <small>{why}</small>
            </span>
            <em>Follow</em>
          </div>
        ))}
        <div className="la-notes">
          <b>Notes</b>
          <div className="la-note-row">
            {[
              { who: P.noor, text: 'bake sale sunday 🍪' },
              { who: P.leo, text: 'new album on repeat' },
              { who: P.ava, text: 'back in town!' },
            ].map(({ who, text }) => (
              <span key={who.user} className="la-note">
                <i>{text}</i>
                <Face who={who} size={52} />
                <small>{who.user.split('.')[0]}</small>
              </span>
            ))}
          </div>
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

/** Posts, reels and stories: a profile. */
export function SharePanel() {
  const tiles = ['lisbon', 'lake', 'dinner', 'tulips', 'city', 'coffee'] as const;
  return (
    <div className="la-panel la-share" aria-hidden>
      <div className="la-share-head">
        <span className="la-ring">
          <Face who={P.maya} size={54} />
        </span>
        <div>
          <b>{P.maya.user}</b>
          <span className="la-counts">
            <span>
              <b>48</b> posts
            </span>
            <span>
              <b>9</b> followers
            </span>
            <span>
              <b>9</b> following
            </span>
          </span>
        </div>
      </div>
      <p className="la-bio">
        <b>{P.maya.name}</b>
        <br />
        film photos, long walks, too much coffee
      </p>
      <p className="la-mutual">
        <span className="la-stack">
          <Face who={P.leo} size={16} />
          <Face who={P.noor} size={16} />
        </span>
        <span>
          Followed by <b>{P.leo.user}</b>, <b>{P.noor.user}</b> and 5 others
        </span>
      </p>
      <div className="la-grid">
        {tiles.map((t, i) => (
          <Photo key={t} scene={t}>
            {i === 1 && <Clapperboard size={13} className="la-tile-icon" />}
          </Photo>
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
        <span className="la-online">
          <Face who={P.leo} size={36} />
        </span>
        <span>
          <b>{P.leo.name}</b>
          <small>Active now</small>
        </span>
        <span className="la-chat-icons">
          <Video size={19} />
        </span>
      </div>
      <div className="la-bubbles">
        <span className="la-time">Today 19:02</span>
        <span className="la-row them">
          <Face who={P.leo} size={22} />
          <span className="la-bubble them">dinner saturday? 🍝</span>
        </span>
        <span className="la-bubble me">yes!! I’ll bring dessert</span>
        <span className="la-bubble me reacted">
          and the good playlist 🎶
          <i className="la-reaction">❤️</i>
        </span>
        <span className="la-row them">
          <Face who={P.leo} size={22} />
          <span className="la-bubble them voice">
            <Play size={14} fill="currentColor" />
            <span className="la-wave">
              {[6, 12, 18, 10, 16, 22, 14, 8, 16, 20, 12, 7, 14, 18, 9].map((h, i) => (
                <i key={i} style={{ height: h }} />
              ))}
            </span>
            <small>0:07</small>
          </span>
        </span>
        <span className="la-seen">Seen</span>
      </div>
      <div className="la-composer">
        <Smile size={18} />
        <span>Message…</span>
        <Mic size={18} />
      </div>
    </div>
  );
}

/** A group video call. */
export function CallPanel() {
  const tiles = [P.maya, P.leo, P.noor, P.sam];
  return (
    <div className="la-panel la-call" aria-hidden>
      <div className="la-tiles">
        {tiles.map((who, i) => (
          <span key={who.user} className={`la-tile ${i === 0 ? 'speaking' : ''}`}>
            <img src={who.src} alt="" draggable={false} />
            <small>{who.name.split(' ')[0]}</small>
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
