package io.github.techuouo520.folia
import kotlin.math.abs

private fun closeTo(a: Float,b: Float,epsilon: Float=.001f) = check(abs(a-b)<epsilon) {"$a != $b"}
private fun settle(m: CornerMotion,open: Boolean,hz: Int) {
    var moving=true
    repeat(hz*5) {if(moving) moving=m.step(open,1f/hz)}
    check(!moving) {"motion did not stop at $hz Hz"}
}
fun main() {
    for(hz in listOf(60,90,120)) for(right in listOf(false,true)) {
        val m=CornerMotion();m.bounds(360f,480f,right)
        closeTo(m.rect().width,50f);closeTo(m.rect().height,50f);closeTo(m.radius(),25f)
        closeTo(m.rect().left,if(right) 310f else 0f)
        m.step(true,1f/hz)
        check(m.rect().height>50f && m.rect().height<480f)
        check(m.blur>0f)
        repeat(hz*2) {frame->
            val open=(frame/7)%2==0
            val rect=m.rect();val blur=m.blur
            m.target(open)
            check(m.rect()==rect) {"retarget changed the outline"};closeTo(m.blur,blur)
            m.step(open,1f/hz)
            val r=m.rect()
            check(r.width.isFinite()&&r.height.isFinite()&&r.width in 30f..440f&&r.height in 30f..600f)
            check(m.blur in 0f..1f)
        }
        settle(m,true,hz)
        closeTo(m.rect().width,360f);closeTo(m.rect().height,480f);closeTo(m.radius(),26f)
        check(m.content(180f,240f)==CornerContent(0f,0f,1f,1f));closeTo(m.blur,0f)
        // Async content arrival wakes a settled window without snapping its height.
        val before=m.rect()
        m.rows(2);m.rows(5)
        check(m.bounds(360f,540f,right));check(m.rect()==before)
        closeTo(m.rowAppearance(4),0f)
        repeat(6){m.step(true,1f/hz)}
        val during=m.rect();m.target(false);check(m.rect()==during)
        repeat(3){m.step(false,1f/hz)}
        val returning=m.rect();m.target(true);check(m.rect()==returning)
        m.bounds(360f,540f,right)
        settle(m,true,hz);closeTo(m.rect().height,540f);closeTo(m.rowAppearance(4),1f)
        settle(m,false,hz)
        check(m.docked);closeTo(m.rect().width,50f);closeTo(m.rect().height,50f);closeTo(m.blur,0f)
        closeTo(m.iconAlpha(),1f);closeTo(m.iconScale(),1f)
        // Source press uses swell + inertia, with no frame loop after release has settled.
        repeat(hz/5){m.step(false,1f/hz,true)}
        check(m.rect().width>50f)
        settle(m,false,hz);closeTo(m.rect().width,50f);closeTo(m.rect().height,50f)
    }
    for(hz in listOf(60,90,120)) {
        val dock=CornerMotion().apply {bounds(50f,100f,false)}
        val window=CornerMotion().apply {bounds(360f,480f,false)}
        fun step(detail: Boolean,account: Boolean): Boolean {
            val d=dock.step(detail,1f/hz)
            window.dockBounds(dock.rect())
            val w=window.step(account,1f/hz)
            val r=window.rect()
            check(r.width.isFinite()&&r.height.isFinite()&&r.width>25&&r.height>25)
            return d||w
        }
        fun rest(detail: Boolean,account: Boolean) {
            var moving=true
            repeat(hz*6) {if(moving) moving=step(detail,account)}
            check(!moving) {"pill/window did not settle at $hz Hz"}
        }
        rest(true,false)
        closeTo(window.rect().width,50f);closeTo(window.rect().height,100f)
        closeTo(window.radius(),25f);closeTo(window.iconAlpha(),1f)
        rest(true,true)
        closeTo(window.rect().width,360f);closeTo(window.rect().height,480f)
        // Changing pages under a fully expanded account must not move that window.
        repeat(hz*2) {step(false,true);closeTo(window.rect().height,480f)}
        rest(false,false);closeTo(window.rect().height,50f)
        repeat(hz*3) {i ->
            val detail=i/5%2==0;val account=i/11%2==0
            val beforeDock=dock.rect();val beforeWindow=window.rect()
            dock.target(detail);window.target(account)
            check(dock.rect()==beforeDock && window.rect()==beforeWindow) {"intent snapped geometry"}
            step(detail,account)
        }
        rest(true,false)
        closeTo(window.rect().width,50f);closeTo(window.rect().height,100f)
        rest(true,true);rest(true,false)
        closeTo(window.rect().height,100f) // Account closes back to pill, not the home circle.
        rest(false,false);closeTo(window.rect().height,50f)
    }
    println("PASS: actual 50 dp origin, full outline docking, press recovery, rapid reversals, blur carry, live resize, pill/account coexistence and frame-loop rest at 60/90/120 Hz")
}
