package io.github.techuouo520.folia

import android.graphics.Color as AndroidColor
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import kotlin.math.hypot
import kotlin.math.roundToInt

/** IColor.Scheme and GradientPainter, using the SAME transition clock as the app ColorScheme. */
internal data class PlayerPalette(val colors: List<Color>,val spots: List<Float>) {
    fun blend(to: PlayerPalette,t: Float)=PlayerPalette(colors.zip(to.colors){a,b->lerp(a,b,t)},spots.zip(to.spots){a,b->playerMix(a,b,t)})
}
internal val LocalPlayerPalette=staticCompositionLocalOf {playerPalette(null,false)}

internal fun playerPalette(clusters: IntArray?,dark: Boolean): PlayerPalette {
    val raw=List(3){i->if(clusters!=null) Color(clusters[i*4]) else SkyBlue}
    fun hsv(c:Color)=FloatArray(3).also {AndroidColor.colorToHSV(c.toArgb(),it)}
    fun clean(h:FloatArray) {
        val brown=h[0] in 14f..42f&&h[1] in .18f..0.66f&&h[2]<=.60f
        val muddy=h[0] in 34f..76f&&h[1] in .14f..0.62f&&h[2] in .16f..0.84f
        when {
            brown->{h[0]=playerMix(h[0],22f,.78f);h[1]=(maxOf(h[1],.38f)+.05f).coerceIn(.26f,.82f);h[2]=maxOf(h[2]+if(dark).16f else .10f,if(dark).64f else .42f).coerceIn(0f,.92f)}
            muddy->{h[0]=if(h[0]<54) playerMix(h[0],28f,.72f) else playerMix(h[0],94f,.62f);h[1]=(maxOf(h[1],if(dark).38f else .30f)+.04f).coerceIn(.24f,.82f);h[2]=(h[2]+if(dark).12f else .08f).coerceIn(if(dark).56f else .34f,.92f)}
            h[1]<.18f&&h[2] in .18f..0.82f->h[1]=(maxOf(h[1],.18f)+.03f).coerceAtMost(.72f)
        }
    }
    fun sanitize(c:Color,i:Int):Color {
        val h=hsv(c);clean(h)
        val minS=if(dark) floatArrayOf(.28f,.24f,.20f,.14f) else floatArrayOf(.24f,.20f,.18f,.10f)
        val minV=if(dark) floatArrayOf(.30f,.26f,.22f,.16f) else floatArrayOf(.46f,.40f,.36f,.30f)
        val maxV=if(dark) floatArrayOf(.88f,.82f,.76f,.60f) else floatArrayOf(.96f,.92f,.88f,.82f)
        h[1]=h[1].coerceIn(minS[i],if(i==3) {if(dark).54f else .48f} else {if(dark).82f else .76f})
        h[2]=h[2].coerceIn(minV[i],maxV[i])
        if(h[1]<.18f&&h[2]>.18f&&h[2]<.82f){h[1]=(maxOf(h[1],if(dark).22f else .18f)+.04f).coerceAtMost(1f);h[2]=(h[2]+if(dark).04f else .06f).coerceAtMost(1f)}
        return Color(AndroidColor.HSVToColor(h))
    }
    val tuned=raw.mapIndexed {i,c->
        val h=hsv(c)
        h[1]=(h[1]*(if(dark) floatArrayOf(1.10f,1.04f,.98f) else floatArrayOf(1.18f,1.10f,1.04f))[i]).coerceAtMost(1f)
        h[2]=(h[2]*(if(dark) floatArrayOf(1.06f,1.02f,.98f) else floatArrayOf(1.12f,1.06f,1.02f))[i]).coerceAtMost(1f)
        val mixed=sourceColorMix(Color(AndroidColor.HSVToColor(h)),c,if(dark).18f else .20f)
        sanitize(sourceColorMix(mixed,if(dark)Color.Black else Color.White,if(dark).03f else .04f),i)
            .copy(alpha=(if(dark) intArrayOf(232,212,190) else intArrayOf(218,198,176))[i]/255f)
    }
    val bottomSeed=sourceColorMix(sourceColorMix(raw[0],raw[1],.35f),raw[2],.28f)
    val bottom=sanitize(sourceColorMix(if(dark)sourceColorMix(bottomSeed,Color.Black,.44f) else sourceColorMix(bottomSeed,raw[2],.20f),Color.Black,if(dark).10f else .12f),3)
    val spots=List(9){i->val c=i/3;when(i%3){
        0->(clusters?.let {Float.fromBits(it[c*4+1])}?:listOf(.3f,.7f,.5f)[c]).coerceIn(.14f,.86f)
        1->(clusters?.let {Float.fromBits(it[c*4+2])}?:listOf(.2f,.5f,.8f)[c]).coerceIn(.08f,.84f)
        else->((clusters?.let {Float.fromBits(it[c*4+3])}?:.3f)*listOf(1.55f,1.50f,1.45f)[c]).coerceIn(listOf(.28f,.26f,.24f)[c],listOf(.60f,.58f,.56f)[c])
    }}
    return PlayerPalette(tuned+bottom+(if(dark)Color.Black.copy(alpha=48/255f) else Color.White.copy(alpha=18/255f))+(if(dark)Color(0xFF1A1A1A) else Color(0xFFF7F3EC)),spots)
}

internal fun Modifier.playerBackdrop(palette:PlayerPalette)=drawWithCache {
    val c=palette.colors
    val base=Brush.verticalGradient(0f to sourceColorMix(c[0],c[1],.36f).let{it.copy(alpha=maxOf(170/255f,it.alpha))},.58f to sourceColorMix(c[1],c[2],.42f).let{it.copy(alpha=maxOf(210/255f,it.alpha))},1f to c[3])
    val diag=hypot(size.width,size.height)
    val spots=List(3){i->val alpha=listOf(.96f,.88f,.82f)[i];Brush.radialGradient(
        0f to c[i].copy(alpha=c[i].alpha*alpha),.52f to c[i].copy(alpha=c[i].alpha*.34f*alpha),1f to c[i].copy(alpha=0f),
        center=Offset(palette.spots[i*3]*size.width,palette.spots[i*3+1]*size.height),radius=maxOf(diag*.18f,diag*palette.spots[i*3+2]))}
    onDrawBehind {drawRect(c[5]);drawRect(base);spots.forEach{drawRect(it)};drawRect(c[4])}
}

/** IColor.mix blends ARGB channels, not Compose's perceptual colour interpolation. */
private fun sourceColorMix(a:Color,b:Color,t:Float):Color {
    val x=a.toArgb();val y=b.toArgb()
    fun channel(shift:Int)=playerMix(((x ushr shift) and 255).toFloat(),((y ushr shift) and 255).toFloat(),t).roundToInt().coerceIn(0,255)
    return Color(AndroidColor.argb(channel(24),channel(16),channel(8),channel(0)))
}
