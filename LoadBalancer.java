

import java.util.Map;
import java.util.function.Consumer;

/**
 * Periodically inspects load (ready-queue length + running process) across
 * all UP nodes and migrates a waiting process from the busiest node to the
 * least busy node when the imbalance exceeds a threshold.
 */
public class LoadBalancer {

    private static final int IMBALANCE_THRESHOLD = 2;

    /** Runs one balancing pass. Returns a human-readable outcome message, or null if nothing was done. */
    public String balanceOnce(Map<Integer, VirtualNode> nodes, Consumer<String> logger) {
        VirtualNode busiest = null, idlest = null;

        for (VirtualNode n : nodes.values()) {
            if (n.getStatus() != VirtualNode.Status.UP) continue;
            if (busiest == null || n.getLoad() > busiest.getLoad()) busiest = n;
            if (idlest == null || n.getLoad() < idlest.getLoad()) idlest = n;
        }

        if (busiest == null || idlest == null || busiest.getId() == idlest.getId()) return null;
        if (busiest.getLoad() - idlest.getLoad() < IMBALANCE_THRESHOLD) return null;

        Process victim = busiest.getReadyQueue().poll();
        if (victim == null) return null; // nothing waiting to move (only the running one is on this node)

        busiest.getProcessTable().remove(victim.getPid());
        busiest.getMemoryManager().free(victim.getAllocatedPages());
        victim.getAllocatedPages().clear();

        boolean admitted = idlest.admit(victim);
        if (!admitted) {
            // put it back where it came from, no capacity on target
            busiest.admit(victim);
            String msg = "Load balancer: wanted to migrate PID " + victim.getPid() + " to node " + idlest.getName()
                    + " but insufficient memory there; kept on " + busiest.getName();
            if (logger != null) logger.accept(msg);
            return msg;
        }

        victim.incrementMigrations();
        String msg = "Load balancer: migrated PID " + victim.getPid() + " (" + victim.getName() + ") from node "
                + busiest.getName() + " (load " + (busiest.getLoad() + 1) + ") to node " + idlest.getName()
                + " (load " + (idlest.getLoad() - 1) + ")";
        if (logger != null) logger.accept(msg);
        return msg;
    }
}
