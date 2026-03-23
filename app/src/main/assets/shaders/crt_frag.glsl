#extension GL_OES_EGL_image_external : require
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
varying highp vec2 vTexCoord;
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
uniform float uColorTemp;
uniform float uBlackLevel;
uniform float uHalation;
uniform int   uMaskType;
uniform float uGamma;
const float PI = 3.14159265;

vec2 curve(vec2 uv) {
  vec2 cc = uv * 2.0 - 1.0;
  float k = uCurvature * 0.12;
  float d = dot(cc, cc) * k;
  return uv + cc * d;
}

float gaussW(float d2) {
  return exp(-d2 * 2.0);
}

/* Single tap at emulated cell centre (same convention as pre-integration shader). */
vec3 sampleCellPoint(vec2 nPx) {
  return texture2D(sTexture, (nPx + 0.5) / uEmulatedSize).rgb;
}

/* Box average over the cell in normalized UV — reduces aliasing when capture >> emulated grid. */
vec3 sampleCellBox2x2(vec2 nPx) {
  vec2 cellMin = nPx / uEmulatedSize;
  vec2 cellMax = (nPx + vec2(1.0)) / uEmulatedSize;
  vec3 s = texture2D(sTexture, mix(cellMin, cellMax, vec2(0.25, 0.25))).rgb;
  s += texture2D(sTexture, mix(cellMin, cellMax, vec2(0.75, 0.25))).rgb;
  s += texture2D(sTexture, mix(cellMin, cellMax, vec2(0.25, 0.75))).rgb;
  s += texture2D(sTexture, mix(cellMin, cellMax, vec2(0.75, 0.75))).rgb;
  return s * 0.25;
}

vec3 sampleCellBox3x3(vec2 nPx) {
  vec2 cellMin = nPx / uEmulatedSize;
  vec2 cellMax = (nPx + vec2(1.0)) / uEmulatedSize;
  vec3 s = vec3(0.0);
  for (float oy = 0.0; oy < 3.0; oy += 1.0) {
    for (float ox = 0.0; ox < 3.0; ox += 1.0) {
      vec2 t = mix(cellMin, cellMax, (vec2(ox, oy) + vec2(0.5)) / 3.0);
      s += texture2D(sTexture, t).rgb;
    }
  }
  return s * (1.0 / 9.0);
}

