

import java.util.List;
import java.util.Map;
import java.util.Scanner;

/**
 * Text-based menu driven console for interacting with the distributed OS
 * simulation: create nodes/processes, share resources, migrate work,
 * trigger load-balancing / deadlock detection, and simulate failures.
 */
public class ConsoleUI {

    private final DistributedOperatingSystem os = new DistributedOperatingSystem();
    private final Scanner scanner = new Scanner(System.in);

    public void run() {
        printBanner();
        seedDemoCluster();

        boolean exit = false;
        while (!exit) {
            printMenu();
            String choice = prompt("Choose an option: ");
            try {
                switch (choice.trim()) {
                    case "1": addNode(); break;
                    case "2": createProcess(); break;
                    case "3": showNodes(); break;
                    case "4": showProcesses(); break;
                    case "5": killProcess(); break;
                    case "6": migrateProcess(); break;
                    case "7": addResource(); break;
                    case "8": requestResource(); break;
                    case "9": releaseResource(); break;
                    case "10": showResources(); break;
                    case "11": os.balanceLoadOnce(); break;
                    case "12": runDeadlockDetection(); break;
                    case "13": failNode(); break;
                    case "14": recoverNode(); break;
                    case "15": toggleBackgroundServices(); break;
                    case "16": toggleAutoResolve(); break;
                    case "0": exit = true; break;
                    default: System.out.println("Invalid option."); break;
                }
            } catch (Exception e) {
                System.out.println("Error: " + e.getMessage());
            }
        }
        os.shutdown();
        System.out.println("Distributed OS simulation shut down. Goodbye!");
    }

    private void printBanner() {
        System.out.println("==================================================================");
        System.out.println("        DISTRIBUTED OPERATING SYSTEM SIMULATOR (Core Java)");
        System.out.println("  Scheduling | Memory | Resource Sharing | Load Balancing |");
        System.out.println("  Deadlock Detection | Failure Recovery | Process Migration");
        System.out.println("==================================================================");
    }

    private void seedDemoCluster() {
        os.addNode("NodeA", 4, 64);
        os.addNode("NodeB", 4, 64);
        os.addNode("NodeC", 2, 32);
        os.addResource("Printer", 1);
        os.addResource("Database", 2);
        os.addResource("Scanner", 1);
        System.out.println("(A demo cluster of 3 nodes and 3 shared resources has been created for you.)\n");
    }

    private void printMenu() {
        System.out.println("\n------------------------------ MENU ------------------------------");
        System.out.println(" 1  Add virtual node");
        System.out.println(" 2  Create process");
        System.out.println(" 3  Show node status");
        System.out.println(" 4  Show process table");
        System.out.println(" 5  Kill process");
        System.out.println(" 6  Migrate process to another node");
        System.out.println(" 7  Register shared resource");
        System.out.println(" 8  Request resource for a process");
        System.out.println(" 9  Release resource from a process");
        System.out.println("10  Show resource status");
        System.out.println("11  Run load balancer once");
        System.out.println("12  Run deadlock detection once");
        System.out.println("13  Simulate node FAILURE");
        System.out.println("14  Recover a failed node");
        System.out.println("15  Start/Stop background services (auto LB + deadlock check)");
        System.out.println("16  Toggle auto-resolve on deadlock (" + (os.isAutoResolveDeadlocks() ? "ON" : "OFF") + ")");
        System.out.println(" 0  Exit");
        System.out.println("--------------------------------------------------------------------");
    }

    // -------------------------------------------------------------- nodes --
    private void addNode() {
        String name = prompt("Node name: ");
        int cores = promptInt("CPU cores: ");
        int mem = promptInt("Memory (MB): ");
        os.addNode(name, cores, mem);
    }

    private void showNodes() {
        System.out.printf("%-4s %-10s %-6s %-8s %-14s %-10s %-6s%n",
                "ID", "Name", "Status", "Cores", "Memory(used/tot)", "Load", "Queue");
        for (VirtualNode n : os.getNodes().values()) {
            System.out.printf("%-4d %-10s %-6s %-8d %-14s %-10d %-6d%n",
                    n.getId(), n.getName(), n.getStatus(), n.getCpuCores(),
                    n.getMemoryManager().getUsedMB() + "/" + n.getMemoryManager().getTotalMB() + "MB",
                    n.getLoad(), n.getReadyQueue().size());
        }
    }

    private void failNode() {
        int id = promptInt("Node ID to fail: ");
        os.failNode(id);
    }

    private void recoverNode() {
        int id = promptInt("Node ID to recover: ");
        os.recoverNode(id);
    }

    // ---------------------------------------------------------- processes --
    private void createProcess() {
        String name = prompt("Process name: ");
        int priority = promptInt("Priority (1=highest .. 10=lowest): ");
        int burst = promptInt("Burst time (CPU units): ");
        int mem = promptInt("Memory required (MB): ");
        os.createProcess(name, priority, burst, mem);
    }

    private void showProcesses() {
        if (os.getAllProcesses().isEmpty()) {
            System.out.println("No active processes.");
            return;
        }
        for (Process p : os.getAllProcesses().values()) {
            System.out.println(p);
        }
    }

    private void killProcess() {
        int pid = promptInt("PID to kill: ");
        if (!os.killProcess(pid)) System.out.println("No such process.");
    }

    private void migrateProcess() {
        int pid = promptInt("PID to migrate: ");
        int target = promptInt("Target node ID: ");
        boolean ok = os.migrateProcess(pid, target);
        System.out.println(ok ? "Migration successful." : "Migration failed.");
    }

    // ----------------------------------------------------------- resources --
    private void addResource() {
        String name = prompt("Resource name: ");
        int instances = promptInt("Total instances: ");
        os.addResource(name, instances);
    }

    private void requestResource() {
        int pid = promptInt("PID: ");
        String name = prompt("Resource name: ");
        int count = promptInt("Instances requested: ");
        boolean granted = os.requestResource(pid, name, count);
        System.out.println(granted ? "Granted." : "Not available; process is now WAITING.");
    }

    private void releaseResource() {
        int pid = promptInt("PID: ");
        String name = prompt("Resource name: ");
        os.releaseResource(pid, name);
    }

    private void showResources() {
        System.out.printf("%-12s %-10s %-10s %-20s %-15s%n", "Resource", "Total", "Available", "Held By (pid:count)", "Wait Queue");
        for (Resource r : os.getResourceManager().getResources().values()) {
            System.out.printf("%-12s %-10d %-10d %-20s %-15s%n",
                    r.getName(), r.getTotalInstances(), r.getAvailableInstances(),
                    r.getHolders().isEmpty() ? "-" : r.getHolders().toString(),
                    r.getWaitQueue().isEmpty() ? "-" : r.getWaitQueue().toString());
        }
    }

    // ------------------------------------------------------------- misc ----
    private void runDeadlockDetection() {
        List<Integer> cycle = os.detectDeadlockOnce();
        if (!cycle.isEmpty()) {
            System.out.println("Cycle: " + cycle);
        }
    }

    private void toggleBackgroundServices() {
        if (os.isBackgroundServicesRunning()) os.stopBackgroundServices();
        else os.startBackgroundServices();
    }

    private void toggleAutoResolve() {
        os.setAutoResolveDeadlocks(!os.isAutoResolveDeadlocks());
        System.out.println("Auto-resolve deadlocks is now " + (os.isAutoResolveDeadlocks() ? "ON" : "OFF"));
    }

    // ------------------------------------------------------------ helpers --
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
                System.out.println("Please enter a valid integer.");
            }
        }
    }
}
