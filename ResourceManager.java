

import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * System-wide registry of shared resources (spans all nodes), giving the
 * distributed OS a single point to manage resource sharing and to build
 * the wait-for graph used for deadlock detection.
 */
public class ResourceManager {

    private final Map<String, Resource> resources = new ConcurrentHashMap<>();

    public void addResource(String name, int instances) {
        resources.put(name, new Resource(name, instances));
    }

    public boolean requestResource(Process p, String resourceName, int count) {
        Resource r = resources.get(resourceName);
        if (r == null) return false;
        boolean granted = r.request(p.getPid(), count);
        if (granted) {
            p.getHeldResources().add(resourceName);
            p.setWaitingForResource(null);
        } else {
            p.setWaitingForResource(resourceName);
        }
        return granted;
    }

    public void releaseResource(Process p, String resourceName) {
        Resource r = resources.get(resourceName);
        if (r == null) return;
        r.release(p.getPid());
        p.getHeldResources().remove(resourceName);
    }

    public void releaseAll(Process p) {
        for (String rn : new HashSet<>(p.getHeldResources())) {
            releaseResource(p, rn);
        }
        p.setWaitingForResource(null);
    }

    public Map<String, Resource> getResources() { return resources; }
}
