#version 120
uniform vec2 r;
uniform float t;

mat2 rot(float a) {
    float c = cos(a), s = sin(a);
    return mat2(c, -s, s, c);
}
float ink(float v, float width) {
    return 1. - smoothstep(0., width, abs(v));
}
void main() {
    vec2 uv = (gl_FragCoord.xy - .5 * r) / r.y;
    vec2 p = rot(t * .12) * uv * 1.65;
    float rad = length(p);
    float sector = 6.2831853 / 10.;
    float a = abs(mod(atan(p.y, p.x), sector) - sector * .5);

    vec2 q = rad * vec2(cos(a), sin(a));
    q += .08 * vec2(sin(q.y * 11. + t * .7),
                     cos(q.x * 11. - t * .55));
    float lace = sin(q.x * 18. + sin(q.y * 9. - t * .7))
               * cos(q.y * 19. - t * .8);
    float petal = sin(28. * rad - 18. * a + 2. * sin(q.x * 6.) - t * 1.1);
    float ring = sin(46. * rad + 4. * sin(a * 10.) + t * .6);

    float glow = exp(-rad * rad * 1.05);
    vec3 tint = .5 + .5 * cos(6.2831853 *
                (vec3(.05, .33, .63) + rad * .28 + lace * .10 + t * .025));
    vec3 col = vec3(.008, .004, .028)
             + glow * tint * (.20 + .82 * ink(lace, .13)
                                + 1.05 * ink(petal, .21)
                                + .32 * ink(ring, .09));
    col += vec3(.35, .8, 1.) * exp(-rad * rad * 50.) * .35;
    gl_FragColor = vec4(1. - exp(-col * 1.55), 1.);
}
