import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Centralised validation utility for the Distributed OS Simulator.
 *
 * Uses java.util collections (List, Map, Pattern) to accumulate and
 * report all validation errors in one shot rather than failing on the
 * first bad field — giving the user a complete picture of what went
 * wrong.
 *
 * Every public method is static so callers need no instance.
 * Throws IllegalArgumentException with a full error summary when
 * one or more rules are violated.
 */
public final class Validator {

    // ── constants ─────────────────────────────────────────────────────────
    public static final int MIN_NODE_CORES       = 1;
    public static final int MAX_NODE_CORES       = 64;
    public static final int MIN_NODE_MEMORY_MB   = 8;
    public static final int MAX_NODE_MEMORY_MB   = 4096;

    public static final int MIN_PRIORITY         = 1;
    public static final int MAX_PRIORITY         = 10;
    public static final int MIN_BURST_TIME       = 1;
    public static final int MAX_BURST_TIME       = 100;
    public static final int MIN_PROCESS_MEM_MB   = 1;
    public static final int MAX_PROCESS_MEM_MB   = 512;

    public static final int MIN_RESOURCE_INSTANCES = 1;
    public static final int MAX_RESOURCE_INSTANCES = 32;

    /** Names must be 1-20 alphanumeric/underscore/-/. characters, no spaces. */
    private static final Pattern VALID_NAME = Pattern.compile("^[A-Za-z0-9_\\-.]{1,20}$");

    private Validator() { /* utility class — no instances */ }

    // ══════════════════════════════════════════════════════════════════════
    // Node validation
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Validates all parameters for creating a new virtual node.
     *
     * @param name      proposed node name
     * @param cores     number of CPU cores
     * @param memoryMB  total memory in MB
     * @param existingNodes current node map (used to check duplicate names)
     * @throws IllegalArgumentException with a full error list if anything is invalid
     */
    public static void validateNode(String name, int cores, int memoryMB,
                                    Map<Integer, VirtualNode> existingNodes) {
        List<String> errors = new ArrayList<>();

        // name
        if (name == null || name.trim().isEmpty()) {
            errors.add("Node name must not be blank.");
        } else if (!VALID_NAME.matcher(name.trim()).matches()) {
            errors.add("Node name '" + name + "' is invalid. "
                    + "Use 1-20 alphanumeric / _ - . characters only (no spaces).");
        } else {
            // duplicate check using java.util.Map
            for (VirtualNode n : existingNodes.values()) {
                if (n.getName().equalsIgnoreCase(name.trim())) {
                    errors.add("A node named '" + name.trim() + "' already exists (ID=" + n.getId() + ").");
                    break;
                }
            }
        }

        // cores
        if (cores < MIN_NODE_CORES || cores > MAX_NODE_CORES) {
            errors.add("CPU cores must be between " + MIN_NODE_CORES
                    + " and " + MAX_NODE_CORES + " (given: " + cores + ").");
        }

        // memory
        if (memoryMB < MIN_NODE_MEMORY_MB || memoryMB > MAX_NODE_MEMORY_MB) {
            errors.add("Memory must be between " + MIN_NODE_MEMORY_MB + " MB"
                    + " and " + MAX_NODE_MEMORY_MB + " MB (given: " + memoryMB + " MB).");
        }
        if (memoryMB % 4 != 0) {
            errors.add("Memory must be a multiple of 4 MB (page size) (given: " + memoryMB + " MB).");
        }

        throwIfErrors("Node validation failed", errors);
    }

