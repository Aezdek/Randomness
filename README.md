# Wheelhouse: a storefront for restored iPods

A fast, no-build website for reselling iPods: a filterable shop, a detail page for every iPod with a link you can share, condition grading, your restore process, a "sell me yours" section for sourcing, an FAQ and contact links. It works on phones and supports dark mode.

> Part of [Aezdek/Randomness](https://github.com/Aezdek/Randomness), where every project too small for its own repo gets a branch.
> Want the business side? Read **[PLAYBOOK.md](PLAYBOOK.md)** for how to source, fix, price, list and ship iPods.

## Files

| File | What it is |
| --- | --- |
| `inventory.js` | **The only file you normally edit.** Shop name, contact links and every iPod you're selling. |
| `index.html` | Page text: hero, process, FAQ and so on. Edit the wording here. |
| `app.js` | Shop logic and the iPod drawings. Grade wording is at the top in `GRADES`. |
| `styles.css` | Look and feel. Change `--accent` near the top to rebrand. |
| `images/` | Your listing photos. |

## Run it locally

Any static file server works:

```bash
python3 -m http.server 8000
```

Then open <http://localhost:8000>. You can also double-click `index.html`.

## Add or change an iPod

Open `inventory.js` and copy one of the `{ ... }` blocks. The fields are explained at the top of the file. The ones that matter most:

```js
{
  id: "c7-256-black",            // goes in the share link: yoursite.com/#ipod-c7-256-black
  family: "classic",             // classic | nano | mini | shuffle | touch
  name: "iPod classic",
  generation: "7th gen (2009)",
  capacity: "256GB",
  color: "Black",
  grade: "A",                    // A | B | C
  price: 229,
  status: "available",           // available | reserved | sold
  upgrades: ["256GB flash storage", "New battery"],
  description: "Honest description, including flaws.",
  photos: ["images/c7-256-front.jpg", "images/c7-256-back.jpg"],
  ebayUrl: "",                   // paste the eBay listing link to show a "Buy on eBay" button
},
```

- **No photos yet?** Leave `photos: []` and the site draws that model in its colour.
- **Sold one?** Change `status` to `"sold"` and leave it up. A shelf of sold iPods tells visitors you're real.
- **The "Setup mode" banner** goes away once you replace `you@example.com` with your real contact details.

## Put it online (free)

**Cloudflare Pages** (works with private repos)
1. Go to dash.cloudflare.com, then Workers & Pages, Create, Pages, and Connect to Git.
2. Pick this repo and this branch. Leave the build command empty and set the output directory to `/`.
3. Deploy. Every push to the branch updates the site, and you can add your own domain.

**GitHub Pages** (needs a public repo on the free plan)
1. Go to Settings, then Pages, and choose "Deploy from a branch".
2. Pick this branch and `/ (root)`, then save.

**Netlify Drop** (no Git needed): drag this folder onto <https://app.netlify.com/drop>.

## Two rules to keep in mind

- **Don't put this site's link in your eBay listings or eBay messages.** eBay doesn't allow steering buyers to buy off-platform. Share the site on Instagram, TikTok and Reddit instead, and link *from* the site *to* eBay.
- **Keep the trademark line in the footer.** You're allowed to say you sell iPods, but don't use Apple's logo or present the shop as official.
