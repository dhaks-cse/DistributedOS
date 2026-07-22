import java.util.List;
import java.util.Map;
import java.util.Scanner;

/**
 * Text-based menu-driven console for the Distributed OS Simulator.
 *
 * All user input is forwarded to DistributedOperatingSystem, which
 * delegates to Validator before changing any state.  Validation errors
 * are caught here and displayed as clean, numbered error lists so the
 * user sees exactly what was wrong without a stack trace.
 */
public class ConsoleUI {

    private final DistributedOperatingSystem os      = new DistributedOperatingSystem();
    private final Scanner                    scanner = new Scanner(System.in);

    public void run() {
        printBanner();
        seedDemoCluster();

        boolean exit = false;
        while (!exit) {
            printMenu();
            String choice = prompt("Choose an option: ");
            try {
                switch (choice.trim()) {
                    case  "1": addNode();                  break;
                    case  "2": createProcess();            break;
                    case  "3": showNodes();                break;
                    case  "4": showProcesses();            break;
                    case  "5": killProcess();              break;
                    case  "6": migrateProcess();           break;
                    case  "7": addResource();              break;
                    case  "8": requestResource();          break;
                    case  "9": releaseResource();          break;
                    case "10": showResources();            break;
                    case "11": os.balanceLoadOnce();       break;
                    case "12": runDeadlockDetection();     break;
                    case "13": failNode();                 break;
                    case "14": recoverNode();              break;
                    case "15": toggleBackgroundServices(); break;
                    case "16": toggleAutoResolve();        break;
                    case  "0": exit = true;                break;
                    default:   System.out.println("  [!] Invalid option. Enter a number from the menu."); break;
                }
            } catch (IllegalArgumentException e) {
                // Validator throws these — print cleanly, no stack trace
                System.out.println(e.getMessage());
            } catch (Exception e) {
                System.out.println("  [!] Unexpected error: " + e.getMessage());
            }
        }
        os.shutdown();
        System.out.println("\nDistributed OS simulation shut down. Goodbye!");
    }

    // ── banner / seed ─────────────────────────────────────────────────────
    private void printBanner() {
        System.out.println("==================================================================");
        System.out.println("        DISTRIBUTED OPERATING SYSTEM SIMULATOR (Core Java)       ");
        System.out.println("  Scheduling | Memory | Resource Sharing | Load Balancing        ");
        System.out.println("  Deadlock Detection | Failure Recovery | Process Migration       ");
        System.out.println("==================================================================");
    }

    private void seedDemoCluster() {
        os.addNode("NodeA", 4, 64);
        os.addNode("NodeB", 4, 64);
        os.addNode("NodeC", 2, 32);
        os.addResource("Printer",  1);
        os.addResource("Database", 2);
        os.addResource("Scanner",  1);
        System.out.println(
            "\n(Demo cluster ready: 3 nodes [NodeA/B/C] + 3 resources [Printer/Database/Scanner])\n");
    }

    // ── menu ──────────────────────────────────────────────────────────────
    private void printMenu() {
        System.out.println("\n------------------------------ MENU ------------------------------");
        System.out.println("  1  Add virtual node");
        System.out.println("  2  Create process");
        System.out.println("  3  Show node status");
        System.out.println("  4  Show process table");
        System.out.println("  5  Kill process");
        System.out.println("  6  Migrate process to another node");
        System.out.println("  7  Register shared resource");
        System.out.println("  8  Request resource for a process");
        System.out.println("  9  Release resource from a process");
        System.out.println(" 10  Show resource status");
        System.out.println(" 11  Run load balancer (manual)");
        System.out.println(" 12  Run deadlock detection (manual)");
        System.out.println(" 13  Simulate node FAILURE");
        System.out.println(" 14  Recover a failed node");
        System.out.println(" 15  Toggle background services  ["
                + (os.isBackgroundServicesRunning() ? "RUNNING" : "STOPPED") + "]");
        System.out.println(" 16  Toggle auto-resolve deadlock ["
                + (os.isAutoResolveDeadlocks() ? "ON" : "OFF") + "]");
        System.out.println("  0  Exit");
        System.out.println("------------------------------------------------------------------");
    }

    // ── nodes ─────────────────────────────────────────────────────────────
    private void addNode() {
        System.out.println("\n-- Add Virtual Node --");
        String name  = prompt("  Node name (1-20 chars, no spaces): ");
        int    cores = promptInt("  CPU cores [1-64]: ");
        int    mem   = promptInt("  Memory MB [8-4096, multiple of 4]: ");
        os.addNode(name, cores, mem);   // Validator runs inside here
    }

    private void showNodes() {
        System.out.println();
        System.out.printf("%-4s  %-10s  %-6s  %-6s  %-18s  %-6s  %-6s%n",
                "ID", "Name", "Status", "Cores", "Memory(used/tot)", "Load", "Queue");
        System.out.println("  " + "-".repeat(68));
        for (VirtualNode n : os.getNodes().values()) {
            String mem = n.getMemoryManager().getUsedMB() + "/" + n.getMemoryManager().getTotalMB() + "MB";
            System.out.printf("%-4d  %-10s  %-6s  %-6d  %-18s  %-6d  %-6d%n",
                    n.getId(), n.getName(), n.getStatus(), n.getCpuCores(),
                    mem, n.getLoad(), n.getReadyQueue().size());
        }
    }

