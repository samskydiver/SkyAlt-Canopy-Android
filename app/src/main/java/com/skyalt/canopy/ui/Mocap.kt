package com.skyalt.canopy.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.skyalt.canopy.sim.MotionCaptureController
import com.skyalt.canopy.sim.PoseJoint
import com.skyalt.canopy.sim.PosePreview
import kotlin.math.roundToInt

/** The motion capture controls in the drawer. The camera picture floats over the 3D view while capture runs. */
@Composable
fun MocapPanel(capture: MotionCaptureController, onToggleCamera: () -> Unit, onOpenAppSettings: () -> Unit) {
    val s = LocalStrings.current
    val p = LocalPalette.current
    Block("${s("Motion capture")} · ${s("your arms are the toggles")}") {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            ChipButton(
                if (capture.active) s("Stop camera") else s("Enable camera"),
                isOn = capture.active,
                isDisabled = capture.status == MotionCaptureController.Status.Asking,
                onClick = onToggleCamera,
            )
        }
        SwitchRow(s("Mirror"), capture.isMirrored) { capture.setMirrored(it) }
        SwitchRow(s("Head turns the camera"), capture.isHeadCameraOn) { capture.setHeadCamera(it) }
        if (capture.isHeadCameraOn) {
            ChipButton(s("Re-centre"), isDisabled = !capture.active) { capture.recentreHead() }
            Note(s("Face the screen when the camera starts or after Re-centre: that is the straight-ahead view. Turn your head a little to swing the camera around the canopy, look up or down to raise or lower it."))
        }
        Note(statusText(capture, s))
        if (capture.active) {
            Note(s("The camera picture floats beside the 3D view while capture runs; the brake sliders are hidden until you stop it."))
        }
        if (capture.status == MotionCaptureController.Status.Refused) {
            ChipButton(s("Open Settings"), onClick = onOpenAppSettings)
        }
        if (capture.status is MotionCaptureController.Status.Failed) {
            Box(Modifier.fillMaxWidth().background(p.bad.copy(alpha = 0.12f), RoundedCornerShape(5.dp)).padding(6.dp)) {
                Text(statusText(capture, s), fontSize = 12.sp, color = p.bad)
            }
        }
    }
}

private fun statusText(capture: MotionCaptureController, s: Strings): String = when (val status = capture.status) {
    MotionCaptureController.Status.Idle -> s("Stand back so your shoulders, hands and hips are in frame. Arms straight up releases the toggles. Pull one or both hands down to brake that side; full brake is about at the hips (moved by the stall tolerance on canopies that can stall).")
    MotionCaptureController.Status.Asking -> s("Asking for the camera…")
    MotionCaptureController.Status.Tracking -> s("Tracking. Arms up = toggles up. Pull a hand down to brake that side.")
    MotionCaptureController.Status.Stopped -> s("Camera off. Toggles are back on the sliders and keys.")
    MotionCaptureController.Status.NoCamera -> s("No camera was found on this device.")
    MotionCaptureController.Status.Refused -> s("Camera access was refused here.")
    is MotionCaptureController.Status.Failed -> s.format("Motion capture could not start: %@", status.message)
}

/** The camera picture with the skeleton overlay, the "Live" tag and the pull readout. */
@Composable
fun MocapPreviewBox(capture: MotionCaptureController, width: Dp = 200.dp, height: Dp = 150.dp, scale: Float = 1f) {
    val s = LocalStrings.current
    val p = LocalPalette.current
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier.size(width, height).clip(shape).background(p.surface2).border(1.dp, p.line, shape),
        contentAlignment = Alignment.Center,
    ) {
        val preview = capture.preview
        if (capture.active && preview != null) {
            Image(preview.image, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            PoseOverlay(preview, Modifier.fillMaxSize())
            Column(Modifier.fillMaxSize()) {
                Text(
                    s("Live").uppercase(),
                    style = displayStyle((10 * scale).sp),
                    color = Color.White,
                    modifier = Modifier.padding(6.dp).background(p.bad, RoundedCornerShape(3.dp)).padding(horizontal = 6.dp, vertical = 3.dp),
                )
                Box(Modifier.weight(1f))
                val text = if (preview.isTracked) {
                    s.format("L %d%% R %d%%", (preview.leftPull * 100).roundToInt(), (preview.rightPull * 100).roundToInt())
                } else s("Step back: shoulders, hands and hips in frame")
                Text(
                    text,
                    fontSize = (10 * scale).sp,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 6.dp, vertical = 4.dp),
                )
            }
        } else {
            Text(
                (if (capture.status == MotionCaptureController.Status.Asking) s("Asking for the camera…") else s("Camera off")).uppercase(),
                style = displayStyle((12 * scale).sp),
                color = p.muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(8.dp),
            )
        }
    }
}

