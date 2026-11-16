package io.github.techuouo520.folia.spatial

import kotlin.math.abs

fun main() {
    for(frequency in listOf(16.0,18.0)) for(hz in listOf(60,90,120)) {
        val motion=CollectionMotion(frequency)
        val dt=1.0/hz
        // Fast open/back/open sequences retain current position and velocity at each handoff.
        for(target in listOf(1.0,0.0,1.0,0.0,1.0)) {
            repeat(7) {
                val before=motion.position
                motion.step(target,dt)
                check(motion.position.isFinite() && motion.velocity.isFinite())
                check(motion.position in 0.0..1.0)
                check(abs(motion.position-before)<.20) {"discontinuous handoff at $hz Hz"}
            }
            val before=motion.position;val speed=motion.velocity
            motion.step(1.0-target,0.0)
            check(motion.position==before && motion.velocity==speed) {"retarget reset momentum"}
        }
        repeat(hz*2) {motion.step(0.0,dt)}
        check(motion.position==0.0 && motion.velocity==0.0) {"return did not dock"}
        repeat(hz*2) {motion.step(1.0,dt)}
        check(motion.position==1.0 && motion.velocity==0.0) {"cached reentry failed"}
        repeat(20) {motion.step(1.0,dt)}
        check(motion.position==1.0) {"settled world drifted"}
    }
    // Source geometry and alpha are a reversible function of ONE progress, independent of
    // visit count/direction. The visible card owns the first frame, not a blank seam.
    val frame=FloatArray(7)
    CollectionMorph.frame(400f,890f,140f,190f,6f,0f,frame)
    check(abs(frame[0]*frame[1]-140f)<.001f && abs(frame[0]*frame[2]-190f)<.001f)
    check(frame[CollectionMorph.ORIGIN_ALPHA]==1f && frame[CollectionMorph.CONTENT_ALPHA]==0f)
    CollectionMorph.frame(400f,890f,140f,190f,6f,.04f,frame)
    check(frame[CollectionMorph.ORIGIN_ALPHA]==1f && frame[CollectionMorph.CONTENT_ALPHA]==0f)
    var oldSource=1f;var oldContent=0f
    for(n in 0..100) {
        val p=n/100f
        CollectionMorph.frame(400f,890f,140f,190f,6f,p,frame)
        val opening=frame.copyOf()
        check(frame.all {it.isFinite()})
        check(frame[CollectionMorph.ORIGIN_ALPHA]<=oldSource && frame[CollectionMorph.CONTENT_ALPHA]>=oldContent)
        oldSource=frame[CollectionMorph.ORIGIN_ALPHA];oldContent=frame[CollectionMorph.CONTENT_ALPHA]
        CollectionMorph.frame(400f,890f,140f,190f,6f,1f,frame)
        CollectionMorph.frame(400f,890f,140f,190f,6f,p,frame)
        check(frame.contentEquals(opening)) {"return alpha/geometry diverged from entry"}
    }
    CollectionMorph.frame(400f,890f,140f,190f,6f,1f,frame)
    check(frame[0]==1f && frame[1]==400f && frame[2]==890f && frame[3]==0f)
    check(frame[CollectionMorph.ORIGIN_ALPHA]==0f && frame[CollectionMorph.CONTENT_ALPHA]==1f)
    println("Card morph: source ownership, prewarm, endpoints and reversible alpha passed")
    println("Collection motion: rapid reversal, continuity, docking and reentry passed at 60/90/120 Hz")
}
