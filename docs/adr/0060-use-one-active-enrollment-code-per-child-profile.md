# Use one active Enrollment Code per Child Profile

Guardian Mode will maintain at most one active unexpired Enrollment Code for a Child Profile. Creating or regenerating a code revokes any previous active code for that profile before issuing a new short-lived single-use code, reducing stale QR confusion and preventing multiple competing setup surfaces from binding the same Child Profile.
