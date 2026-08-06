/**
 * PRISM-backed MMP analytics.
 *
 * <p>This package separates endpoint-independent structural MMP universes from
 * endpoint-specific numeric statistics. {@link tech.molecules.structurized.analytics.mmp.MmpAnalyticsSnapshot}
 * is the immutable provider boundary; {@link tech.molecules.structurized.analytics.mmp.MmpEndpointStatsCalculator}
 * computes complete results without I/O, and
 * {@link tech.molecules.structurized.analytics.mmp.MmpAnalyticsPersistenceService} persists those results.
 * The SQLite repository is a reference implementation intended for local validation and as a narrow SQL
 * boundary for later database-specific implementations.</p>
 */
package tech.molecules.structurized.analytics.mmp;
