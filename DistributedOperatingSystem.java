import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Central controller that wires nodes, memory, resources, scheduling,
 * load balancing, deadlock detection, and failure recovery into one
 * coherent distributed operating system simulation.
 *
 * All user-facing mutating operations are guarded by Validator before
 * any state is changed, so invalid input can never corrupt the simulation.
 */
public class DistributedOperatingSystem implements NodeEventListener {

    private final Map<Integer, VirtualNode> nodes         = new ConcurrentHashMap<>();
    private final Map<Integer, Process>     allProcesses  = new ConcurrentHashMap<>();

    private final AtomicInteger nodeIdGen = new AtomicInteger(1);
    private final AtomicInteger pidGen    = new AtomicInteger(1);

    private final ResourceManager        resourceManager        = new ResourceManager();
    private final DeadlockDetector       deadlockDetector       = new DeadlockDetector(resourceManager);
    private final LoadBalancer           loadBalancer           = new LoadBalancer();
    private final FailureRecoveryManager failureRecoveryManager = new FailureRecoveryManager();

    private final ScheduledExecutorService backgroundExecutor = Executors.newScheduledThreadPool(2);
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss");
    private volatile boolean backgroundServicesRunning = false;
    private volatile boolean autoResolveDeadlocks      = true;
    private java.util.concurrent.ScheduledFuture<?> loadBalancerFuture;
    private java.util.concurrent.ScheduledFuture<?> deadlockFuture;

    // ── logging ──────────────────────────────────────────────────────────
    @Override
    public synchronized void log(String message) {
        System.out.println("[" + timeFormat.format(new Date()) + "] " + message);
    }

    @Override
    public void onProcessCompleted(Process process, VirtualNode node) {
        resourceManager.releaseAll(process);
        allProcesses.remove(process.getPid());
    }

    // ── nodes ─────────────────────────────────────────────────────────────
    /**
     * Validates then creates a new virtual node.
     * Validator checks: name format, duplicate names, core/memory ranges.
     */
    public VirtualNode addNode(String name, int cpuCores, int memoryMB) {
        Validator.validateNode(name.trim(), cpuCores, memoryMB, nodes);   // ← VALIDATION
        int id   = nodeIdGen.getAndIncrement();
        VirtualNode node = new VirtualNode(id, name.trim(), cpuCores, memoryMB, this);
        nodes.put(id, node);
        node.start();
        log("Node added: [" + id + "] " + name.trim()
                + " (cores=" + cpuCores + ", memory=" + memoryMB + "MB)");
        return node;
    }

    public Map<Integer, VirtualNode> getNodes()  { return nodes; }
    public VirtualNode               getNode(int id) { return nodes.get(id); }

    // ── processes ─────────────────────────────────────────────────────────
    /**
     * Validates then creates a new process and admits it to the
     * least-loaded UP node.
     * Validator checks: name format, priority/burstTime/memory ranges,
     * at least one UP node exists.
     */
    public Process createProcess(String name, int priority, int burstTime, int memoryMB) {
        Validator.validateProcess(name.trim(), priority, burstTime, memoryMB, nodes); // ← VALIDATION

        VirtualNode target = pickLeastLoadedUpNode();
        int pid = pidGen.getAndIncrement();
        Process p = new Process(pid, name.trim(), priority, burstTime, memoryMB, target.getId());

        if (!target.admit(p)) {
            boolean admitted = false;
            for (VirtualNode n : nodes.values()) {
                if (n.getStatus() == VirtualNode.Status.UP
                        && n.getId() != target.getId() && n.admit(p)) {
                    target   = n;
                    admitted = true;
                    break;
                }
            }
            if (!admitted) {
                log("Cannot create process " + name + ": insufficient memory on all nodes.");
                return null;
            }
        }
        allProcesses.put(pid, p);
        log("Process created: PID=" + pid + " (" + name.trim()
                + ") assigned to node " + target.getName());
        return p;
    }

    /**
     * Validates PID then terminates the process and frees its resources.
     * Validator checks: PID exists, process not already terminated.
     */
    public boolean killProcess(int pid) {
        Validator.validateProcessExists(pid, allProcesses);  // ← VALIDATION
        Process p    = allProcesses.get(pid);
        VirtualNode node = nodes.get(p.getNodeId());
        if (node != null) node.evict(p);
        resourceManager.releaseAll(p);
        p.setState(Process.State.TERMINATED);
        allProcesses.remove(pid);
        log("Process killed: PID=" + pid + " (" + p.getName() + ")");
        return true;
    }

    /**
     * Validates then migrates a process to the specified node.
     * Validator checks: PID exists, target node exists and is UP,
     * process not already on the target.
     */
    public boolean migrateProcess(int pid, int targetNodeId) {
        Validator.validateMigration(pid, targetNodeId, allProcesses, nodes); // ← VALIDATION

        Process     p      = allProcesses.get(pid);
        VirtualNode target = nodes.get(targetNodeId);
        VirtualNode source = nodes.get(p.getNodeId());

        if (source != null) source.evict(p);
        p.getAllocatedPages().clear();

        if (!target.admit(p)) {
            if (source != null) source.admit(p);
            log("Migration failed for PID " + pid
                    + ": insufficient memory on node " + target.getName());
            return false;
        }
        p.incrementMigrations();
        log("Process PID=" + pid + " migrated to node " + target.getName());
        return true;
    }

    public Map<Integer, Process> getAllProcesses() { return allProcesses; }

