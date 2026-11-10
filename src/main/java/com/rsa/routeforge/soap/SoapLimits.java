package com.rsa.routeforge.soap;

/**
 * Bounds applied while parsing an inbound SOAP message, so a hostile or broken request cannot
 * exhaust memory or CPU.
 *
 * @param maxBodyBytes size of the request in UTF-8 bytes
 * @param maxDepth     element nesting depth, counting the Envelope as 1
 * @param maxElements  total number of elements in the message
 */
public record SoapLimits(int maxBodyBytes, int maxDepth, int maxElements) {

    public static final SoapLimits DEFAULTS = new SoapLimits(1024 * 1024, 32, 10_000);
}
