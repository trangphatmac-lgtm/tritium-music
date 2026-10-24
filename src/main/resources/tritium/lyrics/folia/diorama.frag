// SPDX-License-Identifier: AGPL-3.0-only
#version 120

uniform float uGlow;
uniform float uGlowPass;

varying vec3 vColor;
varying float vAlpha;
varying float vReaction;

// three hands a ShaderMaterial its uniforms in the LINEAR working space and expects the sRGB conversion
// on the way out; for its own materials the colorspace_fragment chunk does it. That chunk is useless here
// - it expands to linearToOutputTexel(), which the ShaderMaterial prefix does not define, and including it
// compiles the shader to solid black (measured, not assumed). So this shader has to convert its own output,
// and until now it did not: it wrote linear values raw into an sRGB framebuffer and every point rendered
// far darker and more saturated than the theme colour it was handed - measured #c8783c -> #932f0c, a 3.1x
// drop in luminance, while an ordinary meshBasicMaterial on the same colour read back exactly #c8783c.
// That is what made the adaptive contrast in dioramaParticleMaterials.ts a guarantee about a colour that
// never reached the screen: it binary-searches the theme colour up to a >= 4.6 WCAG ratio, and then the
// point drew three times darker than the value it had just cleared.
vec3 dioramaLinearToSRGB(vec3 linear) {
    vec3 safe = max(linear, vec3(0.0));
    return mix(
        pow(safe, vec3(0.41666)) * 1.055 - vec3(0.055),
        safe * 12.92,
        vec3(safe.r <= .0031308 ? 1.0 : 0.0, safe.g <= .0031308 ? 1.0 : 0.0, safe.b <= .0031308 ? 1.0 : 0.0)
    );
}

void main() {
    vec2 point = gl_PointCoord - vec2(0.5);
    float radius = length(point);

    if (uGlowPass > 0.5) {
        // Additive soft halo: a wide falloff whose strength rides the glow slider and the point's reach.
        // Hard-capped so several overlapping crests cannot stack into a blown-out white blob.
        float halo = pow(clamp(1.0 - radius * 2.0, 0.0, 1.0), 1.7);
        float alpha = min(0.5, halo * vAlpha * uGlow * 0.4);
        if (alpha < 0.003) discard;
        gl_FragColor = vec4(dioramaLinearToSRGB(vColor * (1.0 + uGlow * 0.25)), alpha);
        return;
    }

    // Contrast layer: a soft-edged but near-opaque disc so the theme-adapted colour actually reads.
    float core = 1.0 - smoothstep(0.14, 0.48, radius);
    float alpha = core * vAlpha;
    if (alpha < 0.01) discard;
    float emission = 0.95 + vReaction * 0.22;
    gl_FragColor = vec4(dioramaLinearToSRGB(vColor * emission), alpha);
}
