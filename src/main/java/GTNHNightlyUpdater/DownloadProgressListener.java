package GTNHNightlyUpdater;

/**
 * Callback for {@link StableUpdater} download progress, so a GUI can show a real progress bar
 * instead of an indeterminate spinner (useful for people on slow connections, who'd otherwise
 * think a large download has stalled).
 */
public interface DownloadProgressListener {

    DownloadProgressListener NO_OP = new DownloadProgressListener() {
        @Override
        public void onProgress(String label, long bytesRead, long totalBytes) {
        }
    };

    /**
     * @param label       human-readable name of what's being downloaded (e.g. "Client-Pack")
     * @param bytesRead   bytes downloaded so far
     * @param totalBytes  total size in bytes, or {@code -1} if unknown (no Content-Length header)
     */
    void onProgress(String label, long bytesRead, long totalBytes);

    /**
     * Fired for phases that have no meaningful byte-progress (cache hits, extraction, applying
     * the update to an instance), so a GUI can show e.g. "download done, now extracting…" instead
     * of leaving the last download percentage on screen looking stuck.
     *
     * @param phase human-readable description of what's happening now
     */
    default void onPhase(String phase) {
    }
}
