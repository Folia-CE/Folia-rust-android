package io.github.techuouo520.folia

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Slider
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.spring
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import io.github.techuouo520.folia.bridge.FoliaBridge
import io.github.techuouo520.folia.spatial.sceneInk
import java.util.Locale

/** Settings typography and spacing are independent of the spatial scene's small atlas labels. */
@Composable
internal fun SettingsContents(bridge: FoliaBridge, dark: Boolean, interactive: Boolean) {
    SettingsBody(bridge,dark,interactive)
}

@Composable
private fun SettingsBody(bridge: FoliaBridge,dark: Boolean,interactive: Boolean) {
    val app=FoliaApp.from(LocalContext.current)
    val engine=app.audioEffects
    val settings=engine.settings
    val prefs=settings.value
    var menu by remember {mutableStateOf<String?>(null)}
    LaunchedEffect(interactive) {if(!interactive) menu=null}
    BackHandler(menu!=null){menu=null}
    WindowGroup("声音",dark) {
        SettingsToggle("低频增压","仅作用于内置扬声器",prefs.enabled,dark) {
            settings.update(settings.value.copy(enabled=it))
        }
        SettingsToggle("智能强度","随系统音量自动调整",prefs.smart,dark) {
            settings.update(settings.value.copy(smart=it))
        }
        SettingsAdjustment(
            title="增强强度",display=if(prefs.smart) "自动" else String.format(Locale.ROOT,"%.1f",prefs.strength),
            value=prefs.strength/30f,range=30f,enabled=!prefs.smart,dark=dark,
            start="0",end="30",
        ) {settings.update(settings.value.copy(strength=it*30f))}

    }
    var quality by remember {mutableStateOf(bridge.audioQuality())}
    val qualities=remember {listOf("standard" to "标准","high" to "高品质","ultra" to "超高品质","lossless" to "无损","hires" to "Hi-Res")}
    WindowGroup("播放",dark) {
        SettingsDropdown("在线播放音质",quality,qualities,dark,menu=="quality"&&interactive,
            onOpen={menu="quality"},onDismiss={menu=null}) {id->
            if(id!=quality) {bridge.setAudioQuality(id);quality=id}
            menu=null
        }
    }
    var language by remember {mutableStateOf(bridge.languagePreference())}
    val languages=remember {listOf("system" to "跟随系统","zh-CN" to "简体中文","zh-TW" to "繁體中文","en" to "English","in" to "Bahasa Indonesia","vi" to "Tiếng Việt")}
    WindowGroup("外观",dark) {
        SettingsToggle("HDR",null,app.displaySettings.hdrEnabled,dark,app.displaySettings::setHdr)
        AppearanceChoices(app.displaySettings)
        SettingsDropdown("界面语言",language,languages,dark,menu=="language"&&interactive,
            onOpen={menu="language"},onDismiss={menu=null}) {id->
            if(id!=language) {bridge.setLanguagePreference(id);language=id;engine.appearanceChanged()}
            menu=null
        }
    }
}

@Composable
internal fun WindowGroup(title: String,dark: Boolean,content: @Composable ColumnScope.()->Unit) {
    val ink=sceneInk(dark)
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(9.dp)) {
        BasicText(title,Modifier.padding(start=3.dp),style=TextStyle(
            color=ink.copy(alpha=.58f),fontSize=12.sp,lineHeight=18.sp,fontWeight=FontWeight.SemiBold,letterSpacing=1.sp))
        Column(Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha=.045f),RoundedCornerShape(20.dp))
            .padding(horizontal=16.dp,vertical=8.dp),content=content)
    }
}

@Composable
private fun SettingsToggle(title: String,detail: String?,checked: Boolean,dark: Boolean,change: (Boolean)->Unit) {
    val ink=sceneInk(dark)
    Row(Modifier.fillMaxWidth().heightIn(min=64.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f).padding(vertical=10.dp),verticalArrangement=Arrangement.spacedBy(3.dp)) {
            WindowText(title,ink,15,FontWeight.Medium,lineHeight=22)
            if(detail!=null) WindowText(detail,ink.copy(alpha=.55f),12,lineHeight=18)
        }
        Switch(checked=checked,onCheckedChange=change,modifier=Modifier.semantics {contentDescription=title})
    }
}

