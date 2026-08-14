package dev.sixbridge.manager.conversion

import java.util.concurrent.CancellationException

internal sealed class ConversionDomainException(cause: Throwable) : RuntimeException(cause)

internal class ConversionIdentityException(cause: Throwable) : ConversionDomainException(cause)

internal class ConversionKeyStoreException(cause: Throwable) : ConversionDomainException(cause)

internal class ConversionPackageException(cause: Throwable) : ConversionDomainException(cause)

internal class ConversionBundleException(cause: Throwable) : ConversionDomainException(cause)

internal inline fun <T> withConversionIdentityBoundary(block: () -> T): T =
    withConversionBoundary(::ConversionIdentityException, block)

internal inline fun <T> withConversionKeyStoreBoundary(block: () -> T): T =
    withConversionBoundary(::ConversionKeyStoreException, block)

internal inline fun <T> withConversionPackageBoundary(block: () -> T): T =
    withConversionBoundary(::ConversionPackageException, block)

internal inline fun <T> withConversionBundleBoundary(block: () -> T): T =
    withConversionBoundary(::ConversionBundleException, block)

private inline fun <T> withConversionBoundary(
    wrap: (Throwable) -> ConversionDomainException,
    block: () -> T,
): T = try {
    block()
} catch (error: CancellationException) {
    throw error
} catch (error: ConversionDomainException) {
    throw error
} catch (error: Throwable) {
    throw wrap(error)
}
