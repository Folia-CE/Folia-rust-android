package io.github.techuouo520.folia

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.animation.AnimationUtils
import android.widget.TextView
import kotlin.math.*

/** Kotlin host for SalamPlayer's HdrHighlightTextView smooth-marquee branch.
 * TextView supplies layout and alpha fading edges; neither focus nor selection owns the clock.
 * The unrelated HDR colour mask is not applied to these ordinary UI titles.
 */
internal class MarqueeTitleView(context: Context): TextView(context) {
    private var marquee: SmoothMarquee? = null
    init {
        setSingleLine(true)
        ellipsize=null
        setHorizontallyScrolling(true)
        isHorizontalFadingEdgeEnabled=true
        setFadingEdgeLength((18*resources.displayMetrics.density).roundToInt())
        includeFontPadding=false
        gravity=Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP,TITLE_SIZE_SP)
        typeface=titleTypeface()
        marquee=SmoothMarquee(this)
    }
    companion object {
        private const val TITLE_SIZE_SP=25f
        private fun titleTypeface()=if(Build.VERSION.SDK_INT>=28) Typeface.create(Typeface.SANS_SERIF,800,false) else Typeface.DEFAULT_BOLD
        fun textWidth(context: Context,text: String): Float = android.text.TextPaint().apply {
            textSize=TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,TITLE_SIZE_SP,context.resources.displayMetrics)
            typeface=titleTypeface()
        }.measureText(text)
    }
    override fun setText(text: CharSequence?,type: BufferType?) {
        marquee?.beforeTextChange(getText(),text)
        super.setText(text,type)
    }
    override fun onVisibilityAggregated(visible: Boolean) {
        super.onVisibilityAggregated(visible)
        if(visible) marquee?.onShown() else marquee?.onHidden()
    }
    override fun onDetachedFromWindow() {marquee?.onHidden();super.onDetachedFromWindow()}
    override fun getSolidColor()=0 // Real alpha at the edges; never blend text into black/white.
    override fun getLeftFadingEdgeStrength()=marquee?.leftFadeStrength()?:super.getLeftFadingEdgeStrength()
    override fun getRightFadingEdgeStrength()=marquee?.rightFadeStrength()?:super.getRightFadingEdgeStrength()
    override fun onDraw(canvas: Canvas) {if(marquee?.draw(canvas)!=true) super.onDraw(canvas)}
}

/** Full SmoothMarquee state machine from SalamPlayer/SmoothMarquee.java, in Kotlin. */
private class SmoothMarquee(private val view: TextView) {
    private val invalidate=Runnable {view.invalidate()}
    private var state=IDLE
    private var restUntilMs=0L
    private var glide=0f
    private var lastStepMs=-1L
    private var settleFrom=0f
    private var settleTo=0f
    private var settleStartMs=0L
    private var offset=0f
    private var updatedAtMs=Long.MIN_VALUE
    private var leavingText: String?=null
    private var leavingLeft=0f
    private var leavingCopyLeft=Float.NaN
    private var changeStartMs=-1L
    private var overflow=false
    private var textWidth=0f
    private var cycle=0f
    private var direction=1
    private var startFade=0f
    private var endFade=0f
    private fun dp(value: Float)=value*view.resources.displayMetrics.density

    fun beforeTextChange(previous: CharSequence?,next: CharSequence?) {
        if(TextUtils.equals(previous,next)) return
        val now=AnimationUtils.currentAnimationTimeMillis()
        val visible=view.isAttachedToWindow && view.isShown && view.width>0
        if(visible && !previous.isNullOrEmpty() && view.layout!=null) {
            update(now)
            leavingText=previous.toString()
            leavingLeft=restLeft()-direction*offset
            leavingCopyLeft=if(overflow||offset>.5f) leavingLeft+direction*cycle else Float.NaN
            changeStartMs=now
        } else {leavingText=null;changeStartMs=-1L}
        state=IDLE;offset=0f;glide=0f;lastStepMs=-1L;updatedAtMs=Long.MIN_VALUE
        restUntilMs=now+(if(changeStartMs>=0) LEAVE_MS+ENTER_MS else 0)+REST_MS
        view.invalidate()
    }
    fun onShown(){lastStepMs=-1L;view.invalidate()}
    fun onHidden(){view.removeCallbacks(invalidate);lastStepMs=-1L}
    fun leftFadeStrength(): Float {update(AnimationUtils.currentAnimationTimeMillis());return if(direction>0) startFade else endFade}
    fun rightFadeStrength(): Float {update(AnimationUtils.currentAnimationTimeMillis());return if(direction>0) endFade else startFade}

