#version 120
uniform vec2 r;
uniform float t;

float surface(vec2 p) {
    p *= 3.;
    p.x += .34 * sin(p.y * 1.9 + t * .61);
    p.y += .28 * cos(p.x * 1.7 - t * .54);
    float a = sin(p.x * 2.25 + sin(p.y * 1.6 + t * .30));
    float b = sin(p.y * 2.0 - cos(p.x * 1.8 - t * .35));
    return .58 * a + .42 * b + .16 * sin((p.x + p.y) * 2.5 - t * .75);
}
void main() {
    vec2 uv = (gl_FragCoord.xy - .5 * r) / r.y;
    float h = surface(uv);
    float e = .003;
    vec2 grad = vec2(surface(uv + vec2(e, 0.)) - h,
                     surface(uv + vec2(0., e)) - h) / e;
    vec3 n = normalize(vec3(-grad * .26, 1.));
    vec3 light = normalize(vec3(-.4, .5, 1.));

    float diffuse = .5 + .5 * max(dot(n, light), 0.);
    float specular = pow(max(dot(reflect(-light, n), vec3(0., 0., 1.)), 0.), 52.);
    vec3 rainbow = .52 + .48 * cos(6.2831853 *
                   (vec3(.04, .33, .68) + h * .19 + t * .025));
    float threads = pow(.5 + .5 * cos(18. * h + t * .7), 6.);
    vec3 col = rainbow * (.35 + .55 * diffuse)
             + vec3(1., .82, .96) * specular * .9
             + rainbow.bgr * threads * .13;

    gl_FragColor = vec4(pow(clamp(col, 0., 1.), vec3(.91)), 1.);
}
