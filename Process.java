
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Represents a simulated process that lives inside the distributed OS.
 * Thread-safe: mutable fields are volatile / concurrent collections since
 * a process can be touched by the owning node's scheduler thread, the
 * load balancer thread, and the deadlock detector thread concurrently.
 */
public class Process {

    public enum State { NEW, READY, RUNNING, WAITING, TERMINATED }

    private final int pid;
    private final String name;
    private final int priority;              // lower number = higher priority
    private final int burstTime;              // total CPU time units required
    private volatile int remainingTime;
    private final int memoryRequiredMB;
    private volatile State state;
    private volatile int nodeId;               // node currently hosting the process
    private final List<Integer> allocatedPages = new CopyOnWriteArrayList<>();
    private final Set<String> heldResources = ConcurrentHashMap.newKeySet();
    private volatile String waitingForResource = null;
    private final long createdAt = System.currentTimeMillis();
    private volatile int migrations = 0;

    public Process(int pid, String name, int priority, int burstTime, int memoryRequiredMB, int nodeId) {
        this.pid = pid;
        this.name = name;
        this.priority = priority;
        this.burstTime = burstTime;
        this.remainingTime = burstTime;
        this.memoryRequiredMB = memoryRequiredMB;
        this.nodeId = nodeId;
        this.state = State.NEW;
    }

    public int getPid() { return pid; }
    public String getName() { return name; }
    public int getPriority() { return priority; }
    public int getBurstTime() { return burstTime; }
    public int getRemainingTime() { return remainingTime; }
    public void setRemainingTime(int t) { this.remainingTime = t; }
    public int getMemoryRequiredMB() { return memoryRequiredMB; }
    public State getState() { return state; }
    public void setState(State s) { this.state = s; }
    public int getNodeId() { return nodeId; }
    public void setNodeId(int nodeId) { this.nodeId = nodeId; }
    public List<Integer> getAllocatedPages() { return allocatedPages; }
    public Set<String> getHeldResources() { return heldResources; }
    public String getWaitingForResource() { return waitingForResource; }
    public void setWaitingForResource(String r) { this.waitingForResource = r; }
    public long getCreatedAt() { return createdAt; }
    public int getMigrations() { return migrations; }
    public void incrementMigrations() { migrations++; }

    @Override
    public String toString() {
        return String.format("PID=%-4d %-12s prio=%-3d remaining=%-4d mem=%-5dMB state=%-9s node=%-3d holds=%s waitFor=%s",
                pid, name, priority, remainingTime, memoryRequiredMB, state, nodeId,
                heldResources.isEmpty() ? "-" : heldResources,
                waitingForResource == null ? "-" : waitingForResource);
    }
}
