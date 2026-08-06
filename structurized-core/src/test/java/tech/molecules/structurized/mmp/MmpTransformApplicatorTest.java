package tech.molecules.structurized.mmp;

import com.actelion.research.chem.Canonizer;
import com.actelion.research.chem.Molecule;
import com.actelion.research.chem.SmilesParser;
import com.actelion.research.chem.StereoMolecule;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MmpTransformApplicatorTest {

    @Test
    void appliesMinedOneCutTransformAndReproducesTarget() throws Exception {
        StereoMolecule source = parse("Cc1ccccc1");
        StereoMolecule target = parse("CCc1ccccc1");
        MmpMiningConfig config = config(1);
        MmpPair pair = directedPair(source, target, config, 1);
        MmpFragmentationMatch match = sourceMatch(source, config, pair);

        MmpTransformApplicationAttempt attempt = MmpTransformApplicator.apply(
                match, MmpTransformDefinition.from(pair));

        assertEquals(MmpTransformApplicationStatus.APPLIED, attempt.status());
        assertEquals(canonical(target), attempt.application().productIdcode());
        assertEquals(match.attachments(), attempt.application().attachments());
    }

    @Test
    void appliesAcyclicTwoCutLinkerTransformAndReproducesTarget() throws Exception {
        StereoMolecule source = parse("CCOCC");
        StereoMolecule target = parse("CCNCC");
        MmpMiningConfig config = config(2);
        MmpPair pair = directedPair(source, target, config, 2);
        MmpFragmentationMatch match = sourceMatch(source, config, pair);

        MmpTransformApplicationAttempt attempt = MmpTransformApplicator.apply(
                match, MmpTransformDefinition.from(pair));

        assertEquals(MmpTransformApplicationStatus.APPLIED, attempt.status(), attempt.message());
        assertEquals(canonical(target), attempt.application().productIdcode());
        assertEquals(2, attempt.application().attachments().size());
    }

    @Test
    void reconnectsBothSidesOfMacrocycleFragmentation() throws Exception {
        StereoMolecule macrocycle = parse("C1CCCCCCCCCCC1");
        MmpMiningConfig config = config(2).toBuilder().macrocycleMinRingSize(10).build();
        MmpFragmentationMatch match = MmpFragmenter.fragmentWithMapping(
                        new MmpInputCompound("macro", macrocycle, null), config).stream()
                .filter(candidate -> candidate.record().cutCount() == 2)
                .findFirst()
                .orElseThrow();
        MmpTransformDefinition identity = new MmpTransformDefinition(
                "macro-identity", 2, match.record().valueIdcode(), match.record().valueIdcode());

        MmpTransformApplicationAttempt attempt = MmpTransformApplicator.apply(match, identity);

        assertEquals(MmpTransformApplicationStatus.APPLIED, attempt.status(), attempt.message());
        assertEquals(canonical(macrocycle), attempt.application().productIdcode());
    }

    @Test
    void reportsNotApplicableAndMalformedTransformWithoutThrowing() throws Exception {
        StereoMolecule source = parse("Cc1ccccc1");
        MmpMiningConfig config = config(1);
        MmpFragmentationMatch match = MmpFragmenter.fragmentWithMapping(
                new MmpInputCompound("source", source, null), config).getFirst();
        String fragmentWithoutConnector = canonical(parse("C"));

        MmpTransformApplicationAttempt mismatch = MmpTransformApplicator.apply(match,
                new MmpTransformDefinition("mismatch", 1, fragmentWithoutConnector, fragmentWithoutConnector));
        MmpTransformApplicationAttempt malformed = MmpTransformApplicator.apply(match,
                new MmpTransformDefinition("malformed", 1, match.record().valueIdcode(), fragmentWithoutConnector));

        assertEquals(MmpTransformApplicationStatus.NOT_APPLICABLE, mismatch.status());
        assertEquals(MmpTransformApplicationStatus.INVALID_TRANSFORM, malformed.status());
        assertFalse(malformed.message().isBlank());
    }

    @Test
    void twoCutCanonicalLabelsAndMappingsAreDeterministic() throws Exception {
        StereoMolecule molecule = parse("CCOCC");
        MmpMiningConfig config = config(2);

        List<MmpFragmentationMatch> first = MmpFragmenter.fragmentWithMapping(
                new MmpInputCompound("same", molecule, null), config);
        List<MmpFragmentationMatch> second = MmpFragmenter.fragmentWithMapping(
                new MmpInputCompound("same", molecule, null), config);

        assertEquals(first, second);
        assertTrue(first.stream().filter(match -> match.record().cutCount() == 2)
                .allMatch(match -> match.attachments().stream()
                        .map(MmpAttachment::label).toList().equals(List.of(1, 2))));
    }

    @Test
    void preservesNonSingleAttachmentBondTypesWhenEnabled() throws Exception {
        StereoMolecule alkene = parse("C=C");
        MmpMiningConfig config = config(1).toBuilder().singleBondsOnly(false).build();
        MmpFragmentationMatch match = MmpFragmenter.fragmentWithMapping(
                new MmpInputCompound("alkene", alkene, null), config).getFirst();
        MmpTransformDefinition identity = new MmpTransformDefinition(
                "alkene-identity", 1, match.record().valueIdcode(), match.record().valueIdcode());

        MmpTransformApplicationAttempt attempt = MmpTransformApplicator.apply(match, identity);

        assertEquals(Molecule.cBondTypeDouble, match.attachments().getFirst().bondType());
        assertEquals(MmpTransformApplicationStatus.APPLIED, attempt.status(), attempt.message());
        assertEquals(canonical(alkene), attempt.application().productIdcode());
    }

    @Test
    void applicationDoesNotMutateInputsAndExposesImmutableCollections() throws Exception {
        StereoMolecule source = parse("Cc1ccccc1");
        StereoMolecule target = parse("CCc1ccccc1");
        String sourceBefore = canonical(source);
        MmpMiningConfig config = config(1);
        MmpPair pair = directedPair(source, target, config, 1);
        MmpFragmentationMatch match = sourceMatch(source, config, pair);

        MmpTransformApplicationAttempt attempt = MmpTransformApplicator.apply(
                match, MmpTransformDefinition.from(pair));

        assertEquals(sourceBefore, canonical(source));
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> match.keyAtomIndices().add(999));
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> attempt.application().attachments().clear());
    }

    private static MmpPair directedPair(
            StereoMolecule source,
            StereoMolecule target,
            MmpMiningConfig config,
            int cutCount
    ) {
        MmpMiningResult result = MmpMiner.mine(List.of(
                new MmpInputCompound("source", source, 1.0),
                new MmpInputCompound("target", target, 2.0)
        ), config);
        return result.pairs().stream()
                .filter(pair -> pair.cutCount() == cutCount)
                .filter(pair -> pair.compoundIdA().equals("source") && pair.compoundIdB().equals("target"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected directed " + cutCount + "-cut pair"));
    }

    private static MmpFragmentationMatch sourceMatch(
            StereoMolecule source,
            MmpMiningConfig config,
            MmpPair pair
    ) {
        return MmpFragmenter.fragmentWithMapping(new MmpInputCompound("source", source, null), config).stream()
                .filter(match -> match.record().cutCount() == pair.cutCount())
                .filter(match -> match.record().keyIdcode().equals(pair.keyIdcode()))
                .filter(match -> match.record().valueIdcode().equals(pair.fromValueIdcode()))
                .findFirst()
                .orElseThrow();
    }

    private static MmpMiningConfig config(int maxCuts) {
        return MmpMiningConfig.builder()
                .maxCuts(maxCuts)
                .minKeyHeavyAtoms(1)
                .maxVariableHeavyAtoms(20)
                .maxVariableToMolHeavyAtomFraction(1.0)
                .minTransformSupport(1)
                .build();
    }

    private static String canonical(StereoMolecule molecule) {
        return new Canonizer(molecule).getIDCode();
    }

    private static StereoMolecule parse(String smiles) throws Exception {
        StereoMolecule molecule = new StereoMolecule();
        new SmilesParser().parse(molecule, smiles);
        molecule.ensureHelperArrays(Molecule.cHelperRings);
        return molecule;
    }
}
