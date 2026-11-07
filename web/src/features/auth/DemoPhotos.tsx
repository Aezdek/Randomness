import type { ReactElement, ReactNode } from 'react';

/*
 * Stand-in photos for the landing page's demo screens: small flat illustrations, drawn here so the page needs no
 * pictures of anyone and nothing from another site. Each fills its box (the edges crop, like a photo would).
 */

export type Scene = 'lisbon' | 'lake' | 'dinner' | 'beach' | 'city' | 'coffee' | 'tulips' | 'hike' | 'vinyl';

const WINDOWS = [
  [8, 66], [14, 66], [8, 72], [14, 72], [27, 62], [33, 62], [27, 69], [33, 69], [47, 64], [53, 64], [47, 71], [53, 71],
  [70, 60], [76, 60], [70, 67], [76, 67], [88, 63], [88, 70],
];

function Lisbon() {
  return (
    <>
      <rect width="100" height="100" fill="#5b4bc4" />
      <rect y="18" width="100" height="16" fill="#9a5cc0" />
      <rect y="32" width="100" height="12" fill="#e17aa0" />
      <rect y="43" width="100" height="14" fill="#ffad7a" />
      <circle cx="66" cy="51" r="8" fill="#ffe29a" />
      <path d="M0 55 L6 55 L6 50 L11 50 L11 53 L18 53 L18 48 L22 46 L26 48 L26 54 L34 54 L34 51 L40 51 L40 55 L100 55 L100 60 L0 60 Z" fill="#8b56a6" />
      <rect y="57" width="100" height="5" fill="#f08d84" />
      <rect x="54" y="58.5" width="22" height="0.8" fill="#ffd2a1" />
      <rect x="60" y="60.3" width="12" height="0.7" fill="#ffd2a1" />
      <path d="M0 62 L22 60 L22 100 L0 100 Z" fill="#f3c9a1" />
      <path d="M22 59 L40 57 L40 100 L22 100 Z" fill="#e7a27c" />
      <path d="M40 60 L62 58 L62 100 L40 100 Z" fill="#f7e0c4" />
      <path d="M62 55 L82 54 L82 100 L62 100 Z" fill="#d98b6c" />
      <path d="M82 58 L100 57 L100 100 L82 100 Z" fill="#f0b98f" />
      <path d="M-1 62 L11 56 L23 60 Z" fill="#b5523b" />
      <path d="M21 59 L31 53 L41 57 Z" fill="#a8473a" />
      <path d="M61 55 L72 49 L83 54 Z" fill="#b5523b" />
      {WINDOWS.map(([x, y]) => (
        <rect key={`${x}-${y}`} x={x} y={y} width="3" height="4" rx="0.6" fill="#4b2f5c" />
      ))}
      <rect y="91" width="100" height="9" fill="#3e2c49" />
      <rect y="94" width="100" height="0.7" fill="#7d6386" />
      <rect y="97" width="100" height="0.7" fill="#7d6386" />
      <path d="M30 69 L30 62 L44 58" stroke="#2d2238" strokeWidth="0.7" fill="none" />
      <rect x="8" y="72" width="52" height="19" rx="3" fill="#ffc933" />
      <rect x="8" y="84" width="52" height="3" fill="#f2f0ea" />
      <rect x="8" y="72" width="52" height="3" rx="1.5" fill="#e8ad1c" />
      {[12, 22, 32, 42].map((x) => (
        <rect key={x} x={x} y="76.5" width="7.5" height="6" rx="1" fill="#3b2e4f" />
      ))}
      <rect x="52" y="76.5" width="5" height="12" rx="1" fill="#3b2e4f" />
      <circle cx="18" cy="91" r="2.6" fill="#1f1826" />
      <circle cx="50" cy="91" r="2.6" fill="#1f1826" />
      <text x="13" y="89.6" fontSize="2.6" fontWeight="700" fill="#3b2e4f" fontFamily="sans-serif">
        28
      </text>
    </>
  );
}

