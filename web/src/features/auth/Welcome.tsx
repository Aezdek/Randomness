import { ArrowRight, ChevronDown, Download, KeyRound, LockKeyhole, MessageCircle, Server, Share2, Users, Video, X } from 'lucide-react';
import {
  AnimatePresence,
  MotionConfig,
  motion,
  useMotionValue,
  useMotionValueEvent,
  useReducedMotion,
  useScroll,
  useSpring,
  useTransform,
  type MotionValue,
} from 'motion/react';
import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { BrandMark, Wordmark } from '../../ui/Brand';
import { AuthForm, type AuthMode } from './AuthForm';
import { Downloads, GetTheApp, detectPlatform } from './Downloads';
import { CountUp, Drift, EASE, Eyebrow, Reveal, RiseText, ScrollGrow, useFitScreen } from './LandingBits';
import { CallPanel, DesktopScreen, Hand, PhoneScreen, SharePanel, Starfield, TalkPanel } from './LandingArt';

/*
 * What someone sees at the server's address before signing in: what Hush is, why it's private, and the door in
 * (sign in, or join with the invite someone sent). An invite link opens the join form straight away.
 * The same frame carries the downloads page (/download), which signed-in people can open too.
 */

export type LandingPage = 'home' | 'download';

export function Welcome({ page = 'home', signedIn = false }: { page?: LandingPage; signedIn?: boolean }) {
  const invite = new URLSearchParams(location.search).get('invite') ?? '';
  const [mode, setMode] = useState<AuthMode>(invite ? 'join' : 'signin');
  const [authOpen, setAuthOpen] = useState(Boolean(invite));
  const navigate = useNavigate();
  const start = (m: AuthMode) => {
    if (signedIn) return navigate('/');
    setMode(m);
    setAuthOpen(true);
  };
  useScrollOnNavigate();

  return (
    <MotionConfig reducedMotion="user">
      <div className="landing">
        <div className="lp-content" inert={authOpen}>
          <Nav page={page} signedIn={signedIn} onSignIn={() => start('signin')} />
          <main>
            {page === 'download' ? (
              <Downloads />
            ) : (
              <>
                <Hero onStart={start} />
                <Features onStart={start} />
                <Statement />
                <Pillars />
                <Numbers onStart={start} />
                <Faq />
                <FinalCta onStart={start} />
              </>
            )}
          </main>
          <Footer page={page} onStart={start} />
        </div>
        <AuthDialog open={authOpen} onClose={() => setAuthOpen(false)} mode={mode} onMode={setMode} invite={invite} />
      </div>
    </MotionConfig>
  );
}

/** Moving between the landing page and the downloads page starts at the top, or at the section a link names. */
function useScrollOnNavigate() {
  const { pathname, hash } = useLocation();
  useEffect(() => {
    if (hash) document.getElementById(hash.slice(1))?.scrollIntoView();
    else window.scrollTo(0, 0);
  }, [pathname, hash]);
}

/** A link to a section of the landing page, from either page. */
function SectionLink({ page, id, children }: { page: LandingPage; id: string; children: string }) {
  return page === 'home' ? <a href={`#${id}`}>{children}</a> : <Link to={`/#${id}`}>{children}</Link>;
}

function Nav({ page, signedIn, onSignIn }: { page: LandingPage; signedIn: boolean; onSignIn: () => void }) {
  const { scrollY } = useScroll();
  const [scrolled, setScrolled] = useState(false);
  useMotionValueEvent(scrollY, 'change', (y) => setScrolled(y > 12));
  return (
    <header className={`lp-nav ${scrolled ? 'scrolled' : ''}`}>
      <div className="lp-nav-inner">
        <Link className="lp-logo" to="/" onClick={() => page === 'home' && window.scrollTo({ top: 0 })} aria-label="Hush">
          <BrandMark size={28} />
          <Wordmark />
        </Link>
        <nav className="lp-links" aria-label="Sections">
          <SectionLink page={page} id="features">
            Features
          </SectionLink>
          <SectionLink page={page} id="privacy">
            Privacy
          </SectionLink>
          <SectionLink page={page} id="faq">
            FAQ
          </SectionLink>
          <Link to="/download" className={page === 'download' ? 'on' : ''} aria-current={page === 'download' ? 'page' : undefined}>
            Download
          </Link>
        </nav>
        <button className="btn btn-bold" onClick={onSignIn}>
          {signedIn ? 'Open Hush' : 'Sign in'}
        </button>
      </div>
    </header>
  );
}

