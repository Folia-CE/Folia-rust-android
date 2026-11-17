package io.github.techuouo520.folia
import kotlin.math.abs
fun main() {
    for(hz in listOf(60,90,120)) {
        val m=MusicBarMotion()
        m.target(true)
        repeat(hz/4){m.step(1f/hz)}
        val p=m.value;val v=m.velocity
        m.target(false)
        check(m.value==p&&m.velocity==v)
        repeat(hz*3) {i->
            val before=m.value;m.target(i/4%2==0);check(m.value==before)
            m.step(1f/hz);check(m.value.isFinite()&&m.value in 0f..1f&&m.velocity.isFinite())
        }
        m.target(true);repeat(hz){m.step(1f/hz)};check(abs(m.value-1)<.00001f);check(!m.step(1f/hz))
        m.target(false);repeat(hz){m.step(1f/hz)};check(m.value==0f&&m.velocity==0f)
        m.drag(.4f);m.target(true);repeat(hz){m.step(1f/hz)};check(m.value==1f)
    }
    for ((w,h) in listOf(360f to 720f,393f to 873f,412f to 915f,480f to 960f)) {
        for(fontScale in listOf(1f,1.3f)) {
            val open=MusicBarGeometry(w,h,48f,1f,fontScale)
            check(open.card.x==0f&&open.card.y==0f&&open.card.width==w&&open.card.height==h)
            check(open.cover.x==0f&&open.cover.y==0f&&open.cover.width==w)
            check(open.backgroundAlpha==1f&&open.coverRadius==0f)
            check(open.title.bottom<=open.artist.y+.01f)
            check(open.artist.bottom<open.progress.y)
            check(open.progress.bottom<open.play.y)
            check(open.play.bottom<open.mode.y)
            check(open.queue.bottom<=h-48)
            val closed=MusicBarGeometry(w,h,48f,0f,fontScale)
            check(closed.card.height==68f&&closed.backgroundAlpha==0f&&closed.cover.width==46f)
            check(closed.play.x>closed.title.x&&closed.next.x>closed.play.x)
            // No threshold-driven geometry branch may jump at a reveal/material breakpoint.
            for (point in listOf(.05f,.14f,.28f,.35f,.5f,.9f,.94f,.999f)) {
                val a=MusicBarGeometry(w,h,48f,point-.00001f,fontScale)
                val b=MusicBarGeometry(w,h,48f,point+.00001f,fontScale)
                for ((x,y) in listOf(a.cover to b.cover,a.title to b.title,a.progress to b.progress,a.play to b.play)) {
                    check(abs(x.x-y.x)<.2f&&abs(x.y-y.y)<.2f&&abs(x.width-y.width)<.2f&&abs(x.height-y.height)<.2f)
                }
            }
        }
    }
    println("PASS: MusicBar interruption continuity, rapid reversal, drag settlement and no idle frame loop at 60/90/120Hz; fullscreen endpoints, control bounds and continuous geometry across material/cover thresholds")
}
