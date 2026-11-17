package io.github.techuouo520.folia.spatial

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.opengl.GLES30
import android.opengl.GLUtils
import androidx.compose.ui.geometry.Offset
import io.github.techuouo520.folia.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Coordinates in the printed square, after the renderer's front/back artwork orientation. */
internal object AlbumLyricsChip {
    private const val RIGHT = .96f
    private const val TOP = .04f
    private const val WIDTH = .34f
    private const val HEIGHT = WIDTH * 30f / 66f
    private const val UNIT = 8 // Scene samplers use units 0..7.

    fun cell(frame: FloatArray): Int {
        val action = DailySpatialProtocol.ACTION
        return if (frame[action+DailySpatialProtocol.PLAYING_OPEN_PROGRESS] >= .98f &&
            frame[action+DailySpatialProtocol.PHASE] == 0f) frame[action+DailySpatialProtocol.PLAYING_CELL].toInt() else -1
    }

    fun configure(program: Int) {
        GLES30.glUseProgram(program)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "u_lyrics_mask"), UNIT)
        GLES30.glUniform4f(GLES30.glGetUniformLocation(program, "u_lyrics_rect"), RIGHT-WIDTH, TOP, WIDTH, HEIGHT)
    }

    /** Context-local, immutable mask shared by all wall and Rhine material passes. */
    fun upload(context: Context) {
        val bitmap = Bitmap.createBitmap(264, 120, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 52f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val label = "歌词"
        val icon = context.getDrawable(R.drawable.spatial_lyrics)!!
        val left = ((bitmap.width - 64f - 12f - paint.measureText(label)) * .5f).toInt()
        icon.setBounds(left, 28, left + 64, 92)
        icon.draw(canvas)
        canvas.drawText(label, left + 76f, 60f - (paint.ascent() + paint.descent()) * .5f, paint)
        val id = IntArray(1)
        GLES30.glGenTextures(1, id, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + UNIT)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id[0])
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        bitmap.recycle()
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    }

    private fun project(f: FloatArray, slot: Int, u: Float, v: Float): Offset {
        if (f[DailySpatialProtocol.RHINE] > .5f) {
            val m = DailySpatialProtocol.RHINE_POSES + slot * 16
            return albumPoint(f, slot, if (f[m+10] > 0f) 1f-u else u, 1f-v)
        }
        val o = DailySpatialProtocol.HEADER + slot * DailySpatialProtocol.STRIDE
        val w = 54f + (f[o+3]-54f)*f[1]
        val h = 54f + (f[o+4]-54f)*f[1]
        val side = minOf(w,h)
        return albumPoint(f, slot, u*side/w, v*side/h)
    }

    // The outline is evaluated only for a tap on the already depth-picked playing cell.
    fun contains(f: FloatArray, slot: Int, x: Float, y: Float): Boolean {
        val r = HEIGHT / 2
        var inside = false
        var previous = project(f, slot, RIGHT-r, TOP)
        for (i in 1..26) {
            val right = i <= 12
            val angle = if (right) -PI/2 + PI*i/12 else PI/2 + PI*(i-13)/12
            val center = if (right) RIGHT-r else RIGHT-WIDTH+r
            val next = if (i == 26) project(f, slot, RIGHT-r, TOP)
                else project(f, slot, center + r*cos(angle).toFloat(), TOP+r + r*sin(angle).toFloat())
            if ((next.y > y) != (previous.y > y) &&
                x < (previous.x-next.x)*(y-next.y)/(previous.y-next.y)+next.x) inside = !inside
            previous = next
        }
        return inside
    }
}
