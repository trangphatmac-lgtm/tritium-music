#version 120

uniform vec2 size, start, joint, end;
uniform float radius;
uniform vec4 color;

float segmentDistance(vec2 p, vec2 a, vec2 b) {
    vec2 edge = b - a;
    float t = clamp(dot(p - a, edge) / max(dot(edge, edge), .0001), 0.0, 1.0);
    return length(p - a - t * edge);
}

void main() {
    vec2 p = gl_TexCoord[0].xy * size;
    // Union the segments before antialiasing: no inset endpoints or double-blended joint.
    float distance = min(segmentDistance(p, start, joint), segmentDistance(p, joint, end)) - radius;
    float aa = max(fwidth(distance) * .5, .01);
    float coverage = 1.0 - smoothstep(-aa, aa, distance);
    gl_FragColor = vec4(color.rgb, color.a * coverage);
}
