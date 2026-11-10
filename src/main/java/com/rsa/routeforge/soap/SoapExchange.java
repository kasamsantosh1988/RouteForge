package com.rsa.routeforge.soap;

/** Exchange property names the SOAP endpoint uses to hand its work to {@link SoapCodec}. */
public final class SoapExchange {

    /** The parsed {@link SoapRequest}, so the codec does not parse the body twice. */
    public static final String REQUEST = "routeforge.soap.request";

    /**
     * An {@link com.rsa.routeforge.error.OrchestrationException} raised before an operation could be
     * chosen (malformed XML, unknown operation, wrong method or content type). The codec throws it
     * from {@code parseRequest}, so it is answered as a fault and audited like any other failure.
     */
    public static final String DISPATCH_ERROR = "routeforge.soap.dispatchError";

    private SoapExchange() {
    }
}
