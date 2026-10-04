# Changelog

## 3.43.2

### Fixes
- Calculator inputs and screens now survive screen rotation and process death.
- Widget refresh no longer wakes the device every minute; the Play build uses an inexact alarm.
- Added an adaptive launcher icon (with Android 13+ themed icon support) and legacy icons for older Android versions.
- Reduced app size by removing duplicate 1024px icon images.
- Backup now excludes widget settings and reschedules reminders automatically after a restore.
- Cards in the About section are now easier to see in dark mode.

## 3.43.1

### Build
- Enabled R8 code shrinking, optimization, obfuscation, and resource shrinking for release builds, with required keep rules configured for runtime compatibility.

### About
- Removed the Telegram link from About.
- Added a compact “Buy me a coffee” support option to the direct-distribution build.
- Added PayPal and Google Pay / UPI support with DayCalcy's theme-adaptive UI.
- The Play Store build does not include the external payment UI.
- Updated the Play Store GitHub description to “For more information, check GitHub.”
- Kept the direct-distribution GitHub description as “For updates, check GitHub.”

### Distribution
- Added separate Play Store and direct-distribution build flavors.
- Kept the existing widgets in both flavors.

## 3.43.0

### Navigation Bar
- Made the Android navigation area transparent/edge-to-edge so the app background continues behind the 3-button navigation controls.
- Removed the extra bottom inset that kept content above the transparent navigation area.

### Theme Switching
- Fixed the brief top-screen flash when switching between Light, Dark, and System Default themes.
- Kept the app-bar background stable during theme changes instead of allowing the Material 3 top-app-bar color transition to flash.
- System-bar icon appearance continues to follow the selected DayCalcy theme.
## 3.42.9

### Duration Calculator
- Added a new Duration Calculator as the 7th main calculator.
- Calculates elapsed time between two dates and times, including overnight and multi-day durations.
- Added optional break-time deduction with validation.
- Duration calculations are saved to History and can be reopened.

### About
- Removed the “Thank you for using DayCalcy!” message and tightened the resulting spacing.

## 3.42.8

### Calendar
- Improved selected-date information with matching Reminders, Calculation History and Year Progress sections.
- Added separate reminder and history markers, with yearly reminders using a distinct purple marker on the same month and day.
- Reminder and history items now open their existing screens without changing calendar date selection behavior.

### Navigation
- Returning from a reminder or reopened calculation now returns to the screen it was opened from.

### Home
- Adjusted Tools spacing to adapt to different phone screen sizes while keeping the main Home screen clean without excessive empty space.
- Kept the “Works offline • Simple and private” message with the main Home content above Tools.
- Improved Calendar-to-Reminder navigation to scroll to the exact selected reminder and briefly highlight it with a subtle theme-aware accent.

## 3.42.7

### New
- Added Date Calculation History for recent calculations.
- Added a Calendar view with reminder and history markers.
- Calendar reminder details are view-only; tapping a reminder date does not open the reminder editor.
- Tapping a history entry from Calendar opens that calculation directly.
- History is grouped into Today, Yesterday, and Earlier.
- History IDs are collision-safe.
- Calendar month navigation no longer auto-selects the same day in the new month.
- Added a direct month and year selector from the Calendar header.
- Renamed the app to DayCalcy.

### Other
- Added compact Calendar and History tools to the home screen.
- Added theme-aware Calendar and History icons for Light and Dark mode.
- Updated the proprietary license to allow use and sharing of the official unmodified app.
- Kept the app completely offline.


## 3.42.6

### Launch Screen
- Added a simple Day Calculator launch screen using the new square logo.
- Uses the same blue as the logo background in both Light and Dark mode.

### App Icon
- Added the new Day Calculator calendar and clock icon.
- Added the same logo to the About section with medium-rounded corners.
- Prepared the icon as a clean square source for launcher theming.

### Reminders
- Added **Done** and **Snooze 10 min** actions to reminder notifications.
- Snoozing reschedules the same reminder without changing its saved date.
- Marking a reminder as done removes it and cancels its notification.

### Results
- Added one Copy and Share action for each complete calculation result.

### About
- Refined the Changelog item with a small inline history icon.
- Removed the Changelog arrow.
- Kept the Changelog row fully tappable.

### Stability
- Rechecked date calculations, leap-year handling, Year Progress, reminders and widgets.
- Kept Day Calculator completely offline.

## 3.42.5

### Reminder Notifications

- Improved reminder notification information.
- Added clearer Today, Tomorrow, and In X days wording.
- Added human-readable dates and weekdays.
- Improved expanded notification content.
- Added clearer yearly reminder information.

### Smart Reminders

- Added simple title-based notification presentation for common reminders.
- Unrecognized reminders keep the standard notification.

### Year Progress

- Added special year-end and New Year notifications.
- Added positive messages that vary by year.

### Reminders

- Deleting a reminder now also cancels its posted notification.
- Improved reminder scheduling and cancellation reliability.

### About

- Changed the About title to Day Calculator.
- Removed the Created by Vivek text.
- Added an in-app Changelog.

## 3.42.4

### Year Progress

- Refined Year Progress widgets.
- Improved the 2×2 widget.
- Simplified the 4×2 widget.

### Widgets

- Fixed widget backgrounds to follow Day Calculator's selected Light/Dark theme.

### Reminders

- Improved alarm request ID handling to avoid collisions.
- Improved fallback handling when Exact Alarm access is unavailable.
- Cleaned up migration handling for older reminder alarms.

### Other

- Refreshed the About section with Telegram and GitHub links.
- GitHub link now opens the Day Calculator Releases page.
- Continued stability and correctness improvements.

### UI
- Reminder navigation now uses a soft, theme-aware highlight that follows the reminder dot color.
- Reduced adaptive spacing before Tools so the Home screen stays clean without excessive empty space.
