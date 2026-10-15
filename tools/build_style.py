"""Generates app/src/main/assets/styles/trazo-night.json, Trazo's own dark map style.

Data: OpenFreeMap vector tiles (OpenMapTiles schema, OpenStreetMap data). Free, no API key,
no request limits; attribution is required and is shown by MapLibre from the source.
Run: python3 tools/build_style.py
"""
import json, os

BG = "#0A0D12"
FONT = ["Noto Sans Regular"]
FONT_B = ["Noto Sans Bold"]
FONT_I = ["Noto Sans Italic"]

def zexp(base, *stops):
    out = ["interpolate", ["exponential", base], ["zoom"]]
    for z, v in stops:
        out += [z, v]
    return out

def lin(*stops):
    out = ["interpolate", ["linear"], ["zoom"]]
    for z, v in stops:
        out += [z, v]
    return out

NAME = ["coalesce", ["get", "name:es"], ["get", "name:latin"], ["get", "name"]]

layers = []
def L(**kw):
    layers.append(kw)

L(id="background", type="background", paint={"background-color": BG})

# ---- ground
L(id="landcover-wood", type="fill", source="openmaptiles", **{"source-layer": "landcover"},
  filter=["==", ["get", "class"], "wood"], paint={"fill-color": "#0C1712", "fill-opacity": lin((6, 0.5), (12, 1))})
L(id="landcover-grass", type="fill", source="openmaptiles", **{"source-layer": "landcover"},
  filter=["match", ["get", "class"], ["grass", "farmland", "wetland"], True, False],
  paint={"fill-color": "#0D1511", "fill-opacity": 0.8})
L(id="landcover-sand-ice", type="fill", source="openmaptiles", **{"source-layer": "landcover"},
  filter=["match", ["get", "class"], ["sand", "ice", "rock"], True, False],
  paint={"fill-color": "#13161C", "fill-opacity": 0.7})
L(id="landuse-residential", type="fill", source="openmaptiles", **{"source-layer": "landuse"},
  filter=["match", ["get", "class"], ["residential", "suburb", "neighbourhood"], True, False],
  paint={"fill-color": "#0E1117", "fill-opacity": lin((8, 0.4), (13, 1))})
L(id="landuse-urban", type="fill", source="openmaptiles", **{"source-layer": "landuse"},
  filter=["match", ["get", "class"], ["commercial", "retail", "industrial", "railway"], True, False],
  paint={"fill-color": "#11141B"})
L(id="landuse-civic", type="fill", source="openmaptiles", **{"source-layer": "landuse"},
  filter=["match", ["get", "class"], ["hospital", "school", "university", "college", "kindergarten"], True, False],
  paint={"fill-color": "#141320"})
L(id="landuse-cemetery", type="fill", source="openmaptiles", **{"source-layer": "landuse"},
  filter=["==", ["get", "class"], "cemetery"], paint={"fill-color": "#0F1612"})
L(id="park", type="fill", source="openmaptiles", **{"source-layer": "park"},
  paint={"fill-color": "#0D1A14", "fill-outline-color": "#12241B"})
L(id="landuse-pitch", type="fill", source="openmaptiles", **{"source-layer": "landuse"},
  filter=["match", ["get", "class"], ["pitch", "stadium", "playground"], True, False],
  paint={"fill-color": "#10201A"})

# ---- water
L(id="water", type="fill", source="openmaptiles", **{"source-layer": "water"},
  filter=["!=", ["get", "brunnel"], "tunnel"], paint={"fill-color": "#06141F"})
L(id="water-shore", type="line", source="openmaptiles", **{"source-layer": "water"}, minzoom=10,
  paint={"line-color": "#0C2638", "line-width": lin((10, 0.5), (16, 1.5))})
L(id="waterway", type="line", source="openmaptiles", **{"source-layer": "waterway"},
  filter=["!=", ["get", "brunnel"], "tunnel"],
  paint={"line-color": "#0A2232", "line-width": zexp(1.4, (8, 0.5), (14, 2), (18, 6))})

# ---- aeroway
L(id="aeroway-area", type="fill", source="openmaptiles", **{"source-layer": "aeroway"}, minzoom=11,
  filter=["match", ["geometry-type"], ["Polygon", "MultiPolygon"], True, False], paint={"fill-color": "#12151C"})
