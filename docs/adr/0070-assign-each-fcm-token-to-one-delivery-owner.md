# Assign each FCM token to one delivery owner

Within an environment, an active FCM token belongs to exactly one Guardian Device or Child Device delivery owner. Registration atomically invalidates any previous binding for the same token hash before activating the authenticated device's binding; it never transfers or changes device identity. This accommodates token reuse after restore or reinstallation while preventing a stale owner from continuing to address pushes to a reassigned token.