    private VirtualNode pickLeastLoadedUpNode() {
        VirtualNode best = null;
        for (VirtualNode n : nodes.values()) {
            if (n.getStatus() != VirtualNode.Status.UP) continue;
            if (best == null || n.getLoad() < best.getLoad()) best = n;
        }
        return best;
    }

    // ── resources ─────────────────────────────────────────────────────────
    /**
     * Validates then registers a new shared resource.
     * Validator checks: name format, no duplicates, instance count range.
     */
    public void addResource(String name, int instances) {
        Validator.validateResource(name.trim(), instances,         // ← VALIDATION
                resourceManager.getResources());
        resourceManager.addResource(name.trim(), instances);
        log("Resource registered: " + name.trim() + " (instances=" + instances + ")");
    }

    /**
     * Validates then requests a resource for a process.
     * Validator checks: PID exists, resource exists, count in range,
     * process doesn't already hold the resource.
     */
    public boolean requestResource(int pid, String resourceName, int count) {
        Validator.validateResourceRequest(pid, resourceName, count, // ← VALIDATION
                allProcesses, resourceManager.getResources());

        Process p       = allProcesses.get(pid);
        boolean granted = resourceManager.requestResource(p, resourceName, count);
        if (granted) {
            log("PID " + pid + " granted " + count + "x " + resourceName);
        } else {
            p.setState(Process.State.WAITING);
            log("PID " + pid + " is now WAITING for " + resourceName);
        }
        return granted;
    }

    public void releaseResource(int pid, String resourceName) {
        Process p = allProcesses.get(pid);
        if (p == null) { log("No process with PID=" + pid); return; }
        resourceManager.releaseResource(p, resourceName);
        if (p.getState() == Process.State.WAITING) p.setState(Process.State.READY);
        log("PID " + pid + " released " + resourceName);
    }

    public ResourceManager getResourceManager() { return resourceManager; }

    // ── deadlocks ─────────────────────────────────────────────────────────
    public List<Integer> detectDeadlockOnce() {
        List<Integer> cycle = deadlockDetector.detect(allProcesses);
        if (cycle.isEmpty()) {
            log("Deadlock check: no deadlock detected.");
        } else {
            log("Deadlock DETECTED involving PIDs: " + cycle);
            if (autoResolveDeadlocks) resolveDeadlock(cycle);
        }
        return cycle;
    }

    public void resolveDeadlock(List<Integer> cycle) {
        if (cycle.isEmpty()) return;
        Integer victimPid   = null;
        int     worstPriority = Integer.MIN_VALUE;
        for (Integer pid : cycle) {
            Process p = allProcesses.get(pid);
            if (p != null && p.getPriority() > worstPriority) {
                worstPriority = p.getPriority();
                victimPid     = pid;
            }
        }
        if (victimPid == null) return;
        Process victim = allProcesses.get(victimPid);
        log("Resolving deadlock by preempting PID " + victimPid
                + " (" + victim.getName() + "): releasing its resources.");
        resourceManager.releaseAll(victim);
        victim.setState(Process.State.READY);
        VirtualNode node = nodes.get(victim.getNodeId());
        if (node != null && !node.getReadyQueue().contains(victim))
            node.getReadyQueue().offer(victim);
    }

    public void setAutoResolveDeadlocks(boolean v) { this.autoResolveDeadlocks = v; }
    public boolean isAutoResolveDeadlocks()         { return autoResolveDeadlocks; }

    // ── load balancer ─────────────────────────────────────────────────────
    public void balanceLoadOnce() {
        String result = loadBalancer.balanceOnce(nodes, this::log);
        if (result == null) log("Load balancer: system is already balanced.");
    }

    // ── failure / recovery ────────────────────────────────────────────────
    /**
     * Validates node ID then simulates a node crash.
     * Validator checks: node ID exists.
     */
    public void failNode(int nodeId) {
        Validator.validateNodeExists(nodeId, nodes);  // ← VALIDATION
        failureRecoveryManager.failNode(nodes.get(nodeId), nodes, resourceManager, this::log);
    }

    /**
     * Validates node ID then brings a failed node back online.
     * Validator checks: node ID exists.
     */
    public void recoverNode(int nodeId) {
        Validator.validateNodeExists(nodeId, nodes);  // ← VALIDATION
        failureRecoveryManager.recoverNode(nodes.get(nodeId), this::log);
    }

    // ── background services ───────────────────────────────────────────────
    public void startBackgroundServices() {
        if (backgroundServicesRunning) {
            log("Background services already running."); return;
        }
        backgroundServicesRunning = true;
        loadBalancerFuture = backgroundExecutor.scheduleWithFixedDelay(
                this::balanceLoadOnce,    3, 4, TimeUnit.SECONDS);
        deadlockFuture     = backgroundExecutor.scheduleWithFixedDelay(
                this::detectDeadlockOnce, 5, 6, TimeUnit.SECONDS);
        log("Background services started: auto load-balancing and deadlock detection.");
    }

    public void stopBackgroundServices() {
        backgroundServicesRunning = false;
        if (loadBalancerFuture != null) loadBalancerFuture.cancel(false);
        if (deadlockFuture     != null) deadlockFuture.cancel(false);
        log("Background services stopped.");
    }

    public boolean isBackgroundServicesRunning() { return backgroundServicesRunning; }

    public void shutdown() {
        for (VirtualNode n : nodes.values()) n.stop();
        backgroundExecutor.shutdownNow();
    }
}
