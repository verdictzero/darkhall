package com.verdictzero.darkhall.app.ui

import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.verdictzero.darkhall.core.develop.Adjustment
import com.verdictzero.darkhall.core.develop.MixBand

/** True when any of the colour mixer's sliders is off its default. */
fun DevelopState.mixerUsed(): Boolean =
    MixBand.entries.any { band -> Adjustment.mixer(band).any { !params.isDefault(it, defaults) } }

/**
 * The colour mixer: a row of the eight hue bands (a dot under each band that has been
 * changed), then the hue, saturation and luminance sliders of the band picked.
 */
@Composable
fun MixerControls(state: DevelopState, vm: DevelopViewModel) {
    var picked by rememberSaveable { mutableStateOf(MixBand.RED) }
    val strokes = LocalStrokes.current
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (band in MixBand.entries) {
                val selected = band == picked
                val name = band.title()
                val changed = Adjustment.mixer(band).any { !state.params.isDefault(it, state.defaults) }
                // The whole column width and 48 dp of height take the tap, not just the swatch.
                Column(
                    Modifier
                        .weight(1f)
                        .semantics(mergeDescendants = true) { contentDescription = name; this.selected = selected }
                        .testTag("mix-${band.name}")
                        .clickable(role = Role.Tab) { picked = band },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.height(40.dp), contentAlignment = Alignment.Center) {
                        Box(
                            Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .border(if (selected) strokes.selectedWidth else strokes.width, if (selected) strokes.selected else strokes.container, CircleShape)
                                .padding(if (selected) 5.dp else 4.dp)
                                .clip(CircleShape)
                                .background(Color(band.swatch)),
                        )
                    }
                    Box(
                        Modifier
                            .padding(top = 3.dp)
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(if (changed) strokes.selected else Color.Transparent),
                    )
                }
            }
        }
        for (adj in Adjustment.mixer(picked)) {
            SliderRow(adj, state.params, state.defaults, onChange = { vm.set(adj, it) }, onReset = { vm.resetOne(adj) })
        }
    }
}
