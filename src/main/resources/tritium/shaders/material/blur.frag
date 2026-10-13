#version 120
uniform sampler2D source;
uniform vec2 stepSize;
uniform int encode;
void main() {
    vec3 total = vec3(0.0);
    float weightSum = 0.0;
    for (int i = -6; i <= 6; i++) {
        float offset = float(i) * .5;
        float weight = exp(-offset * offset * .5);
        vec3 sampleColor = texture2D(source, clamp(gl_TexCoord[0].xy + stepSize * offset, .001, .999)).rgb;
        // Blur in approximately linear light, then restore display gamma on the second pass.
        if (encode == 0) sampleColor = pow(max(sampleColor, vec3(0.0)), vec3(2.2));
        total += sampleColor * weight;
        weightSum += weight;
    }
    vec3 result = total / weightSum;
    if (encode == 1) result = pow(max(result, vec3(0.0)), vec3(1.0 / 2.2));
    gl_FragColor = vec4(result, 1.0);
}
