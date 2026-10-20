#version 120

uniform sampler2D u_diffuse_sampler;
uniform sampler2D u_other_sampler;
uniform sampler2D u_original_sampler;
uniform bool u_layer;
uniform vec2 u_texel_size;
uniform vec2 u_direction;
uniform int u_radius;
uniform float u_kernel[128];

void main()
{
    vec2 uv = gl_TexCoord[0].st;

    float base_alpha = texture2D(u_other_sampler, uv).a;
    if (!u_layer && u_direction.x == 0.0 && base_alpha == 0.0) {
        discard;
    }

    vec4 pixel_color = texture2D(u_diffuse_sampler, uv) * u_kernel[0];
    vec2 step = u_direction * u_texel_size;

    for (int i = 1; i < u_radius; i++) {
        vec2 offset = float(i) * step;
        pixel_color += (texture2D(u_diffuse_sampler, uv - offset) + texture2D(u_diffuse_sampler, uv + offset)) * u_kernel[i];
    }

    if (u_layer) {
        // Both sources contain premultiplied RGBA. Preserve sharp pixels outside the mask.
        gl_FragColor = u_direction.x == 0.0
            ? mix(texture2D(u_original_sampler, uv), pixel_color, base_alpha)
            : pixel_color;
        return;
    }

    float final_alpha = u_direction.x == 0.0 ? base_alpha : 1.0;
    gl_FragColor = vec4(pixel_color.rgb, final_alpha);
}
