

import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A virtual node in the distributed OS. Each node owns:
 *  - a ready queue (round-robin scheduling)
 *  - a dedicated scheduler thread that "executes" processes in time slices
 *  - its own paged MemoryManager
 *
 * Nodes can be marked DOWN (failure simulation) and back UP (recovery).
 */
public class VirtualNode {

    public enum Status { UP, DOWN }

    public static final int TIME_QUANTUM = 2;      // CPU units consumed per scheduling slice
    public static final int TICK_MILLIS = 250;      // wall-clock time representing one CPU unit

    private final int id;
    private final String name;
    private final int cpuCores;
    private volatile Status status = Status.UP;

    private final MemoryManager memoryManager;
    private final BlockingQueue<Process> readyQueue = new LinkedBlockingDeque<>();
    private final Map<Integer, Process> processTable = new ConcurrentHashMap<>();
    private final AtomicReference<Process> currentProcess = new AtomicReference<>();

    private final NodeEventListener listener;
    private Thread schedulerThread;
    private volatile boolean running = false;

    public VirtualNode(int id, String name, int cpuCores, int memoryMB, NodeEventListener listener) {
        this.id = id;
        this.name = name;
        this.cpuCores = cpuCores;
        this.memoryManager = new MemoryManager(memoryMB);
        this.listener = listener;
    }

    public void start() {
        if (running) return;
        running = true;
        status = Status.UP;
        schedulerThread = new Thread(this::schedulerLoop, "Scheduler-" + name);
        schedulerThread.setDaemon(true);
        schedulerThread.start();
    }

    public void stop() {
        running = false;
        if (schedulerThread != null) schedulerThread.interrupt();
    }

    private void schedulerLoop() {
        while (running) {
            try {
                if (status == Status.DOWN) {
                    Thread.sleep(TICK_MILLIS);
                    continue;
                }
                Process p = readyQueue.poll(TICK_MILLIS, TimeUnit.MILLISECONDS);
                if (p == null) continue;
                if (p.getState() == Process.State.TERMINATED) continue;

                currentProcess.set(p);
                p.setState(Process.State.RUNNING);
                int slice = Math.min(TIME_QUANTUM, p.getRemainingTime());
                Thread.sleep((long) slice * TICK_MILLIS);
                p.setRemainingTime(p.getRemainingTime() - slice);
                currentProcess.set(null);

                if (!running) break;

                if (p.getRemainingTime() <= 0) {
                    p.setState(Process.State.TERMINATED);
                    memoryManager.free(p.getAllocatedPages());
                    processTable.remove(p.getPid());
                    if (listener != null) {
                        listener.log("Process " + p.getPid() + " (" + p.getName() + ") completed on node " + name);
                        listener.onProcessCompleted(p, this);
                    }
                } else {
                    p.setState(Process.State.READY);
                    readyQueue.offer(p);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public boolean admit(Process p) {
        List<Integer> pages = memoryManager.allocate(p.getMemoryRequiredMB());
        if (pages == null) return false;
        p.getAllocatedPages().addAll(pages);
        p.setNodeId(id);
        p.setState(Process.State.READY);
        processTable.put(p.getPid(), p);
        readyQueue.offer(p);
        return true;
    }

    /** Forcefully removes a process from this node (used for migration/kill), freeing its memory. */
    public void evict(Process p) {
        readyQueue.remove(p);
        processTable.remove(p.getPid());
        memoryManager.free(p.getAllocatedPages());
        p.getAllocatedPages().clear();
        if (currentProcess.get() == p) currentProcess.set(null);
    }

    public int getLoad() {
        return readyQueue.size() + (currentProcess.get() != null ? 1 : 0);
    }

    public int getId() { return id; }
    public String getName() { return name; }
    public int getCpuCores() { return cpuCores; }
    public Status getStatus() { return status; }
    public void setStatus(Status s) { this.status = s; }
    public MemoryManager getMemoryManager() { return memoryManager; }
    public BlockingQueue<Process> getReadyQueue() { return readyQueue; }
    public Map<Integer, Process> getProcessTable() { return processTable; }
    public Process getCurrentProcess() { return currentProcess.get(); }
}
