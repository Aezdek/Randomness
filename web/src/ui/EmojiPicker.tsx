import { Apple, Clock, Flag, Heart, Lightbulb, PawPrint, Plane, Search, Smile, Trophy, Users, X } from 'lucide-react';
import { useEffect, useMemo, useRef, useState, type ComponentType } from 'react';

/*
 * Every emoji, by category, with search, your recent ones and skin tones: the list comes from Emojibase (MIT),
 * trimmed to what the bundled emoji font draws (ui/emojiData.json), and loads the first time a picker opens.
 */

interface EmojiEntry {
  /** The emoji itself. */
  u: string;
  /** Its name, and other words it is known by. */
  n: string;
  k?: string;
  /** Its category (Emojibase's group number). */
  g: number;
  /** Five skin tones, light to dark, when it has them. */
  s?: string[];
}

const GROUPS: { id: number; label: string; icon: ComponentType<{ size?: number }> }[] = [
  { id: 0, label: 'Smileys', icon: Smile },
  { id: 1, label: 'People', icon: Users },
  { id: 3, label: 'Animals and nature', icon: PawPrint },
  { id: 4, label: 'Food and drink', icon: Apple },
  { id: 5, label: 'Travel and places', icon: Plane },
  { id: 6, label: 'Activities', icon: Trophy },
  { id: 7, label: 'Objects', icon: Lightbulb },
  { id: 8, label: 'Symbols', icon: Heart },
  { id: 9, label: 'Flags', icon: Flag },
];

let data: Promise<EmojiEntry[]> | null = null;
const loadEmoji = () => (data ??= import('./emojiData.json').then((m) => m.default as EmojiEntry[]));

const RECENT_KEY = 'hush.emoji.recent';
const TONE_KEY = 'hush.emoji.tone';
const read = <T,>(key: string, fallback: T): T => {
  try {
    return (JSON.parse(localStorage.getItem(key) ?? 'null') as T) ?? fallback;
  } catch {
    return fallback;
  }
};
const write = (key: string, value: unknown) => {
  try {
    localStorage.setItem(key, JSON.stringify(value));
  } catch {
    /* private mode */
  }
};

/** Remembers an emoji as recently used (for the picker's first row), wherever it was picked. */
export function rememberEmoji(emoji: string) {
  write(RECENT_KEY, [emoji, ...read<string[]>(RECENT_KEY, []).filter((e) => e !== emoji)].slice(0, 24));
}

const TONES = ['', '🏻', '🏼', '🏽', '🏾', '🏿'];

export function EmojiPicker({ onPick, onClose, autoFocus = true }: { onPick: (emoji: string) => void; onClose?: () => void; autoFocus?: boolean }) {
  const [all, setAll] = useState<EmojiEntry[] | null>(null);
  const [query, setQuery] = useState('');
  const [tone, setTone] = useState(() => read<number>(TONE_KEY, 0));
  const [recent, setRecent] = useState(() => read<string[]>(RECENT_KEY, []));
  const [active, setActive] = useState(recent.length ? -1 : 0);
  const scroller = useRef<HTMLDivElement>(null);
  const searchBox = useRef<HTMLInputElement>(null);

  useEffect(() => {
    void loadEmoji().then(setAll);
  }, []);
  useEffect(() => {
    if (autoFocus) searchBox.current?.focus({ preventScroll: true });
  }, [autoFocus]);

  const withTone = (e: EmojiEntry) => (tone && e.s ? e.s[tone - 1] : e.u);
  const pick = (emoji: string) => {
    rememberEmoji(emoji);
    setRecent(read<string[]>(RECENT_KEY, []));
    onPick(emoji);
  };

  const found = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q || !all) return null;
    const words = q.split(/\s+/);
    return all.filter((e) => words.every((w) => e.n.includes(w) || e.k?.includes(w))).slice(0, 160);
  }, [all, query]);

  const jump = (id: number) => {
    setActive(id);
    const el = scroller.current?.querySelector<HTMLElement>(`[data-group="${id}"]`);
    if (el && scroller.current) scroller.current.scrollTo({ top: el.offsetTop - 4, behavior: 'smooth' });
  };

  // The category under the top of the list lights up in the tab bar as you scroll.
  const onScroll = () => {
    const box = scroller.current;
    if (!box || found) return;
    let current = recent.length ? -1 : 0;
    for (const section of box.querySelectorAll<HTMLElement>('[data-group]')) if (section.offsetTop - 8 <= box.scrollTop) current = Number(section.dataset.group);
    setActive(current);
  };

  const grid = (list: string[], key: string) => (
    <div className="emoji-cells">
      {list.map((emoji, i) => (
        <button key={`${key}-${i}`} type="button" className="emoji-cell" onClick={() => pick(emoji)}>
          {emoji}
        </button>
      ))}
    </div>
  );

  return (
    <div className="emoji-picker" onKeyDown={(e) => e.key === 'Escape' && onClose?.()}>
      <div className="emoji-top">
        <label className="emoji-search">
          <Search size={16} />
          <input ref={searchBox} value={query} onChange={(e) => setQuery(e.target.value)} placeholder="Search emoji" aria-label="Search emoji" />
          {query && (
            <button type="button" className="icon-btn sm" onClick={() => setQuery('')} aria-label="Clear search">
              <X size={14} />
            </button>
          )}
        </label>
        <div className="emoji-tones" role="radiogroup" aria-label="Skin tone">
          {TONES.map((t, i) => (
            <button
              key={i}
              type="button"
              role="radio"
              aria-checked={tone === i}
              aria-label={i === 0 ? 'Default skin tone' : `Skin tone ${i}`}
              className={tone === i ? 'on' : ''}
              onClick={() => (setTone(i), write(TONE_KEY, i))}
            >
              {'✋' + t}
            </button>
          ))}
        </div>
      </div>
      <div className="emoji-scroll" ref={scroller} onScroll={onScroll}>
        {!all && <p className="emoji-empty">Loading…</p>}
        {found && (found.length ? grid(found.map(withTone), 'found') : <p className="emoji-empty">No emoji found</p>)}
        {all && !found && (
          <>
            {recent.length > 0 && (
              <section data-group={-1}>
                <h4>Recent</h4>
                {grid(recent, 'recent')}
              </section>
            )}
            {GROUPS.map((g) => (
              <section key={g.id} data-group={g.id}>
                <h4>{g.label}</h4>
                {grid(all.filter((e) => e.g === g.id).map(withTone), `g${g.id}`)}
              </section>
            ))}
          </>
        )}
      </div>
      {!found && (
        <nav className="emoji-tabs" aria-label="Emoji categories">
          {recent.length > 0 && (
            <button type="button" className={active === -1 ? 'on' : ''} onClick={() => jump(-1)} aria-label="Recent">
              <Clock size={17} />
            </button>
          )}
          {GROUPS.map(({ id, label, icon: Icon }) => (
            <button key={id} type="button" className={active === id ? 'on' : ''} onClick={() => jump(id)} aria-label={label}>
              <Icon size={17} />
            </button>
          ))}
        </nav>
      )}
    </div>
  );
}
