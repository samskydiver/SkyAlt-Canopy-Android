package com.skyalt.canopy.scene

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLUtils
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer

/**
 * One shader for everything in the scene: lit (two-sided Lambert, as the
 * SceneKit materials) or constant, a uniform colour times optional vertex
 * colours and an optional texture, linear distance fog. A scene that
 * lights its shapes another way passes its own [fragment] source; the
 * uniforms it leaves out are then simply not there.
 */
class SceneShader(vertex: String = VERTEX, fragment: String = FRAGMENT) {
    val program: Int
    val aPosition = 0
    val aNormal = 1
    val aColor = 2
    val aUv = 3
    val uViewProj: Int
    val uModel: Int
    val uColor: Int
    val uUseVertexColor: Int
    val uUseTexture: Int
    val uTexture: Int
    val uUvScale: Int
    val uLit: Int
    val uLightDir: Int
    val uAmbient: Int
    val uSun: Int
    val uEye: Int
    val uFog: Int
    val uFogColor: Int

    init {
        program = link(vertex, fragment)
        uViewProj = GLES30.glGetUniformLocation(program, "uViewProj")
        uModel = GLES30.glGetUniformLocation(program, "uModel")
        uColor = GLES30.glGetUniformLocation(program, "uColor")
        uUseVertexColor = GLES30.glGetUniformLocation(program, "uUseVertexColor")
        uUseTexture = GLES30.glGetUniformLocation(program, "uUseTexture")
        uTexture = GLES30.glGetUniformLocation(program, "uTexture")
        uUvScale = GLES30.glGetUniformLocation(program, "uUvScale")
        uLit = GLES30.glGetUniformLocation(program, "uLit")
        uLightDir = GLES30.glGetUniformLocation(program, "uLightDir")
        uAmbient = GLES30.glGetUniformLocation(program, "uAmbient")
        uSun = GLES30.glGetUniformLocation(program, "uSun")
        uEye = GLES30.glGetUniformLocation(program, "uEye")
        uFog = GLES30.glGetUniformLocation(program, "uFog")
        uFogColor = GLES30.glGetUniformLocation(program, "uFogColor")
    }

    companion object {
        private const val VERTEX = """#version 300 es
layout(location = 0) in vec3 aPosition;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec4 aColor;
layout(location = 3) in vec2 aUv;
uniform mat4 uViewProj;
uniform mat4 uModel;
uniform vec2 uUvScale;
out vec3 vNormal;
out vec4 vColor;
out vec2 vUv;
out vec3 vWorld;
void main() {
    vec4 world = uModel * vec4(aPosition, 1.0);
    vWorld = world.xyz;
    vNormal = mat3(uModel) * aNormal;
    vColor = aColor;
    vUv = aUv * uUvScale;
    gl_Position = uViewProj * world;
}
"""
        private const val FRAGMENT = """#version 300 es
precision highp float;
in vec3 vNormal;
in vec4 vColor;
in vec2 vUv;
in vec3 vWorld;
uniform vec4 uColor;
uniform bool uUseVertexColor;
uniform bool uUseTexture;
uniform sampler2D uTexture;
uniform bool uLit;
uniform vec3 uLightDir;
uniform vec3 uAmbient;
uniform vec3 uSun;
uniform vec3 uEye;
uniform vec2 uFog;
uniform vec3 uFogColor;
out vec4 fragColor;
void main() {
    vec4 base = uColor;
    if (uUseVertexColor) base *= vColor;
    if (uUseTexture) base *= texture(uTexture, vUv);
    vec3 rgb = base.rgb;
    if (uLit) {
        vec3 n = normalize(vNormal);
        if (!gl_FrontFacing) n = -n;
        float d = max(dot(n, uLightDir), 0.0);
        rgb = base.rgb * (uAmbient + uSun * d);
    }
    float dist = distance(vWorld, uEye);
    float f = clamp((dist - uFog.x) / (uFog.y - uFog.x), 0.0, 1.0);
    rgb = mix(rgb, uFogColor, f);
    fragColor = vec4(rgb, base.a);
}
"""

        fun link(vs: String, fs: String): Int {
            val v = compile(GLES30.GL_VERTEX_SHADER, vs)
            val f = compile(GLES30.GL_FRAGMENT_SHADER, fs)
            val p = GLES30.glCreateProgram()
            GLES30.glAttachShader(p, v)
            GLES30.glAttachShader(p, f)
            GLES30.glLinkProgram(p)
            val status = IntArray(1)
            GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, status, 0)
            if (status[0] == 0) Log.e("CanopyGL", "link: " + GLES30.glGetProgramInfoLog(p))
            return p
        }