void main() {
  vec2 uv = curve(vTexCoord);
  if (uv.x < 0.001 || uv.x > 0.999 || uv.y < 0.001 || uv.y > 0.999) {
    gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
    return;
  }

  vec2 emCont = uv * uEmulatedSize;
  vec2 emPx   = floor(emCont);

  /* Downscale ratio: how many capture texels map to one emulated step (approx). */
  float minScale = min(uTexSize.x / uEmulatedSize.x, uTexSize.y / uEmulatedSize.y);

  vec3 acc = vec3(0.0);
  float wTotal = 0.0;
  float hSpread = 0.55 + uPhosphorBleed * 2.35;
  float vSpread = 0.45 + uPhosphorBleed * 1.12;

  vec3 haloAcc = vec3(0.0);
  float haloWTotal = 0.0;
  float haloSpread = 4.0;

  float loopExtent = (uPhosphorBleed < 0.15) ? 1.0 : 2.0;

  for (float dy = -2.0; dy <= 2.0; dy += 1.0) {
    if (abs(dy) > loopExtent) continue;
    for (float dx = -2.0; dx <= 2.0; dx += 1.0) {
      if (abs(dx) > loopExtent) continue;
      vec2 nPx = emPx + vec2(dx, dy);
      vec2 nCenter = nPx + 0.5;
      vec2 diff = emCont - nCenter;
      float d2 = (diff.x * diff.x) / (hSpread * hSpread)
               + (diff.y * diff.y) / (vSpread * vSpread);
      float w = gaussW(d2);
      vec3 cellRgb;
      if (dx == 0.0 && dy == 0.0) {
        if (minScale >= 5.0) {
          cellRgb = sampleCellBox3x3(nPx);
        } else if (minScale >= 1.5) {
          cellRgb = sampleCellBox2x2(nPx);
        } else {
          cellRgb = sampleCellPoint(nPx);
        }
      } else {
        cellRgb = sampleCellPoint(nPx);
      }
      acc += cellRgb * w;
      wTotal += w;

      if (uHalation > 0.001) {
        float hd2 = (diff.x * diff.x + diff.y * diff.y)
                   / (haloSpread * haloSpread);
        float hw = gaussW(hd2);
        haloAcc += cellRgb * hw;
        haloWTotal += hw;
      }
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

  /* --- RGB mask ---------------------------------------------------------- */
  vec3 mask;
  float maskPhase = emCont.x * 3.0 * PI * 2.0 / 3.0;
  if (uMaskType == 1) {
    maskPhase += step(0.5, fract(emCont.y * 0.5)) * PI;
    mask = vec3(
      0.5 + 0.5 * cos(maskPhase),
      0.5 + 0.5 * cos(maskPhase - PI * 2.0 / 3.0),
      0.5 + 0.5 * cos(maskPhase - PI * 4.0 / 3.0)
    );
  } else if (uMaskType == 2) {
    float rowOff = mod(floor(emCont.y), 3.0) * PI * 2.0 / 3.0;
    maskPhase += rowOff;
    float dotEnv = 0.8 + 0.2 * cos(emCont.y * PI);
    mask = vec3(
      0.5 + 0.5 * cos(maskPhase),
      0.5 + 0.5 * cos(maskPhase - PI * 2.0 / 3.0),
      0.5 + 0.5 * cos(maskPhase - PI * 4.0 / 3.0)
    ) * dotEnv;
  } else {
    mask = vec3(
      0.5 + 0.5 * cos(maskPhase),
      0.5 + 0.5 * cos(maskPhase - PI * 2.0 / 3.0),
      0.5 + 0.5 * cos(maskPhase - PI * 4.0 / 3.0)
    );
  }
  mask = mix(vec3(1.0), 0.75 + 0.25 * mask, uMaskStrength);
  col *= mask * scanMul;

  /* --- brightness compensation ------------------------------------------- *
   * The mask and scanlines dim the image; boost to keep perceived           *
   * brightness closer to the original.                                     */
  // Reduced lift so midtones (e.g., warm "peach" colours) don't drift too yellow.
  col *= 1.03 + 0.10 * uScanStrength + 0.06 * uMaskStrength;

  /* --- vignette ---------------------------------------------------------- */
  vec2 vig = uv * 2.0 - 1.0;
  float vigAmt = clamp(1.0 - dot(vig, vig) * uVignette * 0.38, 0.0, 1.0);
  col *= vigAmt;

  /* --- bloom ------------------------------------------------------------- */
  if (uBloom > 0.001) {
    vec3 hi = max(col - vec3(0.55), vec3(0.0));
    col += hi * hi * (uBloom * 3.0);
  }

  /* --- halation (glass glow) ------------------------------------------- */
  if (uHalation > 0.001 && haloWTotal > 0.001) {
    vec3 haloColor = haloAcc / haloWTotal;
    col += haloColor * uHalation * 0.3;
  }

  /* --- color temperature ------------------------------------------------ */
  if (uColorTemp > 0.001) {
    float warmth = uColorTemp * 0.12;
    col.r *= 1.0 + warmth;
    col.g *= 1.0 + warmth * 0.4;
    col.b *= 1.0 - warmth * 0.6;
  }

  /* --- gamma correction ------------------------------------------------- */
  if (uGamma > 0.01) {
    col = pow(max(col, vec3(0.0)), vec3(2.2 / uGamma));
  }

  /* --- OLED black level floor ------------------------------------------- */
  col = max(col, vec3(uBlackLevel * 0.04));

  gl_FragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
}
