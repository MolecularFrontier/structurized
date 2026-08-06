package tech.molecules.structurized.mmp;

import com.actelion.research.chem.Canonizer;
import com.actelion.research.chem.IDCodeParser;
import com.actelion.research.chem.Molecule;
import com.actelion.research.chem.StereoMolecule;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Reconnects canonical MMP key/value fragments through their R1/R2 connectors. */
public final class MmpFragmentAssembler {
    private MmpFragmentAssembler() {
    }

    public static MmpFragmentAssemblyAttempt assemble(
            String keyIdcode,
            String valueIdcode,
            int cutCount
    ) {
        if (cutCount < 1 || cutCount > 2) {
            throw new IllegalArgumentException("cutCount must be 1 or 2");
        }
        StereoMolecule key;
        Map<Integer, Connector> keyConnectors;
        try {
            key = parse(keyIdcode);
            keyConnectors = connectors(key, cutCount);
        } catch (RuntimeException exception) {
            return MmpFragmentAssemblyAttempt.failed(
                    MmpFragmentAssemblyStatus.INVALID_KEY,
                    "invalid key fragment: " + rootMessage(exception));
        }

        StereoMolecule value;
        Map<Integer, Connector> valueConnectors;
        try {
            value = parse(valueIdcode);
            valueConnectors = connectors(value, cutCount);
        } catch (RuntimeException exception) {
            return MmpFragmentAssemblyAttempt.failed(
                    MmpFragmentAssemblyStatus.INVALID_VALUE,
                    "invalid value fragment: " + rootMessage(exception));
        }

        try {
            StereoMolecule product = new StereoMolecule(key);
            int[] valueMap = product.addMolecule(value);
            List<Integer> connectorAtoms = new ArrayList<>(cutCount * 2);
            for (int label = 1; label <= cutCount; label++) {
                Connector keyConnector = keyConnectors.get(label);
                Connector valueConnector = valueConnectors.get(label);
                if (keyConnector.bondType() != valueConnector.bondType()) {
                    return MmpFragmentAssemblyAttempt.failed(
                            MmpFragmentAssemblyStatus.INVALID_VALUE,
                            "connector R" + label + " has incompatible bond types");
                }
                int keyNeighbor = keyConnector.neighborAtom();
                int valueNeighbor = valueMap[valueConnector.neighborAtom()];
                if (keyNeighbor == valueNeighbor) {
                    return MmpFragmentAssemblyAttempt.failed(
                            MmpFragmentAssemblyStatus.INVALID_PRODUCT,
                            "connector R" + label + " would create an invalid duplicate bond");
                }
                product.addBond(keyNeighbor, valueNeighbor, keyConnector.bondType());
                connectorAtoms.add(keyConnector.connectorAtom());
                connectorAtoms.add(valueMap[valueConnector.connectorAtom()]);
            }
            connectorAtoms.forEach(product::markAtomForDeletion);
            product.deleteMarkedAtomsAndBonds();
            product.setFragment(false);
            product.ensureHelperArrays(Molecule.cHelperCIP);
            String validationProblem = validateProduct(product);
            if (validationProblem != null) {
                return MmpFragmentAssemblyAttempt.failed(
                        MmpFragmentAssemblyStatus.INVALID_PRODUCT, validationProblem);
            }
            if (containsConnectorAtoms(product)) {
                return MmpFragmentAssemblyAttempt.failed(
                        MmpFragmentAssemblyStatus.INVALID_PRODUCT,
                        "product still contains connector atoms");
            }
            return MmpFragmentAssemblyAttempt.assembled(new Canonizer(product).getIDCode());
        } catch (RuntimeException exception) {
            return MmpFragmentAssemblyAttempt.failed(
                    MmpFragmentAssemblyStatus.INVALID_PRODUCT, rootMessage(exception));
        }
    }

    private static StereoMolecule parse(String idcode) {
        if (idcode == null || idcode.isBlank()) {
            throw new IllegalArgumentException("fragment IDCode is blank");
        }
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
            if (fragment.getAtomicNo(atom) != 0) continue;
            String customLabel = fragment.getAtomCustomLabel(atom);
            if (customLabel == null || !customLabel.matches("R[1-9][0-9]*")) {
                throw new IllegalArgumentException("unrecognized connector label");
            }
            int label = Integer.parseInt(customLabel.substring(1));
            if (label < 1 || label > cutCount) {
                throw new IllegalArgumentException("unexpected connector " + customLabel);
            }
            if (fragment.getConnAtoms(atom) != 1) {
                throw new IllegalArgumentException(
                        "connector " + customLabel + " must have exactly one neighbor");
            }
            int bond = fragment.getConnBond(atom, 0);
            Connector previous = connectors.put(label, new Connector(
                    atom, fragment.getConnAtom(atom, 0), fragment.getBondType(bond)));
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
            if (molecule.getAtomicNo(atom) == 0) return true;
        }
        return false;
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.isBlank()
                ? current.getClass().getSimpleName()
                : message;
    }

    private record Connector(int connectorAtom, int neighborAtom, int bondType) {
    }
}
