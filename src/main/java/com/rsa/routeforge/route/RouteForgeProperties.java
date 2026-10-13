package com.rsa.routeforge.route;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "routeforge")
public class RouteForgeProperties {

    private boolean enabled = true;

    private final Routes routes = new Routes();
    private final Admin admin = new Admin();

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