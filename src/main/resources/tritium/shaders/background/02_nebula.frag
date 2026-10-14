#version 120
uniform vec2 r;
uniform float t;

float hash21(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}
float noise21(vec2 p) {
    vec2 i = floor(p), f = fract(p);
    f = f * f * (3. - 2. * f);
    return mix(mix(hash21(i), hash21(i + vec2(1., 0.)), f.x),
               mix(hash21(i + vec2(0., 1.)), hash21(i + 1.), f.x), f.y);
}
float fbm(vec2 p) {
    float v = 0., a = .5;
    for (int i = 0; i < 5; i++) {
        v += a * noise21(p);
        p = mat2(1.6, -1.2, 1.2, 1.6) * p + 3.;
        a *= .5;
    }
    return v;
}
mat2 rot(float a) {
    float c = cos(a), s = sin(a);
    return mat2(c, -s, s, c);
}
void main() {
    vec2 uv = (gl_FragCoord.xy - .5 * r) / r.y;
    vec2 p = rot(t * .06) * uv * 1.85;
    float rad = length(p);
    float ang = atan(p.y, p.x);
    float warp = fbm(p * 1.5 + vec2(t * .035, -t * .025));
    float arm = .5 + .5 * cos(ang * 5. - rad * 10. + warp * 5.);
    arm = arm * arm * arm;
    float dust = fbm(p * 4.5 + vec2(warp * 3. + t * .04));
    float halo = exp(-rad * rad * .85);

    vec3 color = vec3(.004, .004, .024);
    vec3 tint = mix(vec3(.12, .36, 1.), vec3(1., .14, .60),
                    smoothstep(.22, .78, dust));
    color += tint * halo * (.13 + .95 * arm) * (.28 + dust) * 1.8;
    color += vec3(.22, .04, .35) * halo * .26;
    color += vec3(1., .72, .43) * (.045 / (rad * rad + .022));

    vec2 grid = (uv + vec2(2.7, 1.5)) * 170.;
    vec2 id = floor(grid);
    float h = hash21(id);
    float star = (1. - smoothstep(.0, .17, length(fract(grid) - .5)))
               * step(.993, h);
    color += vec3(.75, .85, 1.) * star * .85;
    gl_FragColor = vec4(1. - exp(-color), 1.);
}
