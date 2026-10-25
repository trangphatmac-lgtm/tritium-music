// Paper Shaders 0.0.80, Copyright 2026 Paper, Apache-2.0.
// Ported to desktop GLSL 1.20; see THIRD_PARTY_NOTICES.md.
#version 120


uniform vec2 u_resolution;
uniform float u_pixelRatio;
uniform float u_originX;
uniform float u_originY;
uniform float u_worldWidth;
uniform float u_worldHeight;
uniform float u_fit;

uniform float u_scale;
uniform float u_rotation;
uniform float u_offsetX;
uniform float u_offsetY;

uniform vec4 u_colorFront;
uniform vec4 u_colorBack;
uniform vec4 u_colorHighlight;

uniform sampler2D u_image;
uniform float u_imageAspectRatio;

uniform float u_type;
uniform float u_pxSize;
uniform bool u_originalColors;
uniform bool u_inverted;
uniform float u_colorSteps;





  float hash21(vec2 p) {
    p = fract(p * vec2(0.3183099, 0.3678794)) + 0.1;
    p += dot(p, p + 19.19);
    return fract(p.x * p.y);
  }


#define TWO_PI 6.28318530718
#define PI 3.14159265358979323846


float getUvFrame(vec2 uv, vec2 pad) {
  float aa = 0.0001;

  float left   = smoothstep(-pad.x, -pad.x + aa, uv.x);
  float right  = smoothstep(1.0 + pad.x, 1.0 + pad.x - aa, uv.x);
  float bottom = smoothstep(-pad.y, -pad.y + aa, uv.y);
  float top    = smoothstep(1.0 + pad.y, 1.0 + pad.y - aa, uv.y);

  return left * right * bottom * top;
}

vec2 getImageUV(vec2 uv) {
  vec2 boxOrigin = vec2(.5 - u_originX, u_originY - .5);
  float r = u_rotation * PI / 180.;
  mat2 graphicRotation = mat2(cos(r), sin(r), -sin(r), cos(r));
  vec2 graphicOffset = vec2(-u_offsetX, u_offsetY);

  vec2 imageBoxSize;
  if (u_fit == 1.) { // contain
    imageBoxSize.x = min(u_resolution.x / u_imageAspectRatio, u_resolution.y) * u_imageAspectRatio;
  } else if (u_fit == 2.) { // cover
    imageBoxSize.x = max(u_resolution.x / u_imageAspectRatio, u_resolution.y) * u_imageAspectRatio;
  } else {
    imageBoxSize.x = min(10.0, 10.0 / u_imageAspectRatio * u_imageAspectRatio);
  }
  imageBoxSize.y = imageBoxSize.x / u_imageAspectRatio;
  vec2 imageBoxScale = u_resolution.xy / imageBoxSize;

  vec2 imageUV = uv;
  imageUV *= imageBoxScale;
  imageUV += boxOrigin * (imageBoxScale - 1.);
  imageUV += graphicOffset;
  imageUV /= u_scale;
  imageUV.x *= u_imageAspectRatio;
  imageUV = graphicRotation * imageUV;
  imageUV.x /= u_imageAspectRatio;

  imageUV += .5;
  imageUV.y = 1. - imageUV.y;

  return imageUV;
}

float bayer2x2(int index) {
  if (index == 0) return 0.0;
  if (index == 1) return 2.0;
  if (index == 2) return 3.0;
  if (index == 3) return 1.0;
  return 0.;
}
float bayer4x4(int index) {
  if (index == 0) return 0.0;
  if (index == 1) return 8.0;
  if (index == 2) return 2.0;
  if (index == 3) return 10.0;
  if (index == 4) return 12.0;
  if (index == 5) return 4.0;
  if (index == 6) return 14.0;
  if (index == 7) return 6.0;
  if (index == 8) return 3.0;
  if (index == 9) return 11.0;
  if (index == 10) return 1.0;
  if (index == 11) return 9.0;
  if (index == 12) return 15.0;
  if (index == 13) return 7.0;
  if (index == 14) return 13.0;
  if (index == 15) return 5.0;
  return 0.;
}