        private fun compile(type: Int, source: String): Int {
            val s = GLES30.glCreateShader(type)
            GLES30.glShaderSource(s, source)
            GLES30.glCompileShader(s)
            val status = IntArray(1)
            GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) Log.e("CanopyGL", "compile: " + GLES30.glGetShaderInfoLog(s))
            return s
        }
    }
}

/** The sky: a screen-space vertical gradient drawn behind everything. */
class SkyShader {
    val program = SceneShader.link(
        """#version 300 es
layout(location = 0) in vec2 aPosition;
out float vY;
void main() { vY = aPosition.y * 0.5 + 0.5; gl_Position = vec4(aPosition, 0.9999, 1.0); }
""",
        """#version 300 es
precision mediump float;
in float vY;
out vec4 fragColor;
void main() {
    float t = 1.0 - vY;
    vec3 a = vec3(0.357, 0.576, 0.812);
    vec3 b = vec3(0.616, 0.749, 0.878);
    vec3 c = vec3(0.788, 0.847, 0.902);
    vec3 rgb = t < 0.55 ? mix(a, b, t / 0.55) : (t < 0.72 ? mix(b, c, (t - 0.55) / 0.17) : c);
    fragColor = vec4(rgb, 1.0);
}
""",
    )
    private val vbo: Int

    init {
        val ids = IntArray(1)
        GLES30.glGenBuffers(1, ids, 0)
        vbo = ids[0]
        val data = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.size * 4, floatBuffer(data, data.size), GLES30.GL_STATIC_DRAW)
    }

    fun draw() {
        GLES30.glUseProgram(program)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(false)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDepthMask(true)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    }
}

fun floatBuffer(data: FloatArray, count: Int): FloatBuffer {
    val b = ByteBuffer.allocateDirect(maxOf(count, 1) * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    b.put(data, 0, count)
    b.position(0)
    return b
}

fun intBuffer(data: IntArray, count: Int): IntBuffer {
    val b = ByteBuffer.allocateDirect(maxOf(count, 1) * 4).order(ByteOrder.nativeOrder()).asIntBuffer()
    b.put(data, 0, count)
    b.position(0)
    return b
}

/**
 * Vertex buffers on the GPU: positions, and optionally normals, RGBA
 * colours and texture coordinates, drawn with or without indices. Dynamic
 * meshes are refilled with [set].
 */
class GpuMesh(val mode: Int = GLES30.GL_TRIANGLES) {
    private val buffers = IntArray(5)
    private var vertexCount = 0
    private var indexCount = 0
    var hasNormals = false; private set
    var hasColors = false; private set
    var hasUvs = false; private set
    private var hasIndices = false

    init {
        GLES30.glGenBuffers(5, buffers, 0)
    }

    val isEmpty get() = if (hasIndices) indexCount == 0 else vertexCount == 0

    fun set(
        positions: FloatArray,
        positionCount: Int = positions.size,
        normals: FloatArray? = null,
        colors: FloatArray? = null,
        uvs: FloatArray? = null,
        indices: IntArray? = null,
        indexCountIn: Int = indices?.size ?: 0,
    ) {
        vertexCount = positionCount / 3
        upload(0, positions, positionCount)
        hasNormals = normals != null
        if (normals != null) upload(1, normals, vertexCount * 3)
        hasColors = colors != null
        if (colors != null) upload(2, colors, vertexCount * 4)
        hasUvs = uvs != null
        if (uvs != null) upload(3, uvs, vertexCount * 2)
        hasIndices = indices != null
        if (indices != null) {
            indexCount = indexCountIn
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, buffers[4])
            var b = indexStaging
            if (b == null || b.capacity() < indexCount) {
                b = ByteBuffer.allocateDirect(maxOf(indexCount, 64) * 4).order(ByteOrder.nativeOrder()).asIntBuffer()
                indexStaging = b
            }
            b!!.clear()
            b.put(indices, 0, indexCount)
            b.position(0)
            GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, maxOf(indexCount, 1) * 4, b, GLES30.GL_DYNAMIC_DRAW)
        }
    }

    fun set(data: MeshData) = set(data.positions, data.positions.size, data.normals, data.colors, data.uvs, data.indices)

    /** Direct buffers kept between uploads, so a per-frame refill allocates nothing. */
    private val staging = arrayOfNulls<FloatBuffer>(4)
    private var indexStaging: IntBuffer? = null

    private fun upload(slot: Int, data: FloatArray, count: Int) {
        var b = staging[slot]
        if (b == null || b.capacity() < count) {
            b = ByteBuffer.allocateDirect(maxOf(count, 64) * 4 * 3 / 2).order(ByteOrder.nativeOrder()).asFloatBuffer()
            staging[slot] = b
        }
        b!!.clear()
        b.put(data, 0, count)
        b.position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffers[slot])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, maxOf(count, 1) * 4, b, GLES30.GL_DYNAMIC_DRAW)
    }

    fun draw(shader: SceneShader) {
        if (isEmpty) return
        bind(0, shader.aPosition, 3)
        if (hasNormals) bind(1, shader.aNormal, 3) else GLES30.glVertexAttrib3f(shader.aNormal, 0f, 1f, 0f)
        if (hasColors) bind(2, shader.aColor, 4) else GLES30.glVertexAttrib4f(shader.aColor, 1f, 1f, 1f, 1f)
        if (hasUvs) bind(3, shader.aUv, 2) else GLES30.glVertexAttrib2f(shader.aUv, 0f, 0f)
        if (hasIndices) {
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, buffers[4])
            GLES30.glDrawElements(mode, indexCount, GLES30.GL_UNSIGNED_INT, 0)
        } else {
            GLES30.glDrawArrays(mode, 0, vertexCount)
        }
        for (a in 0..3) GLES30.glDisableVertexAttribArray(a)
    }

    private fun bind(slot: Int, attribute: Int, size: Int) {
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffers[slot])
        GLES30.glEnableVertexAttribArray(attribute)
        GLES30.glVertexAttribPointer(attribute, size, GLES30.GL_FLOAT, false, 0, 0)
    }

    fun release() {
        GLES30.glDeleteBuffers(5, buffers, 0)
    }
}

