# Store only hashed Enrollment Codes

Convex will store only a hash of each Enrollment Code, while Guardian Mode receives the raw code only at creation time for QR display. Although Enrollment Codes expire after five minutes, they are bearer bootstrap secrets during that window, so hashing limits the blast radius of database inspection, debug dumps, or accidental logging without materially complicating preview or completion lookup.
