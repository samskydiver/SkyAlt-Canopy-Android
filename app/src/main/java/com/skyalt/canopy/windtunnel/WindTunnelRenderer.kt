package com.skyalt.canopy.windtunnel

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Build
import com.skyalt.canopy.scene.GpuMesh
import com.skyalt.canopy.scene.MeshData
import com.skyalt.canopy.scene.Primitives
import com.skyalt.canopy.scene.RawTexture
import com.skyalt.canopy.scene.SceneShader
import com.skyalt.canopy.scene.Textures
import com.skyalt.canopy.scene.floatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Draws the wind tunnel's 3D picture with OpenGL ES 3: what
 * WindTunnelSceneController.swift hands to SceneKit. Each frame it steps
 * the engine, lets [model] work the picture out, and draws it: the lit
 * mannequin first, then what is solid and flat (rings, arrows), then what
 * is see-through (grids, glass, net, streaks, the path), which never hides
 * anything behind it.
 */
class WindTunnelRenderer(
    private val engine: WindTunnelEngine,
    val model: WindTunnelSceneModel,
    private val density: Float,
) : GLSurfaceView.Renderer {
    private lateinit var flat: SceneShader
    private lateinit var lit: SceneShader
    private var uFillDir = -1
    private var uFill = -1
    private var uEmission = -1
    private var uShine = -1
    private lateinit var backdrop: Backdrop

    private val figureMeshes = ArrayList<GpuMesh>()
    private var figureVersion = -1

    private lateinit var shaft: GpuMesh
    private lateinit var arrowHead: GpuMesh
    private lateinit var dot: GpuMesh
    private lateinit var fineGrid: GpuMesh
    private lateinit var coarseGrid: GpuMesh
    private lateinit var circle: GpuMesh
    private lateinit var hoop: GpuMesh
    private lateinit var plumb: GpuMesh
    private lateinit var tunnelRing: GpuMesh
    private lateinit var glass: GpuMesh
    private lateinit var diffuser: GpuMesh
    private lateinit var edges: GpuMesh
    private lateinit var net: GpuMesh
    private lateinit var shadow: GpuMesh
    private lateinit var dropLine: GpuMesh
    private lateinit var streaks: GpuMesh
    private lateinit var trail: GpuMesh
    private var netTexture = 0
    private var shadowTexture = 0
    private val labelMeshes = ArrayList<GpuMesh>()
    private val labelTextures = ArrayList<Int>()
    /** Width and height of each label in metres. */
    private val labelSizes = ArrayList<FloatArray>()

    private var width = 1
    private var height = 1
    private val matrix = FloatArray(16)
    private val quad = FloatArray(12)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // A new context: nothing made in the old one is there any more.
        flat = SceneShader()
        lit = SceneShader(fragment = LIT_FRAGMENT)
        uFillDir = GLES30.glGetUniformLocation(lit.program, "uFillDir")
        uFill = GLES30.glGetUniformLocation(lit.program, "uFill")
        uEmission = GLES30.glGetUniformLocation(lit.program, "uEmission")
        uShine = GLES30.glGetUniformLocation(lit.program, "uShine")
        backdrop = Backdrop()
        figureMeshes.clear()
        figureVersion = -1

        shaft = GpuMesh().apply { set(Primitives.cone(1f, 1f, 1f, 16)) }
        arrowHead = GpuMesh().apply { set(Primitives.cone(0f, 1f, 1f, 16)) }
        dot = GpuMesh().apply { set(Primitives.sphere(0.035f, 10, 14)) }
        fineGrid = lines(grid(14f, 1f))
        coarseGrid = lines(grid(60f, 5f))
        circle = lines(FloatArray(CIRCLE_POINTS * 3).also {
            for (i in 0 until CIRCLE_POINTS) {
                val a = 2 * PI * i / CIRCLE_POINTS
                it[i * 3] = cos(a).toFloat(); it[i * 3 + 2] = sin(a).toFloat()
            }
        }, GLES30.GL_LINE_LOOP)
        hoop = GpuMesh().apply { set(Primitives.torus(1.5f, 0.011f, 64, 6)) }
        plumb = lines(floatArrayOf(0f, -120f, 0f, 0f, 120f, 0f))
        val rt = WTCore.TUNNEL.radius.toFloat(); val gt = WTCore.TUNNEL.glassTop.toFloat()
        tunnelRing = GpuMesh().apply { set(Primitives.torus(rt, 0.03f, 96, 8)) }
        glass = GpuMesh().apply { set(Primitives.cone(rt, rt, gt, 72, capBottom = false, capTop = false)) }
        diffuser = GpuMesh().apply { set(Primitives.cone(rt + DIFFUSER_WIDER, rt, DIFFUSER_HEIGHT, 72, capBottom = false, capTop = false)) }
        edges = lines(tunnelEdges(rt, gt))
        net = GpuMesh().apply { set(disc(rt, 72)) }
        netTexture = Textures.uploadRaw(netPicture(), repeat = true)
        shadow = GpuMesh().apply { set(Primitives.groundPlane(2f, 2f)) }
        shadowTexture = Textures.uploadRaw(shadowPicture(), repeat = false)
        dropLine = lines(floatArrayOf(0f, 0f, 0f, 0f, 1f, 0f))
        streaks = GpuMesh(GLES30.GL_LINES)
        trail = GpuMesh(GLES30.GL_LINE_STRIP)
        labelMeshes.clear(); labelTextures.clear(); labelSizes.clear()
        for (i in 1..gt.toInt()) {
            val picture = labelPicture("$i m")
            labelTextures += Textures.uploadRaw(picture, repeat = false)
            labelSizes += floatArrayOf(LABEL_EM * picture.width / LABEL_PIXELS, LABEL_EM * picture.height / LABEL_PIXELS)
            labelMeshes += GpuMesh()
        }
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = max(w, 1); height = max(h, 1)
        model.resize(width / density.toDouble(), height / density.toDouble())
    }

    override fun onDrawFrame(gl: GL10?) {
        // The engine's frame and the picture made from it are one step: nothing else touches the engine in between.
        synchronized(engine) {
            val dt = engine.frame()
            model.update(engine, dt)
        }
        GLES30.glViewport(0, 0, width, height)
        GLES30.glDepthMask(true)
        GLES30.glClearColor(red(WindTunnelSceneModel.BOTTOM), green(WindTunnelSceneModel.BOTTOM), blue(WindTunnelSceneModel.BOTTOM), 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        backdrop.draw()
        if (!model.ready) return

        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glLineWidth(1f)

        drawFigure()

        begin(flat)
        val world = model.world
        val wx = world.x.toFloat(); val wy = world.y.toFloat(); val wz = world.z.toFloat()
        val gt = WTCore.TUNNEL.glassTop.toFloat(); val rt = WTCore.TUNNEL.radius.toFloat()

        // Solid and flat: the tunnel's two rings and the arrows.
        if (model.tunnelOn) {
            draw(tunnelRing, WindTunnelSceneModel.LINE, 1f, moved(wx, wy, wz))
            draw(tunnelRing, WindTunnelSceneModel.LINE, 1f, moved(wx, wy + gt, wz))
        }
        for (arrow in model.segmentArrows) drawArrow(arrow)
        drawArrow(model.netArrow)
        drawArrow(model.weightArrow)

        // See-through: drawn over what is there, hidden by what is nearer.
        GLES30.glDepthMask(false)
        if (model.tunnelOn) {
            drawTunnel(wx, wy, wz, rt, gt)
        } else {
            for (level in model.levels) {
                if (level.fineOpacity > 0) {
                    draw(fineGrid, WindTunnelSceneModel.LINE, level.fineOpacity.toFloat(), moved(level.fineX.toFloat(), level.y.toFloat(), level.fineZ.toFloat()))
                }
                if (level.coarseOpacity > 0) {
                    draw(coarseGrid, WindTunnelSceneModel.LINE, level.coarseOpacity.toFloat(), moved(level.coarseX.toFloat(), level.y.toFloat(), level.coarseZ.toFloat()))
                }
            }
            // The start point: a hoop at the height the flight began, rings on the level below, a plumb line.
            val o = model.ringsOpacity.toFloat()
            val below = wy + WindTunnelSceneModel.LEVEL_BASE.toFloat()
            draw(hoop, WindTunnelSceneModel.LINE, 0.7f * o, moved(wx, wy, wz))
            draw(circle, WindTunnelSceneModel.LINE, 0.5f * o, moved(wx, below, wz, 5f, 1f, 5f))
            draw(circle, WindTunnelSceneModel.LINE, 0.5f * o, moved(wx, below, wz, 10f, 1f, 10f))
            draw(plumb, WindTunnelSceneModel.LINE, 0.4f * o, moved(wx, wy, wz))
        }

        // The shadow on the level below, and the line down to it.
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, shadowTexture)
        GLES30.glUniform1i(flat.uTexture, 0)
        GLES30.glUniform1i(flat.uUseTexture, 1)
        val sc = model.shadowScale.toFloat()
        draw(shadow, 0xffffff, model.shadowOpacity.toFloat(), moved(0f, model.shadowY.toFloat(), 0f, sc, 1f, sc))
        GLES30.glUniform1i(flat.uUseTexture, 0)
        if (model.dropLength > 0) {
            draw(dropLine, WindTunnelSceneModel.LINE, 0.3f, moved(0f, model.dropY.toFloat(), 0f, 1f, model.dropLength.toFloat(), 1f))
        }

        // The air going by, and the path flown.
        Matrix.setIdentityM(matrix, 0)
        if (model.streaksVisible) {
            streaks.set(model.streakPoints)
            draw(streaks, WindTunnelSceneModel.WIND, 0.42f, matrix)
        }
        if (model.trailCount > 1) {
            trail.set(model.trailPoints, model.trailCount * 3)
            draw(trail, WindTunnelSceneModel.SUIT, 0.7f, matrix)
        }

        // The centre of mass shows through the body.
        if (model.showsArrows) {
            GLES30.glDisable(GLES30.GL_DEPTH_TEST)
            draw(dot, WindTunnelSceneModel.WEIGHT, 1f, matrix)
            GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        }
        GLES30.glDepthMask(true)
    }

    private fun drawFigure() {
        val parts = model.parts
        if (model.figureVersion != figureVersion || figureMeshes.size != parts.size) {
            while (figureMeshes.size > parts.size) figureMeshes.removeAt(figureMeshes.size - 1).release()
            while (figureMeshes.size < parts.size) figureMeshes += GpuMesh()
            for ((i, part) in parts.withIndex()) {
                figureMeshes[i].set(part.mesh.positions, normals = part.mesh.normals, indices = part.mesh.indices)
            }
            figureVersion = model.figureVersion
        }
        begin(lit)
        // The sun from above the camera's right shoulder, a weaker light from below, as on the iPhone.
        GLES30.glUniform3f(lit.uLightDir, 0.347f, 0.780f, 0.520f)
        GLES30.glUniform3f(lit.uAmbient, 0.52f, 0.52f, 0.52f)
        GLES30.glUniform3f(lit.uSun, 0.75f, 0.75f, 0.75f)
        GLES30.glUniform3f(uFillDir, -0.707f, -0.566f, -0.424f)
        GLES30.glUniform3f(uFill, 0.26f, 0.26f, 0.26f)
        for ((i, part) in parts.withIndex()) {
            val m = if (part.segment >= 0) model.segmentModels[part.segment] else model.jointModels[part.joint]
            GLES30.glUniformMatrix4fv(lit.uModel, 1, false, m, 0)
            val rgb = part.material.rgb
            GLES30.glUniform4f(lit.uColor, red(rgb), green(rgb), blue(rgb), 1f)
            GLES30.glUniform1f(uShine, part.material.shine)
            if (model.highlighted.contains(part.grp)) {
                GLES30.glUniform3f(uEmission, GLOW[0], GLOW[1], GLOW[2])
            } else {
                GLES30.glUniform3f(uEmission, 0f, 0f, 0f)
            }
            figureMeshes[i].draw(lit)
        }
    }

    private fun drawTunnel(wx: Float, wy: Float, wz: Float, rt: Float, gt: Float) {
        // The net: a fine mesh across the bottom.
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, netTexture)
        GLES30.glUniform1i(flat.uTexture, 0)
        GLES30.glUniform1i(flat.uUseTexture, 1)
        GLES30.glUniform2f(flat.uUvScale, 18f, 18f)
        draw(net, 0xffffff, 1f, moved(wx, wy, wz))
        GLES30.glUniform2f(flat.uUvScale, 1f, 1f)
        GLES30.glUniform1i(flat.uUseTexture, 0)

        // The glass chamber with a mark every metre, and the diffuser above it.
        draw(glass, WindTunnelSceneModel.GLASS, 0.1f, moved(wx, wy + gt / 2, wz))
        for (i in 1 until gt.toInt()) draw(circle, WindTunnelSceneModel.LINE, 0.35f, moved(wx, wy + i, wz, rt, 1f, rt))
        draw(diffuser, WindTunnelSceneModel.GLASS, 0.04f, moved(wx, wy + gt + DIFFUSER_HEIGHT / 2, wz))
        val wide = rt + DIFFUSER_WIDER
        draw(circle, WindTunnelSceneModel.LINE, 0.35f, moved(wx, wy + gt + DIFFUSER_HEIGHT, wz, wide, 1f, wide))
        draw(edges, WindTunnelSceneModel.LINE, 0.16f, moved(wx, wy, wz))

        // Heights beside the glass, turned to the camera.
        GLES30.glUniform1i(flat.uUseTexture, 1)
        val right = model.right; val up = model.up
        Matrix.setIdentityM(matrix, 0)
        for (i in labelMeshes.indices) {
            val hw = labelSizes[i][0] / 2; val hh = labelSizes[i][1] / 2
            val cx = wx + rt + 0.3f; val cy = wy + (i + 1); val cz = wz
            fun corner(k: Int, sx: Float, sy: Float) {
                quad[k * 3] = cx + (right.x * sx * hw + up.x * sy * hh).toFloat()
                quad[k * 3 + 1] = cy + (right.y * sx * hw + up.y * sy * hh).toFloat()
                quad[k * 3 + 2] = cz + (right.z * sx * hw + up.z * sy * hh).toFloat()
            }
            corner(0, -1f, 1f); corner(1, 1f, 1f); corner(2, 1f, -1f); corner(3, -1f, -1f)
            labelMeshes[i].set(quad, uvs = QUAD_UVS, indices = QUAD_INDICES)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, labelTextures[i])
            draw(labelMeshes[i], 0xffffff, 1f, matrix)
        }
        GLES30.glUniform1i(flat.uUseTexture, 0)
    }

    private fun drawArrow(arrow: WindTunnelSceneModel.Arrow) {
        if (!arrow.visible) return
        draw(shaft, arrow.rgb, 1f, arrow.shaft)
        draw(arrowHead, arrow.rgb, 1f, arrow.head)
    }

    /** The settings every shape of a pass shares: the camera, the fog, no texture. */
    private fun begin(shader: SceneShader) {
        val eye = model.eye
        GLES30.glUseProgram(shader.program)
        GLES30.glUniformMatrix4fv(shader.uViewProj, 1, false, model.viewProjection, 0)
        GLES30.glUniform3f(shader.uEye, eye.x.toFloat(), eye.y.toFloat(), eye.z.toFloat())
        GLES30.glUniform2f(shader.uFog, WindTunnelSceneModel.FOG_START.toFloat(), WindTunnelSceneModel.FOG_END.toFloat())
        GLES30.glUniform3f(shader.uFogColor, red(WindTunnelSceneModel.BOTTOM), green(WindTunnelSceneModel.BOTTOM), blue(WindTunnelSceneModel.BOTTOM))
        GLES30.glUniform2f(shader.uUvScale, 1f, 1f)
        GLES30.glUniform1i(shader.uUseVertexColor, 0)
        GLES30.glUniform1i(shader.uUseTexture, 0)
        GLES30.glUniform1i(shader.uLit, 0)
    }

    /** One flat-coloured shape. */
    private fun draw(mesh: GpuMesh, rgb: Int, alpha: Float, m: FloatArray) {
        GLES30.glUniformMatrix4fv(flat.uModel, 1, false, m, 0)
        GLES30.glUniform4f(flat.uColor, red(rgb), green(rgb), blue(rgb), alpha)
        mesh.draw(flat)
    }

    /** [matrix] set to a move, and a stretch when one is given. */
    private fun moved(x: Float, y: Float, z: Float, sx: Float = 1f, sy: Float = 1f, sz: Float = 1f): FloatArray {
        Matrix.setIdentityM(matrix, 0)
        Matrix.translateM(matrix, 0, x, y, z)
        if (sx != 1f || sy != 1f || sz != 1f) Matrix.scaleM(matrix, 0, sx, sy, sz)
        return matrix
    }

    private fun lines(points: FloatArray, mode: Int = GLES30.GL_LINES) = GpuMesh(mode).apply { set(points) }

    /** The vertical gradient behind everything. */
    private class Backdrop {
        private val program = SceneShader.link(
            """#version 300 es
layout(location = 0) in vec2 aPosition;
out float vY;
void main() { vY = aPosition.y * 0.5 + 0.5; gl_Position = vec4(aPosition, 0.9999, 1.0); }
""",
            """#version 300 es
precision mediump float;
in float vY;
uniform vec3 uTop;
uniform vec3 uBottom;
out vec4 fragColor;
void main() { fragColor = vec4(mix(uBottom, uTop, vY), 1.0); }
""",
        )
        private val uTop = GLES30.glGetUniformLocation(program, "uTop")
        private val uBottom = GLES30.glGetUniformLocation(program, "uBottom")
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
            GLES30.glUniform3f(uTop, red(WindTunnelSceneModel.TOP), green(WindTunnelSceneModel.TOP), blue(WindTunnelSceneModel.TOP))
            GLES30.glUniform3f(uBottom, red(WindTunnelSceneModel.BOTTOM), green(WindTunnelSceneModel.BOTTOM), blue(WindTunnelSceneModel.BOTTOM))
            GLES30.glDisable(GLES30.GL_DEPTH_TEST)
            GLES30.glDisable(GLES30.GL_BLEND)
            GLES30.glDepthMask(false)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            GLES30.glDisableVertexAttribArray(0)
            GLES30.glDepthMask(true)
        }
    }

    companion object {
        private const val CIRCLE_POINTS = 96
        private const val DIFFUSER_HEIGHT = 4f
        private const val DIFFUSER_WIDER = 1.3f
        /** A label's letters are this many metres to the em. */
        private const val LABEL_EM = 0.2f
        private const val LABEL_PIXELS = 64f
        private val QUAD_UVS = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
        private val QUAD_INDICES = intArrayOf(0, 2, 1, 0, 3, 2)

        /** The glow of a picked body part: half the highlight colour, as light. */
        private val GLOW = floatArrayOf(
            linear(red(WindTunnelSceneModel.HIGHLIGHT)) * 0.5f,
            linear(green(WindTunnelSceneModel.HIGHLIGHT)) * 0.5f,
            linear(blue(WindTunnelSceneModel.HIGHLIGHT)) * 0.5f,
        )

        fun red(rgb: Int) = ((rgb shr 16) and 0xff) / 255f
        fun green(rgb: Int) = ((rgb shr 8) and 0xff) / 255f
        fun blue(rgb: Int) = (rgb and 0xff) / 255f
        private fun linear(c: Float) = c.toDouble().pow(2.2).toFloat()

        /**
         * The mannequin's light: ambient, a sun and a fill light, worked out
         * as light (not as screen values) the way SceneKit's physically
         * based materials are, with a highlight as sharp as the material is
         * smooth, and a glow for a picked part.
         */
        private const val LIT_FRAGMENT = """#version 300 es
precision highp float;
in vec3 vNormal;
in vec3 vWorld;
uniform vec4 uColor;
uniform vec3 uLightDir;
uniform vec3 uAmbient;
uniform vec3 uSun;
uniform vec3 uFillDir;
uniform vec3 uFill;
uniform vec3 uEmission;
uniform float uShine;
uniform vec3 uEye;
uniform vec2 uFog;
uniform vec3 uFogColor;
out vec4 fragColor;
void main() {
    vec3 n = normalize(vNormal);
    vec3 albedo = pow(uColor.rgb, vec3(2.2));
    float d = max(dot(n, uLightDir), 0.0);
    vec3 light = uAmbient + uSun * d + uFill * max(dot(n, uFillDir), 0.0);
    vec3 toEye = normalize(uEye - vWorld);
    vec3 h = normalize(toEye + uLightDir);
    float spec = 0.04 * (uShine + 8.0) / 8.0 * pow(max(dot(n, h), 0.0), uShine) * d;
    vec3 rgb = albedo * light + uSun * spec + uEmission;
    rgb = pow(clamp(rgb, 0.0, 1.0), vec3(1.0 / 2.2));
    float f = clamp((distance(vWorld, uEye) - uFog.x) / (uFog.y - uFog.x), 0.0, 1.0);
    fragColor = vec4(mix(rgb, uFogColor, f), uColor.a);
}
"""

        /** Lines of a square grid on the ground, [half] metres each way, a line every [step]. */
        fun grid(half: Float, step: Float): FloatArray {
            val n = (half / step).roundToInt()
            val p = FloatArray((2 * n + 1) * 12)
            var k = 0
            for (j in -n..n) {
                val v = j * step
                p[k++] = v; p[k++] = 0f; p[k++] = -half; p[k++] = v; p[k++] = 0f; p[k++] = half
                p[k++] = -half; p[k++] = 0f; p[k++] = v; p[k++] = half; p[k++] = 0f; p[k++] = v
            }
            return p
        }

        /** The uprights of the glass and the struts of the diffuser. */
        fun tunnelEdges(rt: Float, gt: Float): FloatArray {
            val wide = rt + DIFFUSER_WIDER; val top = gt + DIFFUSER_HEIGHT
            val p = FloatArray((16 + 12) * 6)
            var k = 0
            for (i in 0 until 16) {
                val a = i / 16.0 * 2 * PI
                val c = cos(a).toFloat(); val s = sin(a).toFloat()
                p[k++] = c * rt; p[k++] = 0f; p[k++] = s * rt; p[k++] = c * rt; p[k++] = gt; p[k++] = s * rt
            }
            for (i in 0 until 12) {
                val a = i / 12.0 * 2 * PI
                val c = cos(a).toFloat(); val s = sin(a).toFloat()
                p[k++] = c * rt; p[k++] = gt; p[k++] = s * rt; p[k++] = c * wide; p[k++] = top; p[k++] = s * wide
            }
            return p
        }

        /** A disc on the ground with a picture laid across it once. */
        fun disc(radius: Float, segments: Int): MeshData {
            val positions = FloatArray((segments + 1) * 3)
            val uvs = FloatArray((segments + 1) * 2)
            val indices = IntArray(segments * 3)
            uvs[0] = 0.5f; uvs[1] = 0.5f
            for (i in 0 until segments) {
                val a = 2 * PI * i / segments
                val c = cos(a).toFloat(); val s = sin(a).toFloat()
                positions[(i + 1) * 3] = c * radius; positions[(i + 1) * 3 + 2] = s * radius
                uvs[(i + 1) * 2] = c * 0.5f + 0.5f; uvs[(i + 1) * 2 + 1] = s * 0.5f + 0.5f
                indices[i * 3] = 0; indices[i * 3 + 1] = 1 + (i + 1) % segments; indices[i * 3 + 2] = 1 + i
            }
            return MeshData(positions, null, indices, uvs = uvs)
        }

        /** One tile of the net: four cells each way, thin lines, see-through between them. */
        fun netPicture(size: Int = 128): RawTexture {
            val rgba = ByteArray(size * size * 4)
            val cell = size / 4.0
            fun cover(v: Int): Double {
                val p = (v + 0.5) % cell
                val away = minOf(p, cell - p)
                return (1.1 + 0.5 - away).coerceIn(0.0, 1.0)
            }
            for (y in 0 until size) {
                for (x in 0 until size) {
                    val k = (y * size + x) * 4
                    rgba[k] = ((WindTunnelSceneModel.NET shr 16) and 0xff).toByte()
                    rgba[k + 1] = ((WindTunnelSceneModel.NET shr 8) and 0xff).toByte()
                    rgba[k + 2] = (WindTunnelSceneModel.NET and 0xff).toByte()
                    rgba[k + 3] = (0.9 * max(cover(x), cover(y)) * 255).roundToInt().toByte()
                }
            }
            return RawTexture(size, size, rgba)
        }

        /** A soft round shadow: black, darkest in the middle. */
        fun shadowPicture(size: Int = 128): RawTexture {
            val rgba = ByteArray(size * size * 4)
            val half = size / 2.0
            for (y in 0 until size) {
                for (x in 0 until size) {
                    val dx = x + 0.5 - half; val dy = y + 0.5 - half
                    val a = 0.55 * (1 - sqrt(dx * dx + dy * dy) / half).coerceIn(0.0, 1.0)
                    rgba[(y * size + x) * 4 + 3] = (a * 255).roundToInt().toByte()
                }
            }
            return RawTexture(size, size, rgba)
        }

        /** A height mark such as "3 m": the letters in one colour, see-through around them. */
        private fun labelPicture(text: String): RawTexture {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFFFFF.toInt()
                textSize = LABEL_PIXELS
                typeface = if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.DEFAULT, 600, false) else Typeface.DEFAULT_BOLD
            }
            val metrics = paint.fontMetrics
            val w = ceil(paint.measureText(text)).toInt() + 8
            val h = ceil(metrics.descent - metrics.ascent).toInt() + 8
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).drawText(text, 4f, 4f - metrics.ascent, paint)
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            bitmap.recycle()
            val rgba = ByteArray(w * h * 4)
            for (i in pixels.indices) {
                rgba[i * 4] = ((WindTunnelSceneModel.LABEL shr 16) and 0xff).toByte()
                rgba[i * 4 + 1] = ((WindTunnelSceneModel.LABEL shr 8) and 0xff).toByte()
                rgba[i * 4 + 2] = (WindTunnelSceneModel.LABEL and 0xff).toByte()
                rgba[i * 4 + 3] = (pixels[i] ushr 24).toByte()
            }
            return RawTexture(w, h, rgba)
        }
    }
}
