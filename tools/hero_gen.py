#!/usr/bin/env python3
"""Writes the animated README banner (docs/hero-light.svg and docs/hero-dark.svg).

One phone charges: its battery fills from 45% to 80% to 100%. Each milestone travels over the
relay to the second phone, which shows the alert. The animation is plain CSS inside the SVG,
so it plays in GitHub's README (an <img>), loops every 9 s, and has no external files.

    python3 tools/hero_gen.py
"""
import os

THEMES = {
    "light": dict(bg="#FBFCF8", card="#FFFFFF", border="#E2E8F0", fg="#0F172A", muted="#64748B",
                  primary="#AFF33E", on_primary="#000000", charge="#22C55E", label="#166534",
                  chip="#F0FDF4", chip_fg="#166534", screen="#FBFCF8", phone="#0F172A", track="#F1F5F9",
                  glow="#D9F99D"),
    "dark": dict(bg="#020617", card="#0F172A", border="#1E293B", fg="#F8FAFC", muted="#94A3B8",
                 primary="#96F104", on_primary="#020617", charge="#AFF33E", label="#AFF33E",
                 chip="#0F172A", chip_fg="#AFF33E", screen="#020617", phone="#000000", track="#1E293B",
                 glow="#1A2E05"),
}

SERIF = "Inter, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif"
SANS = "Inter, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif"
MONO = "'JetBrains Mono', ui-monospace, Menlo, Consolas, monospace"


def svg(t):
    c = t
    return f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 880 340" width="880" height="340" role="img" aria-label="ChargeWatch: when one phone charges, the other gets a notification at 80% and when it is full">
<title>ChargeWatch: know when your other phone is charged</title>
<style>
  .serif {{ font-family: {SERIF}; }}
  .sans {{ font-family: {SANS}; }}
  .mono {{ font-family: {MONO}; }}
  /* 9 s loop: 0-36% charge to 80, 40% alert one, 40-68% charge to 100, 72% alert two. */
  .fill {{ transform-box: fill-box; transform-origin: left center; animation: fill 9s ease-in-out infinite; }}
  @keyframes fill {{
    0% {{ transform: scaleX(0.45); }} 36% {{ transform: scaleX(0.8); }} 40% {{ transform: scaleX(0.8); }}
    68% {{ transform: scaleX(1); }} 95% {{ transform: scaleX(1); }} 100% {{ transform: scaleX(0.45); }}
  }}
  .p45 {{ animation: p45 9s linear infinite; }}
  .p80 {{ animation: p80 9s linear infinite; }}
  .p100 {{ animation: p100 9s linear infinite; }}
  @keyframes p45 {{ 0%, 30% {{ opacity: 1; }} 33%, 97% {{ opacity: 0; }} 100% {{ opacity: 1; }} }}
  @keyframes p80 {{ 0%, 33% {{ opacity: 0; }} 36%, 62% {{ opacity: 1; }} 65%, 100% {{ opacity: 0; }} }}
  @keyframes p100 {{ 0%, 65% {{ opacity: 0; }} 68%, 95% {{ opacity: 1; }} 98%, 100% {{ opacity: 0; }} }}
  .bolt {{ animation: bolt 1.5s ease-in-out infinite; transform-box: fill-box; transform-origin: center; }}
  @keyframes bolt {{ 0%, 100% {{ opacity: 1; }} 50% {{ opacity: 0.45; }} }}
  .pulse {{ animation: pulse 9s linear infinite; }}
  @keyframes pulse {{
    0%, 35% {{ transform: translateX(0); opacity: 0; }} 36% {{ opacity: 1; }} 40% {{ transform: translateX(78px); opacity: 1; }}
    41%, 67% {{ transform: translateX(0); opacity: 0; }} 68% {{ opacity: 1; }} 72% {{ transform: translateX(78px); opacity: 1; }}
    73%, 100% {{ transform: translateX(0); opacity: 0; }}
  }}
  .n1 {{ animation: n1 9s ease-out infinite; }}
  .n2 {{ animation: n2 9s ease-out infinite; }}
  @keyframes n1 {{
    0%, 40% {{ transform: translateY(-14px); opacity: 0; }} 43%, 63% {{ transform: translateY(0); opacity: 1; }}
    66%, 100% {{ transform: translateY(-14px); opacity: 0; }}
  }}
  @keyframes n2 {{
    0%, 72% {{ transform: translateY(-14px); opacity: 0; }} 75%, 95% {{ transform: translateY(0); opacity: 1; }}
    98%, 100% {{ transform: translateY(-14px); opacity: 0; }}
  }}
  .wire {{ animation: wire 1.2s linear infinite; }}
  @keyframes wire {{ to {{ stroke-dashoffset: -12; }} }}
  @media (prefers-reduced-motion: reduce) {{
    .fill, .p45, .p80, .p100, .bolt, .pulse, .n1, .n2, .wire {{ animation: none; }}
    .p45, .p100, .n1 {{ opacity: 0; }} .p80, .n2 {{ opacity: 1; }}
  }}
</style>
<defs>
  <radialGradient id="glow" cx="78%" cy="20%" r="60%">
    <stop offset="0" stop-color="{c['glow']}" stop-opacity="0.9"/>
    <stop offset="1" stop-color="{c['glow']}" stop-opacity="0"/>
  </radialGradient>
  <filter id="shadow" x="-20%" y="-20%" width="140%" height="140%">
    <feDropShadow dx="1" dy="2" stdDeviation="6" flood-color="#000000" flood-opacity="0.10"/>
  </filter>
</defs>
<rect width="880" height="340" rx="20" fill="{c['bg']}"/>
<rect width="880" height="340" rx="20" fill="url(#glow)"/>

