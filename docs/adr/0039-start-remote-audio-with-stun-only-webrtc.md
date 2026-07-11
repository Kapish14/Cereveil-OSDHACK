# Start Remote Audio with STUN-only WebRTC

Cereveil's initial Remote Audio implementation will use WebRTC with STUN-only NAT traversal and no TURN relay provider. Convex remains responsible for Remote Audio authorization, session state, signaling, cooldown checks, and lifecycle cleanup, while WebRTC carries the live audio stream directly between devices when connectivity permits. This avoids adding a paid relay dependency during the hackathon phase, but accepts that Remote Audio may fail on restrictive networks until TURN relay support is added later.