function Lake() {
  return (
    <>
      <rect width="100" height="100" fill="#cfe9ff" />
      <rect y="30" width="100" height="25" fill="#e4f3ff" />
      <circle cx="76" cy="20" r="6" fill="#fff6d6" />
      <path d="M-5 58 L18 26 L32 44 L48 22 L66 46 L80 30 L105 58 Z" fill="#8fb1e3" />
      <path d="M13 33 L18 26 L23 33 L20 32 L18 34 L16 32 Z M43 29 L48 22 L53 29 L50 28 L48 30 L46 28 Z M76 35 L80 30 L84 35 L81 34 L80 36 L78 34 Z" fill="#fff" />
      <path d="M-5 60 L12 44 L28 58 L44 42 L62 60 L80 46 L105 60 Z" fill="#5d7fc2" />
      {[2, 9, 16, 23, 30, 37, 44, 51, 58, 65, 72, 79, 86, 93].map((x, i) => (
        <path key={x} d={`M${x} 64 L${x + 3.5} ${52 + (i % 3) * 2} L${x + 7} 64 Z`} fill={i % 2 ? '#2f5e4e' : '#3d7560'} />
      ))}
      <rect y="63" width="100" height="37" fill="#6fa6dc" />
      <path d="M-5 63 L12 76 L28 65 L44 78 L62 63 L80 74 L105 63 Z" fill="#82b4e4" />
      {[70, 76, 83, 90, 96].map((y, i) => (
        <rect key={y} x={8 + i * 9} y={y} width={30 - i * 3} height="0.8" rx="0.4" fill="#b7daf6" />
      ))}
      <rect x="14" y="57" width="10" height="7" fill="#c94c3c" />
      <path d="M12.5 57.5 L19 52 L25.5 57.5 Z" fill="#7d2b25" />
      <rect x="18" y="60" width="2.5" height="4" fill="#3b2420" />
      <path d="M48 84 C52 82 58 82 62 84 L60 86 L50 86 Z" fill="#f2d7a6" />
      <path d="M55 84 L55 76 L60 83 Z" fill="#fff" />
    </>
  );
}

function Dinner() {
  const plates = [
    [28, 34, '#f2c46b'],
    [72, 30, '#e8734f'],
    [50, 74, '#9ecb6b'],
  ] as const;
  return (
    <>
      <rect width="100" height="100" fill="#3b2a24" />
      {[14, 34, 54, 74, 94].map((y) => (
        <rect key={y} y={y} width="100" height="0.8" fill="#33241f" />
      ))}
      <rect x="38" y="0" width="24" height="100" fill="#efe6d8" />
      <rect x="38" y="0" width="1.4" height="100" fill="#d9ccb7" />
      <rect x="60.6" y="0" width="1.4" height="100" fill="#d9ccb7" />
      {plates.map(([cx, cy, food]) => (
        <g key={`${cx}-${cy}`}>
          <circle cx={cx} cy={cy} r="17" fill="#e5ddcf" />
          <circle cx={cx} cy={cy} r="14.5" fill="#fbf8f2" />
          <circle cx={cx} cy={cy} r="9.5" fill={food} />
          <path
            d={`M${cx - 6} ${cy - 2} q3 -4 6 0 t6 0 M${cx - 5} ${cy + 3} q3 -4 6 0 t5 0`}
            stroke="#00000026"
            strokeWidth="1.2"
            fill="none"
            strokeLinecap="round"
          />
          <ellipse cx={cx + 3} cy={cy - 4} rx="2.2" ry="1.2" fill="#3f8f45" transform={`rotate(-30 ${cx + 3} ${cy - 4})`} />
          <circle cx={cx - 3} cy={cy + 1} r="1.6" fill="#d9483b" />
        </g>
      ))}
      <circle cx="84" cy="62" r="6" fill="#f6eef3" opacity="0.35" />
      <circle cx="84" cy="62" r="4" fill="#8c1f3b" />
      <circle cx="12" cy="62" r="6" fill="#f6eef3" opacity="0.35" />
      <circle cx="12" cy="62" r="4" fill="#a3294a" />
      <circle cx="50" cy="44" r="4.5" fill="#fff3c4" />
      <circle cx="50" cy="44" r="1.6" fill="#ffb02e" />
      <rect x="45" y="16" width="1.2" height="16" rx="0.6" fill="#c9c2b6" />
      <rect x="54" y="16" width="1.2" height="16" rx="0.6" fill="#c9c2b6" />
      <ellipse cx="22" cy="88" rx="11" ry="7" fill="#c68b4a" />
      <ellipse cx="20" cy="86" rx="5" ry="3" fill="#e3b277" />
      <ellipse cx="26" cy="90" rx="4.5" ry="2.6" fill="#e3b277" />
    </>
  );
}