    // ══════════════════════════════════════════════════════════════════════
    // Process validation
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Validates all parameters for creating a new process.
     *
     * @param name       process name
     * @param priority   scheduling priority (1 = highest, 10 = lowest)
     * @param burstTime  total CPU units needed
     * @param memoryMB   memory footprint
     * @param upNodes    map of currently UP nodes (must have at least one)
     * @throws IllegalArgumentException with a full error list if anything is invalid
     */
    public static void validateProcess(String name, int priority, int burstTime,
                                       int memoryMB, Map<Integer, VirtualNode> upNodes) {
        List<String> errors = new ArrayList<>();

        // name
        if (name == null || name.trim().isEmpty()) {
            errors.add("Process name must not be blank.");
        } else if (!VALID_NAME.matcher(name.trim()).matches()) {
            errors.add("Process name '" + name + "' is invalid. "
                    + "Use 1-20 alphanumeric / _ - . characters only (no spaces).");
        }

        // priority
        if (priority < MIN_PRIORITY || priority > MAX_PRIORITY) {
            errors.add("Priority must be between " + MIN_PRIORITY + " (highest) and "
                    + MAX_PRIORITY + " (lowest) (given: " + priority + ").");
        }

        // burst time
        if (burstTime < MIN_BURST_TIME || burstTime > MAX_BURST_TIME) {
            errors.add("Burst time must be between " + MIN_BURST_TIME
                    + " and " + MAX_BURST_TIME + " CPU units (given: " + burstTime + ").");
        }

        // memory
        if (memoryMB < MIN_PROCESS_MEM_MB || memoryMB > MAX_PROCESS_MEM_MB) {
            errors.add("Process memory must be between " + MIN_PROCESS_MEM_MB
                    + " MB and " + MAX_PROCESS_MEM_MB + " MB (given: " + memoryMB + " MB).");
        }

        // cluster must have at least one UP node
        long upCount = upNodes.values().stream()
                .filter(n -> n.getStatus() == VirtualNode.Status.UP)
                .count();
        if (upCount == 0) {
            errors.add("No UP nodes available to host the process.");
        }

        throwIfErrors("Process validation failed", errors);
    }

    // ══════════════════════════════════════════════════════════════════════
    // Resource validation
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Validates parameters for registering a new shared resource.
     *
     * @param name              resource name
     * @param instances         number of instances
     * @param existingResources current resource map (duplicate-name check)
     * @throws IllegalArgumentException with a full error list if anything is invalid
     */
    public static void validateResource(String name, int instances,
                                        Map<String, Resource> existingResources) {
        List<String> errors = new ArrayList<>();

        if (name == null || name.trim().isEmpty()) {
            errors.add("Resource name must not be blank.");
        } else if (!VALID_NAME.matcher(name.trim()).matches()) {
            errors.add("Resource name '" + name + "' is invalid. "
                    + "Use 1-20 alphanumeric / _ - . characters only (no spaces).");
        } else if (existingResources.containsKey(name.trim())) {
            errors.add("Resource '" + name.trim() + "' is already registered.");
        }

        if (instances < MIN_RESOURCE_INSTANCES || instances > MAX_RESOURCE_INSTANCES) {
            errors.add("Instance count must be between " + MIN_RESOURCE_INSTANCES
                    + " and " + MAX_RESOURCE_INSTANCES + " (given: " + instances + ").");
        }

        throwIfErrors("Resource validation failed", errors);
    }

    // ══════════════════════════════════════════════════════════════════════
    // Resource-request validation
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Validates a process's request for a number of resource instances.
     *
     * @param pid               requesting process ID
     * @param resourceName      name of the resource
     * @param count             number of instances requested
     * @param allProcesses      system-wide process table
     * @param existingResources system-wide resource registry
     * @throws IllegalArgumentException with a full error list if anything is invalid
     */
    public static void validateResourceRequest(int pid, String resourceName, int count,
                                               Map<Integer, Process> allProcesses,
                                               Map<String, Resource> existingResources) {
        List<String> errors = new ArrayList<>();

        // process must exist
        Process p = allProcesses.get(pid);
        if (p == null) {
            errors.add("No process with PID=" + pid + " exists.");
        } else if (p.getState() == Process.State.TERMINATED) {
            errors.add("Process PID=" + pid + " is already TERMINATED.");
        }

        // resource must exist
        Resource r = existingResources.get(resourceName);
        if (resourceName == null || resourceName.trim().isEmpty()) {
            errors.add("Resource name must not be blank.");
        } else if (r == null) {
            errors.add("Resource '" + resourceName + "' is not registered. "
                    + "Available: " + existingResources.keySet());
        } else {
            // count within resource capacity
            if (count < 1 || count > r.getTotalInstances()) {
                errors.add("Requested count " + count + " is out of range for resource '"
                        + resourceName + "' (total instances: " + r.getTotalInstances() + ").");
            }
            // duplicate hold check
            if (p != null && p.getHeldResources().contains(resourceName)) {
                errors.add("Process PID=" + pid + " already holds '" + resourceName
                        + "'. Release it before requesting again.");
            }
        }

        throwIfErrors("Resource-request validation failed", errors);
    }

