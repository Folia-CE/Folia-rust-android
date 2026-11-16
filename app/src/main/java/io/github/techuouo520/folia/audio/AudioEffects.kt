package io.github.techuouo520.folia.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import androidx.compose.runtime.*
import io.github.techuouo520.folia.bridge.FoliaBridge
import kotlinx.coroutines.*

/** Process lifetime owner, independent of settings windows and media notification lifecycle. */
internal class AudioEffects(context: Context,private val bridge: FoliaBridge,val appearanceChanged: ()->Unit) {
    val settings=BassSettings(context)
    var status by mutableStateOf("等待播放")
        private set
    var speaker by mutableStateOf(false)
        private set
    private val manager=context.getSystemService(AudioManager::class.java)
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    @Volatile private var effect: HeadroomEffect?=null
    init {scope.launch {
        var token=0L;var device=0;var nextAttempt=0L;var attempts=0;var lastVerify=0L
        var previous: Triple<Boolean,Float,Pair<Float,Float>>?=null
        while(isActive) {
            try {
                val output=bridge.audioOutput()
                if(output.token!=token) {
                    bridge.setAudioHeadroomReady(token,false)
                    val old=effect;effect=null;old?.close();token=output.token;attempts=0;nextAttempt=0L;device=output.device
                }
                val now=SystemClock.uptimeMillis()
                if(output.device!=device){device=output.device;lastVerify=0L;nextAttempt=0L;attempts=0;bridge.setAudioHeadroomReady(token,false)}
                val devices=manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                // Use the actual AAudio output ID. Unknown routing never enables speaker bass.
                val route=devices.firstOrNull {it.id==output.device}
                speaker=route?.type==AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                val volume=manager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()/manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                val knobs=Triple(settings.value.enabled&&speaker&&Build.VERSION.SDK_INT>=28,settings.strength(volume),settings.cutoffs())
                if(knobs!=previous){bridge.setBassProcessing(knobs.first,knobs.second,knobs.third.first,knobs.third.second);previous=knobs}
                when {
                    token==0L -> status="等待播放"
                    output.error!=0 -> {bridge.setAudioHeadroomReady(token,false);status="音频输出中断（${output.error}）"}
                    Build.VERSION.SDK_INT<28 || output.session<=0 -> status="当前系统不支持 DynamicsProcessing，使用原始输出"
                    else -> {
                        if(effect==null&&now>=nextAttempt) {
                            try {
                                val created=HeadroomEffect.open(output.session);effect=created
                                created.effect.setControlStatusListener { _,control -> if(!control&&effect===created) bridge.setAudioHeadroomReady(token,false) }
                                created.effect.setEnableStatusListener { _,enabled -> if(!enabled&&effect===created) bridge.setAudioHeadroomReady(token,false) }
                                lastVerify=0L;attempts=0
                            } catch(error: Exception) {
                                attempts++;nextAttempt=now+(250L shl attempts.coerceAtMost(4));status=error.message?:"回补初始化失败"
                            }
                        }
                        val owned=effect
                        if(owned!=null&&(lastVerify==0L||now-lastVerify>=500L||!output.ready)) {
                            if(owned.verify()){bridge.setAudioHeadroomReady(token,true);status=if(speaker) "PCM −55 dB → 低频增压 → DP +55 dB" else "PCM −55 dB → DP +55 dB · 外接设备不启用低频增压"}
                            else{bridge.setAudioHeadroomReady(token,false);effect=null;owned.close();nextAttempt=now+500;status="正在重新绑定音频回补"}
                            lastVerify=now
                        }
                    }
                }
            } catch(error: Exception){status=error.message?:"音频线路异常";bridge.setAudioHeadroomReady(token,false)}
            delay(100)
        }
    }}
}
