package tech.molecules.structurized.ai.prism;

import com.actelion.research.chem.IDCodeParser;
import com.actelion.research.chem.IsomericSmilesCreator;
import com.actelion.research.chem.StereoMolecule;

import java.util.Map;

final class PrismMmpTransformRenderer {
    private PrismMmpTransformRenderer() {
    }

    static PrismMmpTransformText render(Map<String, Object> properties) {
        if (properties == null || properties.isEmpty()) {
            return new PrismMmpTransformText(null, null, null, null, null, null);
        }
        String transformId = text(properties.get("transformId"));
        Integer cutCount = integer(properties.get("cutCount"));
        String key = idcodeToSmiles(text(properties.get("keyIdcode")));
        String from = idcodeToSmiles(text(properties.get("fromValueIdcode")));
        String to = idcodeToSmiles(text(properties.get("toValueIdcode")));
        String transformText = from == null && to == null ? transformId : nullToQuestion(from) + " -> " + nullToQuestion(to);
        return new PrismMmpTransformText(transformId, cutCount, key, from, to, transformText);
    }

    static String idcodeToSmiles(String idcode) {
        if (idcode == null || idcode.isBlank()) {
            return null;
        }
        try {
            StereoMolecule molecule = new IDCodeParser(false).getCompactMolecule(idcode);
            if (molecule == null) {
                return null;
            }
            molecule.ensureHelperArrays(StereoMolecule.cHelperCIP);
            return IsomericSmilesCreator.createSmiles(molecule);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static String nullToQuestion(String value) {
        return value == null || value.isBlank() ? "?" : value;
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private static Integer integer(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value.toString());
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