/**
 * Shoulders, hips, torso and arms over the camera picture, with the pull range
 * as dashed guides. The picture is cropped to fill the box, so the joints are
 * mapped through the same crop.
 */
@Composable
private fun PoseOverlay(preview: PosePreview, modifier: Modifier) {
    val p = LocalPalette.current
    Canvas(modifier) {
        val imageAspect = preview.image.width.toFloat() / maxOf(1, preview.image.height)
        // ContentScale.Crop: scale to cover, centred.
        val drawW: Float
        val drawH: Float
        if (imageAspect > size.width / size.height) {
            drawH = size.height; drawW = drawH * imageAspect
        } else {
            drawW = size.width; drawH = drawW / imageAspect
        }
        val ox = (size.width - drawW) / 2
        val oy = (size.height - drawH) / 2
        fun point(j: PoseJoint): Offset? = preview.joints[j]?.let { Offset(ox + it.x * drawW, oy + it.y * drawH) }
        fun segment(a: PoseJoint, b: PoseJoint, color: Color) {
            val pa = point(a) ?: return
            val pb = point(b) ?: return
            drawLine(color, pa, pb, 3.dp.toPx(), StrokeCap.Round)
        }
        val top = preview.pullTop
        val bottom = preview.pullBottom
        if (top != null && bottom != null) {
            for (y in listOf(top, bottom)) {
                val yy = oy + y * drawH
                drawLine(Color.White.copy(alpha = 0.7f), Offset(0f, yy), Offset(size.width, yy), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())))
            }
        }
        segment(PoseJoint.LEFT_SHOULDER, PoseJoint.RIGHT_SHOULDER, Color.White)
        segment(PoseJoint.LEFT_HIP, PoseJoint.RIGHT_HIP, Color.White)
        val ls = point(PoseJoint.LEFT_SHOULDER)
        val rs = point(PoseJoint.RIGHT_SHOULDER)
        val lh = point(PoseJoint.LEFT_HIP)
        val rh = point(PoseJoint.RIGHT_HIP)
        if (ls != null && rs != null && lh != null && rh != null) {
            drawLine(Color.White, (ls + rs) / 2f, (lh + rh) / 2f, 3.dp.toPx(), StrokeCap.Round)
        }
        segment(PoseJoint.LEFT_SHOULDER, PoseJoint.LEFT_ELBOW, p.left)
        segment(PoseJoint.LEFT_ELBOW, PoseJoint.LEFT_WRIST, p.left)
        segment(PoseJoint.RIGHT_SHOULDER, PoseJoint.RIGHT_ELBOW, p.right)
        segment(PoseJoint.RIGHT_ELBOW, PoseJoint.RIGHT_WRIST, p.right)
        point(PoseJoint.NOSE)?.let { drawCircle(Color.Yellow, 4.dp.toPx(), it) }
        for ((joint, color) in listOf(PoseJoint.LEFT_WRIST to p.left, PoseJoint.RIGHT_WRIST to p.right)) {
            val pt = point(joint) ?: continue
            drawCircle(color, 5.dp.toPx(), pt)
            drawCircle(Color.White, 5.dp.toPx(), pt, style = Stroke(1.5.dp.toPx()))
        }
    }
}
