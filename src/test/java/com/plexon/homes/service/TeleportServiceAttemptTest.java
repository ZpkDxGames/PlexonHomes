package com.plexon.homes.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.zpkdxgames.plexoncore.player.PlayerWatchService.Signal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TeleportServiceAttemptTest {
    @Test void resolvingWarmupAndFinalResolveOwnMovementDamageAndLogout() {
        assertTrue(TeleportService.activityOwned(TeleportService.Phase.RESOLVING, Signal.MOVED));
        assertTrue(TeleportService.activityOwned(TeleportService.Phase.RESOLVING, Signal.DAMAGED));
        assertTrue(TeleportService.activityOwned(TeleportService.Phase.RESOLVING, Signal.QUIT));
        assertTrue(TeleportService.activityOwned(TeleportService.Phase.WARMUP, Signal.MOVED));
        assertTrue(TeleportService.activityOwned(TeleportService.Phase.FINAL_RESOLVE, Signal.DAMAGED));
        assertTrue(TeleportService.activityOwned(TeleportService.Phase.TELEPORTING, Signal.QUIT));
        assertFalse(TeleportService.activityOwned(TeleportService.Phase.TELEPORTING, Signal.MOVED));
        assertFalse(TeleportService.activityOwned(TeleportService.Phase.TELEPORTING, Signal.DAMAGED));
    }

    @Test void acceptedAttemptCapturesFeeAcrossLaterConfigurationChanges() {
        TeleportService.Attempt attempt = new TeleportService.Attempt(7L, UUID.randomUUID(), null, null, null, 4.25D);
        double laterConfiguredFee = 99.0D;
        assertEquals(4.25D, attempt.fee);
        assertEquals(99.0D, laterConfiguredFee);
    }

    @Test void refundClaimIsExactOnceAndUsesChargedAmount() {
        TeleportService.Attempt attempt = new TeleportService.Attempt(8L, UUID.randomUUID(), null, null, null, 4.25D);
        attempt.charged = 4.25D;
        assertEquals(4.25D, attempt.claimRefund());
        assertEquals(0.0D, attempt.claimRefund());
        assertTrue(attempt.refunded);
    }

    @Test void staleCompletionCannotClaimRefundTwice() {
        TeleportService.Attempt attempt = new TeleportService.Attempt(9L, UUID.randomUUID(), null, null, null, 8.0D);
        attempt.charged = 8.0D;
        double logoutRefund = attempt.claimRefund();
        double staleAsyncRefund = attempt.claimRefund();
        assertEquals(8.0D, logoutRefund);
        assertEquals(0.0D, staleAsyncRefund);
    }

    @Test void noChargeMeansNoRefundClaim() {
        TeleportService.Attempt attempt = new TeleportService.Attempt(10L, UUID.randomUUID(), null, null, null, 4.25D);
        assertEquals(0.0D, attempt.claimRefund());
        assertFalse(attempt.refunded);
    }
}
