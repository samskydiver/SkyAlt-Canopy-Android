package com.skyalt.canopy.scene

import com.skyalt.canopy.model.CanopyModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Random
import java.util.concurrent.locks.LockSupport

class CanopyRigRequestsTest {
    private fun rig(key: String): CanopyModelRig {
        val asset = CanopyModel.forKey(key)!!.modelAsset
        val file = listOf(File("src/main/assets", asset), File("app/src/main/assets", asset)).first { it.exists() }
        return CanopyModelRig.build(Glb.parse(file.readBytes()), key)!!
    }

    @Test
    fun aSeriesAskedForTwiceIsReadOnceAndHung() {
        // Every start of the simulator: the same canopy asked for twice before the first frame.
        val requests = CanopyRigRequests()
        assertTrue(requests.want("peregrine", hung = null))
        assertFalse(requests.want("peregrine", hung = null))
        assertNull(requests.take())
        val rig = rig("peregrine")
        requests.read("peregrine", rig)
        assertSame(rig, requests.take())
        assertNull(requests.take())
        // Hung, it is not read again.
        assertFalse(requests.want("peregrine", hung = "peregrine"))
        assertNull(requests.take())
    }

    @Test
    fun aLateModelOfAnEarlierChoiceDoesNotPushOutTheOneWanted() {
        val requests = CanopyRigRequests()
        assertTrue(requests.want("navigator", hung = null))
        assertTrue(requests.want("sabre3", hung = null))
        val wanted = rig("sabre3")
        requests.read("sabre3", wanted)
        requests.read("navigator", rig("navigator"))
        assertSame(wanted, requests.take())
        assertNull(requests.take())
    }

    @Test
    fun aModelNoLongerWantedIsNotHung() {
        val requests = CanopyRigRequests()
        assertTrue(requests.want("navigator", hung = null))
        requests.read("navigator", rig("navigator"))
        // Another series is chosen before a frame has taken the first.
        assertTrue(requests.want("sabre3", hung = null))
        assertNull(requests.take())
        val wanted = rig("sabre3")
        requests.read("sabre3", wanted)
        assertSame(wanted, requests.take())
    }

    @Test
    fun goingBackToASeriesStillBeingReadWaitsForThatReader() {
        val requests = CanopyRigRequests()
        assertTrue(requests.want("navigator", hung = null))
        assertTrue(requests.want("sabre3", hung = null))
        assertFalse(requests.want("navigator", hung = null))
        requests.read("sabre3", rig("sabre3"))
        assertNull(requests.take())
        val wanted = rig("navigator")
        requests.read("navigator", wanted)
        // Read and waiting for a frame, it is not read again either.
        assertFalse(requests.want("navigator", hung = null))
        assertSame(wanted, requests.take())
    }

    @Test
    fun aSeriesThatCouldNotBeReadIsReadAgainAtTheNextRequest() {
        val requests = CanopyRigRequests()
        assertTrue(requests.want("navigator", hung = null))
        requests.read("navigator", null)
        assertNull(requests.take())
        assertTrue(requests.want("navigator", hung = null))
    }

    @Test
    fun readersEndingInAnyOrderLeaveTheWantedModelToHang() {
        val navigator = rig("navigator")
        val peregrine = rig("peregrine")
        val random = Random(7)
        repeat(2_000) {
            val requests = CanopyRigRequests()
            val readers = ArrayList<Thread>()
            // The scene's part in a request; a reader here only takes its time.
            fun ask(rig: CanopyModelRig) {
                if (!requests.want(rig.key, hung = null)) return
                val nanos = random.nextInt(200) * 1_000L
                readers += Thread { LockSupport.parkNanos(nanos); requests.read(rig.key, rig) }.also { it.start() }
            }
            // An earlier choice, then the start's two requests for the canopy to fly.
            ask(navigator); ask(peregrine); ask(peregrine)
            assertEquals(2, readers.size)
            // The frames: each takes what waits and hangs it.
            var hung: CanopyModelRig? = null
            while (readers.any { it.isAlive }) {
                requests.take()?.let { hung = it }
                Thread.yield()
            }
            requests.take()?.let { hung = it }
            assertSame(peregrine, hung)
        }
    }
}
