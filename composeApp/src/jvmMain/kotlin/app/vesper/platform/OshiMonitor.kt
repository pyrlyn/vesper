package app.vesper.platform

import app.vesper.app.MonitorSnapshot
import app.vesper.app.PortInfo
import app.vesper.app.ProcessInfo
import app.vesper.app.SystemMonitor
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import oshi.SystemInfo
import oshi.software.os.OSProcess

/** CPU, memory, processes and network via OSHI; ports via lsof/ss/netstat. */
class OshiMonitor : SystemMonitor {
    private val state = MutableStateFlow(MonitorSnapshot())
    override val snapshot: StateFlow<MonitorSnapshot> = state
    private var started = false

    override fun start(scope: CoroutineScope) {
        if (started) return
        started = true
        scope.launch(Dispatchers.IO) {
            val si = runCatching { SystemInfo() }.getOrNull() ?: return@launch
            val hal = si.hardware
            val cpu = hal.processor
            val mem = hal.memory
            val osys = si.operatingSystem
            var ticks = cpu.systemCpuLoadTicks
            var coreTicks = cpu.processorCpuLoadTicks
            var prevProcs: Map<Int, OSProcess> = emptyMap()
            var prevNet = netTotals(si)
            var prevTime = System.nanoTime()
            val cpuHistory = ArrayDeque<Double>()
            val netHistory = ArrayDeque<Double>()
            val logical = cpu.logicalProcessorCount
            while (isActive) {
                delay(2000)
                runCatching {
                    val total = cpu.getSystemCpuLoadBetweenTicks(ticks) * 100
                    ticks = cpu.systemCpuLoadTicks
                    val perCore = cpu.getProcessorCpuLoadBetweenTicks(coreTicks).map { it * 100 }
                    coreTicks = cpu.processorCpuLoadTicks
                    cpuHistory.addLast(total)
                    while (cpuHistory.size > 40) cpuHistory.removeFirst()

                    val procs = osys.getProcesses(null, null, 0)
                    val top = procs.map { p ->
                        val prev = prevProcs[p.processID]
                        val load = if (prev != null) p.getProcessCpuLoadBetweenTicks(prev) else 0.0
                        ProcessInfo(p.processID.toLong(), p.name, load * 100, p.residentSetSize)
                    }.sortedByDescending { it.cpu }.take(10)
                    prevProcs = procs.associateBy { it.processID }

                    val now = System.nanoTime()
                    val net = netTotals(si)
                    val dt = (now - prevTime) / 1e9
                    val down = ((net.first - prevNet.first) / dt).coerceAtLeast(0.0)
                    val up = ((net.second - prevNet.second) / dt).coerceAtLeast(0.0)
                    prevNet = net
                    prevTime = now
                    netHistory.addLast(down + up)
                    while (netHistory.size > 30) netHistory.removeFirst()

                    val vm = mem.virtualMemory
                    state.value = MonitorSnapshot(
                        cpuTotal = total,
                        cpuHistory = cpuHistory.toList(),
                        perCore = perCore,
                        coreCount = logical,
                        memUsed = mem.total - mem.available,
                        memCached = linuxCached(),
                        memTotal = mem.total,
                        swapUsed = vm.swapUsed,
                        processes = top,
                        netDownBps = down,
                        netUpBps = up,
                        netHistory = netHistory.toList(),
                    )
                }
            }
        }
    }

    private fun netTotals(si: SystemInfo): Pair<Long, Long> {
        var rx = 0L
        var tx = 0L
        for (nif in si.hardware.getNetworkIFs(false)) {
            if (nif.name.startsWith("lo")) continue
            nif.updateAttributes()
            rx += nif.bytesRecv
            tx += nif.bytesSent
        }
        return rx to tx
    }

    private fun linuxCached(): Long {
        if (HostOs.current != HostOs.Linux) return 0
        return runCatching {
            File("/proc/meminfo").readLines().firstOrNull { it.startsWith("Cached:") }
                ?.split(Regex("\\s+"))?.getOrNull(1)?.toLong()?.times(1024) ?: 0
        }.getOrDefault(0)
    }

    override suspend fun listeningPorts(): List<PortInfo> = when (HostOs.current) {
        HostOs.Windows -> netstat()
        else -> lsof() ?: ss() ?: emptyList()
    }.distinctBy { it.port to it.pid }.sortedBy { it.port }

    private fun exec(vararg cmd: String): String? = runCatching {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        p.outputStream.close()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(3, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            null
        } else {
            out
        }
    }.getOrNull()

    private fun lsof(): List<PortInfo>? {
        val exe = listOf("/usr/sbin/lsof", "/usr/bin/lsof", "/bin/lsof").firstOrNull { File(it).canExecute() } ?: return null
        val out = exec(exe, "-nP", "-iTCP", "-sTCP:LISTEN", "-F", "pcn") ?: return null
        val result = ArrayList<PortInfo>()
        var pid = 0L
        var name = ""
        for (line in out.lineSequence()) {
            if (line.isEmpty()) continue
            when (line[0]) {
                'p' -> pid = line.substring(1).toLongOrNull() ?: 0
                'c' -> name = line.substring(1)
                'n' -> {
                    val addr = line.substring(1)
                    val port = addr.substringAfterLast(':').toIntOrNull() ?: continue
                    result += PortInfo(port, "tcp", addr.substringBeforeLast(':'), pid, name)
                }
            }
        }
        return result
    }

    private fun ss(): List<PortInfo>? {
        val out = exec("ss", "-ltnpH") ?: return null
        return out.lineSequence().mapNotNull { line ->
            val cols = line.trim().split(Regex("\\s+"))
            val local = cols.getOrNull(3) ?: return@mapNotNull null
            val port = local.substringAfterLast(':').toIntOrNull() ?: return@mapNotNull null
            val users = cols.drop(5).joinToString(" ")
            val proc = Regex("\"([^\"]+)\",pid=(\\d+)").find(users)
            PortInfo(port, "tcp", local.substringBeforeLast(':'), proc?.groupValues?.get(2)?.toLong() ?: 0, proc?.groupValues?.get(1) ?: "?")
        }.toList()
    }

    private fun netstat(): List<PortInfo> {
        val out = exec("netstat", "-ano", "-p", "TCP") ?: return emptyList()
        val names = HashMap<Long, String>()
        return out.lineSequence().mapNotNull { line ->
            val cols = line.trim().split(Regex("\\s+"))
            if (cols.size < 5 || cols[3] != "LISTENING") return@mapNotNull null
            val local = cols[1]
            val port = local.substringAfterLast(':').toIntOrNull() ?: return@mapNotNull null
            val pid = cols[4].toLongOrNull() ?: 0
            val name = names.getOrPut(pid) { ProcessHandle.of(pid).flatMap { it.info().command() }.map { File(it).name }.orElse("pid $pid") }
            PortInfo(port, "tcp", local.substringBeforeLast(':'), pid, name)
        }.toList()
    }

    override fun kill(pid: Long): Boolean = ProcessHandle.of(pid).map { it.destroy() }.orElse(false)
}
