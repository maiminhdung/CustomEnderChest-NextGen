package org.maiminhdung.customenderchest.update;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateManagerVersionTest {

	@Test
	void numericSegmentsCompareCorrectly() {
		assertTrue(UpdateManager.compareVersions("2.2.0", "2.1.9") > 0);
		assertTrue(UpdateManager.compareVersions("2.10.0", "2.9.0") > 0);
		assertTrue(UpdateManager.compareVersions("2.2.0", "2.2.0") == 0);
		assertTrue(UpdateManager.compareVersions("3.0", "2.99.99") > 0);
	}

	@Test
	void missingSegmentsCountAsZero() {
		assertTrue(UpdateManager.compareVersions("2.2", "2.2.0") == 0);
		assertTrue(UpdateManager.compareVersions("2.2.1", "2.2") > 0);
	}

	@Test
	void stableBeatsQualifier() {
		// 2.2.0 (stable) is newer than 2.2.0-dev
		assertTrue(UpdateManager.compareVersions("2.2.0", "2.2.0-dev") > 0);
		assertTrue(UpdateManager.compareVersions("2.2.0-dev", "2.2.0") < 0);
	}

	@Test
	void qualifiersCompareLexically() {
		assertTrue(UpdateManager.compareVersions("2.2.0-dev", "2.2.0-beta") > 0);
	}

	@Test
	void leadingVIsIgnored() {
		assertTrue(UpdateManager.compareVersions("v2.2.0", "2.2.0") == 0);
		assertTrue(UpdateManager.compareVersions("v3.0.0", "v2.9.9") > 0);
	}

	@Test
	void dirtySegmentsAreTolerated() {
		// "1.21.1-R0.1-SNAPSHOT" style versions: trailing junk after numbers is
		// treated as a qualifier, so it sorts BEFORE the stable release
		assertTrue(UpdateManager.compareVersions("1.21.1-R0.1-SNAPSHOT", "1.21.1") < 0);
		assertTrue(UpdateManager.compareVersions("1.21.5", "1.21.4-R0.1-SNAPSHOT") > 0);
	}

	// ==================== release channel filtering ====================

	@Test
	void releaseChannelShowsOnlyReleases() {
		assertTrue(UpdateManager.matchesChannel("release", UpdateManager.channelRank("release")));
		assertTrue(UpdateManager.matchesChannel("beta", UpdateManager.channelRank("beta")));
		assertTrue(UpdateManager.matchesChannel("alpha", UpdateManager.channelRank("alpha")));
	}

	@Test
	void stricterChannelHidesLooserReleases() {
		int release = UpdateManager.channelRank("release");
		assertFalse(UpdateManager.matchesChannel("beta", release));
		assertFalse(UpdateManager.matchesChannel("alpha", release));

		int beta = UpdateManager.channelRank("beta");
		assertTrue(UpdateManager.matchesChannel("release", beta));
		assertFalse(UpdateManager.matchesChannel("alpha", beta));

		int alpha = UpdateManager.channelRank("alpha");
		assertTrue(UpdateManager.matchesChannel("release", alpha));
		assertTrue(UpdateManager.matchesChannel("beta", alpha));
	}

	@Test
	void unknownReleaseTypesAndChannelsAreRejectedOrDowngraded() {
		assertFalse(UpdateManager.matchesChannel("nightly", UpdateManager.channelRank("release")));
		assertFalse(UpdateManager.matchesChannel(null, UpdateManager.channelRank("release")));
		assertEquals(0, UpdateManager.channelRank("garbage")); // falls back to release
	}

	// ==================== loader filtering ====================

	@Test
	void serverPluginLoadersAreRecognized() {
		assertTrue(UpdateManager.isSupportedLoader("paper"));
		assertTrue(UpdateManager.isSupportedLoader("FOLIA"));
		assertTrue(UpdateManager.isSupportedLoader("purpur"));
		assertFalse(UpdateManager.isSupportedLoader("fabric"));
		assertFalse(UpdateManager.isSupportedLoader("forge"));
		assertFalse(UpdateManager.isSupportedLoader(null));
	}

	// ==================== game version filtering ====================

	@Test
	void exactGameVersionMatches() {
		assertTrue(UpdateManager.supportsGameVersion(List.of("1.21", "1.21.1"), "1.21.1"));
		assertFalse(UpdateManager.supportsGameVersion(List.of("1.21", "1.21.1"), "1.21.4"));
	}

	@Test
	void minorVersionEntryCoversPatchServers() {
		// Release tagged "1.21" still matches a 1.21.1 server
		assertTrue(UpdateManager.supportsGameVersion(List.of("1.20.6", "1.21"), "1.21.1"));
	}

	@Test
	void missingGameVersionMetadataIsTreatedAsCompatible() {
		assertTrue(UpdateManager.supportsGameVersion(List.of(), "1.21.1"));
		assertTrue(UpdateManager.supportsGameVersion(null, "1.21.1"));
		assertTrue(UpdateManager.supportsGameVersion(List.of("1.21.1"), null));
	}
}