function Beach() {
  return (
    <>
      <rect width="100" height="100" fill="#9fd6ff" />
      <circle cx="22" cy="18" r="7" fill="#fff3c2" />
      <rect y="38" width="100" height="12" fill="#2a86d1" />
      <rect y="49" width="100" height="10" fill="#48aee6" />
      <path d="M0 59 q6 -3 12 0 t12 0 t12 0 t12 0 t12 0 t12 0 t12 0 t12 0 t12 0 L100 63 L0 63 Z" fill="#ffffff" />
      <rect y="62" width="100" height="38" fill="#f6d8a4" />
      <rect x="10" y="44" width="16" height="0.8" fill="#9bd3f5" />
      <rect x="60" y="53" width="22" height="0.8" fill="#9bd3f5" />
      <rect x="61.2" y="54" width="1.4" height="34" fill="#6b4a3a" transform="rotate(8 62 70)" />
      <path d="M44 56 Q62 38 82 54 Z" fill="#ff6b6b" />
      <path d="M54 50.5 Q62 44 61 41 Q66 44 70 51 Z" fill="#fff" />
      <rect x="18" y="74" width="30" height="13" rx="1.5" fill="#7c5cff" transform="rotate(-6 33 80)" />
      <rect x="18" y="78" width="30" height="2.2" fill="#ffd36e" transform="rotate(-6 33 80)" />
      <circle cx="78" cy="84" r="5" fill="#fff" />
      <path d="M73 84 A5 5 0 0 1 83 84 Z" fill="#ff8a3d" />
      <rect x="77.3" y="79" width="1.4" height="10" fill="#4fb3e8" />
      <ellipse cx="88" cy="70" rx="3" ry="1.4" fill="#e9c58b" />
    </>
  );
}

function City() {
  const buildings = [
    [0, 52, 12, '#231f4f'],
    [11, 40, 10, '#2c2766'],
    [20, 58, 9, '#1f1b45'],
    [28, 30, 12, '#2a2560'],
    [39, 46, 11, '#231f4f'],
    [49, 24, 9, '#302a6e'],
    [57, 50, 12, '#231f4f'],
    [68, 36, 10, '#2a2560'],
    [77, 54, 9, '#1f1b45'],
    [85, 42, 15, '#2c2766'],
  ] as const;
  return (
    <>
      <rect width="100" height="100" fill="#141236" />
      {[[10, 10], [30, 6], [44, 14], [68, 8], [86, 18], [92, 6], [20, 22]].map(([x, y]) => (
        <circle key={`${x}-${y}`} cx={x} cy={y} r="0.6" fill="#e8e4ff" />
      ))}
      <circle cx="78" cy="22" r="7" fill="#f4f1ff" />
      <circle cx="75.5" cy="20" r="1.4" fill="#ddd8f6" />
      <circle cx="80" cy="25" r="1" fill="#ddd8f6" />
      {buildings.map(([x, top, w, fill], i) => (
        <g key={x}>
          <rect x={x} y={top} width={w} height={78 - top} fill={fill} />
          {Array.from({ length: Math.floor((74 - top) / 5) }, (_, r) =>
            Array.from({ length: Math.floor(w / 3.5) }, (_, c) =>
              (r * 7 + c * 3 + i) % 4 !== 0 ? null : (
                <rect key={`${r}-${c}`} x={x + 1.2 + c * 3.5} y={top + 2 + r * 5} width="1.6" height="2.2" fill="#ffd37a" />
              ),
            ),
          )}
        </g>
      ))}
      <rect y="78" width="100" height="22" fill="#1b1840" />
      {[[6, 82, 10], [30, 86, 6], [50, 81, 8], [70, 88, 12], [88, 83, 6]].map(([x, y, w]) => (
        <rect key={x} x={x} y={y} width={w} height="0.8" rx="0.4" fill="#ffd37a" opacity="0.6" />
      ))}
    </>
  );
}

function Coffee() {
  return (
    <>
      <rect width="100" height="100" fill="#d9c3a6" />
      {[12, 30, 48, 66, 84].map((x) => (
        <rect key={x} x={x} width="0.8" height="100" fill="#cdb593" />
      ))}
      <circle cx="44" cy="50" r="27" fill="#f4f0ea" />
      <circle cx="44" cy="50" r="24" fill="#ebe5dc" />
      <circle cx="44" cy="50" r="17" fill="#fbfaf7" />
      <circle cx="44" cy="50" r="14" fill="#8a5a3c" />
      <path d="M44 60 C36 54 36 46 40.5 45 C42.5 44.6 43.6 46 44 47.5 C44.4 46 45.5 44.6 47.5 45 C52 46 52 54 44 60 Z" fill="#f1e2cf" />
      <rect x="62" y="56" width="22" height="3" rx="1.5" fill="#c8c3bb" transform="rotate(28 62 56)" />
      <path d="M70 18 C82 12 96 20 94 32 C90 28 84 26 78 28 C76 24 73 21 70 18 Z" fill="#e8a04f" />
      <path d="M78 28 C80 24 82 20 82 16 M86 28 C87 24 88 21 88 17" stroke="#c97c2e" strokeWidth="1.2" fill="none" />
      <circle cx="16" cy="82" r="5" fill="#b33a3a" />
      <circle cx="24" cy="88" r="4" fill="#c94848" />
    </>
  );
}

