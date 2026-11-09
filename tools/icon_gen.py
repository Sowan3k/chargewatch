"""Generates the ChargeWatch launcher icon: a chrome battery holding glassy 3D "CW" letters.

Writes the Android vector drawables and, if cairosvg is installed, PNG previews, all from
the same geometry.

    python3 tools/icon_gen.py app/src/main/res [preview-dir]
"""
import os
import sys

OUT_RES = sys.argv[1]
OUT_PREV = sys.argv[2] if len(sys.argv) > 2 else None

def rrect(x, y, w, h, r):
    return (f"M{x+r:.2f},{y:.2f} H{x+w-r:.2f} A{r},{r} 0 0 1 {x+w:.2f},{y+r:.2f} V{y+h-r:.2f} "
            f"A{r},{r} 0 0 1 {x+w-r:.2f},{y+h:.2f} H{x+r:.2f} A{r},{r} 0 0 1 {x:.2f},{y+h-r:.2f} "
            f"V{y+r:.2f} A{r},{r} 0 0 1 {x+r:.2f},{y:.2f} Z")

# Battery body and terminal
BX, BY, BW, BH, BR = 24, 37.5, 54, 33, 7.5
BODY = rrect(BX, BY, BW, BH, BR)
NUB = "M78,48 H81 A2,2 0 0 1 83,50 V58 A2,2 0 0 1 81,60 H78 Z"
# Letters as strokes with round caps and joins: a chunky, rounded "bubble" face
C = "M42.35,48.06 A6.8,8 0 1 0 42.35,59.94"
W = "M50.35,46 L55.1,62 L60.35,51.5 L65.6,62 L70.35,46"
LETTERS = C + " " + W

# The body is drawn around x=51. These transforms center the body (not the body plus its
# terminal) on the icon's middle, and leave margin for masks and for launchers such as
# HyperOS that draw themed icons larger than stock Android.
BODY_CX, BODY_CY = BX + BW / 2, BY + BH / 2
# HyperOS themes icons by turning the full-color foreground gray and placing it in a circle
# about 60 units wide, so everything (terminal and shadows included) stays within ~26 of the
# center at these scales.
FG_SCALE = 0.8
MONO_SCALE = 0.78
SHIFT_X = 54 - BODY_CX

CHROME = [(0, "#FFFFFFFF"), (0.35, "#FFAEB7C6"), (0.55, "#FFF2F5FA"), (0.8, "#FF7E889A"), (1, "#FFD9DFE8")]
GLASS = [(0, "#FFFFFFFF"), (0.45, "#FFE3DCFA"), (1, "#FFB8C6EE")]
FILL = [(0, "#26FFFFFF"), (1, "#0AFFFFFF")]

def argb_to_svg(c):
    a = int(c[1:3], 16) / 255; rgb = "#" + c[3:]
    return rgb, a

# ---------- SVG ----------
def svg_grad(id_, y1, y2, stops):
    s = "".join(f'<stop offset="{o}" stop-color="{argb_to_svg(c)[0]}" stop-opacity="{argb_to_svg(c)[1]:.3f}"/>' for o, c in stops)
    return f'<linearGradient id="{id_}" gradientUnits="userSpaceOnUse" x1="0" y1="{y1}" x2="0" y2="{y2}">{s}</linearGradient>'

def stroke_svg(d, width, color, extra=""):
    return f'<path d="{d}" fill="none" stroke="{color}" stroke-width="{width}" stroke-linecap="round" stroke-linejoin="round" {extra}/>'

def fg_svg():
    return (f'<g transform="translate({SHIFT_X},0) translate({BODY_CX},{BODY_CY}) scale({FG_SCALE}) '
            f'translate({-BODY_CX},{-BODY_CY})">{fg_svg_raw()}</g>')