L(id="aeroway-runway", type="line", source="openmaptiles", **{"source-layer": "aeroway"}, minzoom=11,
  filter=["match", ["get", "class"], ["runway", "taxiway"], True, False],
  paint={"line-color": "#1E232C", "line-width": zexp(1.5, (11, 2), (17, 50))})

# ---- buildings (flat at mid zoom, extruded in 3D at street level)
L(id="building", type="fill", source="openmaptiles", **{"source-layer": "building"}, minzoom=13, maxzoom=15.5,
  paint={"fill-color": "#141920", "fill-outline-color": "#1C222C", "fill-opacity": lin((13, 0), (14, 1))})
L(id="building-3d", type="fill-extrusion", source="openmaptiles", **{"source-layer": "building"}, minzoom=15,
  filter=["!=", ["get", "hide_3d"], True],
  paint={
      "fill-extrusion-color": ["interpolate", ["linear"], ["coalesce", ["get", "render_height"], 6],
                               0, "#141920", 40, "#1A2029", 120, "#202733"],
      "fill-extrusion-height": lin((15, 0), (15.6, ["coalesce", ["get", "render_height"], 6])),
      "fill-extrusion-base": ["coalesce", ["get", "render_min_height"], 0],
      "fill-extrusion-opacity": 0.92,
  })

# ---- roads
ROAD_CLASSES = [
    # class, core colour, casing colour, width stops
    ("service", ["service", "track"], "#1A1E25", "#07090C", [(14, 0.5), (16, 3), (20, 14)]),
    ("minor", ["minor"], "#20252D", "#07090C", [(12, 0.5), (14, 1.6), (16, 5), (20, 26)]),
    ("tertiary", ["tertiary"], "#282E37", "#06080B", [(10, 0.5), (13, 1.6), (16, 7), (20, 34)]),
    ("secondary", ["secondary"], "#2F3640", "#05070A", [(8, 0.5), (12, 1.6), (16, 8), (20, 38)]),
    ("primary", ["primary"], "#38414D", "#05070A", [(7, 0.6), (11, 1.8), (16, 10), (20, 44)]),
    ("trunk", ["trunk"], "#3E4A58", "#04060A", [(5, 0.6), (10, 1.8), (16, 11), (20, 46)]),
    ("motorway", ["motorway"], "#3F5A73", "#03050A", [(4, 0.8), (10, 2.2), (16, 12), (20, 50)]),
]

def road_filter(classes, brunnel=None):
    f = ["all", ["match", ["get", "class"], classes, True, False]]
    if brunnel == "tunnel":
        f.append(["==", ["get", "brunnel"], "tunnel"])
    elif brunnel == "bridge":
        f.append(["==", ["get", "brunnel"], "bridge"])
    else:
        f.append(["!", ["match", ["get", "brunnel"], ["tunnel", "bridge"], True, False]])
    return f

def widths(stops, extra=0.0, factor=1.0):
    return zexp(1.5, *[(z, round(w * factor + extra, 2)) for z, w in stops])

for brunnel in ["tunnel", None, "bridge"]:
    suffix = "" if brunnel is None else "-" + brunnel
    for name, classes, color, casing, stops in ROAD_CLASSES:
        minz = stops[0][0]
        L(id=f"road-{name}{suffix}-casing", type="line", source="openmaptiles", **{"source-layer": "transportation"},
          minzoom=max(minz, 11) if name in ("service", "minor") else minz,
          filter=road_filter(classes, brunnel),
          layout={"line-cap": "round", "line-join": "round"},
          paint={"line-color": casing, "line-width": widths(stops, extra=1.5, factor=1.25),
                 "line-opacity": 0.5 if brunnel == "tunnel" else 1})
    for name, classes, color, casing, stops in ROAD_CLASSES:
        minz = stops[0][0]
        paint = {"line-color": color, "line-width": widths(stops)}
        if brunnel == "tunnel":
            paint["line-opacity"] = 0.45
        L(id=f"road-{name}{suffix}", type="line", source="openmaptiles", **{"source-layer": "transportation"},
          minzoom=minz, filter=road_filter(classes, brunnel),
          layout={"line-cap": "round", "line-join": "round"}, paint=paint)