function Tulips() {
  const heads = ['#ff5d8f', '#ffb3c8', '#ff8a3d', '#ff5d8f', '#ffd36e', '#ffb3c8'];
  return (
    <>
      <rect width="100" height="100" fill="#ffe7ee" />
      <circle cx="80" cy="18" r="8" fill="#fff7d6" />
      <rect y="46" width="100" height="54" fill="#7cc488" />
      {[0, 1, 2].map((row) =>
        Array.from({ length: 7 }, (_, i) => {
          const x = 6 + i * 15 + (row % 2) * 7;
          const y = 40 + row * 18;
          const color = heads[(i + row * 2) % heads.length];
          return (
            <g key={`${row}-${i}`}>
              <rect x={x - 0.6} y={y + 5} width="1.4" height="16" fill="#3e8b4f" />
              <ellipse cx={x + 3} cy={y + 15} rx="3" ry="1.2" fill="#58a865" transform={`rotate(-40 ${x + 3} ${y + 15})`} />
              <path d={`M${x - 4} ${y} L${x - 2.5} ${y + 7} Q${x} ${y + 9} ${x + 2.5} ${y + 7} L${x + 4} ${y} L${x + 2} ${y + 2.5} L${x} ${y - 1} L${x - 2} ${y + 2.5} Z`} fill={color} />
            </g>
          );
        }),
      )}
    </>
  );
}

function Hike() {
  return (
    <>
      <rect width="100" height="100" fill="#ffd9b0" />
      <rect y="30" width="100" height="20" fill="#ffc7a0" />
      <circle cx="30" cy="38" r="10" fill="#fff0c9" />
      <path d="M-5 70 L22 40 L40 58 L60 34 L82 56 L105 42 L105 100 L-5 100 Z" fill="#c77d6b" />
      <path d="M-5 80 L18 62 L36 74 L58 58 L80 76 L105 64 L105 100 L-5 100 Z" fill="#8f4f57" />
      <path d="M-5 92 L30 80 L60 88 L105 78 L105 100 L-5 100 Z" fill="#5b2f45" />
      <circle cx="62" cy="64" r="1.8" fill="#2a1726" />
      <path d="M61 66 L60.3 72 L59 76 M60.5 72 L62 76 M61 67 L63.5 70" stroke="#2a1726" strokeWidth="1" strokeLinecap="round" fill="none" />
      <rect x="61.2" y="66" width="2.4" height="3.6" rx="0.8" fill="#ff8a3d" />
    </>
  );
}

function Vinyl() {
  return (
    <>
      <rect width="100" height="100" fill="#2c2350" />
      <rect x="8" y="14" width="56" height="56" fill="#ff8a3d" />
      <circle cx="36" cy="42" r="16" fill="#fff4e6" />
      <circle cx="36" cy="42" r="6" fill="#7c5cff" />
      <circle cx="62" cy="52" r="32" fill="#111018" />
      {[28, 24, 20, 16].map((r) => (
        <circle key={r} cx="62" cy="52" r={r} fill="none" stroke="#25222f" strokeWidth="0.6" />
      ))}
      <circle cx="62" cy="52" r="10" fill="#e84393" />
      <circle cx="62" cy="52" r="1.4" fill="#111018" />
      <rect x="10" y="80" width="40" height="3" rx="1.5" fill="#fff" opacity="0.85" />
      <rect x="10" y="87" width="26" height="2.4" rx="1.2" fill="#fff" opacity="0.45" />
    </>
  );
}

const SCENES: Record<Scene, () => ReactElement> = {
  lisbon: Lisbon,
  lake: Lake,
  dinner: Dinner,
  beach: Beach,
  city: City,
  coffee: Coffee,
  tulips: Tulips,
  hike: Hike,
  vinyl: Vinyl,
};

export function Photo({ scene, className = '', children }: { scene: Scene; className?: string; children?: ReactNode }) {
  const Drawing = SCENES[scene];
  return (
    <span className={`la-photo ${className}`}>
      <svg className="la-photo-art" viewBox="0 0 100 100" preserveAspectRatio="xMidYMid slice" aria-hidden>
        <Drawing />
      </svg>
      {children}
    </span>
  );
}
