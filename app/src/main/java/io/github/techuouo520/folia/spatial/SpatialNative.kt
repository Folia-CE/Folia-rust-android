package io.github.techuouo520.folia.spatial

import java.nio.ByteBuffer

internal object SpatialNative {
    init { System.loadLibrary("folia_bridge") }
    const val DAILY_SPATIAL_FLOATS = DailySpatialProtocol.FLOATS
    private external fun nativeDailySpatialCreate(): Long
    fun dailySpatialCreate(): Long = nativeDailySpatialCreate()
    private external fun nativeDailySpatialRelease(id: Long): Unit
    fun dailySpatialRelease(id: Long): Unit = nativeDailySpatialRelease(id)
    private external fun nativeDailySpatialCommand(id: Long, op: Int, index: Int, a: Float, b: Float): Boolean
    fun dailySpatialCommand(id: Long, op: Int, index: Int, a: Float, b: Float): Boolean = nativeDailySpatialCommand(id, op, index, a, b)
    private external fun nativeDailySpatialHit(id: Long, x: Float, y: Float): Int
    fun dailySpatialHit(id: Long, x: Float, y: Float): Int = nativeDailySpatialHit(id, x, y)
    private external fun nativeDailySpatialStep(id: Long, dt: Float, w: Float, h: Float, output: ByteBuffer): Boolean
    fun dailySpatialStep(id: Long, dt: Float, w: Float, h: Float, output: ByteBuffer): Boolean = nativeDailySpatialStep(id, dt, w, h, output)
    private external fun nativeCoverAccent(pixels: IntArray): Int
    fun coverAccent(pixels: IntArray): Int = nativeCoverAccent(pixels)
    private external fun nativeCoverClusters(pixels: IntArray,width: Int,height: Int,output: IntArray): Boolean
    fun coverClusters(pixels: IntArray,width: Int,height: Int): IntArray? = IntArray(12).takeIf {nativeCoverClusters(pixels,width,height,it)}
    private external fun nativeLyricPreloadQuota(period: Long, elapsed: Long, cost: Long, remaining: Int): Int
    fun lyricPreloadQuota(period: Long, elapsed: Long, cost: Long, remaining: Int): Int = nativeLyricPreloadQuota(period, elapsed, cost, remaining)
    private external fun nativeDailyContextBlurKernel(radius: Float, density: Float, output: ByteBuffer): Boolean
    fun dailyContextBlurKernel(radius: Float, density: Float, output: ByteBuffer): Boolean = nativeDailyContextBlurKernel(radius, density, output)
    private external fun nativeDailyBlurRegions(height: Int, density: Float, top: Float, bottom: Float, radius: Float): Long
    fun dailyBlurRegions(height: Int, density: Float, top: Float, bottom: Float, radius: Float): Long = nativeDailyBlurRegions(height, density, top, bottom, radius)
    private external fun nativeDailyMeshLevel(span: Float, radius: Float, density: Float): Int
    fun dailyMeshLevel(span: Float, radius: Float, density: Float): Int = nativeDailyMeshLevel(span, radius, density)
    private external fun nativeDailySnapshotPixels(rgba: ByteBuffer, width: Int, height: Int, argb: IntArray): Boolean
    fun dailySnapshotPixels(rgba: ByteBuffer, width: Int, height: Int, argb: IntArray): Boolean = nativeDailySnapshotPixels(rgba, width, height, argb)
    private external fun nativeDailyRhineMesh(kind: Int, output: ByteBuffer): Int
    fun dailyRhineMesh(kind: Int, output: ByteBuffer): Int = nativeDailyRhineMesh(kind, output)
    private external fun nativeDailyRhineIndexedMesh(kind: Int, vertices: ByteBuffer, indices: ByteBuffer): Long
    fun dailyRhineIndexedMesh(kind: Int, vertices: ByteBuffer, indices: ByteBuffer): Long = nativeDailyRhineIndexedMesh(kind, vertices, indices)
    private external fun nativeDailyRhinePack(scene: ByteBuffer, bindings: ByteBuffer, output: ByteBuffer, foreground: Boolean): Int
    fun dailyRhinePack(scene: ByteBuffer, bindings: ByteBuffer, output: ByteBuffer, foreground: Boolean): Int = nativeDailyRhinePack(scene, bindings, output, foreground)
    private external fun nativeDailyRhinePackChanged(scene: ByteBuffer, bindings: ByteBuffer, output: ByteBuffer, previousCount: Int): Long
    fun dailyRhinePackChanged(scene: ByteBuffer, bindings: ByteBuffer, output: ByteBuffer, previousCount: Int): Long = nativeDailyRhinePackChanged(scene, bindings, output, previousCount)
    private external fun nativeDailyPackInstances(scene: ByteBuffer, bindings: ByteBuffer, output: ByteBuffer, density: Float): Long
    fun dailyPackInstances(scene: ByteBuffer, bindings: ByteBuffer, output: ByteBuffer, density: Float): Long = nativeDailyPackInstances(scene, bindings, output, density)
}
