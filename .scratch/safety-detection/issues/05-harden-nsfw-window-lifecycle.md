Status: ready-for-agent

# Harden NSFW Protection Across Android Window Lifecycles

## Parent

[Active Screen Safety Detection](../PRD.md)

## What to build

Extend the working NSFW vertical slice to alternate Android window modes and content without usable Accessibility image nodes. Every capture and overlay must remain bound to the selected app's owned window: adjacent split-screen apps, picture-in-picture surroundings, System UI, bars, keyboards, and Cereveil overlays are outside the classifier input and blur surface. When no usable image node exists, classify only the monitored app window and blur only that window after a positive result.

Copy Revive's applicable cache, scroll, watchdog, and blur-hold defaults as configurable code constants, then complete the runtime stop and cleanup behavior for screen-off, device lock, window departure, content change, detector disablement, and End Supervision.

## Acceptance criteria

- [ ] Accessibility window ownership is established before every capture, crop, classification, and blur operation.
- [ ] Image-node crops and fallback window crops exclude adjacent apps, System UI, system bars, keyboards, and Cereveil-owned overlays.
- [ ] When no usable image node exists, only the visible monitored app window is classified and only its bounds are blurred after a positive result.
- [ ] Split-screen and picture-in-picture are supported on a best-effort basis without classifying or covering another application's window.
- [ ] Relevant-node caching, scrolling behavior, watchdog recovery, and blur-hold behavior preserve Revive's applicable defaults as app-owned runtime constants.
- [ ] NSFW capture stops entirely while the screen is off or the device is locked, when NSFW detection is disabled, and when no relevant monitored window remains visible.
- [ ] Blur persists while the same positive content remains visible and clears after safe replacement, window departure, detector disablement, lock, or End Supervision.
- [ ] A monitored window that cannot be inspected or captured degrades honestly and does not cause unrestricted full-screen capture or blur.
- [ ] Automated window-scoping tests cover normal windows, split-screen, picture-in-picture, system bars, keyboards, Cereveil overlays, image-node crops, fallback crops, scrolling, fast replacement, and every stop condition.
- [ ] Real-device acceptance exercises Instagram, Chrome, and at least one video/social surface across scrolling, rapid content changes, split-screen, picture-in-picture, fallback classification, lock, screen-off, and window departure.
- [ ] Real-device inspection confirms no warning, dialog, toast, banner, false-positive action, dismiss, or snooze accompanies NSFW blur in any window mode.

## Blocked by

- [04 – Detect NSFW Content and Blur a Normal App Window](04-detect-nsfw-content-and-blur-window.md)

