package tech.molecules.structurized.transforms;

import com.actelion.research.chem.SmilesParser;
import com.actelion.research.chem.StereoMolecule;

import java.util.ArrayList;
import java.util.List;

/**
 * Toy demo that wires TransformationBench with an OpenChemLib strict MCS provider and runs a
 * pairwise analysis on a small set of SMILES.
 */
public class TransformationBenchDemo {

    public static void main(String[] args) {
        List<String> smiles = List.of(
                "c1ccccc1C",
                "c1ccccc1F",
                "c1ccccc1Cl",
                "COc1ccccc1",
                "CCc1ccccc1",
                "c1ccncc1"
        );

        List<StereoMolecule> mols = parseSmiles(smiles);

        TransformationBench.Config cfg = new TransformationBench.Config();
        cfg.radiusR = 1;
        cfg.featureMask = TransformationSplitter.FeatureMask.DEFAULT;
        cfg.keepMultiCenter = true;
        cfg.symmetricPairs = false;
        cfg.verbose = true;
        cfg.maxPairs = Integer.MAX_VALUE;

        TransformationBench.Result res = TransformationBench.run(
                mols,
                new OCLMCSFastProvider(),
                cfg
        );

        res.printSummary();
        int shown = 0;
        for (PairTransformation pair : res.pairs) {
            if (pair.failure != null) {
                continue;
            }
            if (pair.groups.isEmpty()) {
                continue;
            }
            System.out.printf("Pair (%d,%d): %d TG(s)%n", pair.i, pair.j, pair.groups.size());
            for (TransformationGroup group : pair.groups) {
                System.out.printf("  - %s  sigId=%s  removedId=%s  addedId=%s  attachments=%s%n",
                        group.type,
                        group.signature.sigId.substring(0, 12),
                        shorten(group.signature.removedIdcode),
                        shorten(group.signature.addedIdcode),
                        group.attachmentsA);
            }
            if (++shown >= 8) {
                break;
            }
        }
    }

    private static String shorten(String s) {
        if (s == null) {
            return "-";
        }
        return s.length() <= 18 ? s : s.substring(0, 18) + "...";
    }

    private static List<StereoMolecule> parseSmiles(List<String> smiles) {
        SmilesParser parser = new SmilesParser();
        List<StereoMolecule> out = new ArrayList<>();
        for (String smi : smiles) {
            StereoMolecule molecule = new StereoMolecule();
            try {
                parser.parse(molecule, smi);
            } catch (Exception e) {
                throw new RuntimeException("SMILES parse failed: " + smi, e);
            }
            molecule.ensureHelperArrays(StereoMolecule.cHelperRings);
            out.add(molecule);
        }
        return out;
    }

    /** Backward-compatible demo alias for the reusable strict MCS provider. */
    public static class OCLMCSFastProvider extends OclStrictMcsProvider {
    }
}
