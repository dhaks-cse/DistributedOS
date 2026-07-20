

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Simulates node crashes and recovery. On failure, every process hosted by
 * the failed node is either migrated to a surviving UP node (if capacity
 * allows) or marked TERMINATED with its resources released (if no node can
 * absorb it). On recovery the node rejoins the pool empty and ready to
 * accept new/migrated work.
 */
public class FailureRecoveryManager {

    public void failNode(VirtualNode node, Map<Integer, VirtualNode> allNodes,
                          ResourceManager resourceManager, Consumer<String> logger) {
        if (node.getStatus() == VirtualNode.Status.DOWN) {
            logger.accept("Node " + node.getName() + " is already DOWN.");
            return;
        }
        node.setStatus(VirtualNode.Status.DOWN);
        logger.accept("!! Node " + node.getName() + " has FAILED. Beginning process recovery...");

        List<Process> stranded = new ArrayList<>(node.getProcessTable().values());
        node.getReadyQueue().clear();
        node.getProcessTable().clear();
        // Reset the failed node's memory bookkeeping since it's offline.
        node.getMemoryManager().reset();

        for (Process p : stranded) {
            p.getAllocatedPages().clear(); // memory was on the dead node; consider it lost
            boolean recovered = false;
            for (VirtualNode target : allNodes.values()) {
                if (target.getId() == node.getId() || target.getStatus() != VirtualNode.Status.UP) continue;
                if (target.admit(p)) {
                    p.incrementMigrations();
                    logger.accept("   -> Recovered PID " + p.getPid() + " (" + p.getName() + ") onto node " + target.getName());
                    recovered = true;
                    break;
                }
            }
            if (!recovered) {
                p.setState(Process.State.TERMINATED);
                resourceManager.releaseAll(p);
                logger.accept("   -> Could not recover PID " + p.getPid() + " (" + p.getName() + "); no capacity available, process lost.");
            }
        }
        logger.accept("Node " + node.getName() + " failure handling complete.");
    }

    public void recoverNode(VirtualNode node, Consumer<String> logger) {
        if (node.getStatus() == VirtualNode.Status.UP) {
            logger.accept("Node " + node.getName() + " is already UP.");
            return;
        }
        node.setStatus(VirtualNode.Status.UP);
        logger.accept("Node " + node.getName() + " has RECOVERED and rejoined the cluster.");
    }
}
