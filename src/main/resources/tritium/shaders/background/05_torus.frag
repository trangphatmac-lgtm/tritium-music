#version 120
uniform vec2 r;
uniform float t;

mat2 rot(float a) {
    float c = cos(a), s = sin(a);
    return mat2(c, -s, s, c);
}

float scene(vec3 p) {
    float a = atan(p.z, p.x);
    float radius = 1.0
    + .11 * sin(a * 7. - t * .8)
    + .05 * cos(a * 13. + t * .5);

    vec2 q = vec2(
    length(p.xz) - radius,
    p.y - .13 * sin(a * 5. + t * .7)
    );
    return length(q) - .15;
}

vec3 normalAt(vec3 p) {
    const float e = .004;
    const vec3 k1 = vec3( 1., -1., -1.);
    const vec3 k2 = vec3(-1., -1.,  1.);
    const vec3 k3 = vec3(-1.,  1., -1.);
    const vec3 k4 = vec3( 1.,  1.,  1.);
    return normalize(
        k1 * scene(p + e * k1)
        + k2 * scene(p + e * k2)
        + k3 * scene(p + e * k3)
        + k4 * scene(p + e * k4)
    );
}

void main() {
    vec2 uv = (gl_FragCoord.xy - .5 * r) / r.y;
    vec3 ro = vec3(0., 0., 3.8);
    vec3 rd = normalize(vec3(uv, -1.8));

    mat2 xz = rot(t * .24), xy = rot(t * .16);
    vec3 localRo = ro, localRd = rd;
    localRo.xz *= xz; localRo.xy *= xy;
    localRd.xz *= xz; localRd.xy *= xy;

    vec3 col = vec3(.005, .008, .036);
    float d = 0., halo = 0.;
    bool hit = false;

    const float bound = 1.45;
    float b = dot(ro, rd);
    float disc = b * b - (dot(ro, ro) - bound * bound);

    if (disc > 0.) {
        float root = sqrt(disc);
        d = max(0., -b - root);
        float farD = -b + root;

        for (int i = 0; i < 48; i++) {
            if (d > farD) break;

            float s = scene(localRo + localRd * d);

            float h = max(0., 1. - abs(s) * 3.5);
            halo += .028 * h * h / (1. + .08 * d * d);

            if (s < .003) {
                hit = true;
                break;
            }
            d += clamp(s * .70, .012, .20);
        }
    }

    if (hit) {
        vec3 p = ro + rd * d;
        vec3 localP = localRo + localRd * d;
        vec3 n = normalAt(localP);
        vec3 l = normalize(vec3(-.4, .7, 1.));
        l.xz *= xz; l.xy *= xy;

        float diffuse = max(dot(n, l), 0.);
        float fresnel = pow(
            1. - max(dot(n, -localRd), 0.), 3.
        );

        float band = .5 + .5 * sin(
            atan(p.z, p.x) * 9. + t
        );
        vec3 tint = mix(
            vec3(.05, .74, 1.),
            vec3(.72, .19, 1.), band
        );

        col += tint * (.20 + .95 * diffuse)
        + vec3(.95, .38, 1.) * fresnel * 1.4;
    }

    col += halo * vec3(.35, .18, 1.65);
    col += vec3(.13, .32, .75)
    * exp(-dot(uv, uv) * 8.) * .18;

    if (!hit) {
        vec2 grid = (uv + 2.4) * 180.;
        vec2 id = floor(grid);
        float h = fract(
            sin(dot(id, vec2(127.1, 311.7)))
            * 43758.5453
        );
        float star = (1. - smoothstep(
            .0, .18, length(fract(grid) - .5)
        )) * step(.995, h);
        col += vec3(.55, .77, 1.) * star * .65;
    }

    gl_FragColor = vec4(1. - exp(-col), 1.);
}
