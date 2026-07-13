---
status: accepted
---

# Use Location Heartbeats and one-time Location Refresh Requests

While Location Sharing is enabled, Cereveil will store only the Child Device's latest known location and will not retain location history. Child Mode normally supplies low-power Location Heartbeats without queuing failed uploads while offline, while a Guardian may create a Location Refresh Request that uses high-priority FCM with an immediate Child-visible notification to wake Child Mode for one fresh high-accuracy measurement and expires after 60 seconds; success overwrites the same latest-location state, and failure leaves the prior point visibly stale. Convex permits at most one request every two minutes per Child Profile so repeated requests cannot recreate continuous tracking. After reconnecting, Child Mode measures again rather than uploading missed points. This replaces the Live Location Session design from ADR-0015, ADR-0016, and ADR-0048, reducing battery use, backend lifecycle complexity, and continuous-tracking exposure at the cost of not showing movement over time.