# Faint luminous centre line on fast roads: the "game map" accent.
L(id="road-motorway-glow", type="line", source="openmaptiles", **{"source-layer": "transportation"}, minzoom=6,
  filter=["all", ["match", ["get", "class"], ["motorway", "trunk"], True, False], ["!=", ["get", "brunnel"], "tunnel"]],
  layout={"line-cap": "round", "line-join": "round"},
  paint={"line-color": "#6FA8D6", "line-opacity": lin((6, 0.15), (12, 0.28), (17, 0.18)),
         "line-width": zexp(1.5, (6, 0.3), (12, 0.8), (18, 2.5))})
L(id="path", type="line", source="openmaptiles", **{"source-layer": "transportation"}, minzoom=15,
  filter=["==", ["get", "class"], "path"],
  paint={"line-color": "#262B33", "line-dasharray": [1.5, 1.5], "line-width": lin((15, 0.6), (18, 1.6))})
L(id="rail", type="line", source="openmaptiles", **{"source-layer": "transportation"}, minzoom=11,
  filter=["match", ["get", "class"], ["rail", "transit"], True, False],
  paint={"line-color": "#2A2F38", "line-width": lin((11, 0.6), (17, 2))})
L(id="rail-hatch", type="line", source="openmaptiles", **{"source-layer": "transportation"}, minzoom=14,
  filter=["match", ["get", "class"], ["rail", "transit"], True, False],
  paint={"line-color": "#2A2F38", "line-dasharray": [0.2, 6], "line-width": lin((14, 3), (18, 8))})

# ---- boundaries
L(id="boundary-country", type="line", source="openmaptiles", **{"source-layer": "boundary"},
  filter=["all", ["==", ["get", "admin_level"], 2], ["!=", ["get", "maritime"], 1]],
  paint={"line-color": "#4A5161", "line-width": lin((2, 0.6), (10, 1.6))})
L(id="boundary-state", type="line", source="openmaptiles", **{"source-layer": "boundary"}, minzoom=4,
  filter=["all", ["==", ["get", "admin_level"], 4], ["!=", ["get", "maritime"], 1]],
  paint={"line-color": "#30353F", "line-dasharray": [2, 2], "line-width": lin((4, 0.5), (10, 1.2))})

# ---- labels
HALO = {"text-halo-color": BG, "text-halo-width": 1.4, "text-halo-blur": 0.4}
L(id="water-name", type="symbol", source="openmaptiles", **{"source-layer": "water_name"},
  layout={"text-field": NAME, "text-font": FONT_I, "text-size": lin((8, 11), (16, 14)), "text-max-width": 8},
  paint={"text-color": "#3C6886", **HALO})
L(id="road-label", type="symbol", source="openmaptiles", **{"source-layer": "transportation_name"}, minzoom=13,
  filter=["match", ["get", "class"], ["motorway", "trunk", "primary", "secondary", "tertiary", "minor", "service"], True, False],
  layout={"symbol-placement": "line", "text-field": NAME, "text-font": FONT,
          "text-size": lin((13, 10), (16, 12), (19, 14)), "text-letter-spacing": 0.02,
          "text-rotation-alignment": "map", "text-pitch-alignment": "viewport", "text-max-angle": 30},
  paint={"text-color": "#9AA4B4", **HALO})
L(id="road-ref", type="symbol", source="openmaptiles", **{"source-layer": "transportation_name"}, minzoom=8, maxzoom=14,
  filter=["all", ["has", "ref"], ["match", ["get", "class"], ["motorway", "trunk"], True, False]],
  layout={"symbol-placement": "line", "symbol-spacing": 400, "text-field": ["get", "ref"], "text-font": FONT_B,
          "text-size": 10, "text-rotation-alignment": "viewport"},
  paint={"text-color": "#7FB2DA", **HALO})
L(id="housenumber", type="symbol", source="openmaptiles", **{"source-layer": "housenumber"}, minzoom=17.5,
  layout={"text-field": ["get", "housenumber"], "text-font": FONT, "text-size": 10},
  paint={"text-color": "#525A67", **HALO})
