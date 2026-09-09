package com.plexon.homes.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class CoreIntegrationContractTest {
    @Test
    void homesUsesCoreTwoLifecycleContract() {
        assertEquals("homes", CoreIntegration.MODULE_ID);
        assertEquals(">=2.0 <3.0", CoreIntegration.API_RANGE);
    }
}
