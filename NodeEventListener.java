

/**
 * Callback used by a VirtualNode's scheduler thread to notify the central
 * controller of significant lifecycle events, without the node package
 * needing to depend on the system package.
 */
public interface NodeEventListener {
    void onProcessCompleted(Process process, VirtualNode node);
    void log(String message);
}
