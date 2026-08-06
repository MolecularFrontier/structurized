package tech.molecules.structurized.mmp;

import com.actelion.research.chem.Canonizer;
import com.actelion.research.chem.IDCodeParser;
import com.actelion.research.chem.Molecule;
import com.actelion.research.chem.StereoMolecule;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Applies canonical one-cut and two-cut MMP transforms at mapped query sites. */
public final class MmpTransformApplicator {
    private MmpTransformApplicator() {}

    public static MmpTransformApplicationAttempt apply(
            MmpFragmentationMatch source,
            MmpTransformDefinition transform
    ) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(transform, "transform");

        MmpFragmentationRecord record = source.record();
        if (record.cutCount() != transform.cutCount()) {
            return MmpTransformApplicationAttempt.notApplicable("transform cut count does not match fragmentation");
        }
        if (!record.valueIdcode().equals(transform.fromValueIdcode())) {
            return MmpTransformApplicationAttempt.notApplicable("transform source fragment does not match fragmentation");
        }

        StereoMolecule key;
        Map<Integer, Connector> keyConnectors;
        try {
            key = parse(record.keyIdcode());
            keyConnectors = connectors(key, transform.cutCount());
        } catch (RuntimeException exception) {
            return MmpTransformApplicationAttempt.invalidProduct(
                    "invalid source key fragment: " + rootMessage(exception));
        }

        StereoMolecule replacement;
        Map<Integer, Connector> replacementConnectors;
        try {
            replacement = parse(transform.toValueIdcode());
            replacementConnectors = connectors(replacement, transform.cutCount());
        } catch (RuntimeException exception) {
            return MmpTransformApplicationAttempt.invalidTransform(
                    "invalid replacement fragment: " + rootMessage(exception));
        }

        try {
            StereoMolecule product = new StereoMolecule(key);
            int[] replacementMap = product.addMolecule(replacement);
            List<Integer> connectorAtoms = new ArrayList<>(transform.cutCount() * 2);

            for (int label = 1; label <= transform.cutCount(); label++) {
                Connector keyConnector = keyConnectors.get(label);
                Connector replacementConnector = replacementConnectors.get(label);
                if (keyConnector.bondType() != replacementConnector.bondType()) {
                    return MmpTransformApplicationAttempt.invalidTransform(
                            "connector R" + label + " has incompatible bond types");
                }

                int keyNeighbor = keyConnector.neighborAtom();
                int replacementNeighbor = replacementMap[replacementConnector.neighborAtom()];
                if (keyNeighbor == replacementNeighbor) {
                    return MmpTransformApplicationAttempt.invalidProduct(
                            "connector R" + label + " would create an invalid duplicate bond");
                }
                product.addBond(keyNeighbor, replacementNeighbor, keyConnector.bondType());
                connectorAtoms.add(keyConnector.connectorAtom());
                connectorAtoms.add(replacementMap[replacementConnector.connectorAtom()]);
            }

            for (Integer connectorAtom : connectorAtoms) {
                product.markAtomForDeletion(connectorAtom);
            }
            product.deleteMarkedAtomsAndBonds();
            product.setFragment(false);
            product.ensureHelperArrays(Molecule.cHelperCIP);
            String validationProblem = validateProduct(product);
            if (validationProblem != null) {
                return MmpTransformApplicationAttempt.invalidProduct(validationProblem);
            }
            if (containsConnectorAtoms(product)) {
                return MmpTransformApplicationAttempt.invalidProduct("product still contains connector atoms");
            }
            String productIdcode = new Canonizer(product).getIDCode();
            return MmpTransformApplicationAttempt.applied(new MmpTransformApplication(
                    record.canonicalRecordId(),
                    transform.transformId(),
                    transform.cutCount(),
                    productIdcode,
                    source.attachments()
            ));
        } catch (Exception exception) {
            return MmpTransformApplicationAttempt.invalidProduct(rootMessage(exception));
        }
    }

    private static StereoMolecule parse(String idcode) {
        StereoMolecule molecule = new StereoMolecule();
        new IDCodeParser().parse(molecule, idcode);
        molecule.ensureHelperArrays(Molecule.cHelperNeighbours);
        if (molecule.getAllAtoms() == 0) {
            throw new IllegalArgumentException("fragment is empty");
        }
        return molecule;
    }

    private static Map<Integer, Connector> connectors(StereoMolecule fragment, int cutCount) {
        Map<Integer, Connector> connectors = new HashMap<>();
        for (int atom = 0; atom < fragment.getAllAtoms(); atom++) {
            if (fragment.getAtomicNo(atom) != 0) {
                continue;
            }
            String customLabel = fragment.getAtomCustomLabel(atom);
            if (customLabel == null || !customLabel.matches("R[1-9][0-9]*")) {
                throw new IllegalArgumentException("unrecognized connector label");
            }
            int label = Integer.parseInt(customLabel.substring(1));
            if (label < 1 || label > cutCount) {
                throw new IllegalArgumentException("unexpected connector " + customLabel);
            }
            if (fragment.getConnAtoms(atom) != 1) {
                throw new IllegalArgumentException("connector " + customLabel + " must have exactly one neighbor");
            }
            int neighbor = fragment.getConnAtom(atom, 0);
            int bond = fragment.getConnBond(atom, 0);
            Connector previous = connectors.put(label,
                    new Connector(atom, neighbor, fragment.getBondType(bond)));
            if (previous != null) {
                throw new IllegalArgumentException("duplicate connector " + customLabel);
            }
        }
        for (int label = 1; label <= cutCount; label++) {
            if (!connectors.containsKey(label)) {
                throw new IllegalArgumentException("missing connector R" + label);
            }
        }
        if (connectors.size() != cutCount) {
            throw new IllegalArgumentException("fragment contains unexpected connectors");
        }
        return Map.copyOf(connectors);
    }

    private static String validateProduct(StereoMolecule molecule) {
        int[] fragmentNumbers = new int[molecule.getAllAtoms()];
        if (molecule.getFragmentNumbers(fragmentNumbers, false, false) != 1) {
            return "product is disconnected";
        }
        for (int atom = 0; atom < molecule.getAtoms(); atom++) {
            if (molecule.getOccupiedValence(atom) > molecule.getMaxValence(atom)) {
                return "product contains an invalid atom valence";
            }
        }
        return null;
    }

    private static boolean containsConnectorAtoms(StereoMolecule molecule) {
        for (int atom = 0; atom < molecule.getAllAtoms(); atom++) {
            if (molecule.getAtomicNo(atom) == 0) {
                return true;
            }
        }
        return false;
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private record Connector(int connectorAtom, int neighborAtom, int bondType) {}
}
