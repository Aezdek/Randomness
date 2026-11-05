import { Bookmark, Heart, Home, MessageCircle, Mic, MicOff, PhoneOff, Play, PlusSquare, Search, Send, Video } from 'lucide-react';
import { useMemo } from 'react';
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

/** A phone showing the Hush home feed. */
export function PhoneMock() {
  return (
    <div className="la-phone" aria-hidden>
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
    </div>
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
