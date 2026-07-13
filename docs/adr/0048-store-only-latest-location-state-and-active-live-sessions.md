---
status: superseded by ADR-0080
---

# Store only latest location state and active live sessions

Cereveil will store only the latest Location Heartbeat for each active Child Device and transient Live Location Session state. The backend will not retain location history or route timelines, and live location points are not retained after the session ends. This limits historical visibility, but keeps location storage aligned with the product's current-safety purpose rather than creating a long-term movement record.
