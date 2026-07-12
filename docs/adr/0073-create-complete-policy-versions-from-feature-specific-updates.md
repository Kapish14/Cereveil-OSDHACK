# Create complete policy versions from feature-specific updates

Guardian Mode will change supervision through feature-specific operations rather than submitting the entire Supervision Policy from every feature screen. Convex will load the current complete policy, validate and replace the requested typed feature section, and store the result as a new complete immutable policy version; this lets feature interfaces evolve independently while preventing an unrelated or stale client-side section from being overwritten as a side effect of one feature change.
