#version 120

uniform sampler2D reflection;
uniform sampler2D depthBuffer;
uniform vec2 projectionScale;
uniform vec2 clipPlanes;
uniform vec2 pixelSize;
uniform float floorY;
uniform float opacity;
uniform float artworkDepth;

void main() {
    vec2 uv = gl_TexCoord[0].xy;
    vec3 eyeRay = normalize(vec3((uv * 2.0 - 1.0) / projectionScale, -1.0));
    if (eyeRay.y >= -0.0001) discard;
    float planeDepth = -(eyeRay.z * floorY / eyeRay.y);
    float rawDepth = texture2D(depthBuffer, uv).r;
    float sceneDepth = clipPlanes.x * clipPlanes.y
        / (clipPlanes.y - rawDepth * (clipPlanes.y - clipPlanes.x));
    if (sceneDepth < planeDepth - 0.1) discard;

    // Distance from the contact line at the middle of the artwork depth interval.
    // It controls material roughness, not geometry: the cover keeps its mirror proportions.
    float height = max(0.0, floorY - eyeRay.y / -eyeRay.z * artworkDepth);
    float spread = smoothstep(0.0, 85.0, height);
    vec2 radius = pixelSize * (0.15 + 2.8 * spread * spread);
    vec4 color = texture2D(reflection, uv) * 0.25;
    color += (texture2D(reflection, uv + vec2(radius.x, 0.0))
            + texture2D(reflection, uv - vec2(radius.x, 0.0))
            + texture2D(reflection, uv + vec2(0.0, radius.y))
            + texture2D(reflection, uv - vec2(0.0, radius.y))) * 0.125;
    color += (texture2D(reflection, uv + radius) + texture2D(reflection, uv - radius)
            + texture2D(reflection, uv + vec2(radius.x, -radius.y))
            + texture2D(reflection, uv + vec2(-radius.x, radius.y))) * 0.0625;
    float fresnel = 0.16 + 0.84 * pow(1.0 - abs(eyeRay.y), 5.0);
    float fade = 1.0 - smoothstep(3.0, 92.0, height);
    gl_FragColor = color * (fresnel * fade * opacity);
}