def fg_svg_raw():
    defs = (svg_grad("chromeB", BY, BY + BH, CHROME) + svg_grad("chromeL", 43.5, 64.5, CHROME) +
            svg_grad("glass", 45, 63, GLASS) + svg_grad("fill", BY, BY + BH, FILL))
    parts = [f"<defs>{defs}</defs>",
             f'<g transform="translate(0.8,1.3)">{stroke_svg(BODY, 3.4, "#000000", "stroke-opacity=\'0.35\'")}</g>',
             f'<path d="{BODY}" fill="url(#fill)"/>',
             stroke_svg(BODY, 3.4, "url(#chromeB)"),
             f'<g transform="translate(0.8,1.3)"><path d="{NUB}" fill="#000000" fill-opacity="0.35"/></g>',
             f'<path d="{NUB}" fill="url(#chromeB)"/>',
             f'<g transform="translate(1.6,2.4)">{stroke_svg(LETTERS, 5.4, "#000000", "stroke-opacity=\'0.45\'")}</g>',
             f'<g transform="translate(1.1,1.7)">{stroke_svg(LETTERS, 5.0, "#3E4656")}</g>',
             f'<g transform="translate(0.55,0.85)">{stroke_svg(LETTERS, 5.0, "#7A8496")}</g>',
             stroke_svg(LETTERS, 5.0, "url(#chromeL)"),
             stroke_svg(LETTERS, 3.0, "url(#glass)"),
             f'<g transform="translate(-0.4,-0.5)">{stroke_svg(LETTERS, 0.8, "#FFFFFF", "stroke-opacity=\'0.85\'")}</g>']
    return "".join(parts)

def bg_svg():
    return ('<defs><linearGradient id="bg" gradientUnits="userSpaceOnUse" x1="0" y1="0" x2="108" y2="108">'
            '<stop offset="0" stop-color="#24395A"/><stop offset="0.55" stop-color="#13233A"/><stop offset="1" stop-color="#0A1322"/></linearGradient>'
            '<radialGradient id="shine" gradientUnits="userSpaceOnUse" cx="27" cy="0" r="100">'
            '<stop offset="0" stop-color="#FFFFFF" stop-opacity="0.22"/><stop offset="0.55" stop-color="#FFFFFF" stop-opacity="0"/></radialGradient></defs>'
            '<rect width="108" height="108" fill="url(#bg)"/><rect width="108" height="108" fill="url(#shine)"/>')

def mono_svg(color="#FFFFFF"):
    inner = stroke_svg(BODY, 3.4, color) + f'<path d="{NUB}" fill="{color}"/>' + stroke_svg(LETTERS, 5.0, color)
    return (f'<g transform="translate({SHIFT_X},0) translate({BODY_CX},{BODY_CY}) scale({MONO_SCALE}) '
            f'translate({-BODY_CX},{-BODY_CY})">{inner}</g>')

def preview(size, mask, guide=False):
    # Visible area of an adaptive icon is the middle 72 of 108.
    clip = {"squircle": '<rect x="18" y="18" width="72" height="72" rx="22"/>',
            "circle": '<circle cx="54" cy="54" r="36"/>'}[mask]
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" viewBox="18 18 72 72">'
            f'<defs><clipPath id="m">{clip}</clipPath></defs><g clip-path="url(#m)">{bg_svg()}{fg_svg()}</g></svg>')

def hyperos_preview(size):
    # HyperOS: the full-color foreground, made gray, in a light circle of radius ~30.
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" viewBox="18 18 72 72">'
            f'<defs><clipPath id="c"><circle cx="54" cy="54" r="30"/></clipPath></defs>'
            f'<rect x="18" y="18" width="72" height="72" rx="22" fill="#3A3A3C"/>'
            f'<circle cx="54" cy="54" r="30" fill="#BDBDBD"/>'
            f'<g clip-path="url(#c)">{fg_svg()}</g>'
            f'<line x1="54" y1="22" x2="54" y2="86" stroke="#ff3b30" stroke-width="0.3" stroke-opacity="0.6"/></svg>')


def themed_preview(size, zoom=1.0):
    # Themed icons: the monochrome layer in a circle. HyperOS draws it larger (zoom ~1.35),
    # so the drawing must still fit the circle at that zoom.
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" viewBox="18 18 72 72">'
            f'<defs><clipPath id="c"><circle cx="54" cy="54" r="30"/></clipPath></defs>'
            f'<rect x="18" y="18" width="72" height="72" rx="22" fill="#3A3A3C"/>'
            f'<circle cx="54" cy="54" r="30" fill="#BDBDBD"/>'
            f'<g clip-path="url(#c)"><g transform="translate(54 54) scale({zoom}) translate(-54 -54)">{mono_svg("#2C2C2E")}</g></g>'
            f'<line x1="54" y1="22" x2="54" y2="86" stroke="#ff3b30" stroke-width="0.3" stroke-opacity="0.6"/></svg>')