@Composable
private fun SettingsDropdown(title: String,value: String,options: List<Pair<String,String>>,dark: Boolean,
    expanded: Boolean,onOpen: ()->Unit,onDismiss: ()->Unit,onSelect: (String)->Unit) {
    val ink=sceneInk(dark)
    val accent=MaterialTheme.colorScheme.primary
    val selected=options.firstOrNull {it.first==value}?.second?:value
    val interaction=remember {MutableInteractionSource()}
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min=64.dp).semantics(mergeDescendants=true) {
            contentDescription="$title，当前$selected";stateDescription=if(expanded) "已展开" else "已收起"
        }.clickable(interactionSource=interaction,indication=null,role=Role.DropdownList) {
            if(expanded) onDismiss() else onOpen()
        },verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f).padding(vertical=10.dp),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                WindowText(title,ink,15,FontWeight.Medium,lineHeight=22)
                WindowText(selected,ink.copy(alpha=.58f),13,lineHeight=19)
            }
            Canvas(Modifier.size(44.dp)) {
                val cx=size.width/2;val cy=size.height/2;val dy=if(expanded) -1 else 1
                drawLine(ink.copy(alpha=.6f),Offset(cx-4.dp.toPx(),cy-2.dp.toPx()*dy),Offset(cx,cy+2.dp.toPx()*dy),1.7.dp.toPx(),StrokeCap.Round)
                drawLine(ink.copy(alpha=.6f),Offset(cx,cy+2.dp.toPx()*dy),Offset(cx+4.dp.toPx(),cy-2.dp.toPx()*dy),1.7.dp.toPx(),StrokeCap.Round)
            }
        }
        // In-flow dropdown: no Popup, separate window or menu laid over the settings.
        AnimatedVisibility(expanded,
            enter=expandVertically(spring(dampingRatio=.8f,stiffness=420f),expandFrom=Alignment.Top),
            exit=shrinkVertically(spring(dampingRatio=.8f,stiffness=420f),shrinkTowards=Alignment.Top)) {
            Column(Modifier.fillMaxWidth().padding(bottom=8.dp)) {
                options.forEach {(id,label)->
                    val source=remember(id){MutableInteractionSource()}
                    Row(Modifier.fillMaxWidth().heightIn(min=48.dp)
                        .background(if(id==value) accent.copy(alpha=.10f) else Color.Transparent,RoundedCornerShape(12.dp))
                        .semantics {this.selected=id==value}
                        .clickable(interactionSource=source,indication=null,role=Role.RadioButton){onSelect(id)}
                        .padding(horizontal=12.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically) {
                        WindowText(label,if(id==value) accent else ink,14,if(id==value) FontWeight.SemiBold else FontWeight.Normal,lineHeight=21,modifier=Modifier.weight(1f))
                        if(id==value) Canvas(Modifier.size(18.dp)) {
                            drawLine(accent,Offset(size.width*.18f,size.height*.5f),Offset(size.width*.42f,size.height*.72f),1.8.dp.toPx(),StrokeCap.Round)
                            drawLine(accent,Offset(size.width*.42f,size.height*.72f),Offset(size.width*.84f,size.height*.25f),1.8.dp.toPx(),StrokeCap.Round)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsAdjustment(title: String,display: String,value: Float,range: Float,enabled: Boolean,
    dark: Boolean,start: String,end: String,change: (Float)->Unit) {
    val ink=sceneInk(dark)
    val opacity=if(enabled) 1f else .42f
    Column(Modifier.fillMaxWidth().padding(top=13.dp,bottom=9.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
            WindowText(title,ink.copy(alpha=opacity),14,FontWeight.Medium,lineHeight=22,modifier=Modifier.weight(1f))
            WindowText(display,ink.copy(alpha=opacity),20,FontWeight.Medium,lineHeight=26)
        }
        Slider(value=value*range,onValueChange={change(it/range)},valueRange=0f..range,enabled=enabled,
            modifier=Modifier.fillMaxWidth().semantics {contentDescription=title})
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            WindowText(start,ink.copy(alpha=.45f*opacity),11,lineHeight=16)
            WindowText(end,ink.copy(alpha=.45f*opacity),11,lineHeight=16)
        }
    }
}

@Composable
internal fun WindowText(text: String,color: Color,size: Int,weight: FontWeight=FontWeight.Normal,
    lineHeight: Int=size+6,modifier: Modifier=Modifier) {
    BasicText(text,modifier,style=TextStyle(color=color,fontFamily=FontFamily.Default,fontSize=size.sp,
        lineHeight=lineHeight.sp,fontWeight=weight,letterSpacing=0.sp,fontFeatureSettings="tnum"))
}
