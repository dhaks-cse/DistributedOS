

import java.util.*;

/**
 * Builds a Wait-For Graph across every process in the system (regardless of
 * which node hosts it, since resources are shared system-wide) and detects
 * cycles using DFS. A cycle == deadlock.
 *
 * Edge P -> Q exists if process P is waiting for a resource currently held
 * (at least partially) by process Q.
 */
public class DeadlockDetector {

    private final ResourceManager resourceManager;

    public DeadlockDetector(ResourceManager resourceManager) {
        this.resourceManager = resourceManager;
    }

    /** Returns a list of pids forming a deadlock cycle, or empty list if none found. */
    public List<Integer> detect(Map<Integer, Process> allProcesses) {
        Map<Integer, List<Integer>> graph = buildWaitForGraph(allProcesses);

        Set<Integer> visited = new HashSet<>();
        Set<Integer> stack = new LinkedHashSet<>();

        for (Integer pid : graph.keySet()) {
            List<Integer> cycle = dfs(pid, graph, visited, stack);
            if (cycle != null) return cycle;
        }
        return Collections.emptyList();
    }

    private Map<Integer, List<Integer>> buildWaitForGraph(Map<Integer, Process> allProcesses) {
        Map<Integer, List<Integer>> graph = new HashMap<>();
        for (Process p : allProcesses.values()) {
            if (p.getState() != Process.State.WAITING || p.getWaitingForResource() == null) continue;
            Resource r = resourceManager.getResources().get(p.getWaitingForResource());
            if (r == null) continue;
            List<Integer> edges = new ArrayList<>();
            for (Integer holderPid : r.getHolders()) {
                if (!holderPid.equals(p.getPid())) edges.add(holderPid);
            }
            graph.put(p.getPid(), edges);
        }
        return graph;
    }

    private List<Integer> dfs(Integer node, Map<Integer, List<Integer>> graph, Set<Integer> visited, Set<Integer> stack) {
        if (stack.contains(node)) {
            // Found a cycle: extract it from the current stack
            List<Integer> path = new ArrayList<>(stack);
            int idx = path.indexOf(node);
            return new ArrayList<>(path.subList(idx, path.size()));
        }
        if (visited.contains(node)) return null;

        visited.add(node);
        stack.add(node);
        for (Integer neighbor : graph.getOrDefault(node, Collections.emptyList())) {
            List<Integer> cycle = dfs(neighbor, graph, visited, stack);
            if (cycle != null) return cycle;
        }
        stack.remove(node);
        return null;
    }
}
