#version 120
// Paper Shaders 0.0.80, Copyright 2026 Paper (Apache-2.0).
// Modified: GLSL 1.20 port of mesh-gradient and warp/4x4 dithering, combined with
// Folia Latent's transforms, CSS filters, soft-light blend and background overlay.
// See THIRD_PARTY_NOTICES.md for licenses and provenance.
uniform vec2 r;
uniform float pixelRatio;
uniform float meshTime;
uniform float ditherTime;
uniform vec3 colors[9];
uniform vec3 audio; // power, bass, mid/vocal
uniform float strength;
uniform int layers; // 0 = both, 1 = mesh, 2 = dithering

vec2 rotate(vec2 uv, float angle) {
    return mat2(cos(angle), sin(angle), -sin(angle), cos(angle)) * uv;
}
float hash21(vec2 p) {
    p = fract(p * vec2(.3183099, .3678794)) + .1;
    p += dot(p, p + 19.19);
    return fract(p.x * p.y);
}
float valueNoise(vec2 st) {
    vec2 i = floor(st), f = fract(st);
    vec2 u = f * f * (3. - 2. * f);
    return mix(mix(hash21(i), hash21(i + vec2(1., 0.)), u.x),
               mix(hash21(i + vec2(0., 1.)), hash21(i + vec2(1., 1.)), u.x), u.y);
}
vec2 position(int i, float t) {
    float a = float(i) * .37;
    float b = .6 + fract(float(i) / 3.) * .9;
    float c = .8 + fract(float(i + 1) / 4.);
    return .5 + .5 * vec2(sin(t * b + a), cos(t * c + a * 1.5));
}
vec3 mesh(vec2 pixel) {
    vec2 uv = (pixel - .5 * r) / min(r.x, r.y) / (1.025 + audio.x * .018) + .5;
    vec2 grainUV = uv * 1000.;
    float t = .5 * (meshTime + 41.5);
    float radius = smoothstep(0., 1., length(uv - .5));
    float center = 1. - radius;
    float distortion = .8 + audio.x * .62;
    for (float i = 1.; i <= 2.; i++) {
        uv.x += distortion * center / i * sin(t + i * .4 * smoothstep(0., 1., uv.y))
            * cos(.2 * t + i * 2.4 * smoothstep(0., 1., uv.y));
        uv.y += distortion * center / i * cos(t + i * 2. * smoothstep(0., 1., uv.x));
    }
    uv = rotate(uv - .5, -3. * (.1 + audio.z * .38) * radius) + .5;
    vec3 color = vec3(0.);
    float total = 0.;
    for (int i = 0; i < 6; i++) {
        float weight = 1. / (pow(length(uv - position(i, t)), 3.5) + .001);
        color += colors[i + 3] * weight;
        total += weight;
    }
    color /= max(.0001, total);
    float grain = mix(valueNoise(rotate(grainUV, 1.) + 3.), valueNoise(rotate(grainUV, 2.) - 1.), .5);
    float grainV = pow(grain, 1.3) * 2. - 1.;
    color = mix(color, vec3(step(0., grainV)), .35 * pow(.01 * abs(grainV), .8));
    // CSS saturate() then brightness(), in sRGB as in the reference browser compositor.
    float luma = dot(color, vec3(.213, .715, .072));
    color = mix(vec3(luma), color, 1.04 + audio.z * .34);
    return clamp(color * (.94 + audio.x * .16), 0., 1.);
}
float bayer2(vec2 p) {
    p = mod(p, 2.);
    return p.y < 1. ? (p.x < 1. ? 0. : 2.) : (p.x < 1. ? 3. : 1.);
}
vec3 dithering(vec2 pixel) {
    float t = .5 * ditherTime;
    float pxSize = max(.5, 2.5 - audio.y * 2.5 * .34) * pixelRatio;
    vec2 pxUV = (pixel - .5 * r) / (1.015 + audio.y * .025) / pxSize;
    vec2 uv = ((floor(pxUV) + .5) * pxSize / pixelRatio + .5) * .003;
    for (float i = 1.; i < 6.; i++) {
        uv.x += .6 / i * cos(i * 2.5 * uv.y + t);
        uv.y += .6 / i * cos(i * 1.5 * uv.x + t);
    }
    float shape = smoothstep(.02, 1., .15 / max(.001, abs(sin(t - uv.y - uv.x))));
    vec2 p = mod(floor(pxUV), 4.);
    float threshold = (4. * bayer2(p) + bayer2(floor(p / 2.))) / 16.;
    return mix(colors[1], colors[2], step(.5, shape + threshold - .5));
}
float softLight(float back, float front) {
    float d = back <= .25 ? ((16. * back - 12.) * back + 4.) * back : sqrt(back);
    return front <= .5 ? back - (1. - 2. * front) * back * (1. - back)
                      : back + (2. * front - 1.) * (d - back);
}
void main() {
    vec3 color;
    if (layers == 2) color = dithering(gl_FragCoord.xy);
    else {
        color = mesh(gl_FragCoord.xy);
        if (layers == 0) {
            vec3 front = dithering(gl_FragCoord.xy);
            vec3 blended = vec3(softLight(color.r, front.r), softLight(color.g, front.g), softLight(color.b, front.b));
            color = mix(color, blended, min(1., .55 + audio.y * .25));
        }
    }
    gl_FragColor = vec4(mix(colors[0], color, strength), 1.);
}
