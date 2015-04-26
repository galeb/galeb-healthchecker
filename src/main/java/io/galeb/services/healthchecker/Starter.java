package io.galeb.services.healthchecker;

import io.galeb.core.cdi.WeldContext;
import io.galeb.core.starter.Properties;


public class Starter extends Properties {

    private Starter() {
        // main class
    }

    public static void main(String[] args) {

        WeldContext.INSTANCE.getBean(HealthChecker.class);

    }

}
