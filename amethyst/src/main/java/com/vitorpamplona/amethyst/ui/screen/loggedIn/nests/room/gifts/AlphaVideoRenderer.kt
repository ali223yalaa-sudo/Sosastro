/*
 * Copyright (c) 2025 Vitor Pamplona
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the
 * Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package com.vitorpamplona.amethyst.ui.screen.loggedIn.nests.room.gifts

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Composites an AlphaPlayer-packed video (a colour frame stacked over an
 * alpha strip) into a single RGBA frame on the GPU.
 *
 * H.264 carries no alpha and the platform video decoder only emits opaque
 * YUV, so a gift video would otherwise be a black box over the room. The
 * packer (`alpha_pack.py`) writes the alpha channel as a second frame below
 * the colour one; this shader samples the top half for RGB and the bottom
 * half's luma for alpha, then draws the result with real transparency.
 *
 * Everything runs on the GPU: the decoder is the phone's hardware H.264
 * block and the composite is one fragment shader per frame. That is the whole
 * point — an SVGA clip is a ZIP of PNGs decoded in software frame by frame,
 * which is why a 20-second clip cost 16 MB of download and visible RAM.
 */
internal class AlphaVideoRenderer(
    private val videoWidth: Int,
    private val videoHeight: Int,
) : GLSurfaceView.Renderer {
    companion object {
        private const val TAG = "AlphaVideoRenderer"

        /**
         * How much of the phone's height a vertical gift clip fills. The
         * operator asked for a near-full-height takeover (about 95%), so a
         * vertical scene reads as part of the room rather than a small box.
         */
        private const val VERTICAL_FILL = 0.95f

        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """

        // The packer stacks RGB over an alpha strip, so the decoded image is
        // twice as tall as one frame. `vTexCoord` runs 0..1 over the drawn
        // quad, which shows the *whole* packed image upright, so the colour
        // half is the image's top half (q.y in [0.5, 1]) and the alpha strip
        // its bottom half (q.y in [0, 0.5]). Each sample is pushed through the
        // decoder's transform matrix, which is what actually puts the image
        // the right way up on any device — we never guess the flip ourselves.
        // The alpha strip is three identical channels, so sampling green is
        // exact and 4:2:0 chroma subsampling cannot bleed into the matte.
        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES uTexture;
            uniform mat4 uTexMatrix;
            vec2 mapTex(vec2 q) {
                return (uTexMatrix * vec4(q, 0.0, 1.0)).xy;
            }
            void main() {
                vec2 color = mapTex(vec2(vTexCoord.x, 0.5 + vTexCoord.y * 0.5));
                vec2 alpha = mapTex(vec2(vTexCoord.x, vTexCoord.y * 0.5));
                gl_FragColor = vec4(texture2D(uTexture, color).rgb, texture2D(uTexture, alpha).g);
            }
        """

        private val VERTICES = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)

        // Standard bottom-left origin; the decoder's transform matrix handles
        // any vertical flip, so no manual V inversion belongs here.
        private val TEX_COORDS = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
    }

    private var program = 0
    private var textureId = 0
    private var positionLocation = 0
    private var texCoordLocation = 0
    private var texMatrixLocation = 0
    private val texMatrix = FloatArray(16)

    // The clip is drawn into a letterboxed quad so a square or vertical gift
    // keeps its own shape instead of being stretched to the screen's aspect
    // ratio. Rebuilt whenever the viewport changes.
    private var vertexBuffer: FloatBuffer = createFloatBuffer(VERTICES)
    private val texCoordBuffer: FloatBuffer = createFloatBuffer(TEX_COORDS)

    // The operator's placement, applied to the quad rather than to the view:
    // a GLSurfaceView's surface is composited by the system in its own layer,
    // so a Compose `graphicsLayer` on the hosting view is ignored and the
    // clip would stay full-screen no matter what the dashboard saved.
    private var baseSx = 1f
    private var baseSy = 1f
    private var clipScale = 1f
    private var clipOffsetX = 0f
    private var clipOffsetY = 0f

    private var surfaceTexture: SurfaceTexture? = null
    private var outputSurface: Surface? = null

    /** Called on the GL thread once the decoder's surface exists. */
    var onSurfaceReady: ((Surface) -> Unit)? = null

    override fun onSurfaceCreated(
        gl: GL10?,
        config: EGLConfig?,
    ) {
        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        positionLocation = GLES20.glGetAttribLocation(program, "aPosition")
        texCoordLocation = GLES20.glGetAttribLocation(program, "aTexCoord")
        texMatrixLocation = GLES20.glGetUniformLocation(program, "uTexMatrix")

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val st = SurfaceTexture(textureId)
        st.setDefaultBufferSize(videoWidth, videoHeight)
        surfaceTexture = st
        outputSurface = Surface(st)
        onSurfaceReady?.invoke(outputSurface!!)

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
    }

    override fun onSurfaceChanged(
        gl: GL10?,
        width: Int,
        height: Int,
    ) {
        GLES20.glViewport(0, 0, width, height)

        // Letterbox a horizontal/square clip (scale to fit inside the
        // viewport, centred) so a square gift stays square. A vertical clip
        // instead fills the phone top-to-bottom at [VERTICAL_FILL] of the
        // height, cropping any horizontal overflow: a vertical gift is a
        // full-height takeover that reads as part of the room, not a small
        // centred box with wide transparent bars.
        val clipAspect = videoWidth.toFloat() / videoHeight.toFloat()
        val viewAspect = width.toFloat() / height.toFloat()
        val sx: Float
        val sy: Float
        if (videoHeight > videoWidth) {
            sy = VERTICAL_FILL
            sx = VERTICAL_FILL * clipAspect / viewAspect
        } else if (clipAspect > viewAspect) {
            sx = 1f
            sy = viewAspect / clipAspect
        } else {
            sx = clipAspect / viewAspect
            sy = 1f
        }
        baseSx = sx
        baseSy = sy
        rebuildQuad()
    }

    /**
     * Apply the operator's saved placement. Called from the GL thread (via
     * [GLSurfaceView.queueEvent]) so the quad is rebuilt without tearing.
     */
    fun setPlacement(
        scale: Float,
        offsetX: Float,
        offsetY: Float,
    ) {
        clipScale = scale
        clipOffsetX = offsetX
        clipOffsetY = offsetY
        rebuildQuad()
    }

    /**
     * Fold the letterbox, the operator's scale and the fractional offset into
     * one quad. The offset is a fraction of the screen, matching the
     * dashboard's preview (and the app's old graphicsLayer maths), so a value
     * of 0.2 moves the clip a fifth of the screen to the right.
     */
    private fun rebuildQuad() {
        val sx = baseSx * clipScale
        val sy = baseSy * clipScale
        val dx = clipOffsetX * 2f
        val dy = -clipOffsetY * 2f
        vertexBuffer = createFloatBuffer(floatArrayOf(-sx + dx, -sy + dy, sx + dx, -sy + dy, -sx + dx, sy + dy, sx + dx, sy + dy))
    }

    override fun onDrawFrame(gl: GL10?) {
        val st = surfaceTexture ?: return
        st.updateTexImage()
        st.getTransformMatrix(texMatrix)

        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(program)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexture"), 0)
        GLES20.glUniformMatrix4fv(texMatrixLocation, 1, false, texMatrix, 0)

        vertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(positionLocation)
        GLES20.glVertexAttribPointer(positionLocation, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        texCoordBuffer.position(0)
        GLES20.glEnableVertexAttribArray(texCoordLocation)
        GLES20.glVertexAttribPointer(texCoordLocation, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(positionLocation)
        GLES20.glDisableVertexAttribArray(texCoordLocation)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        GLES20.glUseProgram(0)
    }

    /** Release GL objects. Call on the GL thread (from [GLSurfaceView.queueEvent]). */
    fun release() {
        surfaceTexture?.release()
        surfaceTexture = null
        outputSurface?.release()
        outputSurface = null
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
        if (textureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }
    }

    private fun createFloatBuffer(data: FloatArray): FloatBuffer =
        ByteBuffer
            .allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(data)
                position(0)
            }

    private fun buildProgram(
        vertexSource: String,
        fragmentSource: String,
    ): Int {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vertexShader)
        GLES20.glAttachShader(prog, fragmentShader)
        GLES20.glLinkProgram(prog)
        val status = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "program link failed: ${GLES20.glGetProgramInfoLog(prog)}")
        }
        return prog
    }

    private fun compileShader(
        type: Int,
        source: String,
    ): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}")
        }
        return shader
    }
}
