package io.github.techuouo520.folia

import kotlin.math.*

internal fun playerMix(a: Float,b: Float,t: Float)=a+(b-a)*t
internal fun playerSmooth(a: Float,b: Float,value: Float): Float {
    val t=((value-a)/(b-a)).coerceIn(0f,1f)
    return t*t*(3-2*t)
}
internal data class PlayerRect(val x: Float,val y: Float,val width: Float,val height: Float) {
    fun scaledAtCenter(scale: Float) = PlayerRect(x+width*(1-scale)/2,y+height*(1-scale)/2,width*scale,height*scale)
    val bottom get()=y+height
    fun towards(other: PlayerRect,t: Float)=PlayerRect(playerMix(x,other.x,t),playerMix(y,other.y,t),playerMix(width,other.width,t),playerMix(height,other.height,t))
}

/** Geometry from NowPlayingBarView.computeLayoutProfile/resolveExpandedLayoutMetrics/onLayout.
 * All coordinates are dp, in the same changing card. No endpoint-only layout or second cover.
 * The accepted Folia pill's closed dimensions are retained; the open endpoint is SalamPlayer's.
 */
internal class MusicBarGeometry(
    val width: Float,val height: Float,bottomInset: Float,val p: Float,
    fontScale: Float=1f,artworkPresence: Float=1f,press: Float=0f,displayRadius: Float=34f,
    lyricsMode: Float=0f,topInset: Float=0f,leftInset: Float=0f,rightInset: Float=0f,
) {
    private val margin=(bottomInset+10)*(1-p)
    private val restingCard=PlayerRect(14*(1-p),0f,width-28*(1-p),68+(height-margin-68)*p).let {it.copy(y=height-margin-it.height)}
    private val insetFromPress=.035f*press*(1-p)*.5f
    val card=PlayerRect(restingCard.x+restingCard.width*insetFromPress,restingCard.y+restingCard.height*insetFromPress,
        restingCard.width*(1-2*insetFromPress),restingCard.height*(1-2*insetFromPress))
    private val edgeGap=max(max(card.x,width-card.x-card.width),max(card.y,height-card.bottom))
    val cardRadius=playerMix(34f,displayRadius,p)*playerMix(.45f,1f,(edgeGap/8f).coerceIn(0f,1f))
    val compactActionAlpha=(1-3*p).coerceIn(0f,1f)
    val backgroundAlpha=(p/.05f).coerceIn(0f,1f)
    val transportAlpha=((p-.35f)/.65f).coerceIn(0f,1f)
    val auxAlpha=((p-.50f)/.40f).coerceIn(0f,1f)
    private val aspect=max(width,height)/min(width,height)
    private val tablet=min(width,height)>=600
    private val largePortrait=tablet && height>=width
    private val roomy=!largePortrait && height>=width && width>=430 && height>=820
    private val availableWidth=width-leftInset-rightInset
    val wide=availableWidth>=628 && width>height && (tablet || availableWidth/height>=1.35f)
    private val sideways=if(wide) WidePlayerLayout(width,height,tablet,bottomInset,topInset,leftInset,rightInset,displayRadius,lyricsMode,fontScale) else null
    val inlineTimes=if(wide && !tablet) 1f else 0f
    private val nearSquare=aspect<=1.45f
    private val controlScale=if(largePortrait) 1f else min(width/360,height/720).coerceIn(.78f,1f)
    private val rhythm=min(1.62f/aspect,(if(largePortrait) 1000f else 820f)/height).coerceIn(if(largePortrait) .68f else .74f,1f)
    private val openTextScale=when { largePortrait->1.08f; roomy->1.12f; else->1.15f } *
        (if(wide) (if(height<560) 1.06f else 1.3f) else 1f)
    val textScale=playerMix(1f,openTextScale,p)
    val titleSize=15*textScale
    val artistSize=12.5f*textScale
    // TextView includeFontPadding=false; measured font metrics are supplied by the caller scale.
    val titleHeight=titleSize*fontScale*1.18f
    val artistHeight=artistSize*fontScale*1.18f
    val progressHeight=42f+13f*fontScale*1.18f
    private val expandedTextHeight=(15+12.5f)*openTextScale*fontScale*1.18f+2
    private val centerSize=max(52f,(if(largePortrait) 64 else 68)*controlScale)
    private val sideSize=max(32f,(if(largePortrait) 38 else 40)*controlScale)
    val auxSize=sideways?.buttonSize ?: max(48f,60*controlScale)
    private val progressGap=max(24f,50*rhythm)
    private val toggleGap=max(28f,64*rhythm)
    private val coverTop=if(largePortrait) 64f else if(roomy) 72f else 80f
    private val coverMargin=(if(largePortrait) 56f else if(roomy) 42f else 35f)+(if(nearSquare) 8 else 0)
    private val coverFraction=if(largePortrait) (if(nearSquare) .38f else .42f) else if(nearSquare) .46f else if(roomy) .50f else if(aspect>=2) .54f else .56f
    private val minimumCover=max(88f,min(if(largePortrait) 132f else 112f,height*.16f))
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
    private val compactHeaderSize=when {largePortrait->72f;roomy->64f;else->60f}
    private val compactHeaderLeft=if(largePortrait) 28f else 24f
    private val compactHeaderTop=((when {largePortrait->84;roomy->88;else->54})+20)*1.1f
    private val header=PlayerRect(compactHeaderLeft,compactHeaderTop,compactHeaderSize,compactHeaderSize)
    private val lyricTravel=if(wide) 0f else p*lyricsMode
    private val baseCover=PlayerRect(10f,centerY-23,46f,46f).towards(sideways?.cover ?: PlayerRect(contentLeft,coverTop,coverSize,coverSize),p)
    val immersive=if(wide) 0f else playerSmooth(.14f,.94f,p)*artworkPresence*(1-lyricsMode)
    val cover=baseCover.towards(PlayerRect(0f,0f,card.width,max(baseCover.bottom,card.height*.62f)),immersive)
        .towards(header,if(wide) 0f else playerSmooth(.04f,.96f,p)*lyricsMode)
    val coverRadius=playerMix(playerMix(23f,16f,p),0f,immersive)
    val coverInnerRadius=playerMix(playerMix(14f,16f,p),0f,immersive)
    val coverFade=playerSmooth(.28f,.90f,immersive)
    private val fadeAnchor=openProgressTop+progressHeight/2+if(aspect>=1.55) progressGap/2 else 0f
    private val fadeDistance=max(120f,min(360f,height*(if(aspect>=1.55) .16f else .20f)))
    val fadeStart=((fadeAnchor-fadeDistance)/height).coerceIn(0f,.98f)
    val fadeEnd=((fadeAnchor+fadeDistance)/height).coerceIn(fadeStart+.01f,1f)
    private val textBlock=titleHeight+2+artistHeight
    private val textTarget=sideways?.title ?: PlayerRect(contentLeft,openProgressTop-16-textBlock,contentWidth,titleHeight)
    private val compactTextX=header.x+header.width+when {largePortrait->16;roomy->14;else->12}
    val title=PlayerRect(66f,centerY-textBlock/2,max(40f,card.width-164),titleHeight)
        .towards(textTarget.copy(height=titleHeight),p)
        .towards(PlayerRect(compactTextX,header.y+(header.height-textBlock)/2,
            (width-compactTextX-24).coerceAtLeast(0f),titleHeight),lyricTravel)
    val artist=title.copy(y=title.bottom+2,height=artistHeight)

    // applyLyricsCompactToggle budgets the sink against the real bottom row and progress rail.
    private val auxShift=height-max(40f,max(bottomInset,displayRadius))-(auxTop+auxSize)
    private val rowGap=when {largePortrait->24;roomy->20;else->16}
    private val sink=min(when {largePortrait->96f;roomy->92f;else->88f},
        (auxTop+auxShift-rowGap-controlTop-centerSize).coerceAtLeast(0f))
    private val minGap=when {largePortrait->18f;roomy->16f;else->14f}
    private val minScale=when {largePortrait->.78f;roomy->.75f;else->.72f}
    private val wantedSink=when {largePortrait->126f;roomy->122f;else->118f}
    private val deficit=minGap+openProgressTop+progressHeight+wantedSink-controlTop-sink
    private val compactScale=(1-2*deficit/centerSize).coerceIn(minScale,.90f)
    private val progressSink=min(wantedSink,(controlTop+sink+centerSize*(1-compactScale)/2-minGap-openProgressTop-progressHeight).coerceAtLeast(0f))
    private fun PlayerRect.inLyrics()=copy(y=y+sink*lyricTravel).scaledAtCenter(playerMix(1f,compactScale,lyricTravel))

    val play=PlayerRect(card.width-96,centerY-22,44f,44f)
        .towards(sideways?.play ?: PlayerRect((width-centerSize)/2,controlTop,centerSize,centerSize),p).inLyrics()
    private val sideTop=controlTop+(centerSize-sideSize)/2
    private val gap=max(32f,(when {largePortrait->48f;roomy->44f;else->40f})*controlScale)
    val previous=(sideways?.previous ?: PlayerRect((width-centerSize)/2-gap-sideSize,sideTop,sideSize,sideSize)).inLyrics()
    val compactNext=PlayerRect(card.width-50,centerY-24,48f,48f)
    val next=(sideways?.next ?: PlayerRect((width+centerSize)/2+gap,sideTop,sideSize,sideSize)).inLyrics()
    val progress=PlayerRect(66f,centerY+19,max(40f,card.width-164),2f)
        .towards(sideways?.progress ?: PlayerRect(contentLeft,openProgressTop,contentWidth,progressHeight),p)
        .let { it.copy(y=it.y+progressSink*lyricTravel) }
    private val auxGap=max(40f,(when {largePortrait->60f;roomy->54f;else->48f})*controlScale)
    private val auxHalf=min(auxSize+auxGap,(width/2-auxSize/2)-(30-auxSize*.25f))*1.2f
    private val safeHalf=min(auxHalf,width/2-auxSize/2-15)
    private val middle=PlayerRect(width/2-auxSize/2,auxTop+auxShift*lyricTravel,auxSize,auxSize)
    val mode=sideways?.slot(4) ?: middle.copy(x=middle.x-safeHalf)
    val lyrics=sideways?.slot(1) ?: middle
    val queue=sideways?.slot(0) ?: middle.copy(x=middle.x+safeHalf)
    private val panelTop=max(cover.bottom,artist.bottom)+10
    val lyricPanel=sideways?.panel ?: PlayerRect(24f,panelTop,(card.width-48).coerceAtLeast(0f),
        (progress.y-10-panelTop).coerceAtLeast(0f))
}