L(id="poi", type="symbol", source="openmaptiles", **{"source-layer": "poi"}, minzoom=15,
  filter=["all", ["has", "name"], ["<=", ["coalesce", ["get", "rank"], 99], 25]],
  layout={"text-field": NAME, "text-font": FONT, "text-size": 11, "text-max-width": 8,
          "text-variable-anchor": ["center", "top", "bottom"], "text-optional": True},
  paint={"text-color": ["match", ["get", "class"],
                        ["park", "garden"], "#4E8A68",
                        ["fuel", "car", "parking"], "#7FB2DA",
                        ["hospital", "pharmacy"], "#C77B8A",
                        "#7A8392"], **HALO})
L(id="airport-label", type="symbol", source="openmaptiles", **{"source-layer": "aerodrome_label"}, minzoom=10,
  layout={"text-field": NAME, "text-font": FONT, "text-size": 11}, paint={"text-color": "#8A93A3", **HALO})
L(id="place-neighbourhood", type="symbol", source="openmaptiles", **{"source-layer": "place"}, minzoom=13,
  filter=["match", ["get", "class"], ["neighbourhood", "quarter", "hamlet", "isolated_dwelling"], True, False],
  layout={"text-field": NAME, "text-font": FONT, "text-size": lin((13, 10), (16, 12)),
          "text-transform": "uppercase", "text-letter-spacing": 0.12, "text-max-width": 7},
  paint={"text-color": "#6E7787", **HALO})
L(id="place-suburb", type="symbol", source="openmaptiles", **{"source-layer": "place"}, minzoom=11, maxzoom=16,
  filter=["==", ["get", "class"], "suburb"],
  layout={"text-field": NAME, "text-font": FONT, "text-size": lin((11, 11), (15, 13)),
          "text-transform": "uppercase", "text-letter-spacing": 0.14, "text-max-width": 7},
  paint={"text-color": "#8790A0", **HALO})
L(id="place-village", type="symbol", source="openmaptiles", **{"source-layer": "place"}, minzoom=9,
  filter=["==", ["get", "class"], "village"],
  layout={"text-field": NAME, "text-font": FONT, "text-size": lin((9, 11), (14, 14)), "text-max-width": 8},
  paint={"text-color": "#B2BAC7", **HALO})
L(id="place-town", type="symbol", source="openmaptiles", **{"source-layer": "place"}, minzoom=7,
  filter=["==", ["get", "class"], "town"],
  layout={"text-field": NAME, "text-font": FONT, "text-size": lin((7, 11), (14, 16)), "text-max-width": 8},
  paint={"text-color": "#CDD3DD", **HALO})
L(id="place-city", type="symbol", source="openmaptiles", **{"source-layer": "place"}, minzoom=4, maxzoom=15,
  filter=["==", ["get", "class"], "city"],
  layout={"text-field": NAME, "text-font": FONT_B, "text-size": lin((4, 11), (12, 18)), "text-max-width": 8},
  paint={"text-color": "#E8ECF2", **HALO})
L(id="place-state", type="symbol", source="openmaptiles", **{"source-layer": "place"}, minzoom=4, maxzoom=8,
  filter=["==", ["get", "class"], "state"],
  layout={"text-field": NAME, "text-font": FONT, "text-size": 11, "text-transform": "uppercase", "text-letter-spacing": 0.2},
  paint={"text-color": "#5F6876", **HALO})
L(id="place-country", type="symbol", source="openmaptiles", **{"source-layer": "place"}, maxzoom=8,
  filter=["==", ["get", "class"], "country"],
  layout={"text-field": NAME, "text-font": FONT_B, "text-size": lin((2, 11), (6, 15)), "text-transform": "uppercase",
          "text-letter-spacing": 0.15, "text-max-width": 7},
  paint={"text-color": "#AAB2BF", **HALO})

style = {
    "version": 8,
    "name": "Trazo Noche",
    "metadata": {"trazo:source": "tools/build_style.py"},
    "sources": {
        "openmaptiles": {
            "type": "vector",
            "url": "https://tiles.openfreemap.org/planet",
            "attribution": "<a href=\"https://openfreemap.org\">OpenFreeMap</a> © <a href=\"https://www.openmaptiles.org/\">OpenMapTiles</a> Datos de <a href=\"https://www.openstreetmap.org/copyright\">OpenStreetMap</a>",
        }
    },
    "glyphs": "https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf",
    "layers": layers,
}
out = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "styles", "trazo-night.json")
with open(out, "w") as f:
    json.dump(style, f, ensure_ascii=False, separators=(",", ":"))
print(len(layers), "layers ->", os.path.normpath(out))