/** The screen drawings are laid out at these sizes, then zoomed to fit the device (so they stay sharp). */
const DESK = { w: 1200, corner: 32 };
const PHONE = { w: 280, corner: 70 };
/** Phones, and tablets held upright: the headline goes above the screen rather than over it. */
const PHONE_QUERY = '(max-width: 760px), (orientation: portrait) and (max-width: 1100px)';

function usePhoneLayout() {
  const [phone, setPhone] = useState(() => matchMedia(PHONE_QUERY).matches);
  useEffect(() => {
    const query = matchMedia(PHONE_QUERY);
    const onChange = () => setPhone(query.matches);
    query.addEventListener('change', onChange);
    return () => query.removeEventListener('change', onChange);
  }, []);
  return phone;
}

/** Eases in and out, so the screen starts and settles gently. */
const smooth = (t: number) => t * t * (3 - 2 * t);
const clamp01 = (t: number) => Math.min(1, Math.max(0, t));
const span = (p: number, from: number, to: number) => clamp01((p - from) / (to - from));

/*
 * The hero, after the template: your own two hands hold a screen with Hush on it, the headline over it. Scrolling
 * brings the screen closer until it fills the view; then the page carries on. The device is laid out at its final,
 * full size and shrunk at the start, so it ends pin-sharp.
 */