/** Open targets of resolvePadCornerGeometry. Phone landscape uses the short inline-time capsule. */
private class WidePlayerLayout(w:Float,h:Float,tablet:Boolean,bottom:Float,top:Float,left:Float,right:Float,
    displayRadius:Float,lyrics:Float,fontScale:Float) {
    private val short=h<560
    private val edge=if(short) 24f else 36f
    private val capBottom=h-(if(tablet) max(bottom,displayRadius)+(if(short) 8 else 16) else bottom+6)
    private val capWidth=if(tablet) (w*.44f).coerceIn(380f,620f) else (w*.36f).coerceIn(280f,340f)
    private val pad=if(tablet) (if(short) 5f else 7f) else 4f
    private val playSize=if(tablet) max(44f,68f*.86f*(if(short) .68f else .80f)) else 40f
    private val sideSize=if(tablet) 28f else 26f
    private val gap=if(tablet) 46f else 34f
    private val progressHeight=if(tablet) 42f+13f*fontScale*1.18f else 18f
    val progress=PlayerRect((w-capWidth)/2+6,capBottom-pad-progressHeight,capWidth-12,progressHeight)
    val play=PlayerRect((w-playSize)/2,progress.y-playSize-(if(tablet) 2 else 0),playSize,playSize)
    val previous=PlayerRect(play.x-gap-sideSize,play.y+(playSize-sideSize)/2,sideSize,sideSize)
    val next=previous.copy(x=play.x+playSize+gap)
    private val railRight=w-right-8
    private val railLeft=railRight-40
    private val railTopInset=max(top,displayRadius*(if(tablet) 1f else .6f))
    val buttonSize=((capBottom-railTopInset-12-8-7*6)/8).coerceIn(34f,37.5f)
    private val railTop=capBottom-8*buttonSize-7*6-8
    fun slot(index:Int)=PlayerRect(railLeft+(40-buttonSize)/2,railTop+4+index*(buttonSize+6),buttonSize,buttonSize)
    val title=PlayerRect(left+edge,max(top,displayRadius*(if(tablet) 1f else .45f))+(if(short) 10 else 20),
        min(w*.46f,railLeft-24-left-edge).coerceAtLeast(160f),0f)
    private val contentTop=title.y+(if(short) 64 else 90)
    private val contentH=max(120f,play.y-pad-(if(short) 18 else 24)-contentTop)
    private val contentW=max(240f,railLeft-28-title.x)
    private val lyricsGap=if(short) 36f else 52f
    private val art=min(contentH,(contentW-lyricsGap)*.44f)
    private val lyricsWidth=max(160f,min(contentW-art-lyricsGap,if(short) contentW else art*1.32f))
    private val pairLeft=title.x+(if(short) 0f else max(0f,(contentW-art-lyricsGap-lyricsWidth)/2))
    val cover=PlayerRect(playerMix(title.x+(contentW-art)/2,pairLeft,lyrics),contentTop+(contentH-art)/2,art,art)
    val panel=PlayerRect(pairLeft+art+lyricsGap,cover.y,lyricsWidth,art)
}
