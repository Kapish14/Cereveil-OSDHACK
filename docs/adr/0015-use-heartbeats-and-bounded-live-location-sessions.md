# Use heartbeats and bounded live location sessions

Child Mode will normally send low-power Location Heartbeats approximately every 15 minutes or after significant movement. When requested, it will enter a Live Location Session with high-accuracy updates every 5–10 seconds for at most 10 minutes, then return to heartbeat behavior; Guardian Mode always shows freshness and service status because delivery is best-effort and continuous high-accuracy GPS would impose excessive battery cost.
