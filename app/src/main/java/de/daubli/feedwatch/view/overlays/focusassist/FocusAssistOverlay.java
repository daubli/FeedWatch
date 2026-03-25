package de.daubli.feedwatch.view.overlays.focusassist;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import android.opengl.GLES20;
import de.daubli.feedwatch.view.overlays.Overlay;

public class FocusAssistOverlay extends Overlay {

    private final FloatBuffer vertexBuffer;

    private final FloatBuffer texCoordBuffer;

    private final float[] dynTex = new float[8];

    private final float[] dynVertices = new float[8]; // reuse to avoid per-frame allocation

    private boolean initialized = false;

    private int program = 0;

    private int aPosition = -1;

    private int aTexCoord = -1;

    private int uTexture = -1;

    private int uTexelSize = -1;

    private int uThreshold = -1;

    private int uEdgeColor = -1;

    // configurable
    private float threshold = 0.3f;

    public FocusAssistOverlay() {
        vertexBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        texCoordBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    }

    public void setThreshold(float threshold) {
        this.threshold = threshold;
        if (initialized && program != 0) {
            GLES20.glUseProgram(program);
            GLES20.glUniform1f(uThreshold, this.threshold);
        }
    }

    private void initGL() {
        if (initialized) {
            return;
        }

        //@formatter:off
        final String vertexShader =
                "attribute vec4 aPosition;" +
                        "attribute vec2 aTexCoord;" +
                        "varying vec2 vTexCoord;" +
                        "void main() {" +
                        "  gl_Position = aPosition;" +
                        "  vTexCoord = aTexCoord;" +
                        "}";

        final String fragmentShader =
                "precision mediump float;" +
                        "uniform sampler2D uTexture;" +
                        "uniform vec2 uTexelSize;" +
                        "uniform float uThreshold;" +
                        "uniform vec3 uEdgeColor;" +
                        "varying vec2 vTexCoord;" +

                        "float gray(vec2 uv) {" +
                        "  vec3 c = texture2D(uTexture, uv).rgb;" +
                        "  return (c.r + c.g + c.b) * 0.3333333;" +
                        "}" +

                        "float blurredGray(vec2 uv) {" +
                        "  float kernel[25];" +
                        "  kernel[ 0]=1.0;  kernel[ 1]=4.0;  kernel[ 2]=7.0;  kernel[ 3]=4.0;  kernel[ 4]=1.0;" +
                        "  kernel[ 5]=4.0;  kernel[ 6]=16.0; kernel[ 7]=26.0; kernel[ 8]=16.0; kernel[ 9]=4.0;" +
                        "  kernel[10]=7.0;  kernel[11]=26.0; kernel[12]=41.0; kernel[13]=26.0; kernel[14]=7.0;" +
                        "  kernel[15]=4.0;  kernel[16]=16.0; kernel[17]=26.0; kernel[18]=16.0; kernel[19]=4.0;" +
                        "  kernel[20]=1.0;  kernel[21]=4.0;  kernel[22]=7.0;  kernel[23]=4.0;  kernel[24]=1.0;" +

                        "  float sum = 0.0;" +
                        "  float wsum = 0.0;" +
                        "  int idx = 0;" +
                        "  for (int y = -2; y <= 2; y++) {" +
                        "    for (int x = -2; x <= 2; x++) {" +
                        "      vec2 o = vec2(float(x), float(y)) * uTexelSize;" +
                        "      float w = kernel[idx];" +
                        "      sum += gray(uv + o) * w;" +
                        "      wsum += w;" +
                        "      idx++;" +
                        "    }" +
                        "  }" +
                        "  return sum / wsum;" +
                        "}" +

                        "void main() {" +
                        "  float sx[9];" +
                        "  sx[0]= 1.0; sx[1]= 0.0; sx[2]=-1.0;" +
                        "  sx[3]= 2.0; sx[4]= 0.0; sx[5]=-2.0;" +
                        "  sx[6]= 1.0; sx[7]= 0.0; sx[8]=-1.0;" +

                        "  float sy[9];" +
                        "  sy[0]= 1.0; sy[1]= 2.0; sy[2]= 1.0;" +
                        "  sy[3]= 0.0; sy[4]= 0.0; sy[5]= 0.0;" +
                        "  sy[6]=-1.0; sy[7]=-2.0; sy[8]=-1.0;" +

                        "  float gx = 0.0;" +
                        "  float gy = 0.0;" +
                        "  int i = 0;" +
                        "  for (int y = -1; y <= 1; y++) {" +
                        "    for (int x = -1; x <= 1; x++) {" +
                        "      vec2 o = vec2(float(x), float(y)) * uTexelSize;" +
                        "      float v = blurredGray(vTexCoord + o);" +
                        "      gx += v * sx[i];" +
                        "      gy += v * sy[i];" +
                        "      i++;" +
                        "    }" +
                        "  }" +

                        "  float edge = length(vec2(gx, gy));" +
                        "  float alpha = (edge >= uThreshold) ? edge : 0.0;" +
                        "  alpha = clamp(alpha, 0.0, 1.0);" +
                        "  gl_FragColor = vec4(uEdgeColor, alpha);" +
                        "}";
        //@formatter:on

        program = createProgram(vertexShader, fragmentShader);

        aPosition = GLES20.glGetAttribLocation(program, "aPosition");
        aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord");

        uTexture = GLES20.glGetUniformLocation(program, "uTexture");
        uTexelSize = GLES20.glGetUniformLocation(program, "uTexelSize");
        uThreshold = GLES20.glGetUniformLocation(program, "uThreshold");
        uEdgeColor = GLES20.glGetUniformLocation(program, "uEdgeColor");

        GLES20.glUseProgram(program);
        GLES20.glUniform1f(uThreshold, threshold);
        GLES20.glUniform3f(uEdgeColor, 1.0f, 0.0f, 0.0f);

        initialized = true;
    }

