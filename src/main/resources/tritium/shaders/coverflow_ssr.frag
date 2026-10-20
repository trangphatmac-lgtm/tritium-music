#version 120

uniform sampler2D scene;
uniform sampler2D depthBuffer;
uniform sampler2D planar;
uniform vec2 projectionScale;
uniform vec2 clipPlanes;
uniform vec2 depthRange;
uniform float floorY;

float viewDepth(vec2 uv) {
    float depth = texture2D(depthBuffer, uv).r;
    return clipPlanes.x * clipPlanes.y / (clipPlanes.y - depth * (clipPlanes.y - clipPlanes.x));
}

vec2 projectPoint(vec3 p) {
    return p.xy * projectionScale / -p.z * 0.5 + 0.5;
}

void main() {
    vec2 uv = gl_TexCoord[0].xy;
    vec3 eyeRay = normalize(vec3((uv * 2.0 - 1.0) / projectionScale, -1.0));
    if (eyeRay.y >= -0.0001) {
        gl_FragColor = vec4(0.0);
        return;
    }
    vec3 origin = eyeRay * (floorY / eyeRay.y);
    // Preserve original covers and their UI wherever they occlude the reflective plane.
    if (viewDepth(uv) < -origin.z - 0.1) {
        gl_FragColor = vec4(0.0);
        return;
    }
    vec4 fallback = texture2D(planar, uv);
    vec3 direction = reflect(eyeRay, vec3(0.0, 1.0, 0.0));
    // Only march through the camera-depth interval occupied by artwork. Bounded cost on GL 2.1.
    float start = max(0.05, (depthRange.x + origin.z) / -direction.z);
    float end = (depthRange.y + origin.z) / -direction.z;
    vec4 result = fallback;
    if (end > start) {
        float stride = (end - start) / 48.0;
        float previous = start;
        for (int i = 0; i < 49; ++i) {
            float distance = start + float(i) * stride;
            vec3 point = origin + direction * distance;
            vec2 hitUV = projectPoint(point);
            if (hitUV.x < 0.0 || hitUV.x > 1.0 || hitUV.y < 0.0 || hitUV.y > 1.0) break;
            float delta = -point.z - viewDepth(hitUV);
            if (delta >= 0.0 && delta < 2.5) {
                // Refine the front/back depth crossing rather than sampling a coarse marching step.
                float low = previous, high = distance;
                for (int j = 0; j < 5; ++j) {
                    float middle = (low + high) * 0.5;
                    vec3 candidate = origin + direction * middle;
                    if (-candidate.z >= viewDepth(projectPoint(candidate))) high = middle;
                    else low = middle;
                }
                vec3 hit = origin + direction * high;
                hitUV = projectPoint(hit);
                float error = abs(-hit.z - viewDepth(hitUV));
                vec4 reflected = texture2D(scene, hitUV);
                float edge = min(min(hitUV.x, 1.0 - hitUV.x), min(hitUV.y, 1.0 - hitUV.y));
                float confidence = smoothstep(0.0, 0.035, edge)
                    * (1.0 - smoothstep(0.5, 2.5, error)) * reflected.a;
                result = mix(fallback, reflected, confidence);
                break;
            }
            previous = distance;
        }
    }
    // Premultiplied alpha keeps the subsequent roughness filter free of dark texture fringes.
    gl_FragColor = vec4(result.rgb * result.a, result.a);
}