/** CPU-side geometry, as the primitive builders return it. */
class MeshData(
    val positions: FloatArray,
    val normals: FloatArray?,
    val indices: IntArray?,
    val colors: FloatArray? = null,
    val uvs: FloatArray? = null,
)

object Textures {
    fun upload(bitmap: Bitmap, repeat: Boolean, mipmap: Boolean): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
        val wrap = if (repeat) GLES30.GL_REPEAT else GLES30.GL_CLAMP_TO_EDGE
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, wrap)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, wrap)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, if (mipmap) GLES30.GL_LINEAR_MIPMAP_LINEAR else GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        if (mipmap) GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        return ids[0]
    }

    /** Pixels as they are, not premultiplied, for soft alpha drawn with SRC_ALPHA blending. */
    fun uploadRaw(raw: RawTexture, repeat: Boolean): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
        val wrap = if (repeat) GLES30.GL_REPEAT else GLES30.GL_CLAMP_TO_EDGE
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, wrap)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, wrap)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        val buffer = ByteBuffer.allocateDirect(raw.rgba.size).order(ByteOrder.nativeOrder())
        buffer.put(raw.rgba)
        buffer.position(0)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, raw.width, raw.height, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buffer)
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        return ids[0]
    }

    fun delete(id: Int) {
        if (id != 0) GLES30.glDeleteTextures(1, intArrayOf(id), 0)
    }
}

/** A model part on the GPU: its mesh, its place in the model, colour, lighting and texture (0 for none). */
class GpuPart(val mesh: GpuMesh, val model: FloatArray, val color: FloatArray, val lit: Boolean, val texture: Int) {
    fun release() {
        mesh.release()
        Textures.delete(texture)
    }

    companion object {
        fun of(part: ModelPart): GpuPart {
            val mesh = GpuMesh(if (part.lines) GLES30.GL_LINES else GLES30.GL_TRIANGLES).apply { set(part.mesh) }
            val texture = part.texture?.let { Textures.upload(it, repeat = false, mipmap = true) }
                ?: part.raw?.let { Textures.uploadRaw(it, repeat = true) }
                ?: 0
            return GpuPart(mesh, part.model, part.color, part.lit, texture)
        }

        fun of(parts: List<ModelPart>) = parts.map { of(it) }
    }
}
