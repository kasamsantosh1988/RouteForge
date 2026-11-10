package com.rsa.routeforge.route;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "routeforge")
public class RouteForgeProperties {

    private boolean enabled = true;

    private final Routes routes = new Routes();
    private final Admin admin = new Admin();
    private final Soap soap = new Soap();

    public Soap getSoap() {
        return soap;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Routes getRoutes() {
        return routes;
    }

    public Admin getAdmin() {
        return admin;
    }
    public static class Routes {
        private boolean enabled = true;
        private String location = "classpath*:routeforge-routes/*.json";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getLocation() {
            return location;
        }

        public void setLocation(String location) {
            this.location = location;
        }
    }

    /** {@code routeforge.soap.*}: the SOAP channel. Off by default; route files with {@code channel: soap} need it on. */
    public static class Soap {
        private boolean enabled = false;
        /** Largest request accepted, in UTF-8 bytes. */
        private int maxBodyBytes = 1024 * 1024;
        /** Deepest element nesting accepted, counting the Envelope as 1. */
        private int maxDepth = 32;
        /** Most elements accepted in one request. */
        private int maxElements = 10_000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMaxBodyBytes() { return maxBodyBytes; }
        public void setMaxBodyBytes(int maxBodyBytes) { this.maxBodyBytes = maxBodyBytes; }
        public int getMaxDepth() { return maxDepth; }
        public void setMaxDepth(int maxDepth) { this.maxDepth = maxDepth; }
        public int getMaxElements() { return maxElements; }
        public void setMaxElements(int maxElements) { this.maxElements = maxElements; }

        public com.rsa.routeforge.soap.SoapLimits limits() {
            return new com.rsa.routeforge.soap.SoapLimits(maxBodyBytes, maxDepth, maxElements);
        }
    }

    public static class Admin {
        private boolean enabled = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

}