#version 120
uniform sampler2D backdrop;
uniform vec2 screenSize, size, lightDirection;
uniform vec4 tint;
uniform float radius, pixelScale, liquid, light, control, hover;

float distanceToGlass(vec2 p) {
    vec2 q = abs(p) - size * .5 + radius;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
}

void main() {
    vec2 p = gl_TexCoord[0].xy - size * .5;
    float sd = distanceToGlass(p);
    float aa = max(fwidth(sd), .12);
    float mask = 1.0 - smoothstep(-aa, aa, sd);
    float shadow = exp(-max(distanceToGlass(p - vec2(0.0, 1.5)), 0.0) / 2.2) * .12 * (1.0 - mask);
    if (mask + shadow < .002) discard;

    vec2 normal = normalize(vec2(distanceToGlass(p + vec2(.1, 0.0)) - distanceToGlass(p - vec2(.1, 0.0)),
                                distanceToGlass(p + vec2(0.0, .1)) - distanceToGlass(p - vec2(0.0, .1))) + vec2(.00001));
    float bevel = min(radius, min(size.x, size.y) * .23);
    float edge = 1.0 - smoothstep(0.0, max(bevel, .5), max(-sd, 0.0));
    // A rounded glass edge bends the sampled scene according to Snell's law (IOR 1.45).
    float incident = asin(clamp(edge * edge, 0.0, .98));
    float transmitted = asin(sin(incident) / 1.45);
    float bend = min(tan(incident - transmitted), 2.0) * bevel * .42 * liquid;
    vec2 displacement = normal * bend * pixelScale / screenSize * vec2(1.0, -1.0);
    vec2 uv = gl_FragCoord.xy / screenSize - displacement;
    vec2 dispersion = displacement * .06 * edge;
    vec3 color;
    color.r = texture2D(backdrop, clamp(uv + dispersion, .001, .999)).r;
    color.g = texture2D(backdrop, clamp(uv, .001, .999)).g;
    color.b = texture2D(backdrop, clamp(uv - dispersion, .001, .999)).b;
    float luma = dot(color, vec3(.2126, .7152, .0722));
    color = mix(vec3(luma), color, mix(1.12, 1.24, liquid));

    float saturation = max(tint.r, max(tint.g, tint.b)) - min(tint.r, min(tint.g, tint.b));
    float accent = smoothstep(.12, .36, saturation);
    float tintAmount = mix(.55, .23, liquid) + control * .06;
    tintAmount = mix(tintAmount, .84, accent);
    color = mix(color, tint.rgb, tintAmount);
    // Keep neutral surfaces legible on very bright/dark custom artwork in either appearance.
    float surfaceLuma = dot(color, vec3(.2126, .7152, .0722));
    float readability = mix(smoothstep(.44, .75, surfaceLuma) * .45,
                            (1.0 - smoothstep(.3, .64, surfaceLuma)) * .6, light);
    color = mix(color, vec3(mix(.08, .96, light)), readability * (1.0 - accent));
    float top = clamp(.5 - p.y / max(size.y, 1.0), 0.0, 1.0);
    color += (top * .04 + hover * .035) * (1.0 - accent * .7);

    float rim = 1.0 - smoothstep(.1, .55 + aa, abs(sd + .45));
    float specular = pow(max(dot(normal, normalize(lightDirection)), 0.0), 3.0);
    float opposite = pow(max(dot(normal, -normalize(lightDirection)), 0.0), 5.0);
    float shine = mix(.10, .12 + specular * .40 + opposite * .13, liquid);
    // Dark frosted surfaces have no bright rim; light Blur and Liquid Glass retain theirs.
    float rimVisibility = mix(smoothstep(.15, .85, light), 1.0, liquid);
    color = mix(color, vec3(1.0), rim * shine * mix(1.0, .65, light) * rimVisibility);
    color += pow(edge, 5.0) * specular * .08 * liquid;

    float alpha = (mask + shadow) * tint.a;
    gl_FragColor = vec4(clamp(color, 0.0, 1.0) * mask / max(mask + shadow, .001), alpha);
}
