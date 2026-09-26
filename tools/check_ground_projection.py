"""Run with python tools/check_ground_projection.py; no game or dependencies needed."""
import math
from pathlib import Path

shader = Path('src/client/resources/shaderpacks/MinaRealism/shaders/lib/realtex.glsl').read_text(encoding='utf-8')
# Guard the shader against restoring the hard projection switch.
projection = shader.split('void realLayer(', 1)[1].split('// The scan', 1)[0]
assert 'pow(abs(faceNormal), vec3(4.0))' in projection
assert 'a.y > 0.35' not in projection

def weights(n):
    w = [abs(v)**4 for v in n]
    return [v/sum(w) for v in w]

for y in [0.0, 0.35, 0.5, 0.75, 1.0, -1.0]:
    n = [math.sqrt(max(0, 1-y*y)), y, 0]
    w = weights(n)
    assert abs(sum(w)-1) < 1e-10
    assert all(math.isfinite(v) and v >= 0 for v in w)
for y in [0.35, 0.5, 0.75]:
    a = weights([math.sqrt(1-(y-1e-6)**2), y-1e-6, 0])
    b = weights([math.sqrt(1-(y+1e-6)**2), y+1e-6, 0])
    assert max(abs(x-z) for x,z in zip(a,b)) < 1e-4
assert weights([0,-1,0]) == [0,1,0]
print('Projection continuity and underside checks passed (not a GPU render test)')

assert 'void realGroundPalette' in shader
assert 'blueDrop / redDrop' not in shader
print('Material palette shader checks passed')

import re
vertex = Path('src/client/resources/shaderpacks/MinaRealism/shaders/program/gbuffers_vertex.glsl').read_text(encoding='utf-8')
assert not re.search(r'\b(?:int|float|vec[234]|ivec[234])\s+packed\b', vertex), 'GLSL reserved word used as variable'
assert 'int materialBits =' in vertex
print('GLSL material decoder reserved-name regression check passed')
