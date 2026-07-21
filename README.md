# DistributedOS — Distributed Operating System Simulator 

A console-based Core Java simulation of a distributed operating system made
of multiple virtual nodes. It demonstrates process scheduling, memory
allocation, shared-resource management, deadlock detection, load balancing,
process migration, and failure recovery through an interactive CLI.

All source files are flat in this single folder (no `src/main/java/...`
package structure) so the project opens directly in IntelliJ as a simple
module with no extra nesting.

## Files

- `Main.java` — entry point
- `ConsoleUI.java` — interactive text menu
- `DistributedOperatingSystem.java` — top-level orchestrator (nodes, processes, background services)
- `VirtualNode.java` — a node with its own ready queue and memory manager
- `Process.java` — process model / state machine
- `MemoryManager.java` — per-node memory allocation
- `Resource.java` / `ResourceManager.java` — shared, multi-instance resources and their allocation graph
- `LoadBalancer.java` — migrates processes from overloaded to underloaded nodes
- `DeadlockDetector.java` — cycle detection over the resource wait-for graph
- `FailureRecoveryManager.java` — simulated node failure/recovery and process rescue
- `NodeEventListener.java` — event hook interface for node state changes

## Build & run

```bash
javac -d out *.java
java -cp out Main
```

(No package declarations are used, so all classes are compiled into the
default/unnamed package — this is what keeps the folder flat.)

## Opening in IntelliJ

1. `File → Open`, select this `DistributedOS` folder.
2. IntelliJ will detect the `.java` files directly — no nested `src` folder needed.
3. If it doesn't auto-mark the folder as a Sources Root, right-click the
   `DistributedOS` folder → `Mark Directory as` → `Sources Root`.
4. Run `Main.java`.


