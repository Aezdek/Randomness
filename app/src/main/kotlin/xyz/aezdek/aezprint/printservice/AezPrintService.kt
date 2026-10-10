package xyz.aezdek.aezprint.printservice

import android.net.Uri
import android.printservice.PrintJob
import android.printservice.PrintService
import android.printservice.PrinterDiscoverySession
import android.print.PrinterId
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import xyz.aezdek.aezprint.R
import xyz.aezdek.aezprint.domain.model.PrintOptions
import xyz.aezdek.aezprint.domain.repository.DocumentRepository
import xyz.aezdek.aezprint.domain.repository.PresetRepository
import xyz.aezdek.aezprint.domain.repository.PrintQueue
import xyz.aezdek.aezprint.domain.repository.PrintServer
import java.io.File
import java.io.FileInputStream
import javax.inject.Inject

/**
 * Makes the home printer appear in every app's system print dialog. Jobs are handed to the same
 * durable queue the app uses, so progress and the flip prompt show up as AezPrint notifications.
 */
@AndroidEntryPoint
class AezPrintService : PrintService() {

    @Inject lateinit var server: PrintServer
    @Inject lateinit var documents: DocumentRepository
    @Inject lateinit var presets: PresetRepository
    @Inject lateinit var queue: PrintQueue
    @Inject lateinit var json: Json

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreatePrinterDiscoverySession(): PrinterDiscoverySession = Discovery()

    override fun onRequestCancelPrintJob(printJob: PrintJob) {
        printJob.cancel()
    }

    override fun onPrintJobQueued(printJob: PrintJob) {
        scope.launch {
            try {
                printJob.start()
                val info = printJob.info
                val name = printJob.document.info.name.let { if (it.endsWith(".pdf", true)) it else "$it.pdf" }
                val file = withContext(Dispatchers.IO) {
                    val target = File(cacheDir, "spool-${info.id.hashCode()}-${System.nanoTime()}/$name").apply { parentFile?.mkdirs() }
                    printJob.document.data!!.use { descriptor ->
                        FileInputStream(descriptor.fileDescriptor).use { input -> target.outputStream().use { input.copyTo(it) } }
                    }
                    target
                }
                val document = documents.import(Uri.fromFile(file).toString())
                withContext(Dispatchers.IO) { file.parentFile?.deleteRecursively() }

                val advanced = printJob.getAdvancedStringOption(SystemPrintMapping.OPTIONS_KEY)
                    ?.let { runCatching { json.decodeFromString(PrintOptions.serializer(), it) }.getOrNull() }
                val base = advanced ?: presets.library.first().default?.options ?: PrintOptions()
                val options = SystemPrintMapping.merge(base, info.attributes, info.copies, info.pages)
                queue.enqueue(document, options)
                printJob.complete()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                printJob.fail(e.message ?: "AezPrint couldn't take this job")
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private inner class Discovery : PrinterDiscoverySession() {
        private val printerId: PrinterId = generatePrinterId(PRINTER_LOCAL_ID)

        override fun onStartPrinterDiscovery(priorityList: MutableList<PrinterId>) = refresh()
        override fun onStopPrinterDiscovery() = Unit
        override fun onValidatePrinters(printerIds: MutableList<PrinterId>) = Unit
        override fun onStartPrinterStateTracking(printerId: PrinterId) = refresh()
        override fun onStopPrinterStateTracking(printerId: PrinterId) = Unit
        override fun onDestroy() = Unit

        private fun refresh() {
            scope.launch {
                val printer = try {
                    server.printer()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                if (!isDestroyed) {
                    addPrinters(listOf(SystemPrintMapping.printerInfo(printerId, printer, getString(R.string.printer_description))))
                }
            }
        }
    }

    private companion object {
        const val PRINTER_LOCAL_ID = "aezprint-home"
    }
}
