#extension GL_OES_EGL_image_external : require
precision mediump float;
varying vec2 vTexCoord;
uniform samplerExternalOES sTexture;
uniform vec2 uTexSize;
uniform vec2 uEmulatedSize;
uniform float uScanStrength;
uniform float uScanSpacing;
uniform float uMaskStrength;
uniform float uVignette;
uniform float uCurvature;
uniform float uBloom;
uniform float uPhosphorBleed;
const float PI = 3.14159265;

vec2 curve(vec2 uv) {
  vec2 cc = uv * 2.0 - 1.0;
  float k = uCurvature * 0.12;
  float d = dot(cc, cc) * k;
  return uv + cc * d;
}

float gaussW(float d2) {
  return exp(-d2 * 1.8);
}

void main() {
  vec2 uv = curve(vTexCoord);
  if (uv.x < 0.001 || uv.x > 0.999 || uv.y < 0.001 || uv.y > 0.999) {
    gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
    return;
  }

  vec2 emCont = uv * uEmulatedSize;
  vec2 emPx   = floor(emCont);

  /* --- soft phosphor glow: weighted blend of surrounding emulated pixels - *
   * Wider kernel + softer falloff gives the dreamy, painted look of a CRT.  *
   * Horizontal spread is wider (phosphors bleed more side-to-side).         */
  vec3 acc = vec3(0.0);
  float wTotal = 0.0;
  float hSpread = 1.2 + uPhosphorBleed * 2.0;
  float vSpread = 0.9 + uPhosphorBleed * 0.8;

  for (float dy = -2.0; dy <= 2.0; dy += 1.0) {
    for (float dx = -2.0; dx <= 2.0; dx += 1.0) {
      vec2 nPx = emPx + vec2(dx, dy);
      vec2 nCenter = nPx + 0.5;
      vec2 diff = emCont - nCenter;
      float d2 = (diff.x * diff.x) / (hSpread * hSpread)
               + (diff.y * diff.y) / (vSpread * vSpread);
      float w = gaussW(d2);
      vec2 sUv = nCenter / uEmulatedSize;
      acc += texture2D(sTexture, sUv).rgb * w;
      wTotal += w;
    }
  }
  vec3 col = acc / max(wTotal, 0.001);

  /* --- scanlines --------------------------------------------------------- *
   * Gaussian beam profile per emulated row. The beam brightens the center   *
   * of each row; between rows it dims. We compensate brightness so the      *
   * average is not crushed.                                                 */
  float rowFract = fract(emCont.y);
  float distFromCenter = abs(rowFract - 0.5);
  float beamSigma = 0.30 + 0.20 * (1.0 - uScanSpacing);
  float beam = exp(-(distFromCenter * distFromCenter) / (2.0 * beamSigma * beamSigma));
  float scanMul = mix(1.0, beam, uScanStrength * 0.7);

  /* --- RGB shadow mask --------------------------------------------------- *
   * Subtle sinusoidal tint per sub-pixel column. At normal strength this    *
   * just adds a slight texture / colour fringing, not heavy coloured bars.  */
  float maskPhase = emCont.x * 3.0 * PI * 2.0 / 3.0;
  vec3 mask = vec3(
    0.5 + 0.5 * cos(maskPhase),
    0.5 + 0.5 * cos(maskPhase - PI * 2.0 / 3.0),
    0.5 + 0.5 * cos(maskPhase - PI * 4.0 / 3.0)
  );
  mask = mix(vec3(1.0), 0.75 + 0.25 * mask, uMaskStrength);
  col *= mask * scanMul;

  /* --- brightness compensation ------------------------------------------- *
   * The mask and scanlines dim the image; boost to keep perceived           *
   * brightness closer to the original.                                     */
  col *= 1.15 + 0.15 * uScanStrength + 0.10 * uMaskStrength;

  /* --- vignette ---------------------------------------------------------- */
  vec2 vig = uv * 2.0 - 1.0;
  float vigAmt = clamp(1.0 - dot(vig, vig) * uVignette * 0.38, 0.0, 1.0);
  col *= vigAmt;

  /* --- bloom ------------------------------------------------------------- */
  if (uBloom > 0.001) {
    vec3 hi = max(col - vec3(0.45), vec3(0.0));
    col += hi * hi * (uBloom * 3.0);
  }

  gl_FragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
}
