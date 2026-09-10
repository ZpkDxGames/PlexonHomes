package com.plexon.homes.release;

import static org.junit.jupiter.api.Assertions.assertTrue;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ReleaseWorkflowContractTest {
    @Test void freezeRequiresSameShaPushAndPullRequestBuildEvidence() throws Exception {
        String release = Files.readString(Path.of(".github/workflows/release.yml"));
        assertTrue(release.contains("contains(github.event.head_commit.message, '[freeze-rc]')"));
        assertTrue(release.contains(".event == \"push\""));
        assertTrue(release.contains(".event == \"pull_request\""));
        assertTrue(release.contains("headRefOid == $sha"));
        assertTrue(release.contains("refs/tags/$TAG"));
        assertTrue(release.contains("v2.0.0-rc.1"));
    }

    @Test void prereleaseAndPublishedAssetVerificationAreImmutable() throws Exception {
        String release = Files.readString(Path.of(".github/workflows/release.yml"));
        assertTrue(release.contains("--prerelease"));
        assertTrue(release.contains("PlexonHomes-2.0.0-rc.1.jar"));
        assertTrue(release.contains("SHA256SUMS.txt"));
        assertTrue(release.contains("TEST_SUMMARY.txt"));
        assertTrue(release.contains("PROVENANCE.txt"));
        assertTrue(release.contains("sha256sum --check SHA256SUMS.txt"));
        assertTrue(release.contains("Stable v2.0.0 tag must not exist."));
        assertTrue(release.contains("Stable v2.0.0 release must not exist."));
        assertTrue(release.contains("runtime_certification=NOT_EXECUTED"));
    }

    @Test void sourceArtifactsCarryExactRollbackAndRuntimeProvenance() throws Exception {
        String build = Files.readString(Path.of(".github/workflows/build.yml"));
        assertTrue(build.contains("source_sha=$GITHUB_SHA"));
        assertTrue(build.contains("version=2.0.0-rc.1"));
        assertTrue(build.contains("java=25"));
        assertTrue(build.contains("class_major=69"));
        assertTrue(build.contains("plexoncore=2.0.4"));
        assertTrue(build.contains("rollback_tag=v1.0.1"));
        assertTrue(build.contains("rollback_sha=d20bef0a76cd3006df51d46d67a3e48636650d31"));
        assertTrue(build.contains("runtime_certification=NOT_EXECUTED"));
    }
}
