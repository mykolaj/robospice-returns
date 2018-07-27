package com.octo.android.robospice.request

import android.content.Context
import com.octo.android.robospice.exception.NetworkException
import com.octo.android.robospice.exception.NoNetworkException
import com.octo.android.robospice.networkstate.NetworkStateChecker
import com.octo.android.robospice.persistence.CacheManager
import com.octo.android.robospice.persistence.DurationInMillis
import com.octo.android.robospice.persistence.exception.CacheCreationException
import com.octo.android.robospice.persistence.exception.CacheLoadingException
import com.octo.android.robospice.persistence.exception.CacheSavingException
import com.octo.android.robospice.persistence.exception.SpiceException
import com.octo.android.robospice.priority.PriorityRunnable
import com.octo.android.robospice.request.listener.RequestStatus
import roboguice.util.temp.Ln
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.locks.ReentrantLock

/**
 * Default implementation of {@link RequestRunner }. Processes requests. This class is massively multi-threaded and offers good
 * performances when processing multiple requests simulaneously.
 * @author SNI
 * @author Andrew Clark
 */
open class DefaultRequestRunner : RequestRunner {

    @Suppress("ConvertSecondaryConstructorToPrimary")
    constructor (context: Context, cacheManager: CacheManager, executorService: ExecutorService, requestProgressBroadcaster: RequestProgressManager,
                 networkStateChecker: NetworkStateChecker) {
        this.applicationContext = context
        this.cacheManager = cacheManager
        this.networkStateChecker = networkStateChecker
        this.executorLock = ReentrantLock()
        this.executorService = executorService
        this.requestProgressManager = requestProgressBroadcaster
        this.networkStateChecker.checkPermissions(context)
    }

    /**
     * Thanks Olivier Croiser from Zenika for his excellent <a href=
     * "http://blog.zenika.com/index.php?post/2012/04/11/Introduction-programmation-concurrente-Java-2sur2. "
     * >blog article</a>.
     */
    private val executorService: ExecutorService
    private val cacheManager: CacheManager
    private val applicationContext: Context
    private var shouldFailOnCacheError = false
    private val networkStateChecker: NetworkStateChecker
    private val requestProgressManager: RequestProgressManager
    private var isStopped = false
    private val executorLock: ReentrantLock

    override fun isFailOnCacheError(): Boolean = shouldFailOnCacheError
    override fun setFailOnCacheError(f: Boolean) {
        shouldFailOnCacheError = f
    }

    override fun executeRequest(request: CachedSpiceRequest<*>) {
        executorLock.lock()
        try {
            if (isStopped) {
                Ln.d("Dropping request : $request as runner is stopped.")
                return
            }
            planRequestExecution(request)
        } finally {
            executorLock.unlock()
        }
    }

