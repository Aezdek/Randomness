/* Shop logic. You shouldn't need to edit this file: stock and contact
   details live in inventory.js. Grade wording is in GRADES just below. */
(() => {
  "use strict";

  const SHOP = Object.assign(
    {
      name: "Wheelhouse",
      tagline: "",
      email: "",
      instagram: "",
      tiktok: "",
      ebayStore: "",
      warrantyDays: 30,
      shipsFrom: "",
      currency: "USD",
      locale: "en-US",
    },
    window.SHOP || {}
  );

  const GRADES = {
    A: {
      word: "Excellent",
      blurb: "Looks close to new. Any marks are faint enough that you'd have to hunt for them under a lamp.",
    },
    B: {
      word: "Good",
      blurb: "Normal signs of use, like light scratches on the back or faceplate. Nothing on the screen you'd notice while it's on.",
    },
    C: {
      word: "Fair",
      blurb: "Visible wear: dings, deeper scratches or scuffs. Works exactly as well as an A and is priced to match the looks.",
    },
  };

  const FAMILIES = {
    classic: { label: "Classic & Video", shape: "classic" },
    nano: { label: "Nano", shape: "nano" },
    mini: { label: "Mini", shape: "mini" },
    shuffle: { label: "Shuffle", shape: "shuffle" },
    touch: { label: "Touch", shape: "touch" },
  };

  const STATUS = { available: "Available", reserved: "Reserved", sold: "Sold" };
  const STATUS_RANK = { available: 0, reserved: 1, sold: 2 };

  const MAIL = {
    general: {
      subject: `Question for ${SHOP.name}`,
      body: "Hi!\n\n",
    },
    sell: {
      subject: "Offer on my iPod",
      body:
        "Hi! I'd like an offer on my iPod.\n\n" +
        "Model (from the back, e.g. A1238):\n" +
        "Storage size:\n" +
        "Does it turn on?\n" +
        "Any damage or problems:\n" +
        "My location:\n\n" +
        "Photos attached: front, back, and the Settings > About screen if it turns on.\n",
    },
    request: {
      subject: "Looking for a specific iPod",
      body: "Hi! I'm looking for:\n\nModel:\nColour:\nStorage:\nBudget:\n",
    },
  };

  /* ------------------------------------------------------------------ helpers */

  const $ = (sel, root = document) => root.querySelector(sel);
  const $$ = (sel, root = document) => Array.from(root.querySelectorAll(sel));

  const esc = (value) =>
    String(value ?? "").replace(/[&<>"']/g, (ch) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[ch]);

  function money(n) {
    const digits = Number.isInteger(n) ? 0 : 2;
    try {
      return new Intl.NumberFormat(SHOP.locale, {
        style: "currency",
        currency: SHOP.currency,
        minimumFractionDigits: digits,
        maximumFractionDigits: digits,
      }).format(n);
    } catch {
      return `$${n}`;
    }
  }

  function capacityGB(text) {
    const m = String(text || "").match(/([\d.]+)\s*(TB|GB|MB)/i);
    if (!m) return 0;
    const n = parseFloat(m[1]);
    const unit = m[2].toUpperCase();
    return unit === "TB" ? n * 1024 : unit === "MB" ? n / 1024 : n;
  }

  function shortDate(iso) {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(iso || "")) return "";
    const [y, m, d] = iso.split("-").map(Number);
    try {
      return new Date(y, m - 1, d).toLocaleDateString(SHOP.locale, { month: "short", day: "numeric" });
    } catch {
      return iso;
    }
  }

  const handle = (h) => String(h || "").trim().replace(/^@/, "");
  const instagramUrl = () => (handle(SHOP.instagram) ? `https://www.instagram.com/${encodeURIComponent(handle(SHOP.instagram))}/` : "");
  const tiktokUrl = () => (handle(SHOP.tiktok) ? `https://www.tiktok.com/@${encodeURIComponent(handle(SHOP.tiktok))}` : "");

  function mailto(subject, body) {
    if (!SHOP.email) return "";
    return `mailto:${SHOP.email}?subject=${encodeURIComponent(subject)}&body=${encodeURIComponent(body)}`;
  }

  // Best way to reach the seller: email if set, else Instagram, TikTok, eBay.
  function contactHref(subject, body) {
    return mailto(subject, body) || instagramUrl() || tiktokUrl() || SHOP.ebayStore || "#contact";
  }

  const isExternal = (href) => /^https?:/i.test(href);
  const linkAttrs = (href) => (isExternal(href) ? ' target="_blank" rel="noopener"' : "");

  /* ------------------------------------------------------------------ data */

  function normalize(raw, index) {
    const family = FAMILIES[raw.family] ? raw.family : "classic";
    const grade = GRADES[String(raw.grade || "").toUpperCase()] ? String(raw.grade).toUpperCase() : "B";
    const status = STATUS[raw.status] ? raw.status : "available";
    return {
      ...raw,
      id: String(raw.id || `ipod-${index + 1}`).replace(/[^\w-]/g, "-"),
      family,
      grade,
      status,
      shape: raw.shape || FAMILIES[family].shape,
      name: raw.name || "iPod",
      generation: raw.generation || "",
      capacity: raw.capacity || "",
      color: raw.color || "",
      price: Number(raw.price) || 0,
      listed: raw.listed || "",
      upgrades: Array.isArray(raw.upgrades) ? raw.upgrades : [],
      included: Array.isArray(raw.included) ? raw.included : [],
      photos: Array.isArray(raw.photos) ? raw.photos.filter(Boolean) : [],
      description: raw.description || "",
      ebayUrl: raw.ebayUrl || "",
      order: index,
    };
  }

  const ITEMS = (Array.isArray(window.INVENTORY) ? window.INVENTORY : []).map(normalize);
  const titleOf = (it) => [it.name, it.capacity].filter(Boolean).join(" ");
  const subOf = (it) => [it.generation, it.color].filter(Boolean).join(" · ");

  /* ------------------------------------------------------------------ drawings */
  // When a listing has no photos, the site draws the iPod in its colour.

  const NAMED_COLORS = {
    black: "#1d1d1f",
    white: "#f5f5f7",
    silver: "#d5d6d9",
    "space gray": "#56575c",
    "space grey": "#56575c",
    slate: "#56575c",
    gray: "#8e8f94",
    grey: "#8e8f94",
    blue: "#5d93d8",
    pink: "#f0a3bf",
    green: "#86c77a",
    red: "#c8322f",
    purple: "#9a7fd4",
    yellow: "#f1d35a",
    gold: "#e3c99d",
    orange: "#f1964a",
  };

  function hexFor(color) {
    const key = String(color || "").trim().toLowerCase();
    if (/^#[0-9a-f]{6}$/.test(key)) return key;
    if (NAMED_COLORS[key]) return NAMED_COLORS[key];
    const hit = Object.keys(NAMED_COLORS).find((k) => key.includes(k));
    return hit ? NAMED_COLORS[hit] : NAMED_COLORS.white;
  }

  function mix(a, b, t) {
    const pa = parseInt(a.slice(1), 16);
    const pb = parseInt(b.slice(1), 16);
    return (
      "#" +
      [16, 8, 0]
        .map((s) => {
          const ca = (pa >> s) & 255;
          const cb = (pb >> s) & 255;
          return Math.round(ca + (cb - ca) * t)
            .toString(16)
            .padStart(2, "0");
        })
        .join("")
    );
  }

  function luminance(hex) {
    const p = parseInt(hex.slice(1), 16);
    return (0.2126 * ((p >> 16) & 255) + 0.7152 * ((p >> 8) & 255) + 0.0722 * (p & 255)) / 255;
  }

  function paletteFor(color, shape) {
    const body = hexFor(color);
    const dark = luminance(body) < 0.4;
    const p = { body, dark, edge: mix(body, "#000000", dark ? 0.45 : 0.2) };
    if (shape === "mini") {
      p.wheel = mix(body, "#ffffff", 0.22);
      p.center = body;
      p.label = mix(body, "#000000", 0.38);
    } else if (dark) {
      p.wheel = mix(body, "#ffffff", 0.09);
      p.center = body;
      p.label = "#a4a4aa";
    } else {
      p.wheel = "#fbfbfc";
      p.center = mix(body, "#ffffff", 0.45);
      p.label = "#9c9ca3";
    }
    p.wheelEdge = dark ? mix(body, "#ffffff", 0.2) : mix(body, "#000000", 0.14);
    p.face = dark ? "#151517" : "#f8f8f9"; // front glass on touch models
    return p;
  }

  let uidCounter = 0;
  const uid = () => `ip${++uidCounter}`;

  const ICON_COLORS = ["#ff6b6b", "#ffa94d", "#ffd43b", "#69db7c", "#4dabf7", "#9775fa", "#f783ac", "#38d9a9"];

  function defs(U, p) {
    return `<defs>
      <linearGradient id="${U}-body" x1="0" y1="0" x2="1" y2="1">
        <stop offset="0" stop-color="${mix(p.body, "#ffffff", 0.14)}"/>
        <stop offset="1" stop-color="${mix(p.body, "#000000", 0.08)}"/>
      </linearGradient>
      <radialGradient id="${U}-wheel" cx="0.5" cy="0.38" r="0.7">
        <stop offset="0" stop-color="${mix(p.wheel, "#ffffff", 0.18)}"/>
        <stop offset="1" stop-color="${mix(p.wheel, "#000000", 0.06)}"/>
      </radialGradient>
      <linearGradient id="${U}-lcd" x1="0" y1="0" x2="0" y2="1">
        <stop offset="0" stop-color="#f7fafe"/><stop offset="1" stop-color="#dde6f1"/>
      </linearGradient>
      <linearGradient id="${U}-mono" x1="0" y1="0" x2="0" y2="1">
        <stop offset="0" stop-color="#dfe2d8"/><stop offset="1" stop-color="#c7cbbf"/>
      </linearGradient>
      <linearGradient id="${U}-hdr" x1="0" y1="0" x2="0" y2="1">
        <stop offset="0" stop-color="#f8f8f9"/><stop offset="1" stop-color="#c9cbd0"/>
      </linearGradient>
      <linearGradient id="${U}-sel" x1="0" y1="0" x2="0" y2="1">
        <stop offset="0" stop-color="#6aa9f6"/><stop offset="1" stop-color="#1c5fd4"/>
      </linearGradient>
      <linearGradient id="${U}-wall" x1="0" y1="0" x2="1" y2="1">
        <stop offset="0" stop-color="${mix(p.body, "#1b2340", 0.45)}"/><stop offset="1" stop-color="#121826"/>
      </linearGradient>
      <linearGradient id="${U}-art" x1="0" y1="0" x2="1" y2="1">
        <stop offset="0" stop-color="#ff8a5b"/><stop offset="0.55" stop-color="#e2467a"/><stop offset="1" stop-color="#5b3fd1"/>
      </linearGradient>
      <linearGradient id="${U}-glare" x1="0" y1="0" x2="1" y2="1">
        <stop offset="0" stop-color="#ffffff" stop-opacity="0.32"/>
        <stop offset="0.48" stop-color="#ffffff" stop-opacity="0.04"/>
        <stop offset="0.49" stop-color="#ffffff" stop-opacity="0"/>
      </linearGradient>
    </defs>`;
  }

  const iconNext = (x, y, s, f) =>
    `<g fill="${f}" transform="translate(${x} ${y}) scale(${s})"><path d="M-5.6 -3.4 L-1.4 0 L-5.6 3.4Z M-1.4 -3.4 L2.8 0 L-1.4 3.4Z"/><rect x="3" y="-3.4" width="1.4" height="6.8"/></g>`;
  const iconPrev = (x, y, s, f) =>
    `<g fill="${f}" transform="translate(${x} ${y}) scale(${-s} ${s})"><path d="M-5.6 -3.4 L-1.4 0 L-5.6 3.4Z M-1.4 -3.4 L2.8 0 L-1.4 3.4Z"/><rect x="3" y="-3.4" width="1.4" height="6.8"/></g>`;
  const iconPlay = (x, y, s, f) =>
    `<g fill="${f}" transform="translate(${x} ${y}) scale(${s})"><path d="M-5.2 -3.2 L-1 0 L-5.2 3.2Z"/><rect x="0.8" y="-3.2" width="1.5" height="6.4"/><rect x="3.4" y="-3.2" width="1.5" height="6.4"/></g>`;

  function clickWheel(cx, cy, r, rc, p, U) {
    const s = r / 44;
    return `
      <circle cx="${cx}" cy="${cy}" r="${r}" fill="url(#${U}-wheel)" stroke="${p.wheelEdge}" stroke-width="0.8"/>
      <circle cx="${cx}" cy="${cy}" r="${rc}" fill="${p.center}" stroke="${p.wheelEdge}" stroke-width="0.8"/>
      <text x="${cx}" y="${cy - r + 13 * s}" font-size="${7 * s}" text-anchor="middle" fill="${p.label}" font-weight="700" letter-spacing="${0.5 * s}">MENU</text>
      ${iconPrev(cx - r + 12.5 * s, cy, s, p.label)}
      ${iconNext(cx + r - 12.5 * s, cy, s, p.label)}
      ${iconPlay(cx, cy + r - 11 * s, s, p.label)}`;
  }

  function batteryIcon(x, y, h) {
    return `<rect x="${x}" y="${y}" width="${h * 1.9}" height="${h}" rx="${h * 0.15}" fill="none" stroke="#3a3a3e" stroke-width="${h * 0.14}"/>
      <rect x="${x + h * 0.18}" y="${y + h * 0.18}" width="${h * 1.35}" height="${h * 0.64}" fill="#43a447"/>`;
  }

  function lcdMenu(x, y, w, h, U, opts = {}) {
    const rows = opts.rows || ["Playlists", "Artists", "Albums", "Songs", "Genres"];
    const title = opts.title || "Music";
    const mono = !!opts.mono;
    const hh = h * 0.15;
    const rh = (h - hh) / 6;
    const fs = rh * 0.6;
    let s = `<rect x="${x}" y="${y}" width="${w}" height="${h}" fill="url(#${U}-${mono ? "mono" : "lcd"})"/>`;
    s += `<rect x="${x}" y="${y}" width="${w}" height="${hh}" fill="${mono ? "#cfd3c6" : `url(#${U}-hdr)`}"/>`;
    s += `<text x="${x + w / 2}" y="${y + hh * 0.72}" font-size="${hh * 0.6}" text-anchor="middle" fill="#26262a" font-weight="700">${title}</text>`;
    s += batteryIcon(x + w - hh * 2.4, y + hh * 0.3, hh * 0.42);
    rows.forEach((label, i) => {
      const ry = y + hh + i * rh + rh * 0.15;
      const on = i === 0;
      if (on) s += `<rect x="${x}" y="${ry}" width="${w}" height="${rh}" fill="${mono ? "#2b2d28" : `url(#${U}-sel)`}"/>`;
      const fill = on ? (mono ? "#dfe2d8" : "#ffffff") : "#1f1f22";
      s += `<text x="${x + w * 0.06}" y="${ry + rh * 0.7}" font-size="${fs}" fill="${fill}" font-weight="${on ? 700 : 500}">${label}</text>`;
      s += `<path d="M${x + w * 0.91} ${ry + rh * 0.3} l${rh * 0.2} ${rh * 0.2} l${-rh * 0.2} ${rh * 0.2}" stroke="${on ? fill : "#8a8a8f"}" stroke-width="${rh * 0.08}" fill="none"/>`;
    });
    return s;
  }

  function lcdNowPlaying(x, y, w, h, U) {
    const hh = h * 0.15;
    const art = h * 0.46;
    const ax = x + w * 0.07;
    const ay = y + hh + h * 0.08;
    const tx = ax + art + w * 0.06;
    const fs = h * 0.072;
    const by = y + h * 0.84;
    const bx = x + w * 0.1;
    const bw = w * 0.8;
    return `
      <rect x="${x}" y="${y}" width="${w}" height="${h}" fill="url(#${U}-lcd)"/>
      <rect x="${x}" y="${y}" width="${w}" height="${hh}" fill="url(#${U}-hdr)"/>
      <text x="${x + w / 2}" y="${y + hh * 0.72}" font-size="${hh * 0.58}" text-anchor="middle" fill="#26262a" font-weight="700">Now Playing</text>
      ${iconPlay(x + w * 0.08, y + hh * 0.52, hh * 0.09, "#3a3a3e")}
      ${batteryIcon(x + w - hh * 2.4, y + hh * 0.3, hh * 0.42)}
      <rect x="${ax}" y="${ay}" width="${art}" height="${art}" fill="url(#${U}-art)"/>
      <circle cx="${ax + art / 2}" cy="${ay + art / 2}" r="${art * 0.22}" fill="none" stroke="#ffffff" stroke-opacity="0.55" stroke-width="${art * 0.05}"/>
      <text x="${tx}" y="${ay + fs * 1.3}" font-size="${fs * 1.05}" font-weight="700" fill="#1d1d20">Afterglow</text>
      <text x="${tx}" y="${ay + fs * 2.7}" font-size="${fs}" fill="#3a3a3f">The Rewinds</text>
      <text x="${tx}" y="${ay + fs * 4}" font-size="${fs}" fill="#6a6a70">Side B</text>
      <text x="${tx}" y="${ay + fs * 5.6}" font-size="${fs * 0.85}" fill="#8a8a90">4 of 12</text>
      <rect x="${bx}" y="${by}" width="${bw}" height="${h * 0.045}" rx="${h * 0.02}" fill="#c4ccd8"/>
      <rect class="np-fill" x="${bx}" y="${by}" width="${bw}" height="${h * 0.045}" rx="${h * 0.02}" fill="url(#${U}-sel)"/>
      <text x="${bx}" y="${by + h * 0.12}" font-size="${fs * 0.8}" fill="#55555b">1:12</text>
      <text x="${bx + bw}" y="${by + h * 0.12}" font-size="${fs * 0.8}" fill="#55555b" text-anchor="end">-2:31</text>`;
  }

  function wrapSvg(vbW, vbH, body, cls = "") {
    return `<svg class="device ${cls}" viewBox="0 0 ${vbW} ${vbH}" font-family="'Helvetica Neue', Helvetica, Arial, sans-serif" aria-hidden="true" focusable="false">${body}</svg>`;
  }

  const SHAPES = {
    classic(p, U, screen) {
      return wrapSvg(
        124,
        208,
        `${defs(U, p)}
        <rect x="1" y="1" width="122" height="206" rx="13" fill="url(#${U}-body)" stroke="${p.edge}" stroke-width="1"/>
        <rect x="11" y="11" width="102" height="80" rx="3.5" fill="#0c0c0e"/>
        ${screen === "now-playing" ? lcdNowPlaying(14, 14, 96, 74, U) : lcdMenu(14, 14, 96, 74, U)}
        <rect x="14" y="14" width="96" height="74" fill="url(#${U}-glare)"/>
        ${clickWheel(62, 150, 44, 16.5, p, U)}`
      );
    },
    nano(p, U) {
      return wrapSvg(
        84,
        196,
        `${defs(U, p)}
        <rect x="1" y="1" width="82" height="194" rx="9" fill="url(#${U}-body)" stroke="${p.edge}" stroke-width="1"/>
        <rect x="8" y="10" width="68" height="58" rx="2" fill="#0c0c0e"/>
        ${lcdMenu(10, 12, 64, 54, U)}
        <rect x="10" y="12" width="64" height="54" fill="url(#${U}-glare)"/>
        ${clickWheel(42, 138, 31, 11.5, p, U)}`
      );
    },
    "nano-fat"(p, U) {
      return wrapSvg(
        106,
        128,
        `${defs(U, p)}
        <rect x="1" y="1" width="104" height="126" rx="13" fill="url(#${U}-body)" stroke="${p.edge}" stroke-width="1"/>
        <rect x="10" y="9" width="86" height="58" rx="3" fill="#0c0c0e"/>
        ${lcdMenu(12, 11, 82, 54, U)}
        <rect x="12" y="11" width="82" height="54" fill="url(#${U}-glare)"/>
        ${clickWheel(53, 96, 25.5, 9.5, p, U)}`
      );
    },
    "nano-touch"(p, U) {
      let icons = "";
      for (let row = 0; row < 4; row++) {
        for (let col = 0; col < 2; col++) {
          const i = row * 2 + col;
          icons += `<circle cx="${25 + col * 30}" cy="${36 + row * 25}" r="9" fill="${ICON_COLORS[i]}"/>`;
        }
      }
      return wrapSvg(
        80,
        154,
        `${defs(U, p)}
        <rect x="1" y="1" width="78" height="152" rx="9" fill="url(#${U}-body)" stroke="${p.edge}" stroke-width="1"/>
        <rect x="3" y="3" width="74" height="148" rx="7" fill="${p.face}"/>
        <rect x="7" y="16" width="66" height="112" rx="1.5" fill="url(#${U}-wall)"/>
        ${icons}
        <rect x="7" y="16" width="66" height="112" rx="1.5" fill="url(#${U}-glare)"/>
        <circle cx="40" cy="140" r="6" fill="none" stroke="${p.dark ? "#3a3a3e" : "#cfd0d4"}" stroke-width="1.2"/>`
      );
    },
    mini(p, U) {
      return wrapSvg(
        104,
        184,
        `${defs(U, p)}
        <rect x="1" y="1" width="102" height="182" rx="12" fill="url(#${U}-body)" stroke="${p.edge}" stroke-width="1"/>
        <rect x="14" y="13" width="76" height="58" rx="3" fill="${mix(p.body, "#000000", 0.25)}"/>
        ${lcdMenu(16, 15, 72, 54, U, { mono: true })}
        ${clickWheel(52, 126, 37, 13, p, U)}`
      );
    },
    shuffle(p, U) {
      const ring = mix(p.body, "#ffffff", p.dark ? 0.12 : 0.28);
      const sign = p.label;
      return wrapSvg(
        140,
        96,
        `${defs(U, p)}
        <rect x="1" y="1" width="138" height="94" rx="9" fill="url(#${U}-body)" stroke="${p.edge}" stroke-width="1"/>
        <circle cx="70" cy="48" r="33" fill="${ring}" stroke="${p.wheelEdge}" stroke-width="0.8"/>
        <circle cx="70" cy="48" r="11.5" fill="${p.body}" stroke="${p.wheelEdge}" stroke-width="0.8"/>
        <path d="M70 18.5 v8 M66 22.5 h8" stroke="${sign}" stroke-width="1.6"/>
        <path d="M66 74 h8" stroke="${sign}" stroke-width="1.6"/>
        ${iconPrev(46, 48, 0.75, sign)}
        ${iconNext(94, 48, 0.75, sign)}
        ${iconPlay(70, 48, 0.6, sign)}`
      );
    },
    touch(p, U) {
      let icons = "";
      for (let row = 0; row < 5; row++) {
        for (let col = 0; col < 4; col++) {
          const i = row * 4 + col;
          icons += `<rect x="${12 + col * 20}" y="${30 + row * 24}" width="13" height="13" rx="3" fill="${ICON_COLORS[i % ICON_COLORS.length]}" opacity="0.9"/>`;
        }
      }
      return wrapSvg(
        100,
        200,
        `${defs(U, p)}
        <rect x="1" y="1" width="98" height="198" rx="15" fill="url(#${U}-body)" stroke="${p.edge}" stroke-width="1"/>
        <rect x="2.6" y="2.6" width="94.8" height="194.8" rx="13.5" fill="${p.face}"/>
        <rect x="40" y="11" width="20" height="2.4" rx="1.2" fill="${p.dark ? "#2c2c30" : "#c7c8cc"}"/>
        <rect x="6" y="22" width="88" height="154" rx="1.5" fill="url(#${U}-wall)"/>
        ${icons}
        <rect x="6" y="156" width="88" height="20" fill="#ffffff" opacity="0.14"/>
        <rect x="6" y="22" width="88" height="154" rx="1.5" fill="url(#${U}-glare)"/>
        <circle cx="50" cy="187" r="7" fill="none" stroke="${p.dark ? "#3a3a3e" : "#cfd0d4"}" stroke-width="1.2"/>
        <rect x="47" y="184" width="6" height="6" rx="1.5" fill="none" stroke="${p.dark ? "#3a3a3e" : "#cfd0d4"}" stroke-width="0.8"/>`
      );
    },
  };

  function deviceSVG(shape, color, screen) {
    const draw = SHAPES[shape] || SHAPES.classic;
    return draw(paletteFor(color, shape), uid(), screen);
  }

  /* ------------------------------------------------------------------ rendering */

  const state = { family: "all", showSold: true, sort: "featured" };

  function visibleItems() {
    const sorters = {
      featured: (a, b) => a.order - b.order,
      newest: (a, b) => String(b.listed).localeCompare(String(a.listed)) || a.order - b.order,
      "price-asc": (a, b) => a.price - b.price,
      "price-desc": (a, b) => b.price - a.price,
      storage: (a, b) => capacityGB(b.capacity) - capacityGB(a.capacity),
    };
    const by = sorters[state.sort] || sorters.featured;
    return ITEMS.filter((it) => (state.family === "all" || it.family === state.family) && (state.showSold || it.status !== "sold")).sort(
      (a, b) => STATUS_RANK[a.status] - STATUS_RANK[b.status] || by(a, b)
    );
  }

  function artFor(it, size) {
    if (it.photos.length) {
      return `<img src="${esc(it.photos[0])}" alt="${esc(titleOf(it))}, photo 1" loading="lazy" decoding="async">`;
    }
    return deviceSVG(it.shape, it.color, size === "hero" ? "now-playing" : "menu");
  }

  function statusPill(it) {
    if (it.status === "available") return "";
    return `<span class="status-pill status-${it.status}">${STATUS[it.status]}</span>`;
  }

  function gradePill(it, long) {
    const g = GRADES[it.grade];
    return `<span class="grade grade-${it.grade.toLowerCase()}" title="Grade ${it.grade}: ${esc(g.word)}">${long ? `Grade ${it.grade} · ${esc(g.word)}` : it.grade}</span>`;
  }

  function cardHTML(it) {
    const ups = it.upgrades.slice(0, 2).map((u) => esc(u.replace(/\s*\(.*?\)\s*/g, " ").trim()));
    const more = it.upgrades.length > 2 ? ` · +${it.upgrades.length - 2} more` : "";
    const label = `${titleOf(it)}, ${subOf(it)}, Grade ${it.grade}, ${money(it.price)}${it.status !== "available" ? `, ${STATUS[it.status]}` : ""}`;
    return `
      <li>
        <article class="card" data-status="${it.status}">
          <div class="card-art" style="--tint:${hexFor(it.color)}">
            ${artFor(it)}
            ${statusPill(it)}
          </div>
          <div class="card-body">
            <div class="card-top">
              <h3 class="card-title"><a class="card-link" href="#ipod-${encodeURIComponent(it.id)}" aria-label="${esc(label)}">${esc(titleOf(it))}</a></h3>
              ${gradePill(it)}
            </div>
            <p class="card-sub">${esc(subOf(it))}</p>
            ${ups.length ? `<p class="card-ups">${ups.join(" · ")}${more}</p>` : `<p class="card-ups card-ups-stock">Original, fully tested</p>`}
            <div class="card-foot">
              <span class="price">${money(it.price)}</span>
              <span class="card-cta" aria-hidden="true">Details</span>
            </div>
          </div>
        </article>
      </li>`;
  }

  function renderFilters() {
    const pool = ITEMS.filter((it) => state.showSold || it.status !== "sold");
    const present = Object.keys(FAMILIES).filter((f) => ITEMS.some((it) => it.family === f));
    const chip = (key, label, count) =>
      `<button type="button" class="chip" data-family="${key}" aria-pressed="${state.family === key}">${esc(label)} <span class="chip-count">${count}</span></button>`;
    $("#filters").innerHTML =
      chip("all", "All", pool.length) + present.map((f) => chip(f, FAMILIES[f].label, pool.filter((it) => it.family === f).length)).join("");
  }

  function renderGrid() {
    const list = visibleItems();
    $("#grid").innerHTML = list.map(cardHTML).join("");
    $("#empty").hidden = list.length > 0;
    const available = list.filter((it) => it.status === "available").length;
    $("#result-count").textContent = list.length
      ? `${list.length} ${list.length === 1 ? "iPod" : "iPods"}${available !== list.length ? `, ${available} available` : ""}`
      : "";
  }

  function render() {
    renderFilters();
    renderGrid();
  }

  function renderStatic() {
    document.title = `${SHOP.name} · Restored iPods`;
    $$("[data-shop-name]").forEach((el) => (el.textContent = SHOP.name));
    $$("[data-year]").forEach((el) => (el.textContent = String(new Date().getFullYear())));
    $$("[data-warranty-days]").forEach((el) => (el.textContent = String(SHOP.warrantyDays)));
    $$("[data-ships-from]").forEach((el) => (el.textContent = SHOP.shipsFrom ? `${SHOP.shipsFrom}.` : ""));

    const count = (s) => ITEMS.filter((it) => it.status === s).length;
    $('[data-stat="available"]').textContent = String(count("available"));
    $('[data-stat="sold"]').textContent = String(count("sold"));
    $('[data-stat="warranty"]').textContent = `${SHOP.warrantyDays} days`;

    $$("[data-mail]").forEach((el) => {
      const t = MAIL[el.dataset.mail] || MAIL.general;
      const href = contactHref(t.subject, t.body);
      el.setAttribute("href", href);
      if (isExternal(href)) {
        el.target = "_blank";
        el.rel = "noopener";
      }
    });

    // Grading cards
    $("#grades").innerHTML = Object.entries(GRADES)
      .map(
        ([k, g]) => `
        <div class="grade-card grade-card-${k.toLowerCase()}">
          <span class="grade grade-${k.toLowerCase()} grade-lg">${k}</span>
          <h3>${esc(g.word)}</h3>
          <p>${esc(g.blurb)}</p>
        </div>`
      )
      .join("");

    // Contact links
    const links = [];
    if (SHOP.email) links.push({ href: mailto(MAIL.general.subject, MAIL.general.body), label: "Email", value: SHOP.email });
    if (instagramUrl()) links.push({ href: instagramUrl(), label: "Instagram", value: `@${handle(SHOP.instagram)}` });
    if (tiktokUrl()) links.push({ href: tiktokUrl(), label: "TikTok", value: `@${handle(SHOP.tiktok)}` });
    if (SHOP.ebayStore) links.push({ href: SHOP.ebayStore, label: "eBay", value: "See my feedback and listings" });
    $("#contact-links").innerHTML = links.length
      ? links
          .map(
            (l) =>
              `<a class="contact-link" href="${esc(l.href)}"${linkAttrs(l.href)}><span class="contact-label">${esc(l.label)}</span><span class="contact-value">${esc(l.value)}</span><span class="contact-arrow" aria-hidden="true">→</span></a>`
          )
          .join("")
      : `<p class="muted">Add your email or socials in <code>inventory.js</code>.</p>`;

    // Hero art
    $("#hero-art").innerHTML = `
      <div class="hero-stage"></div>
      <div class="hero-device hero-device-back">${deviceSVG("nano-touch", "Silver")}</div>
      <div class="hero-device hero-device-main">${deviceSVG("classic", "Black", "now-playing")}</div>
      <div class="hero-device hero-device-front">${deviceSVG("shuffle", "Green")}</div>`;

    const looksUnset = /@example\.(com|org)$/i.test(SHOP.email) || (!SHOP.email && !SHOP.instagram && !SHOP.tiktok && !SHOP.ebayStore);
    $("#setup-banner").hidden = !looksUnset;
  }

  /* ------------------------------------------------------------------ detail dialog */

  const dialog = $("#detail");

  function actionsHTML(it) {
    const ask = contactHref(`About: ${titleOf(it)} (#${it.id})`, `Hi! I'm interested in the ${titleOf(it)} (${subOf(it)}), listed at ${money(it.price)}.\n\n`);
    const buy = contactHref(
      `I'd like to buy: ${titleOf(it)} (#${it.id})`,
      `Hi! I'd like to buy the ${titleOf(it)} (${subOf(it)}), listed at ${money(it.price)}.\n\nShip to (city, country):\n\n`
    );
    const btn = (href, text, cls) => `<a class="btn ${cls}" href="${esc(href)}"${linkAttrs(href)}>${esc(text)}</a>`;

    if (it.status === "sold") {
      return `<span class="btn btn-disabled" aria-disabled="true">Sold</span>${btn(
        contactHref("Looking for a specific iPod", `Hi! I missed the ${titleOf(it)}. Let me know if you get another one like it.\n`),
        "Find me one like it",
        "btn-ghost"
      )}`;
    }
    if (it.status === "reserved") {
      return `<span class="btn btn-disabled" aria-disabled="true">Reserved</span>${btn(ask, "Join the waitlist", "btn-ghost")}`;
    }
    if (it.ebayUrl) {
      return btn(it.ebayUrl, "Buy on eBay", "btn-accent") + btn(ask, "Ask a question", "btn-ghost");
    }
    const viaEmail = !!SHOP.email;
    return btn(buy, viaEmail ? "Message to buy" : "Message me to buy", "btn-accent") + (viaEmail && instagramUrl() ? btn(instagramUrl(), "DM on Instagram", "btn-ghost") : "");
  }

  function mediaHTML(it) {
    if (!it.photos.length) {
      return `<div class="detail-art" style="--tint:${hexFor(it.color)}">${deviceSVG(it.shape, it.color)}<p class="detail-art-note">Illustration. Real photos on request.</p></div>`;
    }
    const thumbs =
      it.photos.length > 1
        ? `<div class="thumbs">${it.photos
            .map(
              (src, i) =>
                `<button type="button" class="thumb" data-src="${esc(src)}" data-alt="${esc(titleOf(it))}, photo ${i + 1}" aria-label="Photo ${i + 1}" aria-pressed="${i === 0}"><img src="${esc(src)}" alt="" loading="lazy"></button>`
            )
            .join("")}</div>`
        : "";
    return `<div class="detail-photos"><img class="detail-main-photo" src="${esc(it.photos[0])}" alt="${esc(titleOf(it))}, photo 1">${thumbs}</div>`;
  }

  function detailHTML(it) {
    const g = GRADES[it.grade];
    const listed = shortDate(it.listed);
    return `
      <div class="detail-media">${mediaHTML(it)}</div>
      <div class="detail-info">
        <p class="eyebrow">${esc(FAMILIES[it.family].label)}${listed ? ` · Listed ${esc(listed)}` : ""}</p>
        <h2 id="detail-title" tabindex="-1" autofocus>${esc(titleOf(it))}</h2>
        <p class="detail-sub">${esc(subOf(it))}</p>
        <div class="detail-price-row">
          <span class="price price-lg">${money(it.price)}</span>
          ${it.status === "available" ? `<span class="status-pill status-available">Available</span>` : statusPill(it)}
        </div>

        <div class="grade-box grade-box-${it.grade.toLowerCase()}">
          ${gradePill(it, true)}
          <p>${esc(g.blurb)}</p>
        </div>

        ${it.description ? `<p class="detail-desc">${esc(it.description)}</p>` : ""}

        ${
          it.upgrades.length
            ? `<h3 class="detail-h">Upgrades &amp; work done</h3><ul class="tick-list">${it.upgrades.map((u) => `<li>${esc(u)}</li>`).join("")}</ul>`
            : ""
        }
        ${it.included.length ? `<h3 class="detail-h">In the box</h3><p class="detail-box">${it.included.map(esc).join(", ")}</p>` : ""}

        <ul class="assure">
          <li>Passed the full checklist</li>
          <li>${esc(String(SHOP.warrantyDays))}-day warranty</li>
          <li>Wiped and restored</li>
        </ul>

        <div class="detail-actions">${actionsHTML(it)}</div>
        <button type="button" class="link-btn" data-copy>Copy link to this iPod</button>
      </div>`;
  }

  let currentId = null;

  // Some hosts (like sandboxed previews) refuse history changes; the dialog works either way.
  function setHash(hash) {
    try {
      history.replaceState(null, "", hash || location.pathname + location.search);
    } catch {
      /* leave the address as it is */
    }
  }

  function openDetail(it) {
    currentId = it.id;
    $("#detail-content").innerHTML = detailHTML(it);
    if (!dialog.open) dialog.showModal();
    dialog.scrollTop = 0;
    $("#detail-content").scrollTop = 0;
  }

  function itemFromHash() {
    const m = location.hash.match(/^#ipod-(.+)$/);
    if (!m) return null;
    let id = m[1];
    try {
      id = decodeURIComponent(id);
    } catch {
      /* keep raw */
    }
    return ITEMS.find((it) => it.id === id) || null;
  }

  function syncDialogToHash() {
    const it = itemFromHash();
    if (it) {
      if (!(dialog.open && currentId === it.id)) openDetail(it);
    } else if (dialog.open) {
      dialog.close();
    }
  }

  dialog.addEventListener("close", () => {
    currentId = null;
    if (/^#ipod-/.test(location.hash)) setHash("");
  });

  dialog.addEventListener("click", async (e) => {
    if (e.target === dialog || e.target.closest("[data-close]")) {
      dialog.close();
      return;
    }
    const thumb = e.target.closest(".thumb");
    if (thumb) {
      const main = $(".detail-main-photo", dialog);
      main.src = thumb.dataset.src;
      main.alt = thumb.dataset.alt;
      $$(".thumb", dialog).forEach((t) => t.setAttribute("aria-pressed", String(t === thumb)));
      return;
    }
    const copy = e.target.closest("[data-copy]");
    if (copy) {
      try {
        await navigator.clipboard.writeText(location.href);
        copy.textContent = "Link copied";
      } catch {
        copy.textContent = "Couldn't copy. Use the link in your address bar.";
      }
      setTimeout(() => (copy.textContent = "Copy link to this iPod"), 2400);
    }
  });

  /* ------------------------------------------------------------------ events */

  $("#grid").addEventListener("click", (e) => {
    const link = e.target.closest(".card-link");
    if (!link) return;
    const it = ITEMS.find((x) => `#ipod-${encodeURIComponent(x.id)}` === link.getAttribute("href"));
    if (!it) return;
    e.preventDefault();
    openDetail(it);
    setHash(`#ipod-${encodeURIComponent(it.id)}`);
  });

  $("#filters").addEventListener("click", (e) => {
    const chip = e.target.closest(".chip");
    if (!chip) return;
    state.family = chip.dataset.family;
    render();
  });

  $("#show-sold").addEventListener("change", (e) => {
    state.showSold = e.target.checked;
    render();
  });

  $("#sort").addEventListener("change", (e) => {
    state.sort = e.target.value;
    renderGrid();
  });

  window.addEventListener("hashchange", syncDialogToHash);

  renderStatic();
  render();
  syncDialogToHash();
})();
