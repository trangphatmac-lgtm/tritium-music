#version 120
// Folia MonetBackgroundLayer composition (AGPL-3.0); see THIRD_PARTY_NOTICES.md.
uniform vec2 r;
uniform sampler2D image, previousImage;
uniform vec2 imageSize, previousSize;
uniform vec3 background, accent, primary, tint, previousTint;
uniform vec3 drift;
uniform float transition, paneWidth;
uniform int mode, halfPane, hasImage, hasPrevious, light;

vec3 over(vec3 base, vec3 color, float opacity) { return mix(base, color, clamp(opacity, 0., 1.)); }
vec2 coverUV(vec2 uv, vec2 box, vec2 size) {
    float scale = max(box.x / size.x, box.y / size.y);
    vec2 sampleUV = (uv - .5) * box / (size * scale) + .5;
    if (mode == 2) sampleUV.y = 1. - sampleUV.y;
    return sampleUV;
}
vec3 picture(vec2 uv, vec2 box) {
    uv = (uv - .5 - drift.xy) / drift.z + .5;
    vec3 current = texture2D(image, coverUV(uv, box, imageSize)).rgb;
    if (hasPrevious == 1) current = mix(texture2D(previousImage, coverUV(uv, box, previousSize)).rgb, current, transition);
    return current;
}
float stops(float x, vec4 position, vec4 value) {
    if (x < position.y) return mix(value.x, value.y, clamp((x-position.x)/(position.y-position.x),0.,1.));
    if (x < position.z) return mix(value.y, value.z, (x-position.y)/(position.z-position.y));
    return mix(value.z, value.w, clamp((x-position.z)/(position.w-position.z),0.,1.));
}
vec3 fallback(vec2 uv) {
    float diagonal = dot(uv * r, vec2(1.)) / (r.x + r.y);
    vec4 a = vec4(accent*.22,.22), b = vec4(background*.96,.96), c = vec4(primary*.18,.18);
    vec4 stop = diagonal < .5 ? mix(a,b,diagonal*2.) : mix(b,c,(diagonal-.5)*2.);
    return background*(1.-stop.a)+stop.rgb;
}
void main() {
    vec2 uv = vec2(gl_FragCoord.x / r.x, 1. - gl_FragCoord.y / r.y);
    vec3 color = background;
    if (mode == 0) {
        vec3 top = mix(previousTint, tint, transition);
        color = over(background, top, .68 * (1. - smoothstep(0., .78, uv.y)));
    } else if (mode == 1 || mode == 2) {
        if (hasImage == 1) color = picture(uv, r);
        color = over(color, mode == 1 ? vec3(0.) : background, .35);
    } else {
        if (halfPane == 0) {
            if (hasImage == 1) color = picture(uv, r);
            else color = fallback(uv);
        } else {
            // CSS linear-gradient(135deg): use the gradient line through the box center.
            color = fallback(uv);
            float paneX = uv.x / paneWidth;
            if (hasImage == 1 && paneX <= 1.) {
                float mask = stops(paneX,vec4(0.,.48,.74,1.),vec4(1.,.94,.46,0.));
                color = over(color,picture(vec2(paneX,uv.y),vec2(r.x*paneWidth,r.y)),mask*((light==1?.7:.3)+.74*.16));
            }
            color = over(color,background,stops(uv.x,vec4(0.,.28,.64,1.),vec4(.14,.28,.62,.86)));
            float radius = length((uv-vec2(.18,.34))*r);
            // CSS circle radius resolves against the furthest corner; the stop is at 36%.
            float extent = length(vec2(.82,.66)*r);
            color = over(color,accent,.18*(1.-clamp(radius/(extent*.36),0.,1.)));
        }
        color = over(color,background,stops(uv.x,vec4(0.,.34,.70,1.),vec4(.18,.32,.66,.82)));
    }
    gl_FragColor = vec4(color,1.);
}
