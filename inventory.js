/* =====================================================================
   EDIT THIS FILE TO RUN YOUR SHOP
   ---------------------------------------------------------------------
   1. SHOP      -> your shop name and how people reach you.
   2. INVENTORY -> one entry per iPod. Add, edit, or delete entries.

   The iPods below are SAMPLE listings so you can see how the site looks.
   Replace them with your real stock before you share the link.
   ===================================================================== */

window.SHOP = {
  name: "Wheelhouse",
  tagline: "Restored iPods, tested and graded honestly.",

  // How buyers contact you. Leave anything you don't use as "".
  email: "you@example.com",      // used for "Message to buy" buttons
  instagram: "",                 // just the handle, e.g. "wheelhouse.ipods"
  tiktok: "",                    // just the handle, e.g. "wheelhouse.ipods"
  ebayStore: "",                 // full link to your eBay seller page

  warrantyDays: 30,              // shown on the site; only promise what you'll honour
  shipsFrom: "",                 // e.g. "Ships from Austin, TX" (optional)
  currency: "USD",               // e.g. "GBP", "EUR", "CAD", "AUD"
  locale: "en-US",               // e.g. "en-GB" for £ formatting
};

/* ---------------------------------------------------------------------
   One block per iPod. Fields:

   id          short unique name, used in the share link (letters, numbers, dashes)
   family      "classic" (includes iPod video) | "nano" | "mini" | "shuffle" | "touch"
   shape       optional, changes the drawing when there are no photos:
               "classic" | "nano" | "nano-fat" (3rd gen) | "nano-touch" (6th/7th gen)
               | "mini" | "shuffle" | "touch"
   name        e.g. "iPod classic"
   generation  e.g. "7th gen (2009)"
   capacity    e.g. "256GB"
   color       e.g. "Black", "Silver", "Blue", "Pink" (also tints the drawing)
   grade       "A" | "B" | "C"   (definitions are on the site)
   price       number, no currency sign
   status      "available" | "reserved" | "sold"
   listed      date you listed it, "YYYY-MM-DD" (used by "Newest" sort)
   upgrades    list of what you replaced or improved
   description a couple of honest sentences, including any flaws
   included    what's in the box
   photos      list of image paths, e.g. ["images/c7-512-front.jpg", "images/c7-512-back.jpg"]
   ebayUrl     link to the eBay listing, if it's also listed there
   --------------------------------------------------------------------- */

window.INVENTORY = [
  {
    id: "c7-512-silver",
    family: "classic",
    name: "iPod classic",
    generation: "7th gen (2009)",
    capacity: "512GB",
    color: "Silver",
    grade: "A",
    price: 279,
    status: "available",
    listed: "2026-10-28",
    upgrades: ["512GB flash storage (iFlash Quad)", "3000mAh battery + thick back", "New faceplate"],
    description:
      "The late-2009 board, rebuilt with silent flash storage and a big battery that runs well past a full day of listening. New faceplate and back plate, so it looks the part too.",
    included: ["iPod", "USB cable"],
    photos: [],
    ebayUrl: "",
  },
  {
    id: "c7-128-black",
    family: "classic",
    name: "iPod classic",
    generation: "7th gen (2009)",
    capacity: "128GB",
    color: "Black",
    grade: "C",
    price: 159,
    status: "available",
    listed: "2026-10-30",
    upgrades: ["128GB flash storage (iFlash Solo)", "New battery"],
    description:
      "Works exactly like an A-grade, but wears its history on the back: deeper scratches and a small ding on the bottom corner. The cheapest way into a flash-upgraded classic.",
    included: ["iPod", "USB cable"],
    photos: [],
    ebayUrl: "",
  },
  {
    id: "v55-256-black",
    family: "classic",
    name: "iPod video",
    generation: "5.5 gen (80GB board)",
    capacity: "256GB",
    color: "Black",
    grade: "B",
    price: 219,
    status: "available",
    listed: "2026-10-24",
    upgrades: ["256GB flash storage (iFlash Solo)", "New high-capacity battery"],
    description:
      "The 5.5 gen with the brighter screen and the 64MB board that copes with big libraries. Light swirl marks on the steel back from normal use; the screen and faceplate are clean.",
    included: ["iPod", "USB cable", "Clear case"],
    photos: [],
    ebayUrl: "",
  },
  {
    id: "c6-80-black",
    family: "classic",
    name: "iPod classic",
    generation: "6th gen",
    capacity: "80GB",
    color: "Black",
    grade: "B",
    price: 129,
    status: "available",
    listed: "2026-10-19",
    upgrades: ["New battery", "Original drive scanned: 0 bad sectors"],
    description:
      "Original hard drive that passed a full surface scan in diagnostics, for anyone who wants the authentic stock experience. Fresh battery fitted.",
    included: ["iPod", "USB cable"],
    photos: [],
    ebayUrl: "",
  },
  {
    id: "n7-16-blue",
    family: "nano",
    shape: "nano-touch",
    name: "iPod nano",
    generation: "7th gen",
    capacity: "16GB",
    color: "Blue",
    grade: "A",
    price: 119,
    status: "available",
    listed: "2026-10-26",
    upgrades: [],
    description:
      "Bluetooth, FM radio and a touchscreen in something the size of a credit card. Barely used, battery run-down tested before listing.",
    included: ["iPod", "Lightning cable"],
    photos: [],
    ebayUrl: "",
  },
  {
    id: "n3-8-green",
    family: "nano",
    shape: "nano-fat",
    name: "iPod nano",
    generation: "3rd gen",
    capacity: "8GB",
    color: "Green",
    grade: "B",
    price: 59,
    status: "available",
    listed: "2026-10-12",
    upgrades: ["New battery"],
    description: "The little 'fatty' nano. Plays music and video and fits in a coin pocket. Small scuffs on two corners.",
    included: ["iPod", "USB cable"],
    photos: [],
    ebayUrl: "",
  },
  {
    id: "mini2-16-pink",
    family: "mini",
    name: "iPod mini",
    generation: "2nd gen",
    capacity: "16GB",
    color: "Pink",
    grade: "B",
    price: 99,
    status: "sold",
    listed: "2026-09-30",
    upgrades: ["16GB flash storage", "New battery"],
    description: "Aluminium mini with the old Microdrive swapped for flash: lighter, silent and much tougher.",
    included: ["iPod", "USB cable"],
    photos: [],
    ebayUrl: "",
  },
  {
    id: "sh2-1-orange",
    family: "shuffle",
    name: "iPod shuffle",
    generation: "2nd gen",
    capacity: "1GB",
    color: "Orange",
    grade: "A",
    price: 35,
    status: "sold",
    listed: "2026-09-22",
    upgrades: [],
    description: "The clip-on shuffle. Great for the gym.",
    included: ["iPod", "USB dock"],
    photos: [],
    ebayUrl: "",
  },
];