float bayer8x8(int index) {
  if (index == 0) return 0.0;
  if (index == 1) return 32.0;
  if (index == 2) return 8.0;
  if (index == 3) return 40.0;
  if (index == 4) return 2.0;
  if (index == 5) return 34.0;
  if (index == 6) return 10.0;
  if (index == 7) return 42.0;
  if (index == 8) return 48.0;
  if (index == 9) return 16.0;
  if (index == 10) return 56.0;
  if (index == 11) return 24.0;
  if (index == 12) return 50.0;
  if (index == 13) return 18.0;
  if (index == 14) return 58.0;
  if (index == 15) return 26.0;
  if (index == 16) return 12.0;
  if (index == 17) return 44.0;
  if (index == 18) return 4.0;
  if (index == 19) return 36.0;
  if (index == 20) return 14.0;
  if (index == 21) return 46.0;
  if (index == 22) return 6.0;
  if (index == 23) return 38.0;
  if (index == 24) return 60.0;
  if (index == 25) return 28.0;
  if (index == 26) return 52.0;
  if (index == 27) return 20.0;
  if (index == 28) return 62.0;
  if (index == 29) return 30.0;
  if (index == 30) return 54.0;
  if (index == 31) return 22.0;
  if (index == 32) return 3.0;
  if (index == 33) return 35.0;
  if (index == 34) return 11.0;
  if (index == 35) return 43.0;
  if (index == 36) return 1.0;
  if (index == 37) return 33.0;
  if (index == 38) return 9.0;
  if (index == 39) return 41.0;
  if (index == 40) return 51.0;
  if (index == 41) return 19.0;
  if (index == 42) return 59.0;
  if (index == 43) return 27.0;
  if (index == 44) return 49.0;
  if (index == 45) return 17.0;
  if (index == 46) return 57.0;
  if (index == 47) return 25.0;
  if (index == 48) return 15.0;
  if (index == 49) return 47.0;
  if (index == 50) return 7.0;
  if (index == 51) return 39.0;
  if (index == 52) return 13.0;
  if (index == 53) return 45.0;
  if (index == 54) return 5.0;
  if (index == 55) return 37.0;
  if (index == 56) return 63.0;
  if (index == 57) return 31.0;
  if (index == 58) return 55.0;
  if (index == 59) return 23.0;
  if (index == 60) return 61.0;
  if (index == 61) return 29.0;
  if (index == 62) return 53.0;
  if (index == 63) return 21.0;
  return 0.;
}

float getBayerValue(vec2 uv, int size) {
  ivec2 pos = ivec2(fract(uv / float(size)) * float(size));
  int index = pos.y * size + pos.x;

  if (size == 2) {
    return bayer2x2(index) / 4.0;
  } else if (size == 4) {
    return bayer4x4(index) / 16.0;
  } else if (size == 8) {
    return bayer8x8(index) / 64.0;
  }
  return 0.0;
}


void main() {

  float pxSize = u_pxSize * u_pixelRatio;
  vec2 pxSizeUV = gl_FragCoord.xy - .5 * u_resolution;
  pxSizeUV /= pxSize;
  vec2 canvasPixelizedUV = (floor(pxSizeUV) + .5) * pxSize;
  vec2 normalizedUV = canvasPixelizedUV / u_resolution;

  vec2 imageUV = getImageUV(normalizedUV);
  vec2 ditheringNoiseUV = canvasPixelizedUV;
  vec4 image = texture2D(u_image, imageUV);
  float frame = getUvFrame(imageUV, pxSize / u_resolution);

  int type = int(floor(u_type));
  float dithering = 0.0;

  float lum = dot(vec3(.2126, .7152, .0722), image.rgb);
  lum = u_inverted ? (1. - lum) : lum;

  if (type == 1) dithering = step(hash21(ditheringNoiseUV), lum);
  else if (type == 2) dithering = getBayerValue(pxSizeUV, 2);
  else if (type == 3) dithering = getBayerValue(pxSizeUV, 4);
  else dithering = getBayerValue(pxSizeUV, 8);

  float colorSteps = max(floor(u_colorSteps), 1.);
  vec3 color = vec3(0.0);
  float opacity = 1.;

  dithering -= .5;
  float brightness = clamp(lum + dithering / colorSteps, 0.0, 1.0);
  brightness = mix(0.0, brightness, frame);
  brightness = mix(0.0, brightness, image.a);
  float quantLum = floor(brightness * colorSteps + 0.5) / colorSteps;
  quantLum = mix(0.0, quantLum, frame);

  if (u_originalColors == true) {
    vec3 normColor = image.rgb / max(lum, 0.001);
    color = normColor * quantLum;

    float quantAlpha = floor(image.a * colorSteps + 0.5) / colorSteps;
    opacity = mix(quantLum, 1., quantAlpha);
  } else {
    vec3 fgColor = u_colorFront.rgb * u_colorFront.a;
    float fgOpacity = u_colorFront.a;
    vec3 bgColor = u_colorBack.rgb * u_colorBack.a;
    float bgOpacity = u_colorBack.a;
    vec3 hlColor = u_colorHighlight.rgb * u_colorHighlight.a;
    float hlOpacity = u_colorHighlight.a;

    fgColor = mix(fgColor, hlColor, step(1.02 - .02 * u_colorSteps, brightness));
    fgOpacity = mix(fgOpacity, hlOpacity, step(1.02 - .02 * u_colorSteps, brightness));

    color = fgColor * quantLum;
    opacity = fgOpacity * quantLum;
    color += bgColor * (1.0 - opacity);
    opacity += bgOpacity * (1.0 - opacity);
  }

  gl_FragColor = vec4(color, opacity);
}

