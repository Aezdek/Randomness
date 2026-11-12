import { Check, Copy, Download, KeyRound, Laptop, MonitorDown, RefreshCw, Share, ShieldCheck, Smartphone, TabletSmartphone } from 'lucide-react';
import { motion } from 'motion/react';
import { useEffect, useRef, useState, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { DesktopScreen, PhoneScreen } from './LandingArt';
import { EASE, Eyebrow, Reveal, useFitScreen } from './LandingBits';

/*
 * /download: Hush for each kind of device. The Android app comes straight from this server (its version, size and
 * checksum from the public /version the app itself updates from); iPhone, iPad and computers use the web app,
 * added to the Home Screen or installed from the browser.
 */

type Platform = 'android' | 'ios' | 'desktop';

export function detectPlatform(): Platform {
  const ua = navigator.userAgent;
  if (/Android/i.test(ua)) return 'android';
  if (/iPhone|iPad|iPod/i.test(ua) || (/Macintosh/.test(ua) && navigator.maxTouchPoints > 1)) return 'ios';
  return 'desktop';
}

/** Chrome and Edge offer to install the web app with this event; it can come before this page is ever shown. */
interface InstallPrompt extends Event {
  prompt: () => Promise<void>;
  userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }>;
}
let deferredInstall: InstallPrompt | null = null;
const installListeners = new Set<() => void>();
window.addEventListener('beforeinstallprompt', (event) => {
  event.preventDefault();
  deferredInstall = event as InstallPrompt;
  installListeners.forEach((l) => l());
});
window.addEventListener('appinstalled', () => {
  deferredInstall = null;
  installListeners.forEach((l) => l());
});

function useInstallPrompt() {
  const [prompt, setPrompt] = useState(deferredInstall);
  useEffect(() => {
    const update = () => setPrompt(deferredInstall);
    installListeners.add(update);
    return () => void installListeners.delete(update);
  }, []);
  return prompt;
}

interface AndroidBuild {
  versionName: string;
  size: number;
  sha256: string;
}

function useAndroidBuild() {
  const [build, setBuild] = useState<AndroidBuild | null>(null);
  useEffect(() => {
    fetch('/version', { cache: 'no-store' })
      .then((r) => (r.ok ? r.json() : null))
      .then((b: AndroidBuild | null) => b?.versionName && setBuild(b))
      .catch(() => undefined);
  }, []);
  return build;
}

const megabytes = (bytes: number) => `${(bytes / 1_000_000).toFixed(1)} MB`;

function CopyButton({ text, label, children }: { text: string; label: string; children?: ReactNode }) {
  const [copied, setCopied] = useState(false);
  return (
    <button
      className="dl-copy"
      aria-label={label}
      onClick={() =>
        void navigator.clipboard?.writeText(text).then(() => {
          setCopied(true);
          setTimeout(() => setCopied(false), 1600);
        })
      }
    >
      {copied ? <Check size={15} /> : <Copy size={15} />}
      {children}
    </button>
  );
}

function DeviceFrame({ kind }: { kind: 'tablet' | 'phone' }) {
  const screen = useRef<HTMLDivElement>(null);
  useFitScreen(screen, kind === 'tablet' ? 1200 : 280);
  return (
    <div className={`dl-${kind}`}>
      <div className="la-device">
        <div ref={screen} className="la-device-screen">
          <div className="la-screen-zoom">{kind === 'tablet' ? <DesktopScreen /> : <PhoneScreen />}</div>
        </div>
      </div>
    </div>
  );
}

/**
 * On a phone, the landing page's way to the app: on Android the APK itself, straight from this server; on an
 * iPhone or iPad, the steps for adding Hush to the Home Screen. Nothing on a computer, which has the whole page.
 */
export function GetTheApp({ hint = true }: { hint?: boolean }) {
  const [platform] = useState(detectPlatform);
  const build = useAndroidBuild();
  if (platform === 'android') {
    return (
      <div className="lp-get-app">
        {build ? (
          <a className="btn btn-glass btn-lg" href="/download/hush.apk" download>
            <Download size={18} /> Download for Android
          </a>
        ) : (
          <Link className="btn btn-glass btn-lg" to="/download">
            <Download size={18} /> Get the Android app
          </Link>
        )}
        {build && hint && (
          <small>
            {megabytes(build.size)} · <Link to="/download#dl-android">How to install</Link>
          </small>
        )}
      </div>
    );
  }
  if (platform === 'ios') {
    const tablet = /iPad/.test(navigator.userAgent) || /Macintosh/.test(navigator.userAgent);
    return (
      <div className="lp-get-app">
        <Link className="btn btn-glass btn-lg" to="/download#dl-ios">
          <Smartphone size={18} /> Get Hush on your {tablet ? 'iPad' : 'iPhone'}
        </Link>
        {hint && <small>Add it to your Home Screen and it opens like an app</small>}
      </div>
    );
  }
  return null;
}

function Card({ id, here, icon, title, children }: { id: Platform; here: boolean; icon: ReactNode; title: string; children: ReactNode }) {
  return (
    <div className={`dl-card ${here ? 'here' : ''}`} id={`dl-${id}`}>
      <div className="dl-card-head">
        <span className="lp-icon lg">{icon}</span>
        {here && <span className="dl-here">For this device</span>}
      </div>
      <h3>{title}</h3>
      {children}
    </div>
  );
}

