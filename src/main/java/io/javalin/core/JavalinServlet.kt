/*
 * Javalin - https://javalin.io
 * Copyright 2017 David Åse
 * Licensed under Apache 2.0: https://github.com/tipsy/javalin/blob/master/LICENSE
 */

package io.javalin.core

import io.javalin.Context
import io.javalin.MethodNotAllowedResponse
import io.javalin.NotFoundResponse
import io.javalin.RequestLogger
import io.javalin.core.util.*
import io.javalin.staticfiles.JettyResourceHandler
import java.io.InputStream
import java.util.zip.GZIPOutputStream
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

class JavalinServlet(
        val matcher: PathMatcher,
        val exceptionMapper: ExceptionMapper,
        val errorMapper: ErrorMapper,
        val debugLogging: Boolean,
        val requestLogger: RequestLogger?,
        val dynamicGzipEnabled: Boolean,
        val autogeneratedEtagsEnabled: Boolean,
        val defaultContentType: String,
        val maxRequestCacheBodySize: Long,
        val prefer405over404: Boolean,
        val caseSensitiveUrls: Boolean,
        val singlePageHandler: SinglePageHandler,
        val jettyResourceHandler: JettyResourceHandler) {

    fun service(servletRequest: HttpServletRequest, res: HttpServletResponse) {

        val req = CachedRequestWrapper(servletRequest, maxRequestCacheBodySize) // cached for reading multiple times
        val type = HandlerType.fromServletRequest(req)
        val requestUri = req.requestURI.let { if (caseSensitiveUrls) it else it.toLowerCase() }
        val ctx = Context(res, req)

        fun tryWithExceptionMapper(func: () -> Unit) = exceptionMapper.catchException(ctx, func)

        fun tryBeforeAndEndpointHandlers() = tryWithExceptionMapper {
            matcher.findEntries(HandlerType.BEFORE, requestUri).forEach { entry ->
                entry.handler.handle(ContextUtil.update(ctx, entry, requestUri))
            }
            matcher.findEntries(type, requestUri).forEach { entry ->
                entry.handler.handle(ContextUtil.update(ctx, entry, requestUri))
                return@tryWithExceptionMapper // return after first match
            }
            if (type == HandlerType.HEAD && hasGetHandlerMapped(requestUri)) {
                return@tryWithExceptionMapper // return 200, there is a get handler
            }
            if (type == HandlerType.HEAD || type == HandlerType.GET) { // let Jetty check for static resources
                if (jettyResourceHandler.handle(req, res)) return@tryWithExceptionMapper
                if (singlePageHandler.handle(ctx)) return@tryWithExceptionMapper
            }
            val availableHandlerTypes = MethodNotAllowedUtil.findAvailableHttpHandlerTypes(matcher, requestUri)
            if (prefer405over404 && availableHandlerTypes.isNotEmpty()) {
                throw MethodNotAllowedResponse(details = MethodNotAllowedUtil.getAvailableHandlerTypes(ctx, availableHandlerTypes))
            }
            throw NotFoundResponse()
        }

        fun tryErrorHandlers() = tryWithExceptionMapper {
            errorMapper.handle(ctx.status(), ctx)
        }

        fun tryAfterHandlers() = tryWithExceptionMapper {
            matcher.findEntries(HandlerType.AFTER, requestUri).forEach { entry ->
                entry.handler.handle(ContextUtil.update(ctx, entry, requestUri))
            }
        }

        fun writeResult(res: HttpServletResponse) { // can be sync or async
            if (res.isCommitted || ctx.resultStream() == null) return // nothing to write
            val resultStream = ctx.resultStream()!!
            if (res.getHeader(Header.ETAG) != null || (autogeneratedEtagsEnabled && type == HandlerType.GET)) {
                val serverEtag = res.getHeader(Header.ETAG) ?: Util.getChecksumAndReset(resultStream) // calculate if not set
                res.setHeader(Header.ETAG, serverEtag)
                if (serverEtag == req.getHeader(Header.IF_NONE_MATCH)) {
                    res.status = 304
                    return // don't write body
                }
            }
            if (gzipShouldBeDone(ctx)) {
                GZIPOutputStream(res.outputStream, true).use { gzippedStream ->
                    res.setHeader(Header.CONTENT_ENCODING, "gzip")
                    resultStream.copyTo(gzippedStream)
                }
                resultStream.close()
                return
            }
            resultStream.copyTo(res.outputStream) // no gzip
            resultStream.close()
        }

        fun logRequest() {
            if (requestLogger != null) {
                requestLogger.handle(ctx, LogUtil.executionTimeMs(ctx))
            } else if (debugLogging == true) {
                LogUtil.logRequestAndResponse(ctx, matcher)
            }
        }

        LogUtil.startTimer(ctx) // start request lifecycle
        ctx.header(Header.SERVER, "Javalin")
        ctx.contentType(defaultContentType)
        tryBeforeAndEndpointHandlers()
        if (ctx.resultFuture() == null) { // finish request synchronously
            tryErrorHandlers()
            tryAfterHandlers()
            writeResult(res)
            logRequest()
            return // sync lifecycle complete
        } else { // finish request asynchronously
            val asyncContext = req.startAsync()
            ctx.resultFuture()!!.exceptionally { throwable ->
                if (throwable is Exception) {
                    exceptionMapper.handle(throwable, ctx)
                }
                null
            }.thenAccept {
                when (it) {
                    is InputStream -> ctx.result(it)
                    is String -> ctx.result(it)
                }
                tryErrorHandlers()
                tryAfterHandlers()
                writeResult(asyncContext.response as HttpServletResponse)
                logRequest()
                asyncContext.complete() // async lifecycle complete
            }
        }
    }

    private fun hasGetHandlerMapped(requestUri: String) = matcher.findEntries(HandlerType.GET, requestUri).isNotEmpty()

    private fun gzipShouldBeDone(ctx: Context) = dynamicGzipEnabled
            && ctx.resultStream()?.available() ?: 0 > 1500 // mtu is apparently ~1500 bytes
            && (ctx.header(Header.ACCEPT_ENCODING) ?: "").contains("gzip", ignoreCase = true)
}
