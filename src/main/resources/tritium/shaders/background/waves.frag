#version 120
uniform vec2 r;
uniform float t;
void main() {
    vec2 p=(gl_FragCoord.xy-.5*r)/r.y;
    float a=sin(p.x*3.+t*.3+sin(p.y*3.-t*.2));
    float b=sin(p.y*4.-t*.25+cos(p.x*2.+t*.15));
    float blend=.5+.5*sin(a+b+t*.08);
    vec3 color=mix(vec3(.035,.12,.3),vec3(.47,.16,.42),blend);
    color+=vec3(.2,.35,.35)*pow(.5+.5*cos(a-b),8.);
    gl_FragColor=vec4(color,1.);
}