if OUT_PREV:
    import cairosvg

    os.makedirs(OUT_PREV, exist_ok=True)
    for name, svg in [("squircle", preview(512, "squircle")), ("circle", preview(512, "circle")),
                      ("small", preview(144, "squircle")), ("themed", themed_preview(512)),
                      ("hyperos", hyperos_preview(512))]:
        cairosvg.svg2png(bytestring=svg.encode(), write_to=f"{OUT_PREV}/{name}.png")

# ---------- Android vector drawables ----------
AAPT = 'xmlns:aapt="http://schemas.android.com/aapt"'
def vgrad(attr, y1, y2, stops):
    items = "".join(f'\n                <item android:offset="{o}" android:color="{c}" />' for o, c in stops)
    return (f'\n        <aapt:attr name="android:{attr}">\n            <gradient android:type="linear" android:startX="0" '
            f'android:startY="{y1}" android:endX="0" android:endY="{y2}">{items}\n            </gradient>\n        </aapt:attr>')

def vstroke(d, width, color=None, grad=None):
    head = (f'    <path\n        android:pathData="{d}"\n        android:fillColor="#00000000"\n'
            f'        android:strokeWidth="{width}"\n        android:strokeLineCap="round"\n        android:strokeLineJoin="round"')
    if grad:
        return head + ">" + grad + "\n    </path>\n"
    return head + f'\n        android:strokeColor="{color}" />\n'

def vfill(d, color=None, grad=None):
    head = f'    <path\n        android:pathData="{d}"'
    if grad:
        return head + ">" + grad + "\n    </path>\n"
    return head + f'\n        android:fillColor="{color}" />\n'

def group(dx, dy, body):
    return f'    <group android:translateX="{dx}" android:translateY="{dy}">\n' + body.replace("\n    ", "\n        ").replace("    <path", "        <path", 1) + "    </group>\n"

def outer_open(scale):
    # VectorDrawable groups scale around the pivot first, then translate.
    return (f'    <group android:pivotX="{BODY_CX}" android:pivotY="{BODY_CY}" android:scaleX="{scale}" '
            f'android:scaleY="{scale}" android:translateX="{SHIFT_X}">\n')


fg = (f'<?xml version="1.0" encoding="utf-8"?>\n'
      f'<!-- Generated by tools/icon_gen.py: a chrome battery holding glassy 3D "CW" letters. -->\n'
      f'<vector xmlns:android="http://schemas.android.com/apk/res/android"\n    {AAPT}\n'
      f'    android:width="108dp"\n    android:height="108dp"\n    android:viewportWidth="108"\n    android:viewportHeight="108">\n'
      + outer_open(FG_SCALE)
      + group(0.8, 1.3, vstroke(BODY, 3.4, "#59000000"))
      + vfill(BODY, grad=vgrad("fillColor", BY, BY + BH, FILL))
      + vstroke(BODY, 3.4, grad=vgrad("strokeColor", BY, BY + BH, CHROME))
      + group(0.8, 1.3, vfill(NUB, "#59000000"))
      + vfill(NUB, grad=vgrad("fillColor", BY, BY + BH, CHROME))
      + group(1.6, 2.4, vstroke(LETTERS, 5.4, "#73000000"))
      + group(1.1, 1.7, vstroke(LETTERS, 5.0, "#FF3E4656"))
      + group(0.55, 0.85, vstroke(LETTERS, 5.0, "#FF7A8496"))
      + vstroke(LETTERS, 5.0, grad=vgrad("strokeColor", 43.5, 64.5, CHROME))
      + vstroke(LETTERS, 3.0, grad=vgrad("strokeColor", 45, 63, GLASS))
      + group(-0.4, -0.5, vstroke(LETTERS, 0.8, "#D9FFFFFF"))
      + "    </group>\n</vector>\n")
mono = (f'<?xml version="1.0" encoding="utf-8"?>\n'
        f'<!-- Monochrome launcher layer (themed icons): the battery outline with "CW" inside. -->\n'
        f'<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        f'    android:width="108dp"\n    android:height="108dp"\n    android:viewportWidth="108"\n    android:viewportHeight="108">\n'
        + outer_open(MONO_SCALE)
        + vstroke(BODY, 3.4, "#FFFFFFFF") + vfill(NUB, "#FFFFFFFF") + vstroke(LETTERS, 5.0, "#FFFFFFFF")
        + "    </group>\n</vector>\n")
open(f"{OUT_RES}/drawable/ic_launcher_foreground.xml", "w").write(fg)
open(f"{OUT_RES}/drawable/ic_launcher_monochrome.xml", "w").write(mono)
print("ok")
