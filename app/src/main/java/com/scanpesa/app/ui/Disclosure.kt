package com.scanpesa.app.ui

/** Prominent disclosure for the AccessibilityService (Google Play policy). Also copied into the README. */
object Disclosure {
    const val TITLE = "How ScanPesa uses Accessibility"

    val POINTS = listOf(
        "WHAT IT DOES: When you tap Go, ScanPesa opens SIM Toolkit, taps the M-PESA menu items for you and types in only the phone, paybill, account or till number you just confirmed. A small bar at the top shows each step and has a Stop button. If a field refuses typing, ScanPesa pastes that same number instead (it goes on your clipboard).",
        "WHERE IT STOPS: It stops as soon as the amount screen appears. You always type the amount and your PIN yourself.",
        "WHAT IT NEVER DOES: It never reads, stores or types your PIN or any password field. It never types an amount. It never presses Send, Pay or Confirm.",
        "WHICH APPS: It only works inside SIM Toolkit, and only for about 40 seconds after you tap Go. Then it stops and tells you which step it reached.",
        "YOUR DATA: It reads the on-screen labels of those apps only to find the right buttons and fields. Nothing is collected, stored or sent anywhere, and ScanPesa has no internet access to its own servers. Debug mode (off by default) shows those labels on your own phone so you can fix menu wording.",
        "YOUR CHOICE: You can switch it off at any time in Settings › Accessibility. ScanPesa still scans without it, and you then type the numbers yourself.",
    )
}
