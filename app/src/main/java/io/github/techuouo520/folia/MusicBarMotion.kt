package io.github.techuouo520.folia

/** SalamPlayer 650ms ATTACK_SMOOTH track; interrupted segments retain the displayed velocity. */
internal class MusicBarMotion {
    var value=0f;private set
    var velocity=0f;private set
    private var from=0f;private var to=0f;private var elapsed=.65f;private var initialVelocity=0f
    private val points=doubleArrayOf(0.0,.0041,.8,.82,.86,.9,.98,.982,.985,.987,.992,.996,.999,1.0)
    private val work=DoubleArray(points.size)
    fun target(open: Boolean) {
        val next=if(open) 1f else 0f
        if(to==next) return
        from=value;to=next;initialVelocity=velocity;elapsed=0f
    }
    fun hold() {elapsed=.65f;to=value}
    fun drag(progress: Float, dragVelocity: Float=0f) {value=progress.coerceIn(0f,1f);velocity=dragVelocity;elapsed=.65f;to=value}
    private fun sample(t: Float): Float {
        points.copyInto(work)
        for(r in 1 until work.size) for(i in 0 until work.size-r) work[i]=(1-t)*work[i]+t*work[i+1]
        val restSlope=(points.size-1)*(points[1]-points[0]).toFloat()*(to-from)/.65f
        val carry=if(initialVelocity==0f && (from==0f || from==1f)) 0f else (initialVelocity-restSlope)*.65f*t*(1-t)*(1-t)*(1-t)
        return (from+(to-from)*work[0].toFloat()+carry).coerceIn(0f,1f)
    }
    fun step(seconds: Float): Boolean {
        if(elapsed>=.65f) return false
        elapsed=(elapsed+seconds.coerceIn(0f,.05f)).coerceAtMost(.65f)
        val t=elapsed/.65f;value=sample(t)
        val a=(t-.0005f).coerceAtLeast(0f);val b=(t+.0005f).coerceAtMost(1f)
        velocity=if(b>a) (sample(b)-sample(a))/((b-a)*.65f) else 0f
        if(elapsed>=.65f){value=to;velocity=0f;return false}
        return true
    }
}
