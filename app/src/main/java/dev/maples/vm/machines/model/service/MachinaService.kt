package dev.maples.vm.machines.model.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.ServiceManager
import android.system.virtualizationservice.IVirtualMachine
import android.system.virtualizationservice.IVirtualMachineCallback
import android.system.virtualizationservice.IVirtualizationService
import dev.maples.vm.machines.model.data.RootVirtualMachine
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.lsposed.hiddenapibypass.HiddenApiBypass
import timber.log.Timber

class MachinaService : Service() {
    companion object {
        private const val NETWORK_SOCKET = "network.sock"
    }

    private val mBinder = MachinaServiceBinder()
    private lateinit var mVirtService: IVirtualizationService
    private var mRemoteShellManager: RemoteShellManager? = null

    var mVirtualMachine: IVirtualMachine? = null
    private val mConsoleWriter: ParcelFileDescriptor
    private val mLogWriter: ParcelFileDescriptor
    private val mShellWriter: ParcelFileDescriptor

    val mLogReader: ParcelFileDescriptor
    val mConsoleReader: ParcelFileDescriptor
    val mShellReader: ParcelFileDescriptor

    init {
        HiddenApiBypass.addHiddenApiExemptions("")
        System.loadLibrary("machina-jni")

        // Setup pipes
        var pipes: Array<ParcelFileDescriptor> = ParcelFileDescriptor.createPipe()
        mConsoleReader = pipes[0]
        mConsoleWriter = pipes[1]
        pipes = ParcelFileDescriptor.createPipe()
        mLogReader = pipes[0]
        mLogWriter = pipes[1]
        pipes = ParcelFileDescriptor.createPipe()
        mShellReader = pipes[0]
        mShellWriter = pipes[1]
    }

    private external fun proxyVsockToUnix(vsockFd: Int, unixSocket: String)

    private fun getVirtualizationService() {
        mVirtService = IVirtualizationService.Stub.asInterface(
            ServiceManager.waitForService("android.system.virtualizationservice")
        )
        Timber.d("Acquired virtualizationservice")
    }

    fun startVirtualMachine() {
        getVirtualizationService()
        val vmConfig = RootVirtualMachine.config

        mVirtualMachine = mVirtService.createVm(vmConfig, mConsoleWriter, mLogWriter)
        Timber.d("Created virtual machine: ${mVirtualMachine?.cid}")

        mVirtualMachine?.registerCallback(rootVMCallback)
        mVirtualMachine?.start()
        Timber.d("Started virtual machine: ${mVirtualMachine?.cid}")

        CoroutineScope(Dispatchers.IO).launch {
            // TODO: Figure out a way to do this without just hoping its alive after a delay
            // Wait for VM to boot
            delay(2500)
            try {
                mRemoteShellManager = RemoteShellManager(mVirtualMachine!!, mShellWriter)
                sendCommand("clear")
            } catch (e: Exception) {
                Timber.d(e)
            }
        }
    }

    fun stopVirtualMachine() {
        sendCommand("poweroff")
        mVirtualMachine = null
        mRemoteShellManager = null
    }

    fun setupNetworking(context: Context) {
        val netSock = File(filesDir, NETWORK_SOCKET)
        if (netSock.exists()) {
            netSock.delete()
        }

        CoroutineScope(Dispatchers.IO).launch {
            mVirtualMachine?.let {
                delay(2500)
                val networkVsock = it.connectVsock(3001)
                val gvproxy = File(context.applicationInfo.nativeLibraryDir, "libgvproxy-host.so")
                val gvproxyProcess = ProcessBuilder(
                    gvproxy.absolutePath,
                    "-debug",
                    "-listen",
                    "unix://${netSock.path}",
                    "-mtu",
                    "4000"
                ).directory(context.filesDir).redirectErrorStream(true).start()
                val error = BufferedReader(gvproxyProcess.errorStream.reader())
                val out = BufferedReader(gvproxyProcess.inputStream.reader())

                launch {
                    delay(1000)
                    proxyVsockToUnix(networkVsock.fd, netSock.path)
                }
                while (gvproxyProcess.isAlive) {
                    Timber.d(error.read().toChar().toString())
                    Timber.d(out.readLine())
                }
                Timber.d("dead")
            }
        }
    }

    fun sendCommand(cmd: String) {
        mRemoteShellManager?.apply {
            var msg = cmd
            if (cmd.isBlank()) return
            if (cmd.last() != '\n') {
                msg += '\n'
            }

            write(msg)
        }
    }

    private class RemoteShellManager(
        virtualMachine: IVirtualMachine,
        shellWriter: ParcelFileDescriptor
    ) {
        companion object {
            const val READ_PORT = 5000
            const val WRITE_PORT = 3000
            private const val PROMPT_END = " # "
        }

        private val scope = CoroutineScope(Dispatchers.IO)
        private val writeVsock = virtualMachine.connectVsock(WRITE_PORT)
        private val readVsock = virtualMachine.connectVsock(READ_PORT)
        private val vsockReader =
            FileInputStream(readVsock.fileDescriptor).bufferedReader(Charsets.UTF_8)
        private val vsockStream = FileOutputStream(writeVsock.fileDescriptor)

        val shellStream = FileOutputStream(shellWriter.fileDescriptor)
        var state: State = State.Starting

        init {
            // Start reading
            scope.launch { readVsock() }
        }

        fun write(cmd: String) {
            state = State.Running.Writing
            shellStream.write(cmd.toByteArray())
            vsockStream.write(cmd.toByteArray())
        }

        private fun readVsock() {
            var line = ""
            while (readVsock.fileDescriptor.valid()) {
                // Set polling rate
                // delay(10)
                state = State.Running.Reading

                // Try to read from vsock
                val byte = try {
                    vsockReader.read()
                } catch (e: Exception) {
                    Timber.e(e)
                    state = State.Dead
                    return
                }

                // Ignore end of stream
                if (byte != -1) {
                    shellStream.write(byte)
                    line += byte.toChar()

                    // Log once we reach EOL or prompt
                    if (byte.toChar() == '\n' || line.endsWith(PROMPT_END)) {
                        Timber.d(line)
                        line = ""
                    }
                }
            }
        }

        sealed class State {
            object Starting : State()
            sealed class Running : State() {
                object Reading : Running()
                object Writing : Running()
            }

            object Dead : State()
        }
    }

    private val rootVMCallback = object : IVirtualMachineCallback.Stub() {
        override fun onError(cid: Int, errorCode: Int, message: String?) {
            Timber.d("CID $cid error $errorCode: $message")
        }

        override fun onDied(cid: Int, reason: Int) {
            Timber.d("CID $cid died: $reason")
        }

        // No-op for custom VMs
        override fun onPayloadStarted(cid: Int, stream: ParcelFileDescriptor?) {}
        override fun onPayloadReady(cid: Int) {}
        override fun onPayloadFinished(cid: Int, exitCode: Int) {}
    }

    override fun onBind(intent: Intent): IBinder = mBinder
    inner class MachinaServiceBinder : Binder() {
        fun getService(): MachinaService = this@MachinaService
    }
}