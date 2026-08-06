package tech.molecules.structurized.mmp;

import java.util.List;

/** A successfully generated product at one mapped fragmentation site. */
public record MmpTransformApplication(
        String sourceCanonicalRecordId,
        String transformId,
        int cutCount,
        String productIdcode,
        List<MmpAttachment> attachments
) {
    public MmpTransformApplication {
        sourceCanonicalRecordId = requireText(sourceCanonicalRecordId, "sourceCanonicalRecordId");
        transformId = requireText(transformId, "transformId");
        if (cutCount < 1 || cutCount > 2) {
            throw new IllegalArgumentException("cutCount must be 1 or 2");
        }
        productIdcode = requireText(productIdcode, "productIdcode");
        attachments = List.copyOf(attachments == null ? List.of() : attachments);
        if (attachments.size() != cutCount) {
            throw new IllegalArgumentException("attachment count must equal cut count");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
