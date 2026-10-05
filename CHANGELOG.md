# Changelog

## 3.43.3

### UI
- Improved the layout for small, normal, large, and extra-large display and font sizes.
- Fixed pressed card effects so they stay inside rounded corners.
- Updated the About dialog layout for different screen sizes.
- Made the GitHub card consistent between Light and Dark mode and renamed it to Star on GitHub.
- Kept Privacy Policy clickable without the underline.

### Build
## 3.43.2

### Fixes
- Calculator inputs and the open screen are now kept when you rotate the phone or switch apps.
- Reminder and widget settings are handled more reliably after restoring from a backup.
- Widget countdown refresh is lighter on battery.
- Cards in the About section are now easier to see in dark mode.

### Icon
- Added an adaptive app icon that matches your phone's icon shape and supports themed icons on Android 13 and newer.
- Reduced the app size by removing duplicate icon images.

## 3.43.1

### About
- Removed the Telegram link from About.
- Added a compact "Buy me a coffee" support option to the direct-distribution build.
- Added PayPal and Google Pay / UPI support using DayCalcy's theme-adaptive UI.
- Updated the Play Store GitHub description to "For more information, check GitHub."
- Kept the direct-distribution GitHub description as "For updates, check GitHub."

### Build
- Enabled R8 code shrinking and optimization for release builds.

### Distribution
- Added separate Play Store and direct-distribution build flavors.
- The Play Store flavor excludes the external payment UI and payment actions.

## 3.43.0

### Theme & System Bars
- Fixed status-bar icons to follow DayCalcy's selected Light, Dark, or System theme.
- Fixed navigation-bar icons to follow DayCalcy's selected Light, Dark, or System theme.
- Reworked Light/Dark theme switching to apply the selected system-bar style through AndroidX edge-to-edge handling, reducing the visible status-bar transition flash.
- Made the navigation bar transparent so the app background continues naturally behind it.
- Fixed the extra white/black area above the Android navigation bar in 3-button navigation mode.
- Fixed bottom content insets so the last Home screen card is not clipped by the navigation area.

## 3.42.9

### Duration Calculator
- Added a new Duration Calculator as the 6th main calculator, with Date Reminder remaining as the 7th.
- Calculates elapsed time between two dates and times, including overnight and multi-day durations.
- Added optional break-time deduction with validation.
- Duration calculations are saved to History and can be reopened.

### About
- Removed the “Thank you for using DayCalcy!” message and refined the About dialog spacing and Changelog row.

## 3.42.8

### Calendar
- Improved selected-date information with matching Reminders, Calculation History and Year Progress sections.
- Added separate reminder and history markers, including yearly reminder markers on the same month and day.
- Reminder and history items now open their existing screens without changing the calendar date selection behavior.

### Navigation
- Returning from a reminder or reopened calculation now returns to the screen it was opened from.

## 3.42.7

### New
- Added Date Calculation History for recent calculations.
- Added a Calendar view with reminder and history markers.
- Renamed the app to DayCalcy.

### Tools
- Added compact Calendar and History cards to the home screen.
- Added theme-aware Calendar and History icons for Light and Dark mode.

### Other
- Updated the proprietary license to allow use and sharing of the official unmodified app.
- Kept DayCalcy completely offline.

## 3.42.6

### App Icon
- Introduced the new Day Calculator calendar and clock icon.
- Updated the About section to use the same small app logo.
- Prepared the icon as a clean square source for launcher theming.

### Reminders
- Added Done and Snooze 10 min actions to reminder notifications.
- Snoozing keeps the saved reminder date unchanged.

### Results
- Added one Copy and Share action for each complete calculation result.

### About
- Refined the Changelog item with a small inline history icon and no arrow.

### Stability
- Continued date, reminder, widget and Year Progress checks.

## 3.42.5

### Reminder Notifications
- Improved reminder notification information.
- Added clearer Today, Tomorrow, and In X days wording.
- Added human-readable dates and weekdays.
- Improved expanded notification content.

### Smart Reminders
- Clear reminder titles can receive a more relevant notification style.
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