function Hero({ onStart }: { onStart: (m: AuthMode) => void }) {
  const section = useRef<HTMLElement>(null);
  const stage = useRef<HTMLDivElement>(null);
  const held = useRef<HTMLDivElement>(null);
  const screen = useRef<HTMLDivElement>(null);
  const copy = useRef<HTMLDivElement>(null);
  const phone = usePhoneLayout();
  const still = useReducedMotion() ?? false;
  const { scrollYProgress } = useScroll({ target: section, offset: ['start start', 'end end'] });
  // A touch of inertia, so the zoom glides rather than following every notch of the wheel.
  const glide = useSpring(scrollYProgress, { stiffness: 220, damping: 34, mass: 0.4, restDelta: 0.0005 });
  const none = useMotionValue(0);
  const p = still ? none : glide;

  // Where the screen starts: its scale and how far below the middle, measured from the layout.
  const startScale = useMotionValue(0.55);
  const startY = useMotionValue(0);
  const copyY0 = useMotionValue(0);
  useLayoutEffect(() => {
    const measure = () => {
      const st = stage.current;
      const h = held.current;
      const c = copy.current;
      if (!st || !h || !c) return;
      const vw = st.clientWidth;
      const vh = st.clientHeight;
      if (phone) {
        const top = c.offsetTop + c.offsetHeight + 22;
        const height = Math.max(vh - top - 14, h.offsetHeight * 0.3);
        startScale.set(Math.min(height / h.offsetHeight, 0.85));
        startY.set(top + height / 2 - vh / 2);
        copyY0.set(0);
      } else {
        const width = Math.min(vw * 0.56, 800, (vh - 170) * 1.28);
        startScale.set(Math.min(width / h.offsetWidth, 0.85));
        startY.set(vh * 0.05);
        copyY0.set(vh * 0.05);
      }
    };
    measure();
    const observer = new ResizeObserver(measure);
    if (stage.current) observer.observe(stage.current);
    if (copy.current) observer.observe(copy.current);
    return () => observer.disconnect();
  }, [phone, startScale, startY, copyY0]);
  useFitScreen(screen, phone ? PHONE.w : DESK.w);

  const zoom = useTransform(() => smooth(span(p.get(), 0, 0.8)));
  const scale = useTransform(() => startScale.get() + (1 - startScale.get()) * zoom.get());
  const y = useTransform(() => startY.get() * (1 - zoom.get()));
  // The screen starts frosted behind the headline and comes into focus as it nears; the headline stays sharp and
  // simply fades and lifts away first.
  const frost = useTransform(p, [0.1, 0.42], [1, 0]);
  const frostShown = useTransform(frost, (v) => (v > 0.001 ? 'visible' : 'hidden'));
  const screenBlur = useTransform(frost, (v) => (v > 0.001 ? `blur(${(24 * v).toFixed(2)}px)` : 'none'));
  const copyOpacity = useTransform(p, [0, 0.14], [1, 0]);
  const copyScale = useTransform(p, [0, 0.14], [1, 0.95]);
  const copyShift = useTransform(p, [0, 0.14], [0, -48]);
  const copyTop = useTransform(() => copyY0.get() + copyShift.get());
  const copyEvents = useTransform(p, (v) => (v > 0.1 ? 'none' : 'auto'));
  const handsOut = useTransform(() => smooth(span(p.get(), 0.32, 0.8)));
  const leftX = useTransform(handsOut, (t) => `${-14 * t}%`);
  const rightX = useTransform(handsOut, (t) => `${14 * t}%`);
  const handsY = useTransform(handsOut, (t) => `${26 * t}%`);
  const handsOpacity = useTransform(p, [0.55, 0.8], [1, 0]);
  const cue = useTransform(p, [0, 0.06], [1, 0]);
  const corner = phone ? PHONE.corner : DESK.corner;

  return (
    <section ref={section} className={`lp-zoom ${phone ? 'phone' : ''} ${still ? 'still' : ''}`} id="top">
      <div ref={stage} className="lp-zoom-stage">
        <div className="lp-hero-bg" aria-hidden>
          <Starfield />
          <span className="lp-hero-glow a" />
          <span className="lp-hero-glow b" />
        </div>
        <motion.div ref={held} className={`lp-held ${phone ? 'phone' : 'desk'}`} style={{ scale, y }} aria-hidden>
          <div className="la-device">
            <div ref={screen} className="la-device-screen">
              <motion.div className="la-screen-zoom" style={{ filter: screenBlur }}>
                {phone ? <PhoneScreen /> : <DesktopScreen />}
              </motion.div>
              <motion.span className="la-frost" style={{ opacity: frost, visibility: frostShown }} />
            </div>
          </div>
          <motion.div className="lp-hand l" style={{ x: leftX, y: handsY, opacity: handsOpacity }}>
            <Hand corner={corner} />
          </motion.div>
          <motion.div className="lp-hand r" style={{ x: rightX, y: handsY, opacity: handsOpacity }}>
            <Hand corner={corner} />
          </motion.div>
        </motion.div>
        <motion.div ref={copy} className="lp-hero-copy" style={{ y: copyTop, scale: copyScale, opacity: copyOpacity, pointerEvents: copyEvents }}>
          <motion.div className="lp-hero-copy-in" initial={{ opacity: 0, y: 18 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.9, ease: EASE }}>
            <span className="lp-chip">
              <LockKeyhole size={13} /> Private · end-to-end encrypted
            </span>
            <h1>
              Your people.
              <br />
              Nobody else.
            </h1>
            <p className="lp-lede">Photos, stories, messages and calls with the few people who matter, encrypted on your device before they go anywhere.</p>
            <div className="lp-ctas">
              <button className="btn btn-bold btn-lg" onClick={() => onStart('signin')}>
                Sign in <ArrowRight size={18} />
              </button>
              <button className="btn btn-glass btn-lg" onClick={() => onStart('join')}>
                Join with an invite
              </button>
              <GetTheApp hint={false} />
            </div>
          </motion.div>
        </motion.div>
        {!still && (
          <motion.span className="lp-scroll-cue" style={{ opacity: cue }} aria-hidden>
            <span />
            Scroll
          </motion.span>
        )}
      </div>
    </section>
  );
}

const FEATURES = [
  {
    id: 'share',
    label: 'Share',
    icon: Share2,
    caption: 'Posts, carousels, reels and 24-hour stories with polls, questions and Close Friends.',
    Panel: SharePanel,
  },
  {
    id: 'talk',
    label: 'Talk',
    icon: MessageCircle,
    caption: 'Messages with reactions, replies, voice notes, polls, view-once photos and vanish mode.',
    Panel: TalkPanel,
  },
  {
    id: 'call',
    label: 'Call',
    icon: Video,
    caption: 'Voice and video calls for up to ten, and Live for your followers.',
    Panel: CallPanel,
  },
] as const;

