package io.github.techuouo520.folia

internal object MotionKernel {
    init { System.loadLibrary("folia_bridge") }

    external fun backDock(state: FloatArray, horizontal: Boolean, seconds: Float): Boolean
    external fun clearance(boxes: FloatArray, gap: Float, ceiling: Float)
    external fun bubble(state: FloatArray, open: Boolean, pressed: Boolean, seconds: Float,
        width: Float, dockHeight: Float, side: Float, resizeFrom: Float, wantedHeight: Float,
        blurStart: Float, blurCarry: Float): Boolean
    external fun albumPose(state: FloatArray, progress: Float, cx: Float, cy: Float, width: Float, height: Float)
    external fun scan(output: FloatArray, rows: Int, progress: Float): Int
    external fun travel(state: DoubleArray, target: Double, seconds: Double, frequency: Double, dock: Boolean): Boolean
}
