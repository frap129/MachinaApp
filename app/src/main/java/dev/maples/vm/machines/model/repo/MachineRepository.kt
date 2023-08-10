package dev.maples.vm.machines.model.repo

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import dev.maples.vm.machines.model.service.MachinaService
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber

class MachineRepository(private val context: Context) {
    private lateinit var mMachinaService: MachinaService
    private var mMachinaBound: Boolean = false

    private val mConsoleTextState: MutableState<String> = mutableStateOf("")
    private val mShellTextState: MutableState<String> = mutableStateOf("")
    val shellTextState: State<String> = mShellTextState
    val consoleTextState: State<String> = mConsoleTextState

    private val mMachinaServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as MachinaService.MachinaServiceBinder
            mMachinaService = binder.getService()
            mMachinaBound = true

            CoroutineScope(Dispatchers.IO).launch {
                val shell = FileInputStream(mMachinaService.mShellReader.fileDescriptor)
                Reader("shell", mShellTextState, shell).run()
            }

            CoroutineScope(Dispatchers.IO).launch {
                val console = FileInputStream(mMachinaService.mConsoleReader.fileDescriptor)
                Reader("console", mConsoleTextState, console).run()
            }
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            mMachinaBound = false
        }
    }

    init {
        // Start machina service on init
        Intent(context, MachinaService::class.java).also { intent ->
            context.bindService(
                intent,
                mMachinaServiceConnection,
                Context.BIND_AUTO_CREATE
            )
        }
    }

    fun startVirtualMachine() {
        if (mMachinaBound) {
            mMachinaService.startVirtualMachine()

            mMachinaService.setupNetworking(context)
        } else {
            // TODO: Properly handle UI State while machina is loading
            startVirtualMachine()
        }
    }

    fun stopVirtualMachine() {
        if (mMachinaBound) {
            mMachinaService.stopVirtualMachine()
            mConsoleTextState.value = ""
            mShellTextState.value = ""
        }
    }

    fun sendCommand(cmd: String) {
        if (mMachinaBound) {
            mMachinaService.sendCommand(cmd)
        }
    }

    internal class Reader(
        private val mName: String,
        private val mOutput: MutableState<String>,
        private val mStream: InputStream
    ) : Runnable {
        override fun run() {
            try {
                val reader = BufferedReader(InputStreamReader(mStream))
                var char: Char
                while (reader.read().also { char = it.toChar() } != -1 && !Thread.interrupted()) {
                    mOutput.value += char

                    // Handle clear
                    if (mOutput.value.contains("\u001B[H\u001B[J")) {
                        mOutput.value = ""
                    }
                }
            } catch (e: IOException) {
                Timber.d("Exception while posting " + mName + " output: " + e.message)
            }
        }
    }
}