    // ══════════════════════════════════════════════════════════════════════
    // Migration validation
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Validates a process-migration request.
     *
     * @param pid          PID to migrate
     * @param targetNodeId destination node ID
     * @param allProcesses system-wide process table
     * @param allNodes     system-wide node map
     * @throws IllegalArgumentException with a full error list if anything is invalid
     */
    public static void validateMigration(int pid, int targetNodeId,
                                         Map<Integer, Process> allProcesses,
                                         Map<Integer, VirtualNode> allNodes) {
        List<String> errors = new ArrayList<>();

        Process p = allProcesses.get(pid);
        if (p == null) {
            errors.add("No process with PID=" + pid + " exists.");
        } else if (p.getState() == Process.State.TERMINATED) {
            errors.add("Cannot migrate a TERMINATED process (PID=" + pid + ").");
        }

        VirtualNode target = allNodes.get(targetNodeId);
        if (target == null) {
            errors.add("No node with ID=" + targetNodeId + " exists.");
        } else if (target.getStatus() == VirtualNode.Status.DOWN) {
            errors.add("Target node '" + target.getName() + "' (ID=" + targetNodeId + ") is DOWN.");
        } else if (p != null && target.getId() == p.getNodeId()) {
            errors.add("Process PID=" + pid + " is already on node '"
                    + target.getName() + "'. Choose a different target.");
        }

        throwIfErrors("Migration validation failed", errors);
    }

    // ══════════════════════════════════════════════════════════════════════
    // Node-ID lookup validation  (fail / recover)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Ensures the given node ID resolves to a real node.
     *
     * @param nodeId   node ID supplied by the user
     * @param allNodes system-wide node map
     * @throws IllegalArgumentException if the ID is unknown
     */
    public static void validateNodeExists(int nodeId, Map<Integer, VirtualNode> allNodes) {
        if (!allNodes.containsKey(nodeId)) {
            List<String> ids = new ArrayList<>();
            allNodes.keySet().forEach(k -> ids.add(String.valueOf(k)));
            throw new IllegalArgumentException(
                    "No node with ID=" + nodeId + " exists. "
                    + "Valid IDs: " + (ids.isEmpty() ? "(none)" : ids));
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // PID lookup validation  (kill)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Ensures the given PID resolves to an active (non-terminated) process.
     *
     * @param pid          process ID supplied by the user
     * @param allProcesses system-wide process table
     * @throws IllegalArgumentException if the PID is unknown or the process is terminated
     */
    public static void validateProcessExists(int pid, Map<Integer, Process> allProcesses) {
        Process p = allProcesses.get(pid);
        if (p == null) {
            List<String> pids = new ArrayList<>();
            allProcesses.keySet().forEach(k -> pids.add(String.valueOf(k)));
            throw new IllegalArgumentException(
                    "No active process with PID=" + pid + ". "
                    + "Active PIDs: " + (pids.isEmpty() ? "(none)" : pids));
        }
        if (p.getState() == Process.State.TERMINATED) {
            throw new IllegalArgumentException(
                    "Process PID=" + pid + " (" + p.getName() + ") is already TERMINATED.");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Internal helpers
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Joins all error strings from the list using java.util.List and throws
     * a single IllegalArgumentException whose message contains every problem.
     */
    private static void throwIfErrors(String context, List<String> errors) {
        if (errors.isEmpty()) return;
        StringBuilder sb = new StringBuilder("\n[VALIDATION ERROR] ").append(context).append(":\n");
        for (int i = 0; i < errors.size(); i++) {
            sb.append("  ").append(i + 1).append(". ").append(errors.get(i)).append("\n");
        }
        throw new IllegalArgumentException(sb.toString().trim());
    }
}
