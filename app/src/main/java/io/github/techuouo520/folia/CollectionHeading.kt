package io.github.techuouo520.folia

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.techuouo520.folia.spatial.SceneLabel
import io.github.techuouo520.folia.spatial.sceneInk
import kotlin.math.max
import kotlin.math.min

/** Album/playlist name, midway between the fixed account/settings circles in either scene. */
@Composable
internal fun CollectionHeading(title: String,summary: String,dark: Boolean,backdrop: FrostBackdrop) {
    val ink=sceneInk(dark)
    val context=LocalContext.current
    val density=LocalDensity.current
    val textWidth=remember(title,density.density,density.fontScale) {MarqueeTitleView.textWidth(context,title)}
    var summaryWidth by remember(summary) {mutableIntStateOf(0)}
    // Corners:10dp inset +50dp diameter. Equal74dp title insets leave14dp breathing room.
    // Both use safeDrawing top +4dp and a50dp slot, so their centreline is exactly shared.
    BoxWithConstraints(Modifier.fillMaxWidth()
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top+WindowInsetsSides.Horizontal))
        .padding(start=74.dp,end=74.dp,top=4.dp),contentAlignment=Alignment.TopCenter) {
        val haloWidth=min(maxWidth.value,max(textWidth,summaryWidth.toFloat())/density.density+32).dp
        // One feathered backdrop spans both lines; the text itself stays crisp.
        Box(Modifier.matchParentSize().padding(horizontal=(maxWidth-haloWidth)/2).headerBlur(backdrop))
        Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally) {
        AndroidView(factory={MarqueeTitleView(it)},update={view->
            if(view.text.toString()!=title) view.text=title
            view.setTextColor(ink.toArgb())
        },modifier=Modifier.fillMaxWidth().height(CORNER_BUTTON_DIAMETER.dp).semantics {heading()})
        Spacer(Modifier.height(4.dp))
        SceneLabel(summary,dark,modifier=Modifier.onSizeChanged {summaryWidth=it.width},muted=true)
        }
    }
}
