package tech.molecules.structurized.scaffolds;

import com.actelion.research.chem.Molecule;
import com.actelion.research.chem.SSSearcher;
import com.actelion.research.chem.StereoMolecule;
import tech.molecules.structurized.transforms.TransformationGroup;
import tech.molecules.structurized.transforms.TransformationSplitter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Scaffold-centric decomposition built on top of the existing core-relative splitter.
 *
 * <p>The scaffold is treated as the predefined preserved core. A scaffold match provides the
 * atom mapping from scaffold atoms to compound atoms. The existing {@link TransformationSplitter}
 * then splits the compound-relative additions into independent transformation groups. These groups
 * are re-expressed as scaffold substitution events with scaffold atom indices and symmetry classes.</p>
 */
public final class ScaffoldAnalyzer {
    private ScaffoldAnalyzer() {}

    public static final class Config {
        public int radiusR = 1;
        public int featureMask = TransformationSplitter.FeatureMask.DEFAULT;
    }

    public static ScaffoldDecomposition analyze(ScaffoldTemplate template, StereoMolecule compound, Config cfg) {
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(compound, "compound");
        if (cfg == null) {
            cfg = new Config();
        }

        StereoMolecule scaffold = normalizedScaffoldCopy(template.scaffold);
        scaffold.ensureHelperArrays(Molecule.cHelperSymmetrySimple);
        StereoMolecule compoundCopy = new StereoMolecule(compound);
        compoundCopy.ensureHelperArrays(Molecule.cHelperSymmetrySimple);

        StereoMolecule query = new StereoMolecule(scaffold);
        query.setFragment(true);
        query.ensureHelperArrays(Molecule.cHelperSymmetrySimple);

        SSSearcher searcher = new SSSearcher();
        searcher.setMol(query, compoundCopy);
        int matchCount = searcher.findFragmentInMolecule(SSSearcher.cCountModeUnique, SSSearcher.cDefaultMatchMode);
        if (matchCount == 0) {
            return new ScaffoldDecomposition(template, "scaffold not found in compound");
        }

        List<int[]> matches = searcher.getMatchList();
        MatchCandidate best = null;
        for (int matchIndex = 0; matchIndex < matches.size(); matchIndex++) {
            int[] scaffoldToCompoundAtom = matches.get(matchIndex);
            int[] compoundToScaffoldAtom = invertMap(scaffoldToCompoundAtom, compoundCopy.getAtoms());
            TransformationSplitter.MCSMap mcs =
                    new TransformationSplitter.MCSMap(scaffoldToCompoundAtom, compoundToScaffoldAtom);
            List<TransformationGroup> groups =
                    TransformationSplitter.splitIntoTransformations(scaffold, compoundCopy, mcs, cfg.radiusR, cfg.featureMask);
            List<SubstitutionEvent> events = toSubstitutionEvents(template, groups);
            String key = buildCanonicalMatchKey(events);
            MatchCandidate candidate = new MatchCandidate(
                    new ScaffoldMatch(template, scaffoldToCompoundAtom, compoundToScaffoldAtom, matches.size(), matchIndex),
                    groups,
                    events,
                    key
            );
            if (best == null || candidate.sortKey.compareTo(best.sortKey) < 0) {
                best = candidate;
            }
        }

        return new ScaffoldDecomposition(template, best.match, best.groups, best.events);
    }

    private static StereoMolecule normalizedScaffoldCopy(StereoMolecule source) {
        StereoMolecule scaffold = new StereoMolecule(source);
        for (int atom = 0; atom < scaffold.getAllAtoms(); atom++) {
            if (scaffold.getAtomMapNo(atom) != 0) {
                scaffold.setAtomMapNo(atom, 0, false);
                if (isSimpleMappedAtom(scaffold, atom)) {
                    scaffold.setAtomCustomLabel(atom, (byte[]) null);
                    scaffold.setAtomAbnormalValence(atom, -1);
                    scaffold.setAtomRadical(atom, Molecule.cAtomRadicalStateNone);
                }
            }
        }
        return scaffold;
    }

    private static boolean isSimpleMappedAtom(StereoMolecule molecule, int atom) {
        return molecule.getAtomCharge(atom) == 0
                && molecule.getAtomMass(atom) == 0
                && molecule.getAtomList(atom) == null
                && molecule.getAtomQueryFeatures(atom) == 0;
    }

    private static List<SubstitutionEvent> toSubstitutionEvents(
            ScaffoldTemplate template,
            List<TransformationGroup> groups
    ) {
        List<SubstitutionEvent> events = new ArrayList<>(groups.size());
        for (TransformationGroup group : groups) {
            List<Integer> scaffoldAtoms = List.copyOf(group.attachmentsA);
            List<Integer> symmetryClasses = new ArrayList<>(group.attachmentsA.size());
            List<ExitVector> exitVectors = new ArrayList<>(group.attachmentsA.size());
            for (int atom : group.attachmentsA) {
                symmetryClasses.add(template.atomSymmetryClasses[atom]);
                exitVectors.add(template.candidateExitVectors.get(atom));
            }

            SubstitutionEventType eventType =
                    group.attachmentsA.size() <= 1 ? SubstitutionEventType.SINGLE_ATTACHMENT : SubstitutionEventType.MULTI_ATTACHMENT;
            events.add(new SubstitutionEvent(
                    group,
                    exitVectors,
                    scaffoldAtoms,
                    symmetryClasses,
                    group.signature.addedIdcode,
                    group.type,
                    eventType
            ));
        }

        events.sort(Comparator.comparing(ScaffoldAnalyzer::substitutionEventKey));
        return events;
    }

    private static String buildCanonicalMatchKey(List<SubstitutionEvent> events) {
        List<String> parts = events.stream().map(ScaffoldAnalyzer::substitutionEventKey).sorted().toList();
        return String.join(";", parts);
    }

    private static String substitutionEventKey(SubstitutionEvent event) {
        return event.transformationType
                + "|" + event.eventType
                + "|" + event.symmetryClasses
                + "|" + event.scaffoldAtoms
                + "|" + event.addedFragmentIdcode
                + "|" + event.transformationGroup.signature.sigId;
    }

    private static int[] invertMap(int[] scaffoldToCompoundAtom, int compoundAtomCount) {
        int[] inverse = new int[compoundAtomCount];
        Arrays.fill(inverse, -1);
        for (int scaffoldAtom = 0; scaffoldAtom < scaffoldToCompoundAtom.length; scaffoldAtom++) {
            int compoundAtom = scaffoldToCompoundAtom[scaffoldAtom];
            if (compoundAtom >= 0) {
                inverse[compoundAtom] = scaffoldAtom;
            }
        }
        return inverse;
    }

    private record MatchCandidate(
            ScaffoldMatch match,
            List<TransformationGroup> groups,
            List<SubstitutionEvent> events,
            String sortKey
    ) {}
}
