package com.example.video

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.example.model.AspectRatioMode
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * OpenGL ES 2.0 SurfaceTexture pipeline that renders captured screen frames from
 * a VirtualDisplay (at native display aspect ratio, avoiding OS letterboxing) onto the
 * MediaCodec input surface, stretching the image to full 16:9 with ZERO black bars.
 */
class GlStretchRenderer(
    private val encoderSurface: Surface,
    val outputWidth: Int,
    val outputHeight: Int,
    val captureWidth: Int,
    val captureHeight: Int,
    val aspectRatioMode: AspectRatioMode,
    val targetFps: Int
) {
    companion object {
        private const val TAG = "GlStretchRenderer"

        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES uSampler;
            void main() {
                gl_FragColor = texture2D(uSampler, vTexCoord);
            }
        """
    }

    private var handlerThread: HandlerThread? = null
    private var renderHandler: Handler? = null

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var textureId: Int = 0
    private var program: Int = 0
    private var aPositionLoc: Int = -1
    private var aTexCoordLoc: Int = -1
    private var uTexMatrixLoc: Int = -1
    private var uSamplerLoc: Int = -1

    private var vertexBuffer: FloatBuffer? = null
    private var texCoordBuffer: FloatBuffer? = null

    private var surfaceTexture: SurfaceTexture? = null
    var captureSurface: Surface? = null
        private set

    private val isRunning = AtomicBoolean(false)
    private val texMatrix = FloatArray(16)
    @Volatile private var hasDrawnAtLeastOnce = false
    @Volatile private var lastFrameDrawnTimeMs = 0L

    fun start(): Boolean {
        val latch = CountDownLatch(1)
        var initSuccess = false

        val thread = HandlerThread("VeloStream-GlStretchRender").apply { start() }
        handlerThread = thread
        val handler = Handler(thread.looper)
        renderHandler = handler

        handler.post {
            try {
                initEgl()
                initGl()
                initBuffers()
                initSurfaceTexture()
                isRunning.set(true)
                startRepeatFrameLoop()
                initSuccess = true
                Log.d(TAG, "GlStretchRenderer started successfully: capture ${captureWidth}x${captureHeight} -> output ${outputWidth}x${outputHeight} ($aspectRatioMode)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize GlStretchRenderer: ${e.message}", e)
                cleanupGl()
            } finally {
                latch.countDown()
            }
        }

        try {
            latch.await(3000, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            return false
        }

        return initSuccess && captureSurface != null
    }

    private fun initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw RuntimeException("eglGetDisplay failed: ${EGL14.eglGetError()}")
        }

        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw RuntimeException("eglInitialize failed: ${EGL14.eglGetError()}")
        }

        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGLExt.EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )

        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        var chosen = EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, numConfigs, 0)

        if (!chosen || numConfigs[0] == 0) {
            // Fallback without EGL_RECORDABLE_ANDROID for emulator compatibility
            val fallbackAttribList = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE
            )
            chosen = EGL14.eglChooseConfig(eglDisplay, fallbackAttribList, 0, configs, 0, 1, numConfigs, 0)
            if (!chosen || numConfigs[0] == 0) {
                throw RuntimeException("eglChooseConfig failed: ${EGL14.eglGetError()}")
            }
        }

        val eglConfig = configs[0] ?: throw RuntimeException("Null EGLConfig")

        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            throw RuntimeException("eglCreateContext failed: ${EGL14.eglGetError()}")
        }

        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, encoderSurface, surfaceAttribs, 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            throw RuntimeException("eglCreateWindowSurface failed: ${EGL14.eglGetError()}")
        }

        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw RuntimeException("eglMakeCurrent failed: ${EGL14.eglGetError()}")
        }
    }

    private fun initGl() {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val vShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fShader = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)

        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vShader)
        GLES20.glAttachShader(program, fShader)
        GLES20.glLinkProgram(program)

        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES20.GL_TRUE) {
            val error = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            throw RuntimeException("Could not link GL program: $error")
        }

        aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
        uTexMatrixLoc = GLES20.glGetUniformLocation(program, "uTexMatrix")
        uSamplerLoc = GLES20.glGetUniformLocation(program, "uSampler")
    }

    private fun initBuffers() {
        // Quad vertices calculation
        val vertices: FloatArray = when (aspectRatioMode) {
            AspectRatioMode.STRETCH_16_9 -> {
                // Stretches native tablet/phone screen (4:3, 3:2, 7:5, 20:9) to full 16:9 viewport
                // with ZERO black bars!
                floatArrayOf(
                    -1.0f, -1.0f,
                     1.0f, -1.0f,
                    -1.0f,  1.0f,
                     1.0f,  1.0f
                )
            }
            AspectRatioMode.FIT_16_9 -> {
                // Letterbox / pillarbox with black borders
                val captureAspect = captureWidth.toFloat() / captureHeight.toFloat()
                val outputAspect = outputWidth.toFloat() / outputHeight.toFloat()
                var scaleX = 1.0f
                var scaleY = 1.0f

                if (captureAspect > outputAspect) {
                    // Wider than target ratio: letterbox top/bottom
                    scaleY = outputAspect / captureAspect
                } else if (captureAspect < outputAspect) {
                    // Narrower than target ratio: pillarbox left/right
                    scaleX = captureAspect / outputAspect
                }

                floatArrayOf(
                    -scaleX, -scaleY,
                     scaleX, -scaleY,
                    -scaleX,  scaleY,
                     scaleX,  scaleY
                )
            }
            AspectRatioMode.NATIVE -> {
                // 1:1 mapped to native aspect ratio
                floatArrayOf(
                    -1.0f, -1.0f,
                     1.0f, -1.0f,
                    -1.0f,  1.0f,
                     1.0f,  1.0f
                )
            }
        }

        // Texture coordinates (0,0 to 1,1)
        val texCoords = floatArrayOf(
            0.0f, 0.0f,
            1.0f, 0.0f,
            0.0f, 1.0f,
            1.0f, 1.0f
        )

        vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(vertices)
                position(0)
            }

        texCoordBuffer = ByteBuffer.allocateDirect(texCoords.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(texCoords)
                position(0)
            }
    }

    private fun initSurfaceTexture() {
        val st = SurfaceTexture(textureId).apply {
            setDefaultBufferSize(captureWidth, captureHeight)
            setOnFrameAvailableListener({
                if (isRunning.get()) {
                    renderHandler?.post {
                        onFrameAvailable()
                    }
                }
            }, renderHandler)
        }
        surfaceTexture = st
        captureSurface = Surface(st)
    }

    private fun onFrameAvailable() {
        if (!isRunning.get()) return
        val st = surfaceTexture ?: return
        try {
            st.updateTexImage()
            st.getTransformMatrix(texMatrix)
            val ptsNs = st.timestamp
            drawFrame(if (ptsNs > 0) ptsNs else System.nanoTime())
        } catch (e: Exception) {
            Log.w(TAG, "onFrameAvailable error: ${e.message}")
        }
    }

    private fun drawFrame(ptsNs: Long) {
        if (!isRunning.get()) return

        GLES20.glViewport(0, 0, outputWidth, outputHeight)
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(program)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(uSamplerLoc, 0)
        GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, texMatrix, 0)

        val vb = vertexBuffer ?: return
        vb.position(0)
        GLES20.glEnableVertexAttribArray(aPositionLoc)
        GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 0, vb)

        val tb = texCoordBuffer ?: return
        tb.position(0)
        GLES20.glEnableVertexAttribArray(aTexCoordLoc)
        GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 0, tb)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPositionLoc)
        GLES20.glDisableVertexAttribArray(aTexCoordLoc)

        val presentationTime = if (ptsNs > 0) ptsNs else System.nanoTime()
        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, presentationTime)
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)

        hasDrawnAtLeastOnce = true
        lastFrameDrawnTimeMs = System.currentTimeMillis()
    }

    private fun startRepeatFrameLoop() {
        val intervalMs = (1000L / targetFps.coerceIn(15, 60))
        val repeatRunnable = object : Runnable {
            override fun run() {
                if (!isRunning.get()) return
                val now = System.currentTimeMillis()
                // If screen hasn't produced new frames in intervalMs (static game or pause menu),
                // redraw the last frame so hardware encoder maintains steady framerate, bitrate, and duration
                if (hasDrawnAtLeastOnce && (now - lastFrameDrawnTimeMs >= intervalMs)) {
                    try {
                        drawFrame(System.nanoTime())
                    } catch (e: Exception) {
                        Log.w(TAG, "Frame repeat redraw error: ${e.message}")
                    }
                }
                renderHandler?.postDelayed(this, intervalMs)
            }
        }
        renderHandler?.postDelayed(repeatRunnable, intervalMs)
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)

        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val error = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("Could not compile shader $type: $error")
        }
        return shader
    }

    fun release() {
        isRunning.set(false)
        val handler = renderHandler
        if (handler != null) {
            val latch = CountDownLatch(1)
            handler.post {
                cleanupGl()
                latch.countDown()
            }
            try {
                latch.await(1000, TimeUnit.MILLISECONDS)
            } catch (_: Exception) {}
        }

        try {
            handlerThread?.quitSafely()
            handlerThread?.join(500)
        } catch (_: Exception) {}
        handlerThread = null
        renderHandler = null
    }

    private fun cleanupGl() {
        try {
            captureSurface?.release()
        } catch (_: Exception) {}
        captureSurface = null

        try {
            surfaceTexture?.release()
        } catch (_: Exception) {}
        surfaceTexture = null

        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }

        if (textureId != 0) {
            val textures = intArrayOf(textureId)
            GLES20.glDeleteTextures(1, textures, 0)
            textureId = 0
        }

        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
                eglSurface = EGL14.EGL_NO_SURFACE
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                eglContext = EGL14.EGL_NO_CONTEXT
            }
            EGL14.eglTerminate(eglDisplay)
            eglDisplay = EGL14.EGL_NO_DISPLAY
        }
    }
}
