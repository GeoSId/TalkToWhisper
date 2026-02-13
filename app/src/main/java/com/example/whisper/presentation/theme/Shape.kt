package com.example.whisper.presentation.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// ============================================================================
// Talk to Whisper Shapes — Rounded corners for cards, buttons, sheets
// ============================================================================

val TalkToWhisperShapes = Shapes(

    // Subtle rounding for small elements: chips, badges, small buttons
    extraSmall = RoundedCornerShape(4.dp),

    // Standard rounding for text fields, list items
    small = RoundedCornerShape(8.dp),

    // Default rounding for cards, dialogs, and standard containers
    medium = RoundedCornerShape(12.dp),

    // Prominent rounding for FABs, large buttons, bottom sheets
    large = RoundedCornerShape(16.dp),

    // Pill / fully rounded for push-to-talk button, search bars
    extraLarge = RoundedCornerShape(28.dp)
)

// -- Additional named shapes for specific components -----------------------

/** Transcription card shape — slightly more rounded for visual warmth. */
val TranscriptionCardShape = RoundedCornerShape(20.dp)

/** Language selector pill shape. */
val LanguageSelectorShape = RoundedCornerShape(24.dp)

/** Push-to-talk button — fully circular via large corner radius. */
val PushToTalkShape = RoundedCornerShape(50)

/** Waveform visualizer container shape. */
val WaveformContainerShape = RoundedCornerShape(12.dp)
