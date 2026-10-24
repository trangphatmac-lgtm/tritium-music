// Development-only data importer. Node 24+; no JavaScript runtime or framework ships in the app.
// Usage: node tools/folia/import-profiles.mjs /path/to/folia-major
import { registerHooks, stripTypeScriptTypes } from 'node:module';
import { existsSync, readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { pathToFileURL, fileURLToPath } from 'node:url';

const upstream = resolve(process.argv[2]);
const output = resolve('src/main/resources/tritium/lyrics/folia');
mkdirSync(output, { recursive: true });
globalThis.devicePixelRatio = 1;
registerHooks({
    resolve(specifier, context, next) {
        if (specifier.startsWith('.') && context.parentURL?.startsWith('file:')) {
            const base = fileURLToPath(new URL(specifier, context.parentURL));
            for (const suffix of ['.ts', '/index.ts']) {
                if (existsSync(base + suffix)) return { url: pathToFileURL(base + suffix).href, shortCircuit: true };
            }
        }
        return next(specifier, context);
    },
    load(url, context, next) {
        // Optional beat service is inactive when recording static design geometry.
        if (url.endsWith('/beatFxLive.ts')) return { format: 'module', source: 'export const isBeatFxReady=()=>false;', shortCircuit: true };
        if (url.endsWith('/sonnetBeatFx.ts')) return { format: 'module', source: 'export const resolveSonnetIconOnsetPop=()=>0;export const resolveSonnetIconPopScale=()=>1;', shortCircuit: true };
        if (url.endsWith('/sonnetIcons.ts')) return { format: 'module', source: stripTypeScriptTypes(readFileSync(fileURLToPath(url),'utf8').replace(/^import .*;\r?\n/gm,'')), shortCircuit: true };
        if (url.endsWith('.ts')) return { format: 'module', source: stripTypeScriptTypes(readFileSync(fileURLToPath(url), 'utf8').replace("import { MotionValue } from 'framer-motion';", "import type { MotionValue } from 'framer-motion';"), { mode: 'transform' }), shortCircuit: true };
        return next(url, context);
    },
});
const moduleAt = name => import(pathToFileURL(resolve(upstream, 'src/components/visualizer', name)).href);
const save = (name, value) => writeFileSync(resolve(output, name), JSON.stringify(value));
const hanabi = await moduleAt('hanabi/catalog.ts');
save('hanabi.json', hanabi.HANABI_KINDS.map(kind => hanabi.profileOf(kind)));
const lumiere = await moduleAt('lumiere/catalog.ts');
const { createVisualizerRng } = await moduleAt('visualizerSeedHash.ts');
save('lumiere.json', lumiere.LUMIERE_PROFILES.map(profile => {
    const context = { aspect: 16 / 9, random: createVisualizerRng(profile.kind) };
    return { ...profile, light: profile.light(context), lineArt: profile.lineArt(context) };
}));
const tempera = await moduleAt('tempera/temperaShotProfiles.ts');
save('tempera-profiles.json', tempera.TEMPERA_SHOT_PROFILES);
// The fragment body is the original light-field math; only the GLSL transport differs.
const shaderSource = readFileSync(resolve(upstream, 'src/components/visualizer/lumiere/light/lightFieldShader.ts'), 'utf8');
let fragment = shaderSource.match(/const fragment = `([\s\S]*?)`;/)[1]
    .replace('precision highp float;', '#version 120')
    .replace('in vec2 vPos;', 'varying vec2 vPos;')
    .replace('in float vAlpha;', 'uniform float vAlpha;')
    .replace('out vec4 finalColor;', '')
    .replaceAll('finalColor', 'gl_FragColor')
    .replace('${MAX_BEAMS}', '6');
writeFileSync(resolve(output, 'lumiere.frag'), '// SPDX-License-Identifier: AGPL-3.0-only\n' + fragment);
console.log(`Imported ${hanabi.HANABI_KINDS.length} Hanabi, ${lumiere.LUMIERE_PROFILES.length} Lumiere, ${Object.keys(tempera.TEMPERA_SHOT_PROFILES).length} Tempera profiles.`);

// Bake the original analytic firework plans, not screenshots or JavaScript executable code.
const { planBurst } = await moduleAt('hanabi/mg/burstPlan.ts');
const palette = { sparks: [0xffb5d7, 0xffdf8b, 0x9bd8ff, 0xc8b6ff, 0xfff1cc], glow: 0xffd5ee, ink: 0xffffff };
save('hanabi-bursts.json', hanabi.HANABI_KINDS.map(kind => {
    const p = hanabi.profileOf(kind);
    return planBurst({ spec: p.burst, x: 0, y: 0, at: 0, scale: 1, seed: kind }, palette, { unit: 720, amount: 1 });
}));

// Record upstream's pure composition builders to a renderer-neutral vector display list.
// This tiny recorder is a development tool; Pixi/Node are never runtime dependencies.
class Graphics {
    x = 0; y = 0; rotation = 0; px = 0; py = 0; commands = []; paths = [];
    alpha = 1; sx = 1; sy = 1;
    scale = { set: (x, y) => { this.sx = x; this.sy = y ?? x; } };
    position = { set: (x, y) => { this.x = x; this.y = y ?? x; } };
    pivot = { set: (x, y) => { this.px = x; this.py = y ?? x; } };
    poly(points) { this.paths.push({ kind: 'poly', points }); return this; }
    rect(x, y, w, h) { return this.poly([x, y, x+w, y, x+w, y+h, x, y+h]); }
    circle(x, y, radius) { this.paths.push({ kind: 'circle', x, y, radius }); return this; }
    moveTo(x, y) { this.paths.push({ kind: 'line', points: [x, y] }); return this; }
    lineTo(x, y) { this.paths.at(-1).points.push(x, y); return this; }
    quadraticCurveTo(cx, cy, tx, ty) { const [x,y]=this.paths.at(-1).points.slice(-2); for(let i=1;i<=24;i++){let t=i/24,u=1-t;this.lineTo(u*u*x+2*u*t*cx+t*t*tx,u*u*y+2*u*t*cy+t*t*ty);}return this; }
    bezierCurveTo(ax, ay, bx, by, tx, ty) { const [x,y]=this.paths.at(-1).points.slice(-2); for(let i=1;i<=32;i++){let t=i/32,u=1-t;this.lineTo(u*u*u*x+3*u*u*t*ax+3*u*t*t*bx+t*t*t*tx,u*u*u*y+3*u*u*t*ay+3*u*t*t*by+t*t*t*ty);}return this; }
    arc(cx,cy,r,start,end,anticlockwise=false) { let span=end-start;if(anticlockwise&&span>0)span-=Math.PI*2;if(!anticlockwise&&span<0)span+=Math.PI*2;
        if(!this.paths.length||this.paths.at(-1).kind!=='line')this.moveTo(cx+Math.cos(start)*r,cy+Math.sin(start)*r);
        for(let i=1;i<=64;i++){let a=start+span*i/64;this.lineTo(cx+Math.cos(a)*r,cy+Math.sin(a)*r);}return this; }
    closePath(){const p=this.paths.at(-1);if(p?.points?.length)this.lineTo(p.points[0],p.points[1]);return this;}
    clear(){this.commands=[];this.paths=[];return this;}
    fill(style) { this.commands.push({ action: 'fill', style, paths: this.paths }); this.paths = []; return this; }
    stroke(style) { this.commands.push({ action: 'stroke', style, paths: this.paths }); this.paths = []; return this; }
    cut() { this.commands.at(-1).holes ??= []; this.commands.at(-1).holes.push(...this.paths); this.paths = []; return this; }
    toJSON() { return { x: this.x, y: this.y, px: this.px, py: this.py, rotation: this.rotation, commands: this.commands, options: this.options, parent: this.parent }; }
}
const pixi = { Graphics, Color: { shared: { setValue(value) { this.value = value; return this; }, toNumber() { return parseInt(this.value.replace('#', ''), 16); } } } };
const { drawTemperaComposition } = await moduleAt('tempera/temperaCompositions.ts');
const geometry = {};
for (const kind of Object.keys(tempera.TEMPERA_SHOT_PROFILES)) {
    const nodes = [];
    drawTemperaComposition({ pixi, kind, width: 1280, height: 720, seed: 137, showDecor: true, bleed: 217.6, flowAngle: -.24, gradient: null,
        palette: { paper: '#171717', ink: '#f2f2f2', blockA: '#323232', blockB: '#626262', blockC: '#969696', accent: '#c9c9c9', line: '#c9c9c9', shadow: '#000000', tone1: '#323232', tone2: '#626262', tone3: '#969696', tone4: '#c9c9c9' },
        decor: { crossCount: 3, motif: 'diamonds', hatchAngle: -.7, scribbleSeed: 137 },
        createGroup(rotation, x, y) { return { rotation, x, y }; },
        add(node, options = {}, parent = null) { node.options = options; node.parent = parent; nodes.push(node); },
    });
    geometry[kind] = nodes;
}
save('tempera-geometry.json', geometry);
console.log(`Recorded ${Object.keys(geometry).length} native vector compositions.`);

class Container {
    x=0;y=0;rotation=0;alpha=1;sx=1;sy=1;children=[];
    position={set:(x,y)=>{this.x=x;this.y=y??x;}};
    scale={set:(x,y)=>{this.sx=x;this.sy=y??x;}};
    addChild(...children){this.children.push(...children);return children[0];}
}
pixi.Container=Container;
const { buildSonnetShotMg } = await moduleAt('sonnet/sonnetShotMg.ts');
const sonnet = [];
for(let variant=0;variant<100;variant++){
    Math.random=createVisualizerRng(`sonnet:${variant}`);
    const scene=buildSonnetShotMg(pixi,'type-impact',{primaryColor:'#f2f2f2',secondaryColor:'#969696'},1280,720,variant,new Map(),false);
    scene.updateTime(10,[],0,6);
    // The moving central drawing and frame are separate so the native camera can move them independently.
    sonnet.push({geo:scene.geoLayer?.toJSON(),hud:scene.bgLayer?.toJSON()});
}
save('sonnet-geometry.json',sonnet);
console.log(`Recorded ${sonnet.length} Sonnet motion-graphics designs.`);

const camera = await moduleAt('diorama/cameraPath.ts');
const { buildDioramaStructuredSurface } = await moduleAt('diorama/dioramaParticleSurfaces.ts');
const surfaces={};
for(const kind of ['box','sphere','cone','torus'])for(const stretch of [1,2,3]){
    const s=buildDioramaStructuredSurface(kind,576,stretch);
    surfaces[`${kind}:${stretch}`]={...s,positions:Array.from(s.positions),normals:Array.from(s.normals)};
}
save('diorama-surfaces.json',surfaces);
const shots=['pushIn','pullBack','orbit','track','crane','hold','swell','spiral','pendulum','flyby','arc','float','glide'];
const formation={};
const frame=camera.buildDioramaPath(1,137)[0];
// Use the canonical straight local frame; Java composes it onto each song's winding path.
frame.position={x:0,y:0,z:0};frame.right={x:1,y:0,z:0};frame.up={x:0,y:1,z:0};frame.forward={x:0,y:0,z:-1};
for(const shot of shots){formation[shot]=Array.from({length:12},(_,i)=>camera.buildFormation(i,137,shot,frame,{offsetR:0,offsetU:0},1));}
save('diorama-formations.json',formation);
const shaderText=readFileSync(resolve(upstream,'src/components/visualizer/diorama/dioramaParticleShaders.ts'),'utf8');
const modelText=readFileSync(resolve(upstream,'src/components/visualizer/diorama/dioramaParticleModel.ts'),'utf8');
const rippleBands=(modelText.match(/export const RIPPLE_BANDS = \[([\s\S]*?)\];/)[1].match(/band:/g)||[]).length;
const rippleSlots=Number(modelText.match(/RIPPLE_SLOTS_PER_BAND = (\d+)/)[1]);
const constants={...camera,DIORAMA_RIPPLE_COUNT:rippleBands*rippleSlots};
function template(name){return shaderText.match(new RegExp(name+' = `([\\s\\S]*?)`;'))[1].replace(/\$\{([^}]+)\}/g,(_,expression)=>Function(...Object.keys(constants),`return (${expression});`)(...Object.values(constants)));}
let vertex=template('DIORAMA_PARTICLE_VERTEX_SHADER')
    .replace('attribute vec3 aNormal;','#define aNormal gl_Normal')
    .replace('attribute vec3 aAnchor;','uniform vec3 aAnchor;')
    .replace('attribute vec3 aScale;','uniform vec3 aScale;')
    .replace('attribute float aPhase;','uniform float aPhase;')
    .replace('attribute vec3 aStyle;','uniform vec3 aStyle;')
    .replace('attribute vec2 aWave;','#define aWave gl_MultiTexCoord0.xy //')
    .replace(/\bposition\b/g,'gl_Vertex.xyz').replace(/\bviewMatrix\b/g,'gl_ModelViewMatrix').replace(/\bprojectionMatrix\b/g,'gl_ProjectionMatrix');
writeFileSync(resolve(output,'diorama.vert'),'// SPDX-License-Identifier: AGPL-3.0-only\n#version 120\nuniform vec3 cameraPosition;\n'+vertex);
// GLSL 1.20 has no boolean-vector mix overload; equivalent component-wise conversion.
let pixel=template('DIORAMA_PARTICLE_FRAGMENT_SHADER').replace('vec3(lessThanEqual(safe, vec3(0.0031308)))','vec3(safe.r <= .0031308 ? 1.0 : 0.0, safe.g <= .0031308 ? 1.0 : 0.0, safe.b <= .0031308 ? 1.0 : 0.0)');
writeFileSync(resolve(output,'diorama.frag'),'// SPDX-License-Identifier: AGPL-3.0-only\n#version 120\n'+pixel);
console.log('Recorded Diorama surfaces, formations and original particle shaders.');
