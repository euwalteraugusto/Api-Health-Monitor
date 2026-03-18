package com.monitor.exception;

import java.util.UUID;

public class MonitorNotFoundException extends RuntimeException {
    public MonitorNotFoundException(UUID id) {
        super("Monitor with ID " + id + " not found");
    }
}