    protected open fun <T> processRequest(request: CachedSpiceRequest<T>) {
        val startTime = System.currentTimeMillis()
        Ln.d("Processing request : $request")
        var result: T
        // add a progress listener to the request to be notified of
        // progress during load data from network
        val requestProgressListener = requestProgressManager.createProgressListener(request)
        request.setRequestProgressListener(requestProgressListener)
        if (request.requestCacheKey != null && request.cacheDuration != DurationInMillis.ALWAYS_EXPIRED) {
            // First, search data in cache
            try {
                Ln.d("Loading request from cache : $request")
                request.setStatus(RequestStatus.READING_FROM_CACHE)
                result = loadDataFromCache(request.resultType, request.requestCacheKey, request.cacheDuration)
                // if something is found in cache, fire result and finish
                // request
                if (result != null) {
                    Ln.d("Request loaded from cache : $request result=$result")
                    requestProgressManager.notifyListenersOfRequestSuccess(request, result)
                    printRequestProcessingDuration(startTime, request)
                    return
                } else if (request.isAcceptingDirtyCache) {
                    // as a fallback, some request may accept whatever is in the
                    // cache but still
                    // want an update from network.
                    result = loadDataFromCache(request.resultType, request.requestCacheKey, DurationInMillis.ALWAYS_RETURNED)
                    if (result != null) {
                        requestProgressManager.notifyListenersOfRequestSuccessButDontCompleteRequest(request, result)
                    }
                }
            } catch (e: SpiceException) {
                Ln.d(e, "Cache file could not be read.")
                if (isFailOnCacheError) {
                    handleRetry(request, e)
                    printRequestProcessingDuration(startTime, request)
                    return
                }
                cacheManager.removeDataFromCache(request.resultType, request.requestCacheKey)
                Ln.d(e, "Cache file deleted.")
            }
        }
        // if result is not in cache, load data from network
        Ln.d("Cache content not available or expired or disabled")
        if (!networkStateChecker.isNetworkAvailable(applicationContext) && !request.isOffline) {
            Ln.e("Network is down.")
            if (!request.isCancelled) {
                // don't retry when there is no network
                requestProgressManager.notifyListenersOfRequestFailure(request, NoNetworkException())
            }
            printRequestProcessingDuration(startTime, request)
            return
        }
        // network is ok, load data from network
        try {
            if (request.isCancelled) {
                printRequestProcessingDuration(startTime, request)
                return
            }
            Ln.d("Calling network request.")
            request.setStatus(RequestStatus.LOADING_FROM_NETWORK)
            result = request.loadDataFromNetwork()
            Ln.d("Network request call ended.")
        } catch (e: Exception) {
            if (!request.isCancelled) {
                Ln.e(e, "An exception occurred during request network execution :${e.message}")
                handleRetry(request, NetworkException("Exception occurred during invocation of web service.", e))
            } else {
                Ln.e("An exception occurred during request network execution but request was cancelled, so listeners are not called.")
            }
            printRequestProcessingDuration(startTime, request)
            return
        }
        if (result != null && request.requestCacheKey != null) {
            // request worked and result is not null, save
            // it to cache
            try {
                if (request.isCancelled) {
                    printRequestProcessingDuration(startTime, request)
                    return
                }
                Ln.d("Start caching content...")
                request.setStatus(RequestStatus.WRITING_TO_CACHE)
                result = saveDataToCacheAndReturnData(result, request.requestCacheKey)
                if (request.isCancelled) {
                    printRequestProcessingDuration(startTime, request)
                    return
                }
                requestProgressManager.notifyListenersOfRequestSuccess(request, result)
                printRequestProcessingDuration(startTime, request)
                return
            } catch (e: SpiceException) {
                Ln.d(e, "An exception occurred during service execution : ${e.message}")
                if (isFailOnCacheError) {
                    handleRetry(request, e)
                    printRequestProcessingDuration(startTime, request)
                    return
                } else {
                    if (request.isCancelled) {
                        printRequestProcessingDuration(startTime, request)
                        return
                    }
                    // Result can't be saved to
                    // cache but we reached that
                    // point after a success of load
                    // data from network
                    requestProgressManager.notifyListenersOfRequestSuccess(request, result)
                }
                cacheManager.removeDataFromCache(request.resultType, request.requestCacheKey)
                Ln.d(e, "Cache file deleted.")
            }
        } else {
            // result can't be saved to cache but we reached
            // that point after a success of load data from
            // network
            requestProgressManager.notifyListenersOfRequestSuccess(request, result)
            printRequestProcessingDuration(startTime, request)
            return
        }
    }

    protected fun planRequestExecution(request: CachedSpiceRequest<*>) {
        val future = executorService.submit(object : PriorityRunnable {
            override fun getPriority(): Int = request.priority

            override fun run() {
                try {
                    processRequest(request)
                } catch (t: Throwable) {
                    Ln.d(t, "An unexpected error occurred when processsing request %s", request.toString())
                } finally {
                    request.setRequestCancellationListener(null)
                }
            }
        })
        request.setFuture(future)
    }

    override fun shouldStop() {
        executorLock.lock()
        try {
            isStopped = true
            executorService.shutdown()
        } finally {
            executorLock.unlock()
        }
    }

    @Throws(CacheLoadingException::class, CacheCreationException::class)
    private fun <T> loadDataFromCache(clazz: Class<T>, cacheKey: Any, maxTimeInCacheBeforeExpiry: Long): T {
        return cacheManager.loadDataFromCache(clazz, cacheKey, maxTimeInCacheBeforeExpiry)
    }

    @Throws(CacheSavingException::class, CacheCreationException::class)
    private fun <T> saveDataToCacheAndReturnData(data: T, cacheKey: Any): T {
        return cacheManager.saveDataToCacheAndReturnData(data, cacheKey)
    }

    private fun handleRetry(request: CachedSpiceRequest<*>, e: SpiceException) {
        val retryPolicy = request.retryPolicy
        if (retryPolicy != null) {
            retryPolicy.retry(e)
            if (retryPolicy.retryCount > 0) {
                Thread {
                    try {
                        Thread.sleep(request.retryPolicy.delayBeforeRetry)
                        executeRequest(request)
                    } catch (e: InterruptedException) {
                        Ln.e(e, "Retry attempt failed for request $request")
                    }
                }.start()
                return
            }
        }
        requestProgressManager.notifyListenersOfRequestFailure(request, e)
    }

    private fun getTimeString(millis: Long): String {
        return java.lang.String.format(Locale.US, "%02d ms", millis)
    }

    private fun printRequestProcessingDuration(startTime: Long, request: CachedSpiceRequest<*>) {
        Ln.d("It took %s to process request %s.", getTimeString(System.currentTimeMillis() - startTime), request.toString())
    }
}