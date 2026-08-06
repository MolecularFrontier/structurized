package tech.molecules.structurized.mmp;

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

        MmpFragmentAssemblyAttempt assembly = MmpFragmentAssembler.assemble(
                record.keyIdcode(), transform.toValueIdcode(), transform.cutCount());
        if (assembly.isAssembled()) {
            return MmpTransformApplicationAttempt.applied(new MmpTransformApplication(
                    record.canonicalRecordId(),
                    transform.transformId(),
                    transform.cutCount(),
                    assembly.productIdcode(),
                    source.attachments()
            ));
        }
        return assembly.status() == MmpFragmentAssemblyStatus.INVALID_VALUE
                ? MmpTransformApplicationAttempt.invalidTransform(assembly.message())
                : MmpTransformApplicationAttempt.invalidProduct(assembly.message());
    }
}
