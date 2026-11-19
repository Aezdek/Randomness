package xyz.aezdek.daybook.ui.theme

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch

/**
 * Press feedback for list rows: the row tints grey while held, like a UITableView cell, instead of a
 * ripple. It shows the instant the finger lands and clears on release.
 */
object HighlightIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = HighlightNode(interactionSource)
    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = 7
}

private class HighlightNode(private val interactionSource: InteractionSource) :
    Modifier.Node(), DrawModifierNode, CompositionLocalConsumerModifierNode {

    private var pressed = false

    override fun onAttach() {
        coroutineScope.launch {
            var presses = 0
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> presses++
                    is PressInteraction.Release, is PressInteraction.Cancel -> presses = (presses - 1).coerceAtLeast(0)
                }
                val now = presses > 0
                if (now != pressed) {
                    pressed = now
                    invalidateDraw()
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (pressed) drawRect(currentValueOf(LocalAppleColors).fill)
    }
}