function Features({ onStart }: { onStart: (m: AuthMode) => void }) {
  const [active, setActive] = useState(0);
  const [touched, setTouched] = useState(false);
  useEffect(() => {
    if (touched) return;
    const timer = setInterval(() => setActive((a) => (a + 1) % FEATURES.length), 5200);
    return () => clearInterval(timer);
  }, [touched]);
  const feature = FEATURES[active];
  return (
    <section className="lp-section" id="features">
      <div className="lp-head center">
        <Reveal>
          <Eyebrow>Features</Eyebrow>
        </Reveal>
        <RiseText text="Everything you’d expect. Nothing you wouldn’t." />
        <Reveal delay={0.2}>
          <p className="lp-sub">Photos, stories, messages and calls for your own circle. No ads, no strangers, and no feed deciding what you see.</p>
        </Reveal>
      </div>
      <ScrollGrow className="lp-feature-card">
        <div className="lp-feature-left">
          <div className="lp-tabs" role="tablist" aria-label="Features">
            {FEATURES.map((f, i) => (
              <button
                key={f.id}
                role="tab"
                aria-selected={i === active}
                className={i === active ? 'on' : ''}
                onClick={() => {
                  setTouched(true);
                  setActive(i);
                }}
              >
                {f.label}
                <sup>0{i + 1}</sup>
                {i === active && !touched && <motion.span key={active} className="lp-tab-progress" initial={{ scaleX: 0 }} animate={{ scaleX: 1 }} transition={{ duration: 5.2, ease: 'linear' }} />}
              </button>
            ))}
          </div>
          <div className="lp-feature-foot">
            <p>Built for a handful of people who actually know each other: up to ten, each invited by someone already inside.</p>
            <button className="btn btn-bold" onClick={() => onStart('join')}>
              Join with an invite
            </button>
          </div>
        </div>
        <div className="lp-feature-stage">
          <AnimatePresence mode="wait">
            <motion.div
              key={feature.id}
              className="lp-stage-inner"
              initial={{ opacity: 0, y: 14, scale: 0.98 }}
              animate={{ opacity: 1, y: 0, scale: 1 }}
              exit={{ opacity: 0, y: -10, scale: 0.98 }}
              transition={{ duration: 0.45, ease: EASE }}
            >
              <feature.Panel />
              <div className="lp-stage-caption">
                <span className="lp-icon">
                  <feature.icon size={17} />
                </span>
                <p>{feature.caption}</p>
              </div>
            </motion.div>
          </AnimatePresence>
        </div>
      </ScrollGrow>
    </section>
  );
}

const STATEMENT: { text: string; accent?: boolean }[] = [
  { text: 'Most apps read everything you share, because that’s the business.' },
  { text: 'Hush can’t.', accent: true },
  { text: 'Every photo, story, message and call is locked on your device, with keys only you and the people you share with hold.' },
];

function Word({ word, progress, range, accent }: { word: string; progress: MotionValue<number>; range: [number, number]; accent?: boolean }) {
  const opacity = useTransform(progress, range, [0.16, 1]);
  return (
    <motion.span className={accent ? 'lp-accent' : undefined} style={{ opacity }}>
      {word}{' '}
    </motion.span>
  );
}

/** The big statement that lights up word by word as it scrolls past. */
function Statement() {
  const ref = useRef<HTMLParagraphElement>(null);
  const { scrollYProgress } = useScroll({ target: ref, offset: ['start 0.85', 'end 0.5'] });
  const words = STATEMENT.flatMap((part) => part.text.split(' ').map((word) => ({ word, accent: part.accent })));
  return (
    <section className="lp-section lp-statement" id="privacy">
      <Eyebrow>Privacy</Eyebrow>
      <p ref={ref} className="lp-statement-text">
        {words.map((w, i) => (
          <Word key={i} word={w.word} accent={w.accent} progress={scrollYProgress} range={[i / words.length, (i + 1) / words.length]} />
        ))}
      </p>
    </section>
  );
}

const PILLARS = [
  { icon: KeyRound, title: 'Your keys stay with you', text: 'Your password unlocks your keys on your device. Neither ever reaches the server.' },
  { icon: Server, title: 'A server that knows nothing', text: 'It holds only sealed data, and even its own database is encrypted at rest.' },
  { icon: Users, title: 'Invite only', text: 'Up to ten people, each invited by someone already inside. No ads, no strangers, no algorithm.' },
];

