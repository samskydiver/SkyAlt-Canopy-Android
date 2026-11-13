package com.skyalt.canopy.scene

/**
 * Which series' model the scene is to fly, and the hand-over of a model read
 * off the GL thread to the frame that hangs it.
 *
 * The scene is told its canopy many times over: twice at every start (when
 * the engine is made and again when the surface is), then at each scene
 * entered and each setting changed. Every request used to start a reader of
 * its own, and only the newest request's result was taken, out of a single
 * slot. Two readers of one series often end within the same frame; when the
 * newer ended first, the older one's result took its place in the slot, was
 * dropped as out of date, and nothing was left to hang: the jumper flew with
 * no canopy until the next change of a setting.
 *
 * Here a series is read once however often it is asked for, and what was
 * read is kept by series, so a late reader of an earlier choice cannot push
 * out the model that is wanted.
 */
internal class CanopyRigRequests {
    private var wanted: String? = null
    private val reading = HashSet<String>()
    private var waiting: CanopyModelRig? = null

    /**
     * The series to fly from now on. True when the caller is to read its
     * model and end with [read]; false when the series is the one [hung]
     * already, is read and waiting to be taken, or is being read for an
     * earlier request.
     */
    @Synchronized
    fun want(key: String, hung: String?): Boolean {
        wanted = key
        if (waiting?.key != key) waiting = null
        if (hung == key || waiting != null) return false
        return reading.add(key)
    }

    /** A reader's end: the model of [key], or null when it could not be read (the next request for it reads again). */
    @Synchronized
    fun read(key: String, rig: CanopyModelRig?) {
        reading.remove(key)
        if (rig != null && key == wanted) waiting = rig
    }

    /** The wanted series' model once it has been read; handed out once. */
    @Synchronized
    fun take(): CanopyModelRig? = waiting.also { waiting = null }
}
