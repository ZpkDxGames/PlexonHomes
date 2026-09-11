package com.plexon.homes.release;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ReleaseWorkflowContractTest {
    @Test void stablePublisherRequiresExactCurrentMainAndAcceptedPhase3Lineage() throws Exception {
        String release = Files.readString(Path.of(".github/workflows/release.yml"));
        assertTrue(release.contains("branches: ['release/stable']"));
        assertTrue(release.contains("refs/heads/main"));
        assertTrue(release.contains("test \"$GITHUB_SHA\" = \"$main_sha\""));
        assertTrue(release.contains("git merge-base --is-ancestor \"$ACCEPTED_PHASE3_SHA\" HEAD"));
        assertTrue(release.contains("case \"$VERSION\" in *-*)"));
        assertFalse(release.contains("--prerelease"));
    }

    @Test void publishedStableAssetsAreDownloadedAndVerified() throws Exception {
        String release = Files.readString(Path.of(".github/workflows/release.yml"));
        assertTrue(release.contains("SHA256SUMS.txt"));
        assertTrue(release.contains("TEST_SUMMARY.txt"));
        assertTrue(release.contains("PROVENANCE.txt"));
        assertTrue(release.contains("gh release download"));
        assertTrue(release.contains("(cd published && sha256sum --check SHA256SUMS.txt)"));
        assertTrue(release.contains("source_sha=${GITHUB_SHA}"));
        assertTrue(release.contains("sethome_migration=OPERATOR_GATED"));
        assertTrue(release.contains("runtime_certification=NOT_EXECUTED"));
    }

    @Test void sourceArtifactsCarryExactRollbackAndRuntimeProvenance() throws Exception {
        String build = Files.readString(Path.of(".github/workflows/build.yml"));
        assertTrue(build.contains("accepted_phase3_head=${ACCEPTED_PHASE3_SHA}"));
        assertTrue(build.contains("version=${VERSION}"));
        assertTrue(build.contains("java=25"));
        assertTrue(build.contains("class_major=69"));
        assertTrue(build.contains("plexoncore=2.0.4"));
        assertTrue(build.contains("rollback_tag=v1.0.1"));
        assertTrue(build.contains("rollback_sha=d20bef0a76cd3006df51d46d67a3e48636650d31"));
        assertTrue(build.contains("sethome_decommission=OPERATOR_GATED"));
        assertTrue(build.contains("runtime_certification=NOT_EXECUTED"));
    }
}
