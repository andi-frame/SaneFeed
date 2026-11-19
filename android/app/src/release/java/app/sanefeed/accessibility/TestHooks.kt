package app.sanefeed.accessibility

import android.accessibilityservice.AccessibilityService

/** Release builds carry no test hooks; the debug source set drives gestures from `adb shell`. */
internal object TestHooks {
    fun attach(service: AccessibilityService) = Unit

    fun detach(service: AccessibilityService) = Unit
}
