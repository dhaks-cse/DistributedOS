

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The god-object that wires nodes, memory, resources, scheduling, load
 * balancing, deadlock detection and failure recovery into one coherent
 * distributed operating system simulation, driven interactively via CLI.
 */
public class DistributedOperatingSystem implements NodeEventListener {

    private final Map<Integer, VirtualNode> nodes = new ConcurrentHashMap<>();
    private final Map<Integer, Process> allProcesses = new ConcurrentHashMap<>();

    private final AtomicInteger nodeIdGen = new AtomicInteger(1);
    private final AtomicInteger pidGen = new AtomicInteger(1);

    private final ResourceManager resourceManager = new ResourceManager();
    private final DeadlockDetector deadlockDetector = new DeadlockDetector(resourceManager);
    private final LoadBalancer loadBalancer = new LoadBalancer();
    private final FailureRecoveryManager failureRecoveryManager = new FailureRecoveryManager();

    private final ScheduledExecutorService backgroundExecutor = Executors.newScheduledThreadPool(2);
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss");
    private volatile boolean backgroundServicesRunning = false;
    private volatile boolean autoResolveDeadlocks = true;
    private java.util.concurrent.ScheduledFuture<?> loadBalancerFuture;
    private java.util.concurrent.ScheduledFuture<?> deadlockFuture;

    // ---------------------------------------------------------- logging ----
    @Override
    public synchronized void log(String message) {
        System.out.println("[" + timeFormat.format(new Date()) + "] " + message);
    }

    @Override
    public void onProcessCompleted(Process process, VirtualNode node) {
        resourceManager.releaseAll(process);
        allProcesses.remove(process.getPid());
    }

    // ---------------------------------------------------------- nodes ------
    public VirtualNode addNode(String name, int cpuCores, int memoryMB) {
        int id = nodeIdGen.getAndIncrement();
        VirtualNode node = new VirtualNode(id, name, cpuCores, memoryMB, this);
        nodes.put(id, node);
        node.start();
        log("Node added: [" + id + "] " + name + " (cores=" + cpuCores + ", memory=" + memoryMB + "MB)");
        return node;
    }

    public Map<Integer, VirtualNode> getNodes() { return nodes; }

    public VirtualNode getNode(int id) { return nodes.get(id); }

    // ------------------------------------------------------- processes -----
    public Process createProcess(String name, int priority, int burstTime, int memoryMB) {
        VirtualNode target = pickLeastLoadedUpNode();
        if (target == null) {
            log("Cannot create process " + name + ": no UP nodes available.");
            return null;
        }
        int pid = pidGen.getAndIncrement();
        Process p = new Process(pid, name, priority, burstTime, memoryMB, target.getId());

        if (!target.admit(p)) {
            // try any other UP node with capacity
            boolean admitted = false;
            for (VirtualNode n : nodes.values()) {
                if (n.getStatus() == VirtualNode.Status.UP && n.getId() != target.getId() && n.admit(p)) {
                    target = n;
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
        log("Process created: PID=" + pid + " (" + name + ") assigned to node " + target.getName());
        return p;
    }

    public boolean killProcess(int pid) {
        Process p = allProcesses.get(pid);
        if (p == null) return false;
        VirtualNode node = nodes.get(p.getNodeId());
        if (node != null) node.evict(p);
        resourceManager.releaseAll(p);
        p.setState(Process.State.TERMINATED);
        allProcesses.remove(pid);
        log("Process killed: PID=" + pid + " (" + p.getName() + ")");
        return true;
    }

    public boolean migrateProcess(int pid, int targetNodeId) {
        Process p = allProcesses.get(pid);
        VirtualNode target = nodes.get(targetNodeId);
        if (p == null || target == null || target.getStatus() != VirtualNode.Status.UP) return false;

        VirtualNode source = nodes.get(p.getNodeId());
        if (source != null) source.evict(p);
        p.getAllocatedPages().clear();

        if (!target.admit(p)) {
            if (source != null) source.admit(p); // roll back
            log("Migration failed for PID " + pid + ": insufficient memory on node " + target.getName());
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

    // -------------------------------------------------------- resources ----
    public void addResource(String name, int instances) {
        resourceManager.addResource(name, instances);
        log("Resource registered: " + name + " (instances=" + instances + ")");
    }

    public boolean requestResource(int pid, String resourceName, int count) {
        Process p = allProcesses.get(pid);
        if (p == null) return false;
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
        if (p == null) return;
        resourceManager.releaseResource(p, resourceName);
        if (p.getState() == Process.State.WAITING) p.setState(Process.State.READY);
        log("PID " + pid + " released " + resourceName);
    }

    public ResourceManager getResourceManager() { return resourceManager; }

    // -------------------------------------------------------- deadlocks ----
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
        // Victim selection: lowest priority (i.e. numerically highest priority value = least important)
        Integer victimPid = null;
        int worstPriority = Integer.MIN_VALUE;
        for (Integer pid : cycle) {
            Process p = allProcesses.get(pid);
            if (p != null && p.getPriority() > worstPriority) {
                worstPriority = p.getPriority();
                victimPid = pid;
            }
        }
        if (victimPid == null) return;
        Process victim = allProcesses.get(victimPid);
        log("Resolving deadlock by preempting PID " + victimPid + " (" + victim.getName() + "): releasing its resources.");
        resourceManager.releaseAll(victim);
        victim.setState(Process.State.READY);
        VirtualNode node = nodes.get(victim.getNodeId());
        if (node != null && !node.getReadyQueue().contains(victim)) {
            node.getReadyQueue().offer(victim);
        }
    }

    public void setAutoResolveDeadlocks(boolean v) { this.autoResolveDeadlocks = v; }
    public boolean isAutoResolveDeadlocks() { return autoResolveDeadlocks; }

    // ------------------------------------------------------ load balance ---
    public void balanceLoadOnce() {
        String result = loadBalancer.balanceOnce(nodes, this::log);
        if (result == null) log("Load balancer: system is already balanced.");
    }

    // -------------------------------------------------------- failures -----
    public void failNode(int nodeId) {
        VirtualNode node = nodes.get(nodeId);
        if (node == null) { log("No such node: " + nodeId); return; }
        failureRecoveryManager.failNode(node, nodes, resourceManager, this::log);
    }

    public void recoverNode(int nodeId) {
        VirtualNode node = nodes.get(nodeId);
        if (node == null) { log("No such node: " + nodeId); return; }
        failureRecoveryManager.recoverNode(node, this::log);
    }

    // ---------------------------------------------------- background ops ---
    public void startBackgroundServices() {
        if (backgroundServicesRunning) {
            log("Background services already running.");
            return;
        }
        backgroundServicesRunning = true;
        loadBalancerFuture = backgroundExecutor.scheduleWithFixedDelay(this::balanceLoadOnce, 3, 4, TimeUnit.SECONDS);
        deadlockFuture = backgroundExecutor.scheduleWithFixedDelay(this::detectDeadlockOnce, 5, 6, TimeUnit.SECONDS);
        log("Background services started: auto load-balancing and deadlock detection.");
    }

    public void stopBackgroundServices() {
        backgroundServicesRunning = false;
        if (loadBalancerFuture != null) loadBalancerFuture.cancel(false);
        if (deadlockFuture != null) deadlockFuture.cancel(false);
        log("Background services stopped.");
    }

    public boolean isBackgroundServicesRunning() { return backgroundServicesRunning; }

    public void shutdown() {
        for (VirtualNode n : nodes.values()) n.stop();
        backgroundExecutor.shutdownNow();
    }
}