export function Downloads() {
  const platform = detectPlatform();
  const build = useAndroidBuild();
  const install = useInstallPrompt();
  const standalone = matchMedia('(display-mode: standalone)').matches;
  const [installed, setInstalled] = useState(false);

  return (
    <>
      <section className="dl-hero">
        <span className="lp-glow center" aria-hidden />
        <motion.div className="dl-hero-copy" initial={{ opacity: 0, y: 24 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 1, ease: EASE }}>
          <Eyebrow>Download</Eyebrow>
          <h1>Hush, on every screen you own.</h1>
          <p className="lp-sub">One account everywhere. Sign in on any device and your posts, stories and messages are there.</p>
        </motion.div>
        <div className="dl-devices" aria-hidden>
          <motion.div initial={{ opacity: 0, y: 60 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 1.2, delay: 0.15, ease: EASE }}>
            <DeviceFrame kind="tablet" />
          </motion.div>
          <motion.div initial={{ opacity: 0, y: 90 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 1.2, delay: 0.35, ease: EASE }}>
            <DeviceFrame kind="phone" />
          </motion.div>
        </div>
      </section>

      <section className="lp-section dl-section">
        <div className="dl-grid">
          <Reveal className="dl-cell">
            <Card id="android" here={platform === 'android'} icon={<Smartphone size={22} />} title="Android">
              <p className="dl-lead">The full app, with notifications and calls that ring like a phone call. It keeps itself up to date.</p>
              <a className="btn btn-bold btn-lg btn-block" href="/download/hush.apk" download>
                <Download size={18} /> Download for Android
              </a>
              <p className="dl-meta">
                {build ? `Version ${build.versionName} · ${megabytes(build.size)} · ` : ''}Android 10 or later
              </p>
              <ol className="dl-steps">
                <li><span>Open Hush.apk once it has downloaded.</span></li>
                <li><span>If Android asks, let your browser install apps.</span></li>
                <li><span>Open Hush and sign in. If it asks for your server, it's {location.host}.</span></li>
              </ol>
              {build && (
                <div className="dl-hash">
                  <span>SHA-256</span>
                  <code title={build.sha256}>{build.sha256}</code>
                  <CopyButton text={build.sha256} label="Copy the SHA-256 checksum" />
                </div>
              )}
            </Card>
          </Reveal>

          <Reveal className="dl-cell" delay={0.08}>
            <Card id="ios" here={platform === 'ios'} icon={<TabletSmartphone size={22} />} title="iPhone and iPad">
              <p className="dl-lead">Add Hush to your Home Screen. It opens full screen like any other app, with notifications on iOS 16.4 or later.</p>
              <CopyButton text={location.origin} label="Copy this site's link">
                <span>Copy link for Safari</span>
              </CopyButton>
              <ol className="dl-steps">
                <li><span>Open {location.host} in Safari.</span></li>
                <li>
                  <span>
                    Tap Share <Share size={15} className="dl-inline-icon" aria-label="(the square with an arrow)" />.
                  </span>
                </li>
                <li><span>Choose Add to Home Screen, then Add.</span></li>
              </ol>
            </Card>
          </Reveal>

          <Reveal className="dl-cell" delay={0.16}>
            <Card id="desktop" here={platform === 'desktop'} icon={<Laptop size={22} />} title="Mac, Windows and Linux">
              <p className="dl-lead">Hush works in any up-to-date browser. Install it for its own window and a place in your Dock or taskbar.</p>
              {standalone || installed ? (
                <button className="btn btn-glass btn-lg btn-block" disabled>
                  <Check size={18} /> Installed
                </button>
              ) : install ? (
                <button
                  className="btn btn-bold btn-lg btn-block"
                  onClick={() =>
                    void install.prompt().then(() => install.userChoice.then((c) => c.outcome === 'accepted' && setInstalled(true)))
                  }
                >
                  <MonitorDown size={18} /> Install Hush
                </button>
              ) : (
                <Link className="btn btn-glass btn-lg btn-block" to="/">
                  Open Hush in your browser
                </Link>
              )}
              <ol className="dl-steps">
                <li><span>Chrome or Edge: the install button in the address bar.</span></li>
                <li><span>Safari on a Mac: File, then Add to Dock.</span></li>
                <li><span>Firefox: use it in a tab; everything works there too.</span></li>
              </ol>
            </Card>
          </Reveal>
        </div>
      </section>

      <section className="lp-section dl-why">
        <Reveal className="lp-head center">
          <Eyebrow>Why no app store</Eyebrow>
          <h2>Straight from your own server.</h2>
          <p className="lp-sub">Hush isn’t in any store. The app comes from the same server your circle runs, and nobody else is in between.</p>
        </Reveal>
        <div className="dl-points">
          {[
            { icon: ShieldCheck, title: 'Signed, every time', text: 'Each Android build carries the same signature, and Android refuses an update signed by anyone else.' },
            { icon: RefreshCw, title: 'Updates itself', text: 'When the server has a new version, the app fetches it and asks to install. Whoever runs the server decides when.' },
            { icon: KeyRound, title: 'Your keys go with you', text: 'Sign in on a new device and everything is unlocked right there, on that device.' },
          ].map(({ icon: Icon, title, text }, i) => (
            <Reveal key={title} className="dl-point" delay={i * 0.08}>
              <Icon size={22} />
              <b>{title}</b>
              <p>{text}</p>
            </Reveal>
          ))}
        </div>
      </section>
    </>
  );
}
