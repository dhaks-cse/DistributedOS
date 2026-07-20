
import java.util.ArrayList;
import java.util.List;

/**
 * Simulates paged memory allocation for a single virtual node.
 * Memory is divided into fixed-size pages; allocation/free operations
 * are synchronized so multiple threads (scheduler, load balancer,
 * failure recovery) can safely mutate the page table concurrently.
 */
public class MemoryManager {

    private static final int PAGE_SIZE_MB = 4;

    private final int totalPages;
    private final boolean[] pageTable;
    private int usedPages = 0;
    private final Object lock = new Object();

    public MemoryManager(int totalMemoryMB) {
        this.totalPages = Math.max(1, totalMemoryMB / PAGE_SIZE_MB);
        this.pageTable = new boolean[totalPages];
    }

    /** Attempts to allocate enough pages to cover requiredMB. Returns null if not enough free memory. */
    public List<Integer> allocate(int requiredMB) {
        int pagesNeeded = (int) Math.ceil(requiredMB / (double) PAGE_SIZE_MB);
        synchronized (lock) {
            if (totalPages - usedPages < pagesNeeded) {
                return null; // not enough contiguous-free capacity (fragmentation-agnostic simulation)
            }
            List<Integer> allocated = new ArrayList<>(pagesNeeded);
            for (int i = 0; i < totalPages && allocated.size() < pagesNeeded; i++) {
                if (!pageTable[i]) {
                    pageTable[i] = true;
                    allocated.add(i);
                }
            }
            usedPages += allocated.size();
            return allocated;
        }
    }

    public void free(List<Integer> pages) {
        if (pages == null) return;
        synchronized (lock) {
            for (int p : pages) {
                if (p >= 0 && p < totalPages && pageTable[p]) {
                    pageTable[p] = false;
                    usedPages--;
                }
            }
        }
    }

    /** Wipes all allocations, e.g. when a node fails and its memory state is no longer trustworthy. */
    public void reset() {
        synchronized (lock) {
            java.util.Arrays.fill(pageTable, false);
            usedPages = 0;
        }
    }

    public int getTotalPages() { return totalPages; }
    public int getUsedPages() { synchronized (lock) { return usedPages; } }
    public int getFreePages() { synchronized (lock) { return totalPages - usedPages; } }
    public int getTotalMB() { return totalPages * PAGE_SIZE_MB; }
    public int getUsedMB() { return getUsedPages() * PAGE_SIZE_MB; }
    public int getFreeMB() { return getFreePages() * PAGE_SIZE_MB; }
    public double getUtilization() { return totalPages == 0 ? 0 : (100.0 * getUsedPages() / totalPages); }
}