<!-- What it is -->
<text x="44" y="104" class="sans" font-size="12" font-weight="600" letter-spacing="2.2" fill="{c['label']}">ANDROID · FREE · OPEN SOURCE</text>
<text x="42" y="160" class="serif" font-size="50" font-weight="800" letter-spacing="-1.5" fill="{c['fg']}">ChargeWatch</text>
<text x="44" y="200" class="sans" font-size="19" fill="{c['muted']}">Know when your other phone is charged.</text>
<text x="44" y="226" class="sans" font-size="15" fill="{c['muted']}">Alerts at 80% and at 100%, on the phone in your hand.</text>
<g class="sans" font-size="13" font-weight="500">
  <rect x="44" y="250" width="106" height="30" rx="15" fill="{c['chip']}" stroke="{c['border']}"/>
  <text x="97" y="270" text-anchor="middle" fill="{c['chip_fg']}">QR pairing</text>
  <rect x="160" y="250" width="110" height="30" rx="15" fill="{c['chip']}" stroke="{c['border']}"/>
  <text x="215" y="270" text-anchor="middle" fill="{c['chip_fg']}">No account</text>
  <rect x="280" y="250" width="96" height="30" rx="15" fill="{c['chip']}" stroke="{c['border']}"/>
  <text x="328" y="270" text-anchor="middle" fill="{c['chip_fg']}">&lt; 500 KB</text>
</g>

<!-- Phone 1: charging -->
<g filter="url(#shadow)">
  <rect x="452" y="40" width="150" height="262" rx="24" fill="{c['phone']}"/>
  <rect x="459" y="47" width="136" height="248" rx="18" fill="{c['screen']}"/>
</g>
<text x="527" y="86" text-anchor="middle" class="sans" font-size="13" font-weight="600" fill="{c['fg']}">Poco X8</text>
<g transform="translate(482 112)">
  <rect x="0" y="0" width="80" height="42" rx="10" fill="none" stroke="{c['fg']}" stroke-width="4"/>
  <rect x="83" y="13" width="6" height="16" rx="2" fill="{c['fg']}"/>
  <rect class="fill" x="6" y="6" width="68" height="30" rx="5" fill="{c['charge']}"/>
  <path class="bolt" d="M43 9 L31 23 H39 L35 33 L48 18 H40 Z" fill="{c['screen']}" stroke="{c['fg']}" stroke-width="1.6" stroke-linejoin="round"/>
</g>
<g class="serif" font-size="30" font-weight="700" fill="{c['fg']}" text-anchor="middle">
  <text class="p45" x="527" y="196">45%</text>
  <text class="p80" x="527" y="196">80%</text>
  <text class="p100" x="527" y="196">100%</text>
</g>
<circle cx="497" cy="222" r="4" fill="{c['charge']}"/>
<text x="507" y="226" class="sans" font-size="12" fill="{c['muted']}">Charging</text>
<path class="wire" d="M527 302 V332" stroke="{c['muted']}" stroke-width="3" stroke-dasharray="6 6" fill="none"/>

<!-- The relay between them -->
<line x1="610" y1="160" x2="692" y2="160" stroke="{c['border']}" stroke-width="3" stroke-dasharray="2 7" stroke-linecap="round"/>
<circle class="pulse" cx="612" cy="160" r="6" fill="{c['primary']}"/>
<text x="651" y="186" text-anchor="middle" class="mono" font-size="10" fill="{c['muted']}">ntfy relay</text>

<!-- Phone 2: the one in your hand -->
<g filter="url(#shadow)">
  <rect x="698" y="40" width="150" height="262" rx="24" fill="{c['phone']}"/>
  <rect x="705" y="47" width="136" height="248" rx="18" fill="{c['screen']}"/>
</g>
<text x="773" y="96" text-anchor="middle" class="sans" font-size="34" font-weight="300" fill="{c['fg']}">1:03</text>
<text x="773" y="116" text-anchor="middle" class="sans" font-size="10" fill="{c['muted']}">Tue, 10 Nov</text>
<g class="n1">
  <rect x="712" y="134" width="122" height="54" rx="10" fill="{c['card']}" stroke="{c['border']}"/>
  <rect x="720" y="144" width="16" height="16" rx="4" fill="{c['primary']}"/>
  <text x="728" y="156" text-anchor="middle" class="sans" font-size="7" font-weight="700" fill="{c['on_primary']}">CW</text>
  <text x="742" y="155" class="sans" font-size="11" font-weight="600" fill="{c['fg']}">Poco X8 is 80%</text>
  <text x="742" y="172" class="sans" font-size="10" fill="{c['muted']}">Still charging</text>
</g>
<g class="n2">
  <rect x="712" y="134" width="122" height="54" rx="10" fill="{c['card']}" stroke="{c['border']}"/>
  <rect x="720" y="144" width="16" height="16" rx="4" fill="{c['primary']}"/>
  <text x="728" y="156" text-anchor="middle" class="sans" font-size="7" font-weight="700" fill="{c['on_primary']}">CW</text>
  <text x="742" y="155" class="sans" font-size="11" font-weight="600" fill="{c['fg']}">Poco X8 is full</text>
  <text x="742" y="172" class="sans" font-size="10" fill="{c['muted']}">You can use it now.</text>
</g>
<text x="773" y="282" text-anchor="middle" class="sans" font-size="10" fill="{c['muted']}">the phone in your hand</text>
</svg>
'''


if __name__ == "__main__":
    out = os.path.join(os.path.dirname(__file__), "..", "docs")
    os.makedirs(out, exist_ok=True)
    for name, theme in THEMES.items():
        with open(os.path.join(out, f"hero-{name}.svg"), "w") as f:
            f.write(svg(theme))
    print("wrote docs/hero-light.svg and docs/hero-dark.svg")
