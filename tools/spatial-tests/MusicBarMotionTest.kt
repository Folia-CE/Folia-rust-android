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
            check(closed.play.x>closed.title.x&&closed.compactNext.x>closed.play.x)
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
    // The clock uses elapsed display time, including a missed frame. Both directions use
    // SalamPlayer's preset, including its slow final approach.
    val reference=MusicBarMotion()
    reference.target(true)
    reference.step(.325f)
    check(abs(reference.value-playerAttack.sample(.5f))<.00001f)
    reference.step(.325f)
    reference.target(false)
    reference.step(.585f)
    check(abs(reference.value-(1-playerAttack.sample(.9f)))<.00001f)
    check(settleMusicBar(0f,.12f,-72f) && !settleMusicBar(1f,.82f,72f))
    val fast=MusicBarMotion()
    fast.drag(.93f,12f)
    fast.target(true)
    repeat(77) {
        val last=fast.value
        fast.step(1f/120)
        check(fast.value>=last && fast.value<1f)
    }
    for((w,h) in listOf(873f to 393f,1200f to 800f)) {
        val wide=MusicBarGeometry(w,h,24f,1f,lyricsMode=1f)
        check(wide.wide && wide.cover.width==wide.cover.height)
        check(wide.title.bottom<wide.cover.y && wide.cover.bottom<wide.play.y)
        check(wide.play.bottom<=wide.progress.y && wide.progress.bottom<h)
        check(wide.cover.x+wide.cover.width<wide.lyricPanel.x)
    }
    val pressed=MusicBarGeometry(393f,873f,24f,0f,press=1f)
    check(abs(pressed.card.height-68f*.965f)<.0001f)
    val end=MusicBarGeometry(393f,873f,24f,1f,displayRadius=40f)
    check(end.cardRadius==18f)
    println("PASS: MusicBar source timing, press, reversal, fast release, edge contact and portrait/landscape placement")
}
