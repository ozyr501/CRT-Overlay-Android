uniform mat4 uSTMatrix;
attribute vec4 aPosition;
attribute vec4 aTexCoord;
varying highp vec2 vTexCoord;
void main() {
  gl_Position = aPosition;
  vTexCoord = (uSTMatrix * aTexCoord).xy;
}