    @Override
    public void draw(int videoTextureId, int videoRectLeft, int videoRectTop, int videoRectWidth, int videoRectHeight,
            int surfaceWidth, int surfaceHeight) {

        initGL();
        GLES20.glUseProgram(program);

        // We sample the surface-sized FBO texture
        GLES20.glUniform2f(uTexelSize, 1.0f / (float) surfaceWidth, 1.0f / (float) surfaceHeight);
        GLES20.glUniform1f(uThreshold, threshold);

        // Compute NDC quad for the video rect (no heap alloc)
        float left = 2f * videoRectLeft / surfaceWidth - 1f;
        float right = 2f * (videoRectLeft + videoRectWidth) / surfaceWidth - 1f;
        float top = 1f - 2f * videoRectTop / surfaceHeight;
        float bottom = 1f - 2f * (videoRectTop + videoRectHeight) / surfaceHeight;

        dynVertices[0] = left;
        dynVertices[1] = top;
        dynVertices[2] = left;
        dynVertices[3] = bottom;
        dynVertices[4] = right;
        dynVertices[5] = top;
        dynVertices[6] = right;
        dynVertices[7] = bottom;

        vertexBuffer.clear();
        vertexBuffer.put(dynVertices).position(0);

        // Compute UV crop into the FBO texture for the same rect
        float u0 = (float) videoRectLeft / (float) surfaceWidth;
        float u1 = (float) (videoRectLeft + videoRectWidth) / (float) surfaceWidth;

        float v0 = (float) videoRectTop / (float) surfaceHeight;
        float v1 = (float) (videoRectTop + videoRectHeight) / (float) surfaceHeight;

        // flip the image vertically because FBO texture is flipped compared to video frame
        float tmp = v0;
        v0 = v1;
        v1 = tmp;

        // crop the image a bit to avoid detecting the edge at the video border
        final float borderTexels = 3.5f;
        final float du = borderTexels / (float) surfaceWidth;
        final float dv = borderTexels / (float) surfaceHeight;

        u0 += du;
        u1 -= du;
        v0 += dv;
        v1 -= dv;

        // Vertex order: (left,top), (left,bottom), (right,top), (right,bottom)
        dynTex[0] = u0;
        dynTex[1] = v0;
        dynTex[2] = u0;
        dynTex[3] = v1;
        dynTex[4] = u1;
        dynTex[5] = v0;
        dynTex[6] = u1;
        dynTex[7] = v1;

        texCoordBuffer.clear();
        texCoordBuffer.put(dynTex).position(0);

        // Blending overlay
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);

        // Attributes
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer);
        GLES20.glEnableVertexAttribArray(aPosition);

        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer);
        GLES20.glEnableVertexAttribArray(aTexCoord);

        // Texture
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, videoTextureId);
        GLES20.glUniform1i(uTexture, 0);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glDisableVertexAttribArray(aPosition);
        GLES20.glDisableVertexAttribArray(aTexCoord);
        GLES20.glDisable(GLES20.GL_BLEND);
    }

    private int createProgram(String vsCode, String fsCode) {
        int vs = compileShader(GLES20.GL_VERTEX_SHADER, vsCode);
        int fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fsCode);

        int prog = GLES20.glCreateProgram();
        GLES20.glAttachShader(prog, vs);
        GLES20.glAttachShader(prog, fs);
        GLES20.glLinkProgram(prog);

        int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linkStatus, 0);
        if (linkStatus[0] == 0) {
            String log = GLES20.glGetProgramInfoLog(prog);
            GLES20.glDeleteProgram(prog);
            throw new RuntimeException("Program link failed: " + log);
        }
        return prog;
    }

    private int compileShader(int type, String code) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, code);
        GLES20.glCompileShader(shader);

        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new RuntimeException("Shader compile failed: " + log);
        }
        return shader;
    }
}