    fun draw(canvas: Canvas): Boolean {
        val now=AnimationUtils.currentAnimationTimeMillis()
        update(now);scheduleNext(now)
        val layout=view.layout
        val changing=changeStartMs>=0
        if(layout==null || layout.lineCount<1 || (!changing&&offset<=.01f)) return false
        val save=canvas.save()
        canvas.translate(view.scrollX.toFloat(),view.scrollY.toFloat())
        canvas.clipRect(view.compoundPaddingLeft,0,view.width-view.compoundPaddingRight,view.height)
        val paint=view.paint;val color=view.currentTextColor
        paint.drawableState=view.drawableState
        val top=(view.baseline-layout.getLineBaseline(0)).toFloat()
        val rest=restLeft()
        if(changing) {
            val k=now-changeStartMs
            val leaving=leavingText
            if(k<LEAVE_MS && leaving!=null) {
                val p=k.toFloat()/LEAVE_MS
                val baseline=view.baseline-dp(3f)*p
                paint.color=withAlpha(color,1-p)
                canvas.drawText(leaving,leavingLeft,baseline,paint)
                if(!leavingCopyLeft.isNaN()) canvas.drawText(leaving,leavingCopyLeft,baseline,paint)
            } else {
                val q=((k-LEAVE_MS).toFloat()/ENTER_MS).coerceIn(0f,1f)
                val out=1-(1-q)*(1-q)
                paint.color=withAlpha(color,out)
                drawLine(canvas,layout,rest-direction*offset,top+dp(4f)*(1-out))
            }
        } else {
            paint.color=color
            val left=rest-direction*offset
            drawLine(canvas,layout,left,top)
            val copy=left+direction*cycle
            if(copy<view.width && copy+textWidth>0) drawLine(canvas,layout,copy,top)
        }
        paint.color=color;canvas.restoreToCount(save)
        return true
    }
    private fun drawLine(canvas: Canvas,layout: Layout,left: Float,top: Float) {
        val save=canvas.save();canvas.translate(left-layout.getLineLeft(0),top);layout.draw(canvas);canvas.restoreToCount(save)
    }
    private fun restLeft(): Float {
        val layout=view.layout?:return view.compoundPaddingLeft.toFloat()
        return view.compoundPaddingLeft+layout.getLineLeft(0)-view.scrollX
    }
    private fun update(now: Long) {
        if(now==updatedAtMs) return
        updatedAtMs=now
        val layout=view.layout
        val available=view.width-view.compoundPaddingLeft-view.compoundPaddingRight
        if(layout==null || layout.lineCount<1 || available<=0) {
            overflow=false;textWidth=0f;startFade=0f;endFade=0f;return
        }
        direction=if(layout.getParagraphDirection(0)==Layout.DIR_RIGHT_TO_LEFT) -1 else 1
        textWidth=layout.getLineWidth(0);overflow=textWidth-available>.5f
        cycle=textWidth+view.textSize*2.2f
        if(changeStartMs>=0 && now-changeStartMs>=LEAVE_MS+ENTER_MS) {changeStartMs=-1;leavingText=null}
        if(overflow) {
            if(state==IDLE) {state=REST;restUntilMs=max(restUntilMs,now+REST_MS);offset=0f}
        } else if(state==REST||state==IDLE) {state=IDLE;offset=0f}
        else if(state==GLIDE) {state=SETTLE;settleFrom=offset;settleTo=if(offset>cycle*.5f) cycle else 0f;settleStartMs=now}
        when(state) {
            REST -> {offset=0f;if(now>=restUntilMs){state=GLIDE;glide=0f;lastStepMs=now}}
            GLIDE -> {
                val step=if(lastStepMs<0) 0 else (now-lastStepMs).coerceIn(0,50)
                lastStepMs=now
                val duration=max(1.3f,cycle/max(1f,dp(34f))+(.7f+.9f)*.5f)
                glide=min(1f,glide+step/1000f/duration)
                offset=cycle*profile(glide,duration)
                if(glide>=1){offset=0f;state=REST;restUntilMs=now+REST_MS}
            }
            SETTLE -> {
                val k=min(1f,(now-settleStartMs)/450f)
                val eased=1-(1-k)*(1-k)*(1-k)
                offset=settleFrom+(settleTo-settleFrom)*eased
                if(k>=1){offset=0f;state=if(overflow) REST else IDLE;restUntilMs=now+REST_MS}
            }
            else -> offset=0f
        }
        val ramp=dp(12f)
        startFade=if(offset<textWidth) (offset/ramp).coerceIn(0f,1f) else 0f
        var endOverflow=textWidth-offset-available
        val copyStart=cycle-offset
        if(copyStart<available) endOverflow=max(endOverflow,copyStart+textWidth-available)
        endFade=(endOverflow/ramp).coerceIn(0f,1f)
    }
    private fun scheduleNext(now: Long) {
        view.removeCallbacks(invalidate)
        if(!view.isAttachedToWindow||!view.isShown) return
        if(state==GLIDE||state==SETTLE||changeStartMs>=0) view.postInvalidateOnAnimation()
        else if(state==REST) view.postOnAnimationDelayed(invalidate,max(0,restUntilMs-now))
    }
    private fun profile(u: Float,duration: Float): Float {
        if(u<=0) return 0f
        if(u>=1) return 1f
        val fa=min(.45f,.7f/duration);val fd=min(.45f,.9f/duration)
        val peak=1/(1-fa*.5f-fd*.5f)
        if(u<fa){val s=u/fa;return peak*fa*(s*s*s-s*s*s*s*.5f)}
        if(u<1-fd) return peak*(fa*.5f+(u-fa))
        val s=(1-u)/fd
        return 1-peak*fd*(s*s*s-s*s*s*s*.5f)
    }
    private fun withAlpha(color: Int,share: Float)=(color and 0x00FFFFFF) or ((Color.alpha(color)*share.coerceIn(0f,1f)).roundToInt() shl 24)
    companion object {
        private const val TITLE_SIZE_SP=25f
        private const val IDLE=0
        private const val REST=1
        private const val GLIDE=2
        private const val SETTLE=3
        private const val REST_MS=2400L
        private const val LEAVE_MS=160L
        private const val ENTER_MS=260L
    }
}
