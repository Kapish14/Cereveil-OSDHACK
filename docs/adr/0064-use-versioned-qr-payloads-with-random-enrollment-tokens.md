# Use versioned QR payloads with random Enrollment tokens

Enrollment QR codes will contain a small versioned JSON payload with a Cereveil enrollment type marker and a 128-bit random base64url Enrollment Code. The token is opaque, QR-only, and unpadded, while the payload version lets Child Mode reject unrelated QR codes cleanly and gives the enrollment protocol room to evolve without relying on ambiguous raw string formats.
