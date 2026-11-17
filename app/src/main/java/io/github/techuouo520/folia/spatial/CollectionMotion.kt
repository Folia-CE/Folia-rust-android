package io.github.techuouo520.folia.spatial

import kotlin.math.abs
import kotlin.math.exp

/** Exact critically damped spring. Retargeting changes only the goal, never position/velocity. */
internal class CollectionMotion(private val frequency: Double) {
    var position=0.0
        private set
    var velocity=0.0
        private set

    fun step(target: Double, seconds: Double): Boolean {
        val dt=seconds.coerceIn(0.0,.05)
        if(dt==0.0) return abs(position-target)>.0001 || abs(velocity)>.001
        val offset=position-target
        val tangent=velocity+frequency*offset
        val decay=exp(-frequency*dt)
        position=target+(offset+tangent*dt)*decay
        velocity=(velocity-frequency*tangent*dt)*decay
        if(position<0.0) {position=0.0;velocity=velocity.coerceAtLeast(0.0)}
        if(position>1.0) {position=1.0;velocity=velocity.coerceAtMost(0.0)}
        val moving=abs(position-target)>.0001 || abs(velocity)>.001
        if(!moving) {position=target;velocity=0.0}
        return moving
    }
}
