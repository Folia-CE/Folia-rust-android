package io.github.techuouo520.folia

import kotlin.math.*

internal fun playerMix(a: Float,b: Float,t: Float)=a+(b-a)*t
internal fun playerSmooth(a: Float,b: Float,value: Float): Float {
    val t=((value-a)/(b-a)).coerceIn(0f,1f)
    return t*t*(3-2*t)
}
internal data class PlayerRect(val x: Float,val y: Float,val width: Float,val height: Float) {
    val bottom get()=y+height
    fun towards(other: PlayerRect,t: Float)=PlayerRect(playerMix(x,other.x,t),playerMix(y,other.y,t),playerMix(width,other.width,t),playerMix(height,other.height,t))
}

/** Phone branch of NowPlayingBarView.computeLayoutProfile/resolveExpandedLayoutMetrics/onLayout.
 * All coordinates are dp, in the same changing card. No endpoint-only layout or second cover.
 * The accepted Folia pill's closed dimensions are retained; the open endpoint is SalamPlayer's.
 */
internal class MusicBarGeometry(
    val width: Float,val height: Float,bottomInset: Float,val p: Float,
    fontScale: Float=1f,artworkPresence: Float=1f,
) {
    private val margin=(bottomInset+10)*(1-p)
    val card=PlayerRect(14*(1-p),0f,width-28*(1-p),68+(height-margin-68)*p).let {it.copy(y=height-margin-it.height)}
    val backgroundAlpha=(p/.05f).coerceIn(0f,1f)
    val transportAlpha=((p-.35f)/.65f).coerceIn(0f,1f)
    val auxAlpha=((p-.50f)/.40f).coerceIn(0f,1f)
    private val aspect=max(width,height)/min(width,height)
    private val roomy=width>=430&&height>=820
    private val nearSquare=aspect<=1.45f
    private val controlScale=min(width/360,height/720).coerceIn(.78f,1f)
    private val rhythm=min(1.62f/aspect,820/height).coerceIn(.74f,1f)
    val textScale=playerMix(1f,if(roomy) 1.12f else 1.15f,p)
    val titleSize=15*textScale
    val artistSize=12.5f*textScale
    // TextView includeFontPadding=false; measured font metrics are supplied by the caller scale.
    val titleHeight=titleSize*fontScale*1.18f
    val artistHeight=artistSize*fontScale*1.18f
    val progressHeight=42f+13f*fontScale*1.18f
    private val expandedTextHeight=(15+12.5f)*(if(roomy) 1.12f else 1.15f)*fontScale*1.18f+2
    private val centerSize=max(52f,68*controlScale)
    private val sideSize=max(32f,40*controlScale)
    val auxSize=max(48f,60*controlScale)
    private val progressGap=max(24f,50*rhythm)
    private val toggleGap=max(28f,64*rhythm)
    private val coverTop=if(roomy) 72f else 80f
    private val coverMargin=(if(roomy) 42f else 35f)+(if(nearSquare) 8 else 0)
    private val coverFraction=if(nearSquare) .46f else if(roomy) .50f else if(aspect>=2) .54f else .56f
    private val minimumCover=max(88f,min(112f,height*.16f))
    private val coverToControl=30+expandedTextHeight+16+progressHeight+progressGap
    private val footprint=centerSize+coverToControl+toggleGap+auxSize+min(20f,max(10f,20*rhythm))+10+bottomInset+10
    private val budgetCover=max(minimumCover,min(max(96f,width-2*coverMargin),min(max(minimumCover,height*coverFraction),max(minimumCover,height-coverTop-footprint))))
    // Source's fixed 55dp portrait row inset, retaining real navigation-button safety if larger.
    val auxTop=height-max(55f,bottomInset+10)-auxSize
    private val maxControlTop=auxTop-toggleGap-centerSize
    private val coverSize=max(minimumCover,budgetCover-max(0f,coverTop+budgetCover+coverToControl-maxControlTop))
    private val controlTop=max(coverTop+coverSize+coverToControl,maxControlTop)
    val openProgressTop=controlTop-progressGap-progressHeight
    val contentLeft=(width-coverSize)/2
    val contentWidth=coverSize
    private val centerY=card.height/2
    private val baseCover=PlayerRect(10f,centerY-23,46f,46f).towards(PlayerRect(contentLeft,coverTop,coverSize,coverSize),p)
    val immersive=playerSmooth(.14f,.94f,p)*artworkPresence
    val cover=baseCover.towards(PlayerRect(0f,0f,card.width,max(baseCover.bottom,card.height*.62f)),immersive)
    val coverRadius=playerMix(playerMix(23f,16f,p),0f,immersive)
    val coverInnerRadius=playerMix(playerMix(14f,16f,p),0f,immersive)
    val coverFade=playerSmooth(.28f,.90f,immersive)
    private val fadeAnchor=openProgressTop+progressHeight/2+if(aspect>=1.55) progressGap/2 else 0f
    private val fadeDistance=max(120f,min(360f,height*(if(aspect>=1.55) .16f else .20f)))
    val fadeStart=((fadeAnchor-fadeDistance)/height).coerceIn(0f,.98f)
    val fadeEnd=((fadeAnchor+fadeDistance)/height).coerceIn(fadeStart+.01f,1f)
    private val textBlock=titleHeight+2+artistHeight
    val title=PlayerRect(playerMix(66f,contentLeft,p),playerMix(centerY-textBlock/2,openProgressTop-16-textBlock,p),playerMix(max(40f,card.width-164),contentWidth,p),titleHeight)
    val artist=title.copy(y=title.y+titleHeight+2,height=artistHeight)
    val play=PlayerRect(card.width-96,centerY-22,44f,44f).towards(PlayerRect((width-centerSize)/2,controlTop,centerSize,centerSize),p)
    private val sideTop=controlTop+(centerSize-sideSize)/2
    private val gap=max(32f,(if(roomy) 44f else 40f)*controlScale)
    val previous=PlayerRect((width-centerSize)/2-gap-sideSize,sideTop,sideSize,sideSize)
    val next=PlayerRect(card.width-50,centerY-24,48f,48f).towards(PlayerRect((width+centerSize)/2+gap,sideTop,sideSize,sideSize),p)
    // One retained rail: Folia's collapsed thin line grows into the source's 10dp track/time row.
    val progress=PlayerRect(66f,centerY+19,max(40f,card.width-164),2f).towards(PlayerRect(contentLeft,openProgressTop,contentWidth,progressHeight),p)
    private val auxGap=max(40f,(if(roomy) 54f else 48f)*controlScale)
    private val auxHalf=min(auxSize+auxGap,(width/2-auxSize/2)-(30-auxSize*.25f))*1.2f
    private val safeHalf=min(auxHalf,width/2-auxSize/2-15)
    val mode=PlayerRect(width/2-auxSize/2-safeHalf,auxTop,auxSize,auxSize)
    val lyrics=PlayerRect(width/2-auxSize/2,auxTop,auxSize,auxSize)
    val queue=PlayerRect(width/2-auxSize/2+safeHalf,auxTop,auxSize,auxSize)
}
