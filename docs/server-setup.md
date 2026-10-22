# Hosting and troubleshooting

## Media directory

The server creates `cinematic/media/` relative to its game/working directory. In singleplayer this is the game installation directory, shared by its worlds. For Gradle development runs the directory is `run/cinematic/media/`.

Copy ordinary, completed media files into this directory, then run `/cinematic reload` with game-master/operator permission. Scanning and FFmpeg probing run off the server thread. The library UI reports scanning, empty, and invalid-file states; the server log identifies files that fail probing. A second reload while scanning reports that a scan is already running.

Paths are never sent to clients. The HTTP service only serves indexed files by opaque IDs, rejects symbolic links and traversal paths, and checks size and modification time again before opening a file. Replacing, renaming, or modifying a file changes its ID; select the updated file again after reloading. Do not modify files during playback. The directory is administrator-managed, not an upload area.

## Dedicated network setup

By default the media server binds `0.0.0.0:25566`. Permit inbound TCP 25566 through the server firewall and forward it through NAT separately from the Minecraft port. With `advertisedUrl = ""`, clients derive the media hostname from their Minecraft connection. Set an explicit advertised URL when using a Minecraft SRV record, relay, hosting proxy, IPv6-only host, or different media hostname/port.

For a reverse proxy, bind the embedded service to `127.0.0.1`, keep port 25566, and advertise an HTTPS URL accessible to clients. Forward `/media/` unchanged, including `Authorization` and `Range` request headers; preserve `Content-Length`, `Content-Range`, and `Accept-Ranges` responses. Disable response caching and authentication-header logging. TLS terminates at the proxy; the embedded server speaks HTTP. The advertised URL may include a proxy path prefix if the proxy strips it before forwarding.

Access uses random bearer credentials carried in the Authorization header. They expire after ten minutes; clients renew early. Up to two leases per player overlap, and logout revokes that player's credentials. Renewal reopens active decoders at the server timeline. HTTPS protects the media leg when it crosses an untrusted network; the default direct HTTP endpoint does not encrypt it.

The service implements authenticated GET and HEAD, single byte ranges, suffix/open-ended ranges, and meaningful 401, 404, 405, 410, 416, and 503 responses. Multipart ranges are not supported. Transfers and HTTP workers are bounded.

## Recovery

| Symptom | Action |
| --- | --- |
| Media service unavailable | Read the server log; check `config/cinematic-local.toml`, the bind address, and whether another process occupies the port. Restart after correcting it. Minecraft remains running. |
| JDK HTTP module unavailable | Add `--add-modules=jdk.httpserver` to `user_jvm_args.txt` on the dedicated server or the launcher's JVM arguments on the client, then restart. |
| Library is empty or reports invalid files | Confirm the working directory, extensions, permissions, and codecs; inspect the scan log and run `/cinematic reload`. |
| Selected media missing | Reload after copying/replacing media, then choose the file again. |
| Media timed out / opening or seeking never finishes | Check the advertised URL and media port from the client. The status button identifies opening, seeking, or first-frame buffering; a stalled attempt times out after 30 seconds. Click it to retry. |
| Preview appears but timeline does not advance | Newly selected media starts paused. Hold a Remote and press Play. |
| Connecting or playback failed | Confirm the advertised host/port is reachable from the viewing client. A reverse proxy must forward Authorization and byte ranges. Click the playback status button or reopen the controller to retry. |
| Native loader failure | Confirm the OS/architecture is included, the JDK is 25, and the JavaCPP cache is writable. On Linux use a compatible glibc environment. Do not install a separate player as a workaround; test the embedded JARs with `scripts/test-packaged.py`. |
| Buffering or repeated resync | Check bandwidth, file seekability, and CPU load. Try a conventional 1080p/30 MP4 and fewer nearby screens. HTTP errors and decoder exceptions do not stop Minecraft. |
| No sound | Check personal mute/volume, Master and Jukebox/Note Blocks volume, distance from the panel center, and whether the file has an audio track. |
| Controls disabled or rejected | Hold a Remote in either hand within 16 blocks in the same dimension. Wait for the latest snapshot if another player just changed the screen. |
| Large panel not visible | It is one vertical surface, facing the direction selected at placement. The rest of the panel has no collision blocks. Runtime visibility still needs testing on 26.3. |
| Sessions cannot be restored | Stop the server, back up `<world>/data/cinematic-sessions.dat`, and inspect/restore it. A malformed file disables further session saves to avoid overwriting it. Removing it deliberately resets all saved screen state. |

Access tokens are never put in URLs or user-facing playback errors. Native FFmpeg can log its own diagnostics; avoid publishing unreviewed raw logs. Session data is separate from controller chunk NBT: include `data/cinematic-sessions.dat` in world backups. Preferences are client-local and do not affect other viewers.
