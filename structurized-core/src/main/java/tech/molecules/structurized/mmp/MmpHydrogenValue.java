package tech.molecules.structurized.mmp;

/**
 * Sentinel "value fragment" representing the absence of a substituent, i.e. a plain hydrogen.
 *
 * <p>OpenChemLib cuts real bonds between heavy atoms, so an empty substituent cannot be expressed
 * as an ordinary fragment IDCode. Hydrogen replacement (-H &lt;-&gt; -R) is therefore represented by
 * this reserved marker in the {@code valueIdcode} position of a one-cut fragmentation.</p>
 *
 * <p>The marker provably cannot collide with a real IDCode: {@code Canonizer} emits only characters
 * produced by {@code (char)(value + 64)} for six-bit values, so every IDCode character lies in the
 * ASCII range 63..127. {@code '*'} is ASCII 42 and can never occur.</p>
 *
 * <p>Hydrogen cuts are deliberately restricted to one-cut fragmentations. A two-cut set containing a
 * hydrogen site would grow the candidate set from O(B^2) to O((B+H)^2) per molecule, which for
 * drug-sized molecules is roughly an order of magnitude more fragmentations and would trigger
 * order-dependent truncation at {@link MmpMiningConfig#maxFragmentationRecordsPerCompound()}.</p>
 */
public final class MmpHydrogenValue {
    /** Reserved value-fragment marker for "no substituent". */
    public static final String SENTINEL = "*H";

    /** Attachment index used when a cut has no real bond or no heavy value atom. */
    public static final int NO_INDEX = -1;

    private MmpHydrogenValue() {
    }

    /** Returns true when the supplied value fragment is the hydrogen marker. */
    public static boolean isHydrogen(String valueIdcode) {
        return SENTINEL.equals(valueIdcode);
    }
}
