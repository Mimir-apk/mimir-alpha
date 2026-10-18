package com.gruning.mimir.rewrite.feature.library

import androidx.compose.runtime.Stable

/**
 * The glass the Library's launch card wears behind a picker (OPEN WITH, the disc / version menu), as the two things the card's host needs to hand core's
 * `mimirGlassBackdropProgress`: how much of the blur shows ([progress], 0 to 1) and whether the card is to be held as a SNAPSHOT under it ([snapshot], * "one blur under pickers. The card behind freezes into a snapshot and only one blur renders live"). Both are read in the DRAW phase only.
 *
 * There is no Library copy of the glass draw (W2A #9): core's overload is the one freeze primitive, frozen at the glass's peak and thawed on the first clearing frame
 * (`mimirGlassSurfaceFrozen`), and Home calls it the same way. A change to how the glass is drawn (the sharp layer's fade, the freeze) reaches this card through it.
 */
@Stable
internal class LibraryCardGlass(val progress: () -> Float, val snapshot: () -> Boolean)

/**
 * Whether the card is to be held as a snapshot: its picker is up ([pickerOwnsInput]: the request, not the held glass, so a committed pick thaws the card at once and
 * what changes behind the clearing glass is seen), it has fully arrived ([arrived]: a card still fading in is not a still image) and nothing is leaving it
 * ([leaving]). Core freezes only while the glass is also at its peak.
 */
fun libraryCardSnapshotWanted(pickerOwnsInput: Boolean, arrived: Boolean, leaving: Boolean): Boolean =
    pickerOwnsInput && arrived && !leaving
