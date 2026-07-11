# Detect NSFW content from active-window screenshots

NSFW Screen Detection will capture temporary screenshots only while Guardian-selected Monitored Apps are active, run the CNN locally, and discard captured pixels without upload or persistence. Accessibility image nodes do not expose their underlying bitmaps, so node metadata may guide cropping but screenshot capture supplies the model input; capture cadence, cropping, thresholds, and alert payloads remain to be designed.