function Pillars() {
  return (
    <section className="lp-section lp-pillars">
      <span className="lp-glow center" aria-hidden />
      <div className="lp-split-head">
        <div>
          <Reveal>
            <Eyebrow>How it stays private</Eyebrow>
          </Reveal>
          <RiseText text="Private by design, not by promise." />
        </div>
        <Reveal delay={0.15}>
          <p className="lp-sub">Privacy that doesn’t depend on anyone behaving well, the server included. The maths keeps it private, whoever runs it.</p>
        </Reveal>
      </div>
      <div className="lp-pillar-grid">
        {PILLARS.map(({ icon: Icon, title, text }, i) => (
          <Drift key={title} className="lp-pillar-cell" speed={[18, 42, 66][i]}>
            <Reveal className="lp-pillar" delay={i * 0.1}>
              <span className="lp-icon lg">
                <Icon size={22} />
              </span>
              <div>
                <h3>{title}</h3>
                <p>{text}</p>
              </div>
            </Reveal>
          </Drift>
        ))}
      </div>
    </section>
  );
}

const NUMBERS = [
  { value: 0, from: 4096, title: 'Readable bytes on its disk', text: 'Your posts and messages are encrypted end to end, and everything else under the server password.' },
  { value: 10, from: 0, title: 'People, at most', text: 'Hush is for your circle, not the whole internet. Small on purpose.' },
  { value: 1, from: 0, title: 'Server, run by your circle', text: 'No company in the middle. Hush runs on a home server one of you looks after.' },
];

function Numbers({ onStart }: { onStart: (m: AuthMode) => void }) {
  return (
    <section className="lp-section">
      <span className="lp-glow center" aria-hidden />
      <div className="lp-numbers">
        <div className="lp-numbers-left">
          <Reveal>
            <Eyebrow>By the numbers</Eyebrow>
          </Reveal>
          <RiseText text="Small on purpose." />
          <RiseText text="Sealed by default." className="dim" />
          <button className="btn btn-bold btn-lg" onClick={() => onStart('signin')}>
            Sign in <ArrowRight size={18} />
          </button>
        </div>
        <div className="lp-numbers-right">
          {NUMBERS.map((n, i) => (
            <Reveal key={n.title} className="lp-number" delay={i * 0.06}>
              <CountUp className="lp-big" from={n.from} to={n.value} />
              <b>{n.title}</b>
              <p>{n.text}</p>
            </Reveal>
          ))}
        </div>
      </div>
    </section>
  );
}

const QUESTIONS = [
  {
    q: 'How do I join?',
    a: 'With an invite from someone who’s already on Hush. Open their invite link, pick a username and a password, and you’re in.',
  },
  {
    q: 'Can the server read my messages?',
    a: 'No. Posts, stories, messages and calls are encrypted on your device before they’re sent, and only the people you share with hold the keys. The server stores sealed data it can’t open.',
  },
  {
    q: 'What if I forget my password?',
    a: 'Your password protects your encryption keys, so nobody can recover your messages without it, not even the admin. Keep it in a password manager.',
  },
  {
    q: 'Is there a phone app?',
    a: 'Yes: the Android app downloads straight from this server and keeps itself up to date. On iPhone and iPad, add Hush to your Home Screen from Safari.',
    link: { to: '/download', label: 'Get Hush for your devices' },
  },
];

function Faq() {
  const [open, setOpen] = useState<number | null>(0);
  return (
    <section className="lp-section lp-faq" id="faq">
      <Reveal className="lp-faq-head">
        <Eyebrow>FAQ</Eyebrow>
        <RiseText text="Questions, answered." />
        <p className="lp-sub">Anything else? Ask whoever invited you.</p>
      </Reveal>
      <div className="lp-faq-list">
        {QUESTIONS.map((item, i) => {
          const isOpen = open === i;
          return (
            <Reveal key={item.q} delay={i * 0.04}>
              <div className={`lp-q ${isOpen ? 'open' : ''}`}>
                <button aria-expanded={isOpen} onClick={() => setOpen(isOpen ? null : i)}>
                  <h3>{item.q}</h3>
                  <ChevronDown size={22} />
                </button>
                <AnimatePresence initial={false}>
                  {isOpen && (
                    <motion.div initial={{ height: 0, opacity: 0 }} animate={{ height: 'auto', opacity: 1 }} exit={{ height: 0, opacity: 0 }} transition={{ duration: 0.32, ease: EASE }}>
                      <p>
                        {item.a}
                        {item.link && (
                          <>
                            {' '}
                            <Link className="lp-inline-link" to={item.link.to}>
                              {item.link.label} →
                            </Link>
                          </>
                        )}
                      </p>
                    </motion.div>
                  )}
                </AnimatePresence>
              </div>
            </Reveal>
          );
        })}
      </div>
    </section>
  );
}

