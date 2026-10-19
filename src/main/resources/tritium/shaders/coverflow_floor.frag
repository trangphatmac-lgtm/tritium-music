#version 120

uniform sampler2D scene;
uniform float horizon;
uniform float extent;
uniform vec4 pixelSize;
uniform vec3 surface;
uniform float opacity;

vec3 sampleScene(vec2 uv) {
    // Never blur across the mirror edge into the original wallpaper below it.
    vec2 halfTexel = pixelSize.zw * 0.5;
    return texture2D(scene, clamp(uv, vec2(halfTexel.x, 1.0 - horizon + halfTexel.y), vec2(1.0) - halfTexel)).rgb;
}

void main() {
    vec2 floorUV = gl_TexCoord[0].xy;
    float depth = floorUV.y;

    // Foreshortening compresses the reflection vertically; the floor widens toward the viewer.
    float sourceY = horizon - depth * extent / (0.72 - 0.12 * depth);
    vec2 uv = vec2(0.5 + (floorUV.x - 0.5) / (1.0 + 0.10 * depth), 1.0 - sourceY);
    // Sharp at contact, softer farther along the floor. Logical pixel units preserve HiDPI behavior.
    vec2 radius = pixelSize.xy * (0.15 + 5.0 * depth * depth);
    vec3 reflected = sampleScene(uv) * 0.25;
    reflected += (sampleScene(uv + vec2(radius.x, 0.0))
                + sampleScene(uv - vec2(radius.x, 0.0))
                + sampleScene(uv + vec2(0.0, radius.y))
                + sampleScene(uv - vec2(0.0, radius.y))) * 0.125;
    reflected += (sampleScene(uv + radius) + sampleScene(uv - radius)
                + sampleScene(uv + vec2(radius.x, -radius.y))
                + sampleScene(uv + vec2(-radius.x, radius.y))) * 0.0625;
    float luminance = dot(reflected, vec3(0.2126, 0.7152, 0.0722));
    reflected = mix(vec3(luminance), reflected, 0.88) * 0.88;

    float reflectivity = 0.9 * (1.0 - smoothstep(0.16, 1.0, depth));
    vec3 floorColor = surface * (0.86 + 0.14 * depth);
    vec3 color = mix(floorColor, reflected, reflectivity);
    // A broad grazing highlight marks the plane without drawing an opaque border.
    float sheen = exp(-depth * 100.0) * (0.025 + 0.025 * exp(-pow((floorUV.x - 0.5) * 2.0, 2.0)));
    color += vec3(sheen);
    // The surface replaces the lower wallpaper instead of layering a second image over it.
    gl_FragColor = vec4(color, opacity);
}
