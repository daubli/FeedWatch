package de.daubli.feedwatch.view.base;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import android.opengl.GLES20;

public class FullscreenTextureRenderer {

    private static final float[] VERTICES = { -1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f };

    private static final float[] TEXCOORDS = { 0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f };

    private static int program = -1;

    private static int posHandle;

    private static int texHandle;

    private static int samplerHandle;

    private static FloatBuffer vertexBuffer;

    private static FloatBuffer texBuffer;

    public static void init() {
        vertexBuffer = ByteBuffer.allocateDirect(VERTICES.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        vertexBuffer.put(VERTICES).position(0);

        texBuffer = ByteBuffer.allocateDirect(TEXCOORDS.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        texBuffer.put(TEXCOORDS).position(0);

        String vs =
                "attribute vec4 aPosition;" + "attribute vec2 aTexCoord;" + "varying vec2 vTexCoord;" + "void main() {"
                        + "  gl_Position = aPosition;" + "  vTexCoord = aTexCoord;" + "}";

        String fs =
                "precision mediump float;" + "uniform sampler2D uTexture;" + "varying vec2 vTexCoord;" + "void main() {"
                        + "  gl_FragColor = texture2D(uTexture, vTexCoord);" + "}";

        program = createProgram(vs, fs);
        posHandle = GLES20.glGetAttribLocation(program, "aPosition");
        texHandle = GLES20.glGetAttribLocation(program, "aTexCoord");
        samplerHandle = GLES20.glGetUniformLocation(program, "uTexture");
    }

    public static void drawTexture(int textureId) {
        if (program == 0) {
            init();
        }

        GLES20.glUseProgram(program);

        GLES20.glEnableVertexAttribArray(posHandle);
        vertexBuffer.position(0);
        GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer);

        GLES20.glEnableVertexAttribArray(texHandle);
        texBuffer.position(0);
        GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, 0, texBuffer);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
        GLES20.glUniform1i(samplerHandle, 0);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glDisableVertexAttribArray(posHandle);
        GLES20.glDisableVertexAttribArray(texHandle);
    }

    private static int createProgram(String vs, String fs) {
        int vShader = compileShader(GLES20.GL_VERTEX_SHADER, vs);
        int fShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fs);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, vShader);
        GLES20.glAttachShader(p, fShader);
        GLES20.glLinkProgram(p);
        return p;
    }

    private static int compileShader(int type, String code) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, code);
        GLES20.glCompileShader(shader);
        return shader;
    }
}
