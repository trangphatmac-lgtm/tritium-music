#version 120
uniform vec2 r;
uniform float t;

// Adapted from the user's supplied nebula shader for desktop GLSL.
mat2 m(float a) { float c=cos(a), s=sin(a); return mat2(c,-s,s,c); }
float map(vec3 p) {
    p.xz *= m(t*.4); p.xy *= m(t*.1);
    vec3 q=p*2.+t;
    return length(p+vec3(sin(t*.7)))*log(length(p)+1.)
        +sin(q.x+sin(q.z+sin(q.y)))*.5-1.;
}
void main() {
    vec2 a=(gl_FragCoord.xy-.5*r)/r.y;
    vec3 cl=vec3(0.); float d=2.5;
    for (int i=0; i<=5; i++) {
        vec3 p=vec3(0.,0.,4.)+normalize(vec3(a,-1.))*d;
        float rz=map(p), f=clamp((rz-map(p+.1))*.5,-.1,1.);
        vec3 l=vec3(.1,.3,.4)+vec3(5.,2.5,3.)*f;
        // Reversed smoothstep edges are undefined in GLSL; use the equivalent complement.
        cl=cl*l+(1.-smoothstep(0.,2.5,rz))*.6*l;
        d+=min(rz,1.);
    }
    gl_FragColor=vec4(clamp(cl,0.,1.),1.);
}