function FinalCta({ onStart }: { onStart: (m: AuthMode) => void }) {
  return (
    <section className="lp-section">
      <ScrollGrow className="lp-cta" from={0.84}>
        <span className="lp-rays" aria-hidden />
        <BrandMark size={52} />
        <RiseText text="Your people are waiting." />
        <p className="lp-sub">Sign in, or open the invite someone sent you.</p>
        <div className="lp-ctas">
          <button className="btn btn-bold btn-lg" onClick={() => onStart('signin')}>
            Sign in <ArrowRight size={18} />
          </button>
          {detectPlatform() === 'desktop' ? (
            <Link className="btn btn-glass btn-lg" to="/download">
              <Download size={18} /> Download Hush
            </Link>
          ) : (
            <GetTheApp />
          )}
        </div>
      </ScrollGrow>
    </section>
  );
}

function Footer({ page, onStart }: { page: LandingPage; onStart: (m: AuthMode) => void }) {
  return (
    <footer className="lp-footer">
      <div className="lp-footer-top">
        <div className="lp-footer-brand">
          <Link className="lp-logo" to="/" onClick={() => page === 'home' && window.scrollTo({ top: 0 })} aria-label="Hush">
            <BrandMark size={26} />
            <Wordmark />
          </Link>
          <p>A private place for your people.</p>
        </div>
        <div className="lp-footer-cols">
          <div>
            <b>Hush</b>
            <button onClick={() => onStart('signin')}>Sign in</button>
            <button onClick={() => onStart('join')}>Join with an invite</button>
            <Link to="/download">Download</Link>
          </div>
          <div>
            <b>Learn</b>
            <SectionLink page={page} id="features">
              Features
            </SectionLink>
            <SectionLink page={page} id="privacy">
              Privacy
            </SectionLink>
            <SectionLink page={page} id="faq">
              FAQ
            </SectionLink>
          </div>
        </div>
      </div>
      <div className="lp-footer-bottom">
        <span>Hush · end-to-end encrypted</span>
        <span>Made for a few.</span>
      </div>
    </footer>
  );
}

function AuthDialog({ open, onClose, mode, onMode, invite }: { open: boolean; onClose: () => void; mode: AuthMode; onMode: (m: AuthMode) => void; invite: string }) {
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose();
    window.addEventListener('keydown', onKey);
    const overflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      window.removeEventListener('keydown', onKey);
      document.body.style.overflow = overflow;
    };
  }, [open, onClose]);

  return (
    <AnimatePresence>
      {open && (
        <motion.div
          className="lp-scrim"
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          exit={{ opacity: 0 }}
          transition={{ duration: 0.2 }}
          onMouseDown={(e) => e.target === e.currentTarget && onClose()}
        >
          <motion.div
            role="dialog"
            aria-modal="true"
            aria-label={mode === 'join' ? 'Join Hush' : 'Sign in to Hush'}
            className="lp-dialog"
            initial={{ opacity: 0, y: 28, scale: 0.97 }}
            animate={{ opacity: 1, y: 0, scale: 1 }}
            exit={{ opacity: 0, y: 20, scale: 0.98 }}
            transition={{ type: 'spring', stiffness: 380, damping: 34, mass: 0.9 }}
          >
            <div className="lp-dialog-head">
              <BrandMark size={34} />
              <div>
                <h2>{mode === 'join' ? 'Join Hush' : 'Welcome back'}</h2>
                <p>{mode === 'join' ? 'Your keys are made right here, on this device.' : 'Your keys unlock right here, on this device.'}</p>
              </div>
              <button className="icon-btn sm" onClick={onClose} aria-label="Close">
                <X size={20} />
              </button>
            </div>
            <AuthForm mode={mode} onMode={onMode} initialInvite={invite} />
          </motion.div>
        </motion.div>
      )}
    </AnimatePresence>
  );
}
