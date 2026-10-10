package xyz.aezdek.aezprint.data.server

import kotlinx.coroutines.flow.first
import xyz.aezdek.aezprint.data.fake.FakePrintServer
import xyz.aezdek.aezprint.data.network.RemotePrintServer
import xyz.aezdek.aezprint.domain.model.LocalDocument
import xyz.aezdek.aezprint.domain.model.PrintOptions
import xyz.aezdek.aezprint.domain.repository.PrintServer
import xyz.aezdek.aezprint.domain.repository.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/** Routes every call to the fake or the real server according to the debug toggle in Settings. */
@Singleton
class SwitchingPrintServer @Inject constructor(
    private val settings: SettingsRepository,
    private val fake: FakePrintServer,
    private val remote: RemotePrintServer,
) : PrintServer {

    private suspend fun target(): PrintServer = if (settings.settings.first().useFakeServer) fake else remote

    override suspend fun printer() = target().printer()
    override suspend fun upload(document: LocalDocument, onProgress: (Float) -> Unit) = target().upload(document, onProgress)
    override suspend fun render(documentId: String, options: PrintOptions) = target().render(documentId, options)
    override suspend fun sheet(renderId: String, face: Int, widthPx: Int) = target().sheet(renderId, face, widthPx)
    override suspend fun submit(documentId: String, options: PrintOptions) = target().submit(documentId, options)
    override suspend fun job(jobId: String) = target().job(jobId)
    override suspend fun continueJob(jobId: String) = target().continueJob(jobId)
    override suspend fun cancel(jobId: String) = target().cancel(jobId)
    override suspend fun jobs(limit: Int) = target().jobs(limit)
}
