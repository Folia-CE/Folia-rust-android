package io.github.techuouo520.folia

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

/** SalamPlayer AppearancePreviews.ThemeModePreview and OptionTile's compact three choices. */
@Composable
internal fun AppearanceChoices(settings: DisplaySettings) {
    val ink=MaterialTheme.colorScheme.onSurface
    val accent=MaterialTheme.colorScheme.primary
    Column(Modifier.fillMaxWidth().padding(vertical=12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        WindowText("深浅色",ink,15)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf("system" to "跟随系统","light" to "浅色","dark" to "深色").forEach {(mode,label)->
                val chosen=settings.themeMode==mode
                Column(Modifier.weight(1f).semantics {selected=chosen;contentDescription=label}
                    .clickable(role=Role.RadioButton,onClick={settings.setTheme(mode)}),horizontalAlignment=Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxWidth().padding(3.dp).border(if(chosen) 2.dp else 0.dp,if(chosen) accent else Color.Transparent,RoundedCornerShape(18.dp)).padding(5.dp)) {
                        Canvas(Modifier.fillMaxWidth().height(88.dp).clip(RoundedCornerShape(14.dp))) {
                            fun palette(dark: Boolean) {
                                val w=size.width;val h=size.height;val inset=minOf(10.dp.toPx(),w*.12f);val width=w-2*inset
                                val primary=if(dark) Color(0xFFECEEF2) else Color(0xFF242830)
                                val secondary=if(dark) Color(0xFF737984) else Color(0xFFA4AAB3)
                                fun pill(x:Float,y:Float,ww:Float,hh:Float,c:Color)=drawRoundRect(c,Offset(x,y),Size(ww,hh),CornerRadius(hh/2))
                                drawRect(if(dark) Color(0xFF17191E) else Color(0xFFF5F6F9))
                                pill(inset,h*.15f,width*.48f,h*.055f,primary)
                                drawCircle(accent,h*.025f,Offset(w-inset-h*.025f,h*.1775f))
                                val cover=minOf(h*.32f,width*.43f);val top=h*.31f
                                drawRoundRect(Brush.linearGradient(listOf(Color(0xFF35C2F2),Color(0xFF7565EA))),Offset(inset,top),Size(cover,cover),CornerRadius(minOf(5.dp.toPx(),cover*.2f)))
                                val left=inset+cover+width*.10f;val tw=(w-inset-left).coerceAtLeast(0f)
                                pill(left,top+cover*.20f,tw,h*.047f,primary);pill(left,top+cover*.56f,tw*.68f,h*.035f,secondary)
                                pill(inset,h*.77f,width,h*.045f,if(dark) Color(0xFF383D46) else Color(0xFFDDE1E8))
                                pill(inset,h*.77f,width*.36f,h*.045f,accent)
                            }
                            palette(mode=="dark")
                            if(mode=="system") clipPath(Path().apply {moveTo(size.width*.6f,0f);lineTo(size.width,0f);lineTo(size.width,size.height);lineTo(size.width*.4f,size.height);close()}) {palette(true)}
                        }
                    }
                    WindowText(label,ink,13)
                    Canvas(Modifier.padding(top=8.dp).size(22.dp)) {
                        drawCircle(if(chosen) accent else ink.copy(alpha=.3f),style=if(chosen) Fill else Stroke(1.5.dp.toPx()))
                        if(chosen) {
                            val c=if(accent.luminance()>.4f) Color.Black else Color.White
                            drawLine(c,Offset(size.width*.28f,size.height*.5f),Offset(size.width*.44f,size.height*.65f),2.dp.toPx(),StrokeCap.Round)
                            drawLine(c,Offset(size.width*.44f,size.height*.65f),Offset(size.width*.73f,size.height*.35f),2.dp.toPx(),StrokeCap.Round)
                        }
                    }
                }
            }
        }
    }
}
