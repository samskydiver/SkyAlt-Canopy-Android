# Canopy Simulator for Android

A standalone Android port of the canopy simulator in the SkyAlt iOS app: a
ram-air canopy flown from a landing-pattern start point down to touchdown,
with the same flight model (exit weight, canopy size and type → airspeed,
glide, turn rate, three-phase stall), 3D view, instruments, replay and
landing grades.

- Kotlin, Jetpack Compose for the interface, OpenGL ES 3 for the 3D scene.
- `model/` is plain Kotlin (no Android imports) and unit-tested.
- CI (`.github/workflows/android.yml`) runs the tests, builds the APKs and
  publishes the APK as a GitHub release: `latest` from `main`, `preview`
  from other branches.

Not yet ported: motion capture, casting to a TV, the subscription.
