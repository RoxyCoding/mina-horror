"""Builds the photographic ground materials of MinaRealism from Poly Haven
scans (CC0, https://polyhaven.com).

Writes to src/client/resources/shaderpacks/MinaRealism/shaders/tex/:
  real_albedo.png   colour (sRGB), 1024 px per material
  real_surface.png  tangent-space normal (OpenGL, rgb) and roughness (a), 512 px per material

Each material occupies one cell holding its mip chain: level 0 on the left,
levels 1-6 stacked to its right from the top; the cell is as tall as the
taller of the two. Every level carries a one-texel border
copied from the opposite edge so bilinear lookups wrap without seams.
lib/realtex.glsl reads this layout; keep the two in step.

Usage: python3 tools/generate_real_textures.py   (needs numpy, Pillow and network access)
"""
import io
import os
import urllib.request

import numpy as np
from PIL import Image

OUT = os.path.join(os.path.dirname(__file__), '..', 'src/client/resources/shaderpacks/MinaRealism/shaders/tex')
CACHE = os.path.join(os.path.dirname(__file__), '.texture-cache')

# Order is the material index used by the shaders (REAL_* in lib/realtex.glsl).
# grade: (saturation, brightness) applied to the colour to match the block.
MATERIALS = [
    ('grass', 'forrest_ground_01', (1.25, 1.0)),
    ('dirt', 'forest_ground_04', (1.0, 1.0)),
    ('stone', 'rock_boulder_dry', (0.25, 0.72)),
    ('sand', 'sand_01', (1.0, 1.05)),
    ('gravel', 'gravel_floor_02', (0.8, 0.95)),
    ('snow', 'snow_02', (0.6, 1.02)),
    ('deepslate', 'rock_face_04', (0.15, 0.55)),
    ('mud', 'brown_mud_02', (1.0, 0.85)),
]
LEVELS = 7  # 0..6


def fetch(asset, kind, resolution):
    os.makedirs(CACHE, exist_ok=True)
    path = os.path.join(CACHE, '%s_%s_%s.png' % (asset, kind, resolution))
    if not os.path.exists(path):
        url = 'https://dl.polyhaven.org/file/ph-assets/Textures/png/%s/%s/%s_%s_%s.png' % (resolution, asset, asset, kind, resolution)
        print('downloading', url)
        with urllib.request.urlopen(url) as response:
            data = response.read()
        open(path, 'wb').write(data)
    return Image.open(path)


def grade(rgb, saturation, brightness):
    grey = rgb @ np.array([0.2126, 0.7152, 0.0722])
    rgb = grey[..., None] + (rgb - grey[..., None]) * saturation
    return np.clip(rgb * brightness, 0.0, 1.0)


def mip_chain(image, size):
    """Levels 0..LEVELS-1 as float arrays, box-filtered in linear light for colour."""
    levels = []
    for level in range(LEVELS):
        s = size >> level
        levels.append(np.asarray(image.resize((s, s), Image.LANCZOS), dtype=np.float64) / 255.0)
    return levels


def cell(levels, size, channels):
    """One material: level 0 at the left, the rest stacked to its right."""
    tile = size + 2
    height = max(tile, sum((size >> level) + 2 for level in range(1, LEVELS)))
    out = np.zeros((height, tile + (size // 2) + 2, channels))
    y = 0
    for level, data in enumerate(levels):
        padded = np.pad(data, ((1, 1), (1, 1), (0, 0)), mode='wrap')
        if level == 0:
            out[0:tile, 0:tile] = padded
        else:
            h = padded.shape[0]
            out[y:y + h, tile:tile + h] = padded
            y += h
    return out


def atlas(cells):
    """Materials in two columns."""
    h, w, c = cells[0].shape
    rows = (len(cells) + 1) // 2
    out = np.zeros((rows * h, 2 * w, c))
    for i, data in enumerate(cells):
        r, col = divmod(i, 2)
        out[r * h:(r + 1) * h, col * w:(col + 1) * w] = data
    return out


def save(array, name):
    path = os.path.join(OUT, name)
    Image.fromarray((np.clip(array, 0, 1) * 255.0 + 0.5).astype(np.uint8), 'RGBA').save(path, optimize=True)
    print('wrote', os.path.normpath(path), array.shape[1], 'x', array.shape[0])


def main():
    albedo_cells = []
    surface_cells = []
    for name, asset, (saturation, brightness) in MATERIALS:
        diffuse = np.asarray(fetch(asset, 'diff', '1k').convert('RGB'), dtype=np.float64) / 255.0
        diffuse = grade(diffuse, saturation, brightness)
        diffuse = Image.fromarray((diffuse * 255.0 + 0.5).astype(np.uint8), 'RGB').convert('RGBA')
        albedo_cells.append(cell(mip_chain(diffuse, 1024), 1024, 4))

        normal = fetch(asset, 'nor_gl', '1k').convert('RGB').resize((512, 512), Image.LANCZOS)
        rough = fetch(asset, 'rough', '1k').convert('L').resize((512, 512), Image.LANCZOS)
        surface = Image.merge('RGBA', (*normal.split(), rough))
        surface_cells.append(cell(mip_chain(surface, 512), 512, 4))
    save(atlas(albedo_cells), 'real_albedo.png')
    save(atlas(surface_cells), 'real_surface.png')


main()
