package io.github.techuouo520.folia.audio

import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.abs

/** Kotlin port of SalamPlayer's verified session DP + direct AIDL/legacy compatibility path. */
internal class HeadroomEffect private constructor(val effect: AudioEffect,private val direct: Boolean) : AutoCloseable {
    private fun ints(vararg values: Int)=ByteBuffer.allocate(values.size*4).order(ByteOrder.nativeOrder()).apply { values.forEach {putInt(it)} }.array()
    private fun set(key: ByteArray,value: ByteArray) {
        check(HiddenApiBypass.invoke(AudioEffect::class.java,effect,"setParameter",key,value)==AudioEffect.SUCCESS)
    }
    private fun get(key: ByteArray): ByteBuffer {
        val out=ByteArray(4)
        check((HiddenApiBypass.invoke(AudioEffect::class.java,effect,"getParameter",key,out) as Int)>=4)
        return ByteBuffer.wrap(out).order(ByteOrder.nativeOrder())
    }
    private fun channels()=if(direct) get(ints(0x10)).int else (effect as DynamicsProcessing).channelCount
    fun verify(): Boolean=runCatching {
        val count=channels();check(count in 1..32 && effect.hasControl() && effect.enabled)
        repeat(count) { channel ->
            val actual=if(direct) get(ints(0x20,channel)).float else (effect as DynamicsProcessing).getInputGainByChannelIndex(channel)
            check(actual.isFinite() && abs(actual-55f)<.01f)
        };true
    }.getOrDefault(false)
    private fun apply() {
        val count=channels();check(count in 1..32)
        repeat(count) { channel ->
            if(direct) set(ints(0x20,channel),ByteBuffer.allocate(4).order(ByteOrder.nativeOrder()).putFloat(55f).array())
            else (effect as DynamicsProcessing).setInputGainbyChannel(channel,55f)
        }
        check(effect.setEnabled(true)==AudioEffect.SUCCESS)
        check(verify()) {"DynamicsProcessing gain/control verification failed"}
    }
    override fun close() {runCatching {effect.setEnabled(false)};effect.release()}
    companion object {
        @Volatile private var publicRejected=false
        fun open(session: Int): HeadroomEffect {
            require(session>0)
            var public: DynamicsProcessing?=null
            try {
                check(!publicRejected)
                val config=DynamicsProcessing.Config.Builder(0,2,false,0,false,0,false,0,false)
                    .setPreferredFrameDuration(10f).apply {repeat(2){setInputGainByChannelIndex(it,55f)}}.build()
                public=DynamicsProcessing(0,session,config)
                return HeadroomEffect(public,false).also {it.apply()}
            } catch(_: Exception) {publicRejected=true;runCatching {public?.release()}}
            val type=AudioEffect.EFFECT_TYPE_DYNAMICS_PROCESSING
            val implementations=linkedSetOf<UUID>()
            runCatching {AudioEffect.queryEffects()?.filter {it.type==type}?.forEach {implementations.add(it.uuid)}}
            implementations.add(UUID.fromString("fa818d78-588b-11ed-9b6a-0242ac120002"))
            implementations.add(UUID.fromString("e0e6539b-1781-7261-676f-6d7573696340"))
            var failure: Throwable?=null
            for(uuid in implementations) {
                var raw: AudioEffect?=null
                try {
                    raw=HiddenApiBypass.newInstance(AudioEffect::class.java,type,uuid,0,session) as AudioEffect
                    val candidate=HeadroomEffect(raw,true)
                    // Disabled stages: canonical zero bands, then the vendor dormant-band variant.
                    val accepted=(0..1).any { bands -> runCatching {
                        val architecture=ByteBuffer.allocate(36).order(ByteOrder.nativeOrder())
                            .putInt(0).putFloat(10f).putInt(0).putInt(bands).putInt(0).putInt(bands).putInt(0).putInt(bands).putInt(0).array()
                        candidate.set(candidate.ints(0x30),architecture)
                    }.isSuccess }
                    check(accepted);candidate.apply();raw=null;return candidate
                } catch(error: Throwable) {failure=error}
                finally {runCatching {raw?.release()}}
            }
            throw IllegalStateException("DynamicsProcessing 无法建立 +55 dB 回补",failure)
        }
    }
}
