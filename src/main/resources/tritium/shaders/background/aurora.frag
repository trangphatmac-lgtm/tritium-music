#version 120
uniform vec2 r;
uniform float t;
void main() {
    vec2 uv=gl_FragCoord.xy/r;
    vec2 p=(gl_FragCoord.xy-.5*r)/r.y;
    vec3 color=mix(vec3(.018,.025,.09),vec3(.07,.14,.22),uv.y);
    for (int i=0; i<5; i++) {
        float k=float(i);
        float wave=.14*sin(p.x*2.6+t*.18+k*.7)+.06*sin(p.x*5.-t*.26+k);
        float glow=exp(-abs(p.y-wave+.08*k-.2)*(7.+k*2.));
        vec3 tint=mix(vec3(.12,.85,.55),vec3(.35,.22,.9),k/4.);
        color+=glow*tint*(.2+.07*sin(p.x*3.+t*.2+k));
    }
    gl_FragColor=vec4(color,1.);
}
