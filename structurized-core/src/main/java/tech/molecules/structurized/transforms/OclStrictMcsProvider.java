package tech.molecules.structurized.transforms;

import com.actelion.research.chem.SSSearcher;
import com.actelion.research.chem.StereoMolecule;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Strict MCS provider based on OpenChemLib's MCSFast.
 *
 * <p>The preserved core is accepted only if mapped atoms and mapped bonds are identical under the
 * project's current strictness policy.</p>
 */
public class OclStrictMcsProvider implements TransformationBench.StrictMCSProvider {
    @Override
    public TransformationSplitter.MCSMap computeStrictMCS(StereoMolecule A, StereoMolecule B) {
        MCSMappingResult result = computeMCSMapping(A, B, true);
        return result.strict ? result.mcsMap : null;
    }

    public MCSMappingResult computeMCSMapping(StereoMolecule A, StereoMolecule B, boolean requireStrict) {
        try {
            com.actelion.research.chem.mcs.MCSFast mcs = new com.actelion.research.chem.mcs.MCSFast();
            mcs.set(A, B);
            StereoMolecule mcsMol = mcs.getMCS();
            if (mcsMol == null || mcsMol.getAtoms() == 0) {
                return MCSMappingResult.failure("No MCS found.");
            }

            mcsMol.setFragment(true);
            List<int[]> mappingsToA = mappingsFromMcs(mcsMol, A);
            List<int[]> mappingsToB = mappingsFromMcs(mcsMol, B);
            if (mappingsToA.isEmpty() || mappingsToB.isEmpty()) {
                return MCSMappingResult.failure("MCS could not be mapped back to both input structures.");
            }

            int mcsAtomCount = mcsMol.getAtoms();
            for (int[] mcsToA : mappingsToA) {
                for (int[] mcsToB : mappingsToB) {
                    int[] mapAtoB = mapAtoB(mcsToA, mcsToB, A.getAtoms());
                    if (atomsStrictlyMatch(A, B, mapAtoB) && bondsStrictlyMatch(A, B, mapAtoB)) {
                        MappingScore score = scoreMapping(A, B, mapAtoB);
                        return MCSMappingResult.success(
                                new TransformationSplitter.MCSMap(mapAtoB, invertMap(mapAtoB, B.getAtoms())),
                                true,
                                mcsAtomCount,
                                mappingsToA.size(),
                                mappingsToB.size(),
                                score.ringBondsA(),
                                score.ringBondsB(),
                                null
                        );
                    }
                }
            }

            if (requireStrict) {
                return MCSMappingResult.failure(
                        "MCS found, but no mapping passed strict atom/bond identity checks.",
                        mcsAtomCount,
                        mappingsToA.size(),
                        mappingsToB.size()
                );
            }

            MappingCandidate candidate = bestRelaxedMapping(A, B, mappingsToA, mappingsToB);
            return MCSMappingResult.success(
                    new TransformationSplitter.MCSMap(candidate.mapAtoB(), invertMap(candidate.mapAtoB(), B.getAtoms())),
                    false,
                    mcsAtomCount,
                    mappingsToA.size(),
                    mappingsToB.size(),
                    candidate.score().ringBondsA(),
                    candidate.score().ringBondsB(),
                    "Using non-strict MCS mapping selected by ring-bond coverage. "
                            + "Mapped atoms/bonds may differ inside the displayed core."
            );
        } catch (Throwable t) {
            return MCSMappingResult.failure("MCS computation failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static List<int[]> mappingsFromMcs(StereoMolecule mcs, StereoMolecule molecule) {
        SSSearcher searcher = new SSSearcher();
        searcher.setMol(mcs, molecule);
        int matchCount = searcher.findFragmentInMolecule(SSSearcher.cCountModeUnique, SSSearcher.cDefaultMatchMode);
        if (matchCount == 0) {
            return List.of();
        }
        return new ArrayList<>(searcher.getMatchList());
    }

    private static int[] mapAtoB(int[] mcsToA, int[] mcsToB, int atomsA) {
        int[] aToMcs = invertMap(mcsToA, atomsA);
        int[] aToB = new int[aToMcs.length];
        for (int a = 0; a < aToB.length; a++) {
            if (aToMcs[a] >= 0) {
                aToB[a] = mcsToB[aToMcs[a]];
            } else {
                aToB[a] = -1;
            }
        }
        return aToB;
    }

    private static MappingCandidate bestRelaxedMapping(
            StereoMolecule A,
            StereoMolecule B,
            List<int[]> mappingsToA,
            List<int[]> mappingsToB
    ) {
        MappingCandidate best = null;
        for (int[] mcsToA : mappingsToA) {
            for (int[] mcsToB : mappingsToB) {
                int[] mapAtoB = mapAtoB(mcsToA, mcsToB, A.getAtoms());
                MappingScore score = scoreMapping(A, B, mapAtoB);
                MappingCandidate candidate = new MappingCandidate(mapAtoB, score);
                if (best == null || candidate.score().compareTo(best.score()) > 0) {
                    best = candidate;
                }
            }
        }
        return best;
    }

    private static MappingScore scoreMapping(StereoMolecule A, StereoMolecule B, int[] mapAtoB) {
        boolean[] coreB = new boolean[B.getAllAtoms()];
        for (int b : mapAtoB) {
            if (b >= 0 && b < coreB.length) {
                coreB[b] = true;
            }
        }

        int ringBondsA = 0;
        int mappedBondsA = 0;
        for (int bond = 0; bond < A.getBonds(); bond++) {
            int a1 = A.getBondAtom(0, bond);
            int a2 = A.getBondAtom(1, bond);
            if (mapAtoB[a1] < 0 || mapAtoB[a2] < 0) {
                continue;
            }
            mappedBondsA++;
            if (A.isRingBond(bond)) {
                ringBondsA++;
            }
        }

        int ringBondsB = 0;
        int mappedBondsB = 0;
        for (int bond = 0; bond < B.getBonds(); bond++) {
            int b1 = B.getBondAtom(0, bond);
            int b2 = B.getBondAtom(1, bond);
            if (!coreB[b1] || !coreB[b2]) {
                continue;
            }
            mappedBondsB++;
            if (B.isRingBond(bond)) {
                ringBondsB++;
            }
        }

        return new MappingScore(
                Math.min(ringBondsA, ringBondsB),
                ringBondsA + ringBondsB,
                mappedBondsA + mappedBondsB,
                ringBondsA,
                ringBondsB
        );
    }

    private static boolean atomsStrictlyMatch(StereoMolecule A, StereoMolecule B, int[] aToB) {
        for (int a = 0; a < A.getAllAtoms(); a++) {
            int b = aToB[a];
            if (b < 0) {
                continue;
            }
            if (A.getAtomicNo(a) != B.getAtomicNo(b)) {
                return false;
            }
            if (A.getAtomCharge(a) != B.getAtomCharge(b)) {
                return false;
            }
            if (A.getAtomMass(a) != B.getAtomMass(b)) {
                return false;
            }
            if (A.isAromaticAtom(a) != B.isAromaticAtom(b)) {
                return false;
            }
        }
        return true;
    }

    private static boolean bondsStrictlyMatch(StereoMolecule A, StereoMolecule B, int[] aToB) {
        for (int a = 0; a < A.getAllAtoms(); a++) {
            int b = aToB[a];
            if (b < 0) {
                continue;
            }
            for (int i = 0; i < A.getConnAtoms(a); i++) {
                int aNeighbor = A.getConnAtom(a, i);
                int bNeighbor = aToB[aNeighbor];
                if (bNeighbor < 0) {
                    continue;
                }
                int bondA = A.getConnBond(a, i);
                int bondB = B.getBond(b, bNeighbor);
                if (bondB == -1) {
                    return false;
                }
                if (A.getBondOrder(bondA) != B.getBondOrder(bondB)) {
                    return false;
                }
                if (A.getBondType(bondA) != B.getBondType(bondB)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static int[] invertMap(int[] mapAtoB, int lengthB) {
        int[] inverse = new int[lengthB];
        Arrays.fill(inverse, -1);
        for (int a = 0; a < mapAtoB.length; a++) {
            int b = mapAtoB[a];
            if (b >= 0) {
                inverse[b] = a;
            }
        }
        return inverse;
    }

    private record MappingCandidate(int[] mapAtoB, MappingScore score) {
    }

    private record MappingScore(
            int sharedRingBondScore,
            int totalRingBondScore,
            int totalMappedBondScore,
            int ringBondsA,
            int ringBondsB
    ) implements Comparable<MappingScore> {
        @Override
        public int compareTo(MappingScore other) {
            int sharedRingCmp = Integer.compare(sharedRingBondScore, other.sharedRingBondScore);
            if (sharedRingCmp != 0) {
                return sharedRingCmp;
            }
            int totalRingCmp = Integer.compare(totalRingBondScore, other.totalRingBondScore);
            if (totalRingCmp != 0) {
                return totalRingCmp;
            }
            return Integer.compare(totalMappedBondScore, other.totalMappedBondScore);
        }
    }

    public record MCSMappingResult(
            TransformationSplitter.MCSMap mcsMap,
            boolean strict,
            int mcsAtomCount,
            int mappingCountA,
            int mappingCountB,
            int selectedRingBondsA,
            int selectedRingBondsB,
            String warning,
            String failure
    ) {
        private static MCSMappingResult success(
                TransformationSplitter.MCSMap mcsMap,
                boolean strict,
                int mcsAtomCount,
                int mappingCountA,
                int mappingCountB,
                int selectedRingBondsA,
                int selectedRingBondsB,
                String warning
        ) {
            return new MCSMappingResult(
                    mcsMap,
                    strict,
                    mcsAtomCount,
                    mappingCountA,
                    mappingCountB,
                    selectedRingBondsA,
                    selectedRingBondsB,
                    warning,
                    null
            );
        }

        private static MCSMappingResult failure(String failure) {
            return failure(failure, 0, 0, 0);
        }

        private static MCSMappingResult failure(String failure, int mcsAtomCount, int mappingCountA, int mappingCountB) {
            return new MCSMappingResult(null, false, mcsAtomCount, mappingCountA, mappingCountB, 0, 0, null, failure);
        }
    }
}
