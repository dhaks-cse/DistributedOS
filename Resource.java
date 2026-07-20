
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A shared, counting resource (e.g. printer, database connection, file lock)
 * that processes across the whole distributed system can request/release.
 * Used as the basis of the resource-allocation graph for deadlock detection.
 */
public class Resource {

    private final String name;
    private final int totalInstances;
    private final AtomicInteger availableInstances;
    private final Map<Integer, Integer> allocation = new ConcurrentHashMap<>(); // pid -> instances held
    private final Queue<Integer> waitQueue = new ConcurrentLinkedQueue<>();     // pids waiting

    public Resource(String name, int totalInstances) {
        this.name = name;
        this.totalInstances = totalInstances;
        this.availableInstances = new AtomicInteger(totalInstances);
    }

    public synchronized boolean request(int pid, int count) {
        if (availableInstances.get() >= count) {
            availableInstances.addAndGet(-count);
            allocation.merge(pid, count, Integer::sum);
            waitQueue.remove((Integer) pid);
            return true;
        }
        if (!waitQueue.contains(pid)) waitQueue.add(pid);
        return false;
    }

    public synchronized void release(int pid) {
        Integer count = allocation.remove(pid);
        if (count != null) availableInstances.addAndGet(count);
        waitQueue.remove((Integer) pid);
    }

    public String getName() { return name; }
    public int getTotalInstances() { return totalInstances; }
    public int getAvailableInstances() { return availableInstances.get(); }
    public Set<Integer> getHolders() { return allocation.keySet(); }
    public Queue<Integer> getWaitQueue() { return waitQueue; }
}
