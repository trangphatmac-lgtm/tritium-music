#version 120
uniform vec2 r;
uniform float t;

float hash21(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}
float bell(float x, float w) {
    float q = x / w;
    return exp(-q * q);
}
void main() {
    vec2 uv = (gl_FragCoord.xy - .5 * r) / r.y;
    vec3 col = mix(vec3(.004, .009, .035), vec3(.009, .045, .070),
                   smoothstep(-.6, .6, uv.y));

    float wave = .11 * sin(uv.x * 3.6 + t * .28)
               + .05 * sin(uv.x * 8.1 - t * .48);
    float y = uv.y + .26 - wave;
    float curtain = smoothstep(-.09, .07, y) * bell(y - .18, .33);
    float folds = .32 + .68 * pow(.5 + .5 * sin(uv.x * 86.
                  + 4. * sin(uv.x * 9. + t * .36) - t * .85), 4.);
    vec3 aurora = mix(vec3(.06, 1.0, .55), vec3(.16, .36, 1.),
                      smoothstep(.00, .62, y));
    col += aurora * curtain * folds * 1.45;
    col += vec3(.02, .30, .32) * bell(y - .13, .44) * .32;

    float y2 = uv.y - .15 + .10 * sin(uv.x * 2.6 - t * .22);
    float violet = bell(y2, .22) * (.65 + .35 * sin(uv.x * 34. + t * .8));
    col += vec3(.47, .13, .9) * violet * .37;

    vec2 cells = (uv + vec2(3.2, 1.9)) * 170.;
    vec2 id = floor(cells);
    float rnd = hash21(id);
    float star = (1. - smoothstep(.01, .17, length(fract(cells) - .5)))
               * step(.994, rnd);
    col += vec3(.78, .88, 1.) * star * (.75 + .25 * sin(t * 2. + rnd * 70.));

    gl_FragColor = vec4(1. - exp(-col), 1.);
}