    private void failNode() {
        System.out.println("\n-- Simulate Node Failure --");
        showNodes();
        int id = promptInt("  Node ID to fail: ");
        os.failNode(id);    // Validator checks ID exists
    }

    private void recoverNode() {
        System.out.println("\n-- Recover Failed Node --");
        showNodes();
        int id = promptInt("  Node ID to recover: ");
        os.recoverNode(id); // Validator checks ID exists
    }

    // ── processes ─────────────────────────────────────────────────────────
    private void createProcess() {
        System.out.println("\n-- Create Process --");
        String name     = prompt("  Process name (1-20 chars, no spaces): ");
        int    priority = promptInt("  Priority 1=highest .. 10=lowest: ");
        int    burst    = promptInt("  Burst time (CPU units) [1-100]: ");
        int    mem      = promptInt("  Memory required MB [1-512]: ");
        os.createProcess(name, priority, burst, mem);  // Validator runs inside here
    }

    private void showProcesses() {
        if (os.getAllProcesses().isEmpty()) {
            System.out.println("\n  No active processes.");
            return;
        }
        System.out.println();
        System.out.printf("%-6s  %-12s  %-5s  %-9s  %-7s  %-9s  %-5s  %-12s  %s%n",
                "PID", "Name", "Prio", "Remaining", "Mem(MB)", "State", "Node", "Holds", "WaitFor");
        System.out.println("  " + "-".repeat(88));
        for (Process p : os.getAllProcesses().values()) {
            System.out.printf("%-6d  %-12s  %-5d  %-9d  %-7d  %-9s  %-5d  %-12s  %s%n",
                    p.getPid(), p.getName(), p.getPriority(), p.getRemainingTime(),
                    p.getMemoryRequiredMB(), p.getState(), p.getNodeId(),
                    p.getHeldResources().isEmpty() ? "-" : p.getHeldResources(),
                    p.getWaitingForResource() == null ? "-" : p.getWaitingForResource());
        }
    }

    private void killProcess() {
        System.out.println("\n-- Kill Process --");
        showProcesses();
        int pid = promptInt("  PID to kill: ");
        os.killProcess(pid);    // Validator checks PID
    }

    private void migrateProcess() {
        System.out.println("\n-- Migrate Process --");
        showProcesses();
        int pid    = promptInt("  PID to migrate: ");
        showNodes();
        int target = promptInt("  Target node ID: ");
        boolean ok = os.migrateProcess(pid, target);  // Validator checks both
        System.out.println(ok ? "  Migration successful." : "  Migration failed (insufficient memory on target).");
    }

    // ── resources ─────────────────────────────────────────────────────────
    private void addResource() {
        System.out.println("\n-- Register Shared Resource --");
        String name      = prompt("  Resource name (1-20 chars, no spaces): ");
        int    instances = promptInt("  Total instances [1-32]: ");
        os.addResource(name, instances);  // Validator runs inside here
    }

    private void requestResource() {
        System.out.println("\n-- Request Resource --");
        showProcesses();
        int    pid   = promptInt("  PID: ");
        showResources();
        String name  = prompt("  Resource name: ");
        int    count = promptInt("  Instances requested: ");
        boolean granted = os.requestResource(pid, name, count);  // Validator runs inside here
        System.out.println(granted ? "  Granted." : "  Queued — process is now WAITING.");
    }

    private void releaseResource() {
        System.out.println("\n-- Release Resource --");
        showProcesses();
        int    pid  = promptInt("  PID: ");
        String name = prompt("  Resource name: ");
        os.releaseResource(pid, name);
    }

    private void showResources() {
        System.out.println();
        System.out.printf("%-14s  %-7s  %-10s  %-20s  %s%n",
                "Resource", "Total", "Available", "Held By (PIDs)", "Wait Queue");
        System.out.println("  " + "-".repeat(68));
        for (Resource r : os.getResourceManager().getResources().values()) {
            System.out.printf("%-14s  %-7d  %-10d  %-20s  %s%n",
                    r.getName(), r.getTotalInstances(), r.getAvailableInstances(),
                    r.getHolders().isEmpty()   ? "-" : r.getHolders().toString(),
                    r.getWaitQueue().isEmpty() ? "-" : r.getWaitQueue().toString());
        }
    }

    // ── misc ──────────────────────────────────────────────────────────────
    private void runDeadlockDetection() {
        List<Integer> cycle = os.detectDeadlockOnce();
        if (!cycle.isEmpty())
            System.out.println("  Deadlock cycle: " + cycle);
    }

    private void toggleBackgroundServices() {
        if (os.isBackgroundServicesRunning()) os.stopBackgroundServices();
        else                                  os.startBackgroundServices();
    }

    private void toggleAutoResolve() {
        os.setAutoResolveDeadlocks(!os.isAutoResolveDeadlocks());
        System.out.println("  Auto-resolve deadlocks: "
                + (os.isAutoResolveDeadlocks() ? "ON" : "OFF"));
    }

    // ── input helpers ─────────────────────────────────────────────────────
    private String prompt(String msg) {
        System.out.print(msg);
        return scanner.nextLine();
    }

    private int promptInt(String msg) {
        while (true) {
            System.out.print(msg);
            String line = scanner.nextLine().trim();
            try {
                return Integer.parseInt(line);
            } catch (NumberFormatException e) {
                System.out.println("  [!] Please enter a whole number.");
            }
        }
    }
}
